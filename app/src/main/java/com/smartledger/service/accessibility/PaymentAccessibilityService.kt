package com.smartledger.service.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.smartledger.service.AccessibilityFingerprintCache
import com.smartledger.service.AutoCaptureSource
import com.smartledger.service.AutoRecordProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 微信 / 支付宝支付页面识别服务（方案任务 2 / 3）。
 *
 * ## 低耗电设计（方案 3.6 红线，全部遵守）
 * - 只在系统推送 AccessibilityEvent 时工作：不轮询、不 WakeLock、
 *   不前台服务、不截图、不 OCR
 * - xml 配置只监听两个包名 + 两种事件类型（TYPE_WINDOW_STATE_CHANGED /
 *   TYPE_WINDOW_CONTENT_CHANGED），普通 App 的事件根本不会进本服务
 *
 * ## 二级过滤（方案任务 3）
 * ```
 * 事件 → 包名过滤 → 类型分发
 *   ├─ WINDOW_STATE_CHANGED（页面切换，低频）→ 450ms 防抖 → full scan
 *   └─ WINDOW_CONTENT_CHANGED（高频）→ quickProbe 浅层探测（40 节点/深4）
 *        └─ 命中支付强特征才 → 450ms 防抖 → full scan
 * ```
 * 防抖的意义：支付成功页刚出现时 UI 可能未渲染完，立即读取拿不到金额；
 * 同时连续 20 次 UI 更新最终只做一次完整扫描。
 *
 * ## 多窗口候选（debug.4）
 * `rootInActiveWindow` **不一定指向支付页**：真机上微信聊天页与支付结果页
 * 常常并存，扫到的是聊天页（诊断样本里出现的是聊天气泡与 `图片/小视频/红包/转账`，
 * 而不是 `支付成功/¥0.01`）——这是「三场景全部不记账」的根因。
 *
 * 现在每次完整扫描都枚举**全部同包名窗口**，各做一次快照与信号判定，
 * 由 [AccessibilityWindowSelector] 选一个交给 Parser（规则见该类的注释）。
 * `windows` 需要 xml 里的 `flagRetrieveInteractiveWindows`；未生效时
 * [collectPackageWindows] 会退化成「只有活动窗口」，即改造前的行为。
 *
 * ## 线程模型（C6）
 * 主线程只做事件分发、防抖、`windows` / `rootInActiveWindow` 取值；
 * 树遍历（binder 调用）、解析、指纹、入账全部在 IO 协程 —— 主线程零卡顿。
 */
class PaymentAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "PaymentA11y"
        private const val DEBOUNCE_MS = 450L

        /** 单次扫描最多考察的同包名窗口数（多窗口遍历的耗电上限） */
        private const val MAX_WINDOWS = 4

        /**
         * 原始窗口清单的枚举上限（debug.8）。
         *
         * 清单要的是「系统到底给了几个窗口、每个为什么被丢」，所以必须在
         * [MAX_WINDOWS] **之外**单独收口 —— 否则被上限挡掉的那些窗口
         * 连一行记录都没有，等于没观测。8 个足够覆盖真实场景（含输入法、
         * 悬浮层），又不至于在异常情况下把主线程拖住。
         */
        private const val RAW_WINDOW_LIMIT = 8

        /** 窗口不在 `windows` 列表里（仅 activeRoot 降级）时的占位类型 */
        private const val WINDOW_TYPE_UNKNOWN = -1

        /**
         * 整页一个文本节点都没抓到时的重扫次数（debug.6）。
         *
         * debug.5 真机：微信窗口 5/5 读出 `文本节点=0`，其中 4 次 `root=null`
         * （节点已失效）。失效的节点**重读同一个对象没用**，所以重试是重走
         * [performFullScan]（重新取 `rootInActiveWindow`），而不是重扫旧节点。
         * 两次延迟刻意不同（150ms / 400ms）：既能盖住"页面还在渲染"，
         * 也能盖住"节点刚好在窗口切换中失效"，同时又不像固定轮询那样
         * 在页面真的为空时反复空转。
         */
        private const val MAX_EMPTY_RETRIES = 2
        private val RETRY_DELAYS_MS = longArrayOf(150L, 400L)

        /** 主线程基线的浅读上限（节点数 / 深度）——只在调试模式下读 */
        private const val MAIN_THREAD_PROBE_NODES = 20
        private const val MAIN_THREAD_PROBE_DEPTH = 3

        private val ALLOWED_PACKAGES = setOf(
            AccessibilityFingerprintBuilder.WECHAT_PACKAGE,
            AccessibilityFingerprintBuilder.ALIPAY_PACKAGE
        )

        private const val PREFS = "smart_ledger"
        private const val KEY_FEATURE = "accessibility_auto_record_enabled"
        private const val KEY_DEBUG = "debug_toasts"
    }

    /** C2：Service 持有的作用域，onDestroy 取消，杜绝裸 scope 泄漏 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    private var pendingRunnable: Runnable? = null

    /**
     * 待执行的空树重扫（debug.6）。新事件到来时一并取消 ——
     * 否则重扫会和刚安排的那次扫描并发跑，两次扫描各自入账，
     * 一笔转账记成两笔。
     */
    private var pendingRetry: Runnable? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        AccessibilityStatus.setConnected(applicationContext, true)
        // debug.4：把**运行时实际生效**的能力上报诊断。xml 里写了 flag
        // 不等于运行期生效（系统设置可能覆盖、厂商 ROM 可能裁剪），
        // 而 windows 返回空时这一行是唯一的判据
        serviceInfo?.let {
            AccessibilityDiagnostics.onCapabilities(it.flags, it.canRetrieveWindowContent)
            Log.d(TAG, "Service connected flags=0x${Integer.toHexString(it.flags)}")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        // App 内总开关（M4）：系统权限开着但用户暂停解析时，事件全部丢弃
        if (!isFeatureEnabled()) return

        val packageName = event.packageName?.toString() ?: return
        if (packageName !in ALLOWED_PACKAGES) return

        AccessibilityDiagnostics.onEvent(packageName, event.eventType)

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // 页面切换低频：直接安排完整扫描（方案 3.2）
                scheduleFullScan(packageName)
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // 聊天、列表刷新高频：先浅层探测，命中支付强特征才扫描（方案 3.3）
                if (quickProbe(event)) {
                    AccessibilityDiagnostics.onProbeHit(packageName)
                    scheduleFullScan(packageName)
                }
            }
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        // 用户在系统设置里关闭服务时回调 —— 此时不代表崩溃，只更新存活标志
        AccessibilityStatus.setConnected(applicationContext, false)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        AccessibilityStatus.setConnected(applicationContext, false)
        super.onDestroy()
    }

    // ═══ Level 1：浅层探测（方案 3.4，C1 已补齐实现） ═══

    /**
     * 真机校准（9/10 撤销 P2-6 短路盲区）：
     *
     * P2-6 曾把「event.text 有内容但无信号」直接判负、不读树 —— 隐含假设
     * 「支付信号会出现在事件的增量文本里」。真机上微信页面是**碎片化渲染**：
     * 每次 CONTENT_CHANGED 只带一小块文本（如单独的金额「0.01」或
     * 「零钱余额」），谁都不含完整强词 → 全部被拦 → 若页面又是 fragment
     * 级切换（无 STATE_CHANGED 兑底）→ **一次扫描都不会发生**。
     *
     * 现在的结构：
     * - event.text 有信号 → 立即命中（零成本快速路径，保留 P2-6 的优化）
     * - 否则（含文本为空）→ 浅层树探测（40 节点/深 4，主线程几百微秒级，
     *   方案 3.4 原始设计；完整扫描才是 IO 协程的事）
     */
    private fun quickProbe(event: AccessibilityEvent): Boolean {
        val parts = mutableListOf<String>()

        @Suppress("UNCHECKED_CAST")
        (event.text as? List<CharSequence>)?.forEach {
            it?.toString()?.takeIf { t -> t.isNotBlank() }?.let(parts::add)
        }
        event.contentDescription?.toString()?.let(parts::add)

        if (parts.isNotEmpty() && PaymentSignalDetector.hasStrongSignal(parts.joinToString(" "))) {
            return true
        }

        // 文本无信号（或为空）→ 浅层树探测兜底（碎片化渲染的信号拼不全在事件文本里）
        event.source?.let { source ->
            parts += UiTreeSnapshotExtractor.extractShallow(source)
        }

        // 真机校准（debug.6）：`event.source` 对微信的 CONTENT_CHANGED **经常为 null**，
        // 上一版到这里就 `return false` 了 —— 而 CONTENT_CHANGED 恰恰是支付页
        // 渲染完成那一刻唯一还在推的事件类型（STATE_CHANGED 只在页面**开始**切换时来一次）。
        // debug.5 真机 172 个事件 0 次探测命中，卡的就是这里。
        // 退回焦点窗口做同一套浅层探测：成本与 source 路径同量级
        // （40 节点 / 深 4，主线程几百微秒），且判定口径完全一致。
        if (parts.isEmpty()) {
            rootInActiveWindow?.let { parts += UiTreeSnapshotExtractor.extractShallow(it) }
        }
        if (parts.isEmpty()) return false

        return PaymentSignalDetector.hasStrongSignal(parts.joinToString(" "))
    }

    // ═══ 防抖（方案 3.5） ═══

    private fun scheduleFullScan(packageName: String) {
        pendingRunnable?.let(handler::removeCallbacks)
        // 新事件意味着页面又变了：上一轮的待重扫已经过时，取消掉
        pendingRetry?.also { handler.removeCallbacks(it); pendingRetry = null }
        val runnable = Runnable { performFullScan(packageName) }
        pendingRunnable = runnable
        handler.postDelayed(runnable, DEBOUNCE_MS)
    }

    // ═══ Level 2：完整扫描 + 解析 + 入账（方案 4.2） ═══

    /**
     * @param attempt 第几次尝试（0 起，见 [MAX_EMPTY_RETRIES]）
     * @param attemptsSoFar 之前各次尝试抓到的文本节点总数，最终写进 [ScanCapture.attempts]
     */
    private fun performFullScan(
        packageName: String,
        attempt: Int = 0,
        attemptsSoFar: List<Int> = emptyList()
    ) {
        // 调试开关在主线程读一次，协程里复用（避免在 IO 线程读 prefs）
        val debug = debugEnabled()

        // rootInActiveWindow 与 windows 都是 binder 调用，只在主线程取一次。
        // 主线程基线（浅读）也在这里做 —— 它与后面 IO 协程里的深读是
        // **同一个窗口的两次读数**，两者之差就是「节点失效」与「真的没内容」的分界
        val activeRoot = rootInActiveWindow
        val collected = collectPackageWindows(packageName, activeRoot, debug)
        val candidates = collected.candidates
        val windowsNote = collected.note

        if (candidates.isEmpty()) {
            // 事件到了但一个同包名窗口都拿不到（页面切换间隙/系统限制）
            AccessibilityDiagnostics.onRootUnavailable(packageName)
            if (debug) {
                AccessibilityDiagnostics.onScanCapture(
                    ScanCapture(
                        at = System.currentTimeMillis(),
                        packageName = packageName,
                        rootAvailable = false,
                        windowsNote = windowsNote,
                        rawWindows = collected.rawWindows,
                        chosenIndex = null,
                        windows = emptyList(),
                        outcome = "rootInActiveWindow 与 windows 都拿不到窗口"
                    )
                )
            }
            return
        }

        // 只统计第 0 次：重试是同一笔转账的补救，不是新的一次扫描。
        // 计进去会让「完整扫描」这个数在 debug.5/debug.6 之间没法直接比
        if (attempt == 0) AccessibilityDiagnostics.onFullScan(packageName)

        scope.launch {
            val at = System.currentTimeMillis()
            var outcome = "未命中支付信号"
            var windows: List<WindowCapture> = emptyList()
            var chosenIndex: Int? = null
            var failed = false

            try {
                // C6：遍历与解析全部在 IO 线程；每个候选窗口各扫一次
                val scanned = candidates.mapIndexed { index, window ->
                    scanWindow(index, window, packageName)
                }
                windows = scanned.map { it.capture }
                chosenIndex = AccessibilityWindowSelector.selectBest(
                    windows.map {
                        WindowCandidate(
                            index = it.windowIndex,
                            signalHit = it.signalHit,
                            textNodeCount = it.nodes.size,
                            isActiveRoot = it.isActiveRoot
                        )
                    }
                )
                outcome = handleChosenWindow(
                    packageName,
                    scanned.firstOrNull { it.capture.windowIndex == chosenIndex }
                )
            } catch (e: Exception) {
                failed = true
                outcome = "扫描异常：${e.javaClass.simpleName}"
                Log.e(TAG, "Full scan failed", e)
            }

            val total = windows.sumOf { it.nodes.size }
            val attempts = attemptsSoFar + total

            // 空树重试（debug.6）：整页一个文本节点都没有时，换个时间点重走一遍
            // （重新取 rootInActiveWindow —— 失效的节点重读同一个对象没有意义）。
            // 中间几次不单独记抓取，只把节点数并进 attempts —— 环形缓冲只有 5 格，
            // 一次转账的重试就能把它塞满。
            if (shouldRetryEmptyTree(total, windows.size, failed, attempt, MAX_EMPTY_RETRIES)) {
                val retry = Runnable { performFullScan(packageName, attempt + 1, attempts) }
                pendingRetry?.let(handler::removeCallbacks)
                pendingRetry = retry
                handler.postDelayed(retry, RETRY_DELAYS_MS[attempt])
                return@launch
            }

            if (debug) {
                AccessibilityDiagnostics.onScanCapture(
                    ScanCapture(
                        at = at,
                        packageName = packageName,
                        rootAvailable = true,
                        windowsNote = windowsNote,
                        rawWindows = collected.rawWindows,
                        chosenIndex = chosenIndex,
                        windows = windows,
                        outcome = outcome,
                        attempts = attempts
                    )
                )
            }
        }
    }

    /**
     * 对选中窗口做解析 → 指纹 → 入账，并把每一步的结果作为诊断 outcome 返回。
     *
     * 「扫到了但没记账」的每一步（空树 / 无信号 / 解析 null / 去重 / 识别）
     * 都能在诊断里对上号 —— 只看到「成功识别：0」是 debug.3 之前排查的最大障碍。
     */
    private suspend fun handleChosenWindow(packageName: String, scan: WindowScan?): String {
        val snapshot = scan?.snapshot ?: return "没有可用快照"
        if (snapshot.nodes.isEmpty()) return "窗口树为空（无文本节点，可能是自绘/H5 页面）"
        if (!scan.capture.signalHit) return "未命中支付信号"

        val parser: AccessibilityPaymentParser = when (packageName) {
            AccessibilityFingerprintBuilder.WECHAT_PACKAGE -> WeChatAccessibilityParser
            AccessibilityFingerprintBuilder.ALIPAY_PACKAGE -> AlipayAccessibilityParser
            else -> return "无匹配包名的解析器"
        }
        val parsed = parser.parse(snapshot)
            ?: return "命中支付信号但解析为 null（缺金额 / 方向不唯一 / 命中排除词）"

        // 三层去重的前两层在这里，第三层（DedupHelper）在 processor 里
        val key = AccessibilityFingerprintBuilder.build(packageName, snapshot, parsed)
        if (!AccessibilityFingerprintCache.shouldProcess(key)) {
            AccessibilityDiagnostics.onDeduped()
            return "指纹去重拦截"
        }

        AccessibilityDiagnostics.onRecognized(parsed.type, parsed.amount)
        Log.d(
            TAG, "Recognized: pkg=$packageName type=${parsed.type} " +
                "amount=${parsed.amount} merchant=${parsed.merchant}"
        )

        AutoRecordProcessor(applicationContext).process(
            parsed = parsed.copy(notificationKey = key),
            transactionTime = snapshot.capturedAt,
            source = AutoCaptureSource.ACCESSIBILITY
        )

        val label = if (parsed.type == "income") "收入" else "支出"
        return "识别 $label ¥${"%.2f".format(Locale.US, parsed.amount)}"
    }

    /** 单个窗口：完整快照 + 信号分项判定（IO 线程调用） */
    private fun scanWindow(windowIndex: Int, window: PackageWindow, packageName: String): WindowScan {
        val snapshot = UiTreeSnapshotExtractor.extract(window.root, packageName)
        val pageText = snapshot.nodes.joinToString(" ") { it.text }
        return WindowScan(
            capture = WindowCapture(
                windowIndex = windowIndex,
                windowType = window.type,
                isActive = window.isActive,
                isFocused = window.isFocused,
                // 包名用**主线程**读到的值：节点在这里可能已经失效，
                // 失效节点读 packageName 返回 null，会把「这是哪个窗口」这条
                // 最关键的信息丢掉（debug.5 的 dump 就因为这个差点被误读）
                packageName = window.rootPackageName,
                rootClass = window.root.className?.toString()?.substringAfterLast('.'),
                rootChildCount = window.root.childCount,
                isActiveRoot = window.isActiveRoot,
                nodes = snapshot.nodes,
                strongWords = PaymentSignalDetector.strongWordsIn(pageText),
                amountProbeHit = PaymentSignalDetector.hasAmountForm(pageText),
                mainThreadTexts = window.mainThreadTexts,
                activeRootShallowTexts = window.sourceProbe?.activeRootTexts,
                windowRootShallowTexts = window.sourceProbe?.windowRootTexts,
                // 空树才采结构（debug.8）：正常页面白跑一趟 binder 遍历没意义，
                // 而空树时这是唯一还能回答「这页为什么没字」的东西
                structure = if (snapshot.nodes.isEmpty()) {
                    UiTreeSnapshotExtractor.extractStructure(window.root)
                } else {
                    emptyList()
                }
            ),
            snapshot = snapshot
        )
    }

    /**
     * 枚举同包名窗口（debug.4，主线程调用）。
     *
     * `windows` 需要 xml 的 `flagRetrieveInteractiveWindows`：
     * 未声明时它返回空列表（不抛异常），这正是 debug.3 的多窗口兜底空转的原因。
     * 这里把「为什么没有备选窗口」记进 [windowsNote]，让诊断能区分
     * 「flag 没生效」与「真的只有一个窗口」。
     *
     * activeRoot 一定会进候选并排在最前：即使 windows 不可用，
     * 行为也退化成改造前的「只扫活动窗口」，不会比现在更差。
     *
     * ## debug.8：两处收紧
     * 1. **包名读不出（null）的窗口不再被丢**。旧写法 `if (pkg != packageName) continue`
     *    在 `pkg == null` 时也成立 —— 而节点失效或窗口受限时读 `packageName`
     *    就是 null。支付页若在这样一个窗口里，它从来没进过候选，诊断里
     *    连它存在过都看不出来。现在 `pkg == null` 一律保留，由
     *    [PackageWindow.rootPackageName] 如实记成 null 供事后分辨。
     * 2. **原始清单记进 [WindowCollection.rawWindows]**，每个窗口一条
     *    「采纳 / 丢弃 + 原因」，且在 [MAX_WINDOWS] 上限之外单独收口 ——
     *    被上限挡掉的窗口也要留痕，否则等于没观测。
     */
    private fun collectPackageWindows(
        packageName: String,
        activeRoot: AccessibilityNodeInfo?,
        debug: Boolean
    ): WindowCollection {
        val list = mutableListOf<PackageWindow>()
        val raw = mutableListOf<String>()
        var note: String? = null

        // 主线程基线：趁节点**刚拿到、必然有效**时浅读一次。
        // 这是与 IO 协程深读做对照的那一份读数，只在调试模式下产生成本
        fun baseline(root: AccessibilityNodeInfo): List<String> =
            if (!debug) emptyList()
            else UiTreeSnapshotExtractor.extractShallow(
                root,
                maxNodes = MAIN_THREAD_PROBE_NODES,
                maxDepth = MAIN_THREAD_PROBE_DEPTH
            )

        try {
            val all = windows.orEmpty()
            if (all.isEmpty()) {
                note = "windows 返回空（flagRetrieveInteractiveWindows 未生效或被系统限制）"
            } else {
                for ((i, w) in all.withIndex()) {
                    if (i >= RAW_WINDOW_LIMIT) break
                    val marks = buildList {
                        if (w.isActive) add("active")
                        if (w.isFocused) add("focused")
                    }.joinToString(" ").ifBlank { "—" }
                    val type = "w$i type=${w.type} $marks"

                    val root = w.root
                    if (root == null) {
                        // root 为 null 的窗口我们**没有任何办法**读到内容，
                        // 只能留痕：这本身就是「支付页可能藏在这里」的一条证据
                        raw += "$type root=无 → 丢弃：读不到 root"
                        continue
                    }
                    val pkg = root.packageName?.toString()
                    val decision = decideWindow(pkg, packageName, list.size, MAX_WINDOWS)
                    if (!decision.accepted) {
                        val why = when (decision) {
                            WindowDecision.REJECT_OTHER_PKG -> "包名不符"
                            else -> "超出 $MAX_WINDOWS 个上限"
                        }
                        // 对照组（debug.10）：对**别的包**的窗口也浅读一次。
                        // 只记条数、不记内容 —— 这些窗口本来就不参与识别，
                        // 这样既不越界，又能在微信读空时当场分开两种完全不同的病因：
                        // 「我们的服务读不了任何 App」还是「只有微信读不了」。
                        // 光看微信一个窗口，这两者是同一种表象。
                        val control = if (debug && decision == WindowDecision.REJECT_OTHER_PKG) {
                            "（对照浅读=${baseline(root).size}）"
                        } else {
                            ""
                        }
                        raw += "$type root=有 pkg=${pkg ?: "?"} → 丢弃：$why$control"
                        continue
                    }
                    raw += "$type root=有 pkg=${pkg ?: "?（读不出，保留）"} → 采纳"
                    // 活动窗口用 `rootInActiveWindow` **那个对象**，其余窗口用 w.root
                    // （`activeRoot` 为 null 或不属于本窗口时退回 w.root）。
                    // `rootInActiveWindow` 是「用户正在看的那个窗口」的权威节点，
                    // 语义上就该优先于 `AccessibilityWindowInfo.getRoot()`。
                    //
                    // ⚠ 但要说清历史：debug.9 曾把「微信全页面读成空树」归因于
                    // debug.5 换用了 `w.root`，并加了一行 `浅读对照` 来自证。
                    // **那份自证把假设推翻了** —— 真机两个来源都是 0：
                    //     浅读对照：activeRoot=0 / windowRoot=0
                    // 所以节点来源不是根因（本行保留只是因为它是更正确的写法，
                    // 不是因为它是修复）。排除法之后，唯一没被单独验证过的变量
                    // 就剩 XML 的 flag，debug.10 摘掉了 flagIncludeNotImportantViews。
                    //
                    // 顺带修正一个我自己的错误判据：debug.6 加的「主线程读数 vs IO 读数」
                    // 对照**两个数都取自同一个对象**（当时是 w.root），只能证明
                    // 「不是跨线程失效」，证明不了「内容不存在」—— 为此白绕了一轮。
                    val activeNode = activeRoot?.takeIf { it.windowId == root.windowId }
                    val isActiveRoot = activeNode != null
                    val node = activeNode ?: root
                    val lineTexts = baseline(node)
                    // 两个来源确实不同时各浅读一次做对照：activeRoot 有文本而
                    // windowRoot 为 0，就是「换节点来源即修复」的当场自证
                    val probe = if (debug && node !== root) {
                        NodeSourceProbe(
                            activeRootTexts = lineTexts.size,
                            windowRootTexts = baseline(root).size
                        )
                    } else {
                        null
                    }
                    list += PackageWindow(
                        root = node,
                        type = w.type,
                        isActive = w.isActive,
                        isFocused = w.isFocused,
                        // windowId 比较比引用比较可靠：windows 每次返回的是新对象
                        isActiveRoot = isActiveRoot,
                        rootPackageName = pkg,
                        mainThreadTexts = lineTexts,
                        sourceProbe = probe
                    )
                }
                if (all.size > MAX_WINDOWS) {
                    note = "系统报告 ${all.size} 个窗口，只考察前 $MAX_WINDOWS 个同包名窗口"
                }
            }
        } catch (e: Exception) {
            note = "windows 读取异常：${e.javaClass.simpleName}"
            Log.w(TAG, "collect windows failed", e)
        }

        if (activeRoot != null && list.none { it.isActiveRoot }) {
            // 这条**不做包名过滤**（降级路径，宁可多扫也不漏），
            // 所以它可能属于别的包 —— rootPackageName 会如实记下来，
            // 诊断里靠 pkg= 字段区分（debug.5 的启动器文件夹就是这么混进来的）
            list.add(
                0,
                PackageWindow(
                    root = activeRoot,
                    type = WINDOW_TYPE_UNKNOWN,
                    isActive = true,
                    isFocused = true,
                    isActiveRoot = true,
                    rootPackageName = activeRoot.packageName?.toString(),
                    mainThreadTexts = baseline(activeRoot)
                )
            )
            raw += "（降级）rootInActiveWindow 不在上面的清单里，额外作为 w0 加入"
        }
        return WindowCollection(list, note, raw)
    }

    /** [collectPackageWindows] 的产物：候选窗口 + 「为什么没有备选窗口」+ 过滤前的原始清单 */
    private data class WindowCollection(
        val candidates: List<PackageWindow>,
        val note: String?,
        val rawWindows: List<String>
    )

    /** 一个候选窗口：诊断用的 [WindowCapture] + 解析用的 [UiSnapshot] */
    private data class WindowScan(val capture: WindowCapture, val snapshot: UiSnapshot)

    /** 候选窗口（主线程取好，交给 IO 协程遍历） */
    private data class PackageWindow(
        val root: AccessibilityNodeInfo,
        val type: Int,
        val isActive: Boolean,
        val isFocused: Boolean,
        val isActiveRoot: Boolean,
        /** 主线程读到的包名 —— 节点在 IO 线程可能已失效，那时再读会得到 null */
        val rootPackageName: String?,
        /** 主线程浅读到的文本（与 IO 深读对照，判定「节点失效」还是「真的为空」） */
        val mainThreadTexts: List<String>,
        /** 两个节点来源的浅读对照（debug.9，仅调试模式且两者不同时有值） */
        val sourceProbe: NodeSourceProbe? = null
    )

    /**
     * 活动窗口的**节点来源对照**（debug.9）。
     *
     * 同一个窗口，用 `rootInActiveWindow` 和 `AccessibilityWindowInfo.getRoot()`
     * 各浅读一次。debug.5 起改用后者，微信所有页面因此都读成空树 ——
     * 这两个数并排打出来就能当场证实或证伪：
     *
     * - `activeRoot=12 / windowRoot=0` → 换节点来源确实是修复所在
     * - 两边都是 0 → 假设不成立，得回到 flag 那条线上去查
     */
    private data class NodeSourceProbe(
        val activeRootTexts: Int,
        val windowRootTexts: Int
    )

    private fun isFeatureEnabled(): Boolean =
        getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_FEATURE, true)

    private fun debugEnabled(): Boolean =
        getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_DEBUG, false)
}
