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

        /** 窗口不在 `windows` 列表里（仅 activeRoot 降级）时的占位类型 */
        private const val WINDOW_TYPE_UNKNOWN = -1

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

        AccessibilityDiagnostics.onEvent(packageName)

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
        if (parts.isEmpty()) return false

        return PaymentSignalDetector.hasStrongSignal(parts.joinToString(" "))
    }

    // ═══ 防抖（方案 3.5） ═══

    private fun scheduleFullScan(packageName: String) {
        pendingRunnable?.let(handler::removeCallbacks)
        val runnable = Runnable { performFullScan(packageName) }
        pendingRunnable = runnable
        handler.postDelayed(runnable, DEBOUNCE_MS)
    }

    // ═══ Level 2：完整扫描 + 解析 + 入账（方案 4.2） ═══

    private fun performFullScan(packageName: String) {
        // rootInActiveWindow 与 windows 都是 binder 调用，只在主线程取一次
        val activeRoot = rootInActiveWindow
        val (candidates, windowsNote) = collectPackageWindows(packageName, activeRoot)

        // 调试开关在主线程读一次，协程里复用（避免在 IO 线程读 prefs）
        val debug = debugEnabled()

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
                        chosenIndex = null,
                        windows = emptyList(),
                        outcome = "rootInActiveWindow 与 windows 都拿不到窗口"
                    )
                )
            }
            return
        }

        AccessibilityDiagnostics.onFullScan(packageName)

        scope.launch {
            val at = System.currentTimeMillis()
            var outcome = "未命中支付信号"
            var windows: List<WindowCapture> = emptyList()
            var chosenIndex: Int? = null

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
                outcome = "扫描异常：${e.javaClass.simpleName}"
                Log.e(TAG, "Full scan failed", e)
            } finally {
                if (debug) {
                    AccessibilityDiagnostics.onScanCapture(
                        ScanCapture(
                            at = at,
                            packageName = packageName,
                            rootAvailable = true,
                            windowsNote = windowsNote,
                            chosenIndex = chosenIndex,
                            windows = windows,
                            outcome = outcome
                        )
                    )
                }
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
                packageName = window.root.packageName?.toString(),
                rootClass = window.root.className?.toString()?.substringAfterLast('.'),
                rootChildCount = window.root.childCount,
                isActiveRoot = window.isActiveRoot,
                nodes = snapshot.nodes,
                strongWords = PaymentSignalDetector.strongWordsIn(pageText),
                amountProbeHit = PaymentSignalDetector.hasAmountForm(pageText)
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
     */
    private fun collectPackageWindows(
        packageName: String,
        activeRoot: AccessibilityNodeInfo?
    ): Pair<List<PackageWindow>, String?> {
        val list = mutableListOf<PackageWindow>()
        var note: String? = null
        val activeWindowId = activeRoot?.windowId

        try {
            val all = windows.orEmpty()
            if (all.isEmpty()) {
                note = "windows 返回空（flagRetrieveInteractiveWindows 未生效或被系统限制）"
            } else {
                for (w in all) {
                    if (list.size >= MAX_WINDOWS) break
                    val root = w.root ?: continue
                    if (root.packageName?.toString() != packageName) continue
                    list += PackageWindow(
                        root = root,
                        type = w.type,
                        isActive = w.isActive,
                        isFocused = w.isFocused,
                        // windowId 比较比引用比较可靠：windows 每次返回的是新对象
                        isActiveRoot = activeWindowId != null && root.windowId == activeWindowId
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
            list.add(
                0,
                PackageWindow(
                    root = activeRoot,
                    type = WINDOW_TYPE_UNKNOWN,
                    isActive = true,
                    isFocused = true,
                    isActiveRoot = true
                )
            )
        }
        return list to note
    }

    /** 一个候选窗口：诊断用的 [WindowCapture] + 解析用的 [UiSnapshot] */
    private data class WindowScan(val capture: WindowCapture, val snapshot: UiSnapshot)

    /** 候选窗口（主线程取好，交给 IO 协程遍历） */
    private data class PackageWindow(
        val root: AccessibilityNodeInfo,
        val type: Int,
        val isActive: Boolean,
        val isFocused: Boolean,
        val isActiveRoot: Boolean
    )

    private fun isFeatureEnabled(): Boolean =
        getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_FEATURE, true)

    private fun debugEnabled(): Boolean =
        getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_DEBUG, false)
}
