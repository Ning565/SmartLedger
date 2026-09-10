package com.smartledger.service.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.smartledger.service.AccessibilityFingerprintCache
import com.smartledger.service.AutoCaptureSource
import com.smartledger.service.AutoRecordProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

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
 * ## 线程模型（C6）
 * 主线程只做事件分发、防抖与 rootInActiveWindow 判空；
 * 树遍历（300 节点 binder 调用）、解析、指纹、入账全部在 IO 协程 ——
 * 主线程零卡顿。
 */
class PaymentAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "PaymentA11y"
        private const val DEBOUNCE_MS = 450L

        private val ALLOWED_PACKAGES = setOf(
            AccessibilityFingerprintBuilder.WECHAT_PACKAGE,
            AccessibilityFingerprintBuilder.ALIPAY_PACKAGE
        )

        private const val PREFS = "smart_ledger"
        private const val KEY_FEATURE = "accessibility_auto_record_enabled"
    }

    /** C2：Service 持有的作用域，onDestroy 取消，杜绝裸 scope 泄漏 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    private var pendingRunnable: Runnable? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        AccessibilityStatus.setConnected(applicationContext, true)
        Log.d(TAG, "Service connected")
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
        val root = rootInActiveWindow
        if (root == null) {
            // 真机校准（9/10）：事件到了但活动窗口不可用（页面切换间隙/
            // 服务被系统限制）—— 记入诊断避免静默失败无法定位
            AccessibilityDiagnostics.onRootUnavailable(packageName)
            return
        }
        AccessibilityDiagnostics.onFullScan(packageName)
        val debug = debugEnabled()

        scope.launch {
            try {
                // C6：遍历与解析全部在 IO 线程
                val snapshot = UiTreeSnapshotExtractor.extract(root, packageName)
                if (snapshot.nodes.isEmpty()) {
                    recordScanSample(debug, emptyList(), false)
                    return@launch
                }

                // 信号复查（quickProbe 只是浅层，这里用整页文本再判一次）
                val pageText = snapshot.nodes.joinToString(" ") { it.text }
                val signalHit = PaymentSignalDetector.hasStrongSignal(pageText)

                // 真机校准（9/10）：调试模式下保存页面文本样本 ——
                // 信号命中与否都记，词形不匹配的页面正是需要看到的
                recordScanSample(debug, snapshot.nodes.map { it.text }, signalHit)

                if (!signalHit) return@launch

                val parser: AccessibilityPaymentParser = when (packageName) {
                    AccessibilityFingerprintBuilder.WECHAT_PACKAGE -> WeChatAccessibilityParser
                    AccessibilityFingerprintBuilder.ALIPAY_PACKAGE -> AlipayAccessibilityParser
                    else -> return@launch
                }
                val parsed = parser.parse(snapshot) ?: return@launch

                // 三层去重的前两层在这里，第三层（DedupHelper）在 processor 里
                val key = AccessibilityFingerprintBuilder.build(packageName, snapshot, parsed)
                if (!AccessibilityFingerprintCache.shouldProcess(key)) {
                    AccessibilityDiagnostics.onDeduped()
                    return@launch
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
            } catch (e: Exception) {
                Log.e(TAG, "Full scan failed", e)
            }
        }
    }

    private fun isFeatureEnabled(): Boolean =
        getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_FEATURE, true)

    private fun debugEnabled(): Boolean =
        getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("debug_toasts", false)

    private fun recordScanSample(enabled: Boolean, texts: List<String>, signalHit: Boolean) {
        if (!enabled) return
        AccessibilityDiagnostics.onScanTexts(texts, signalHit)
    }
}
