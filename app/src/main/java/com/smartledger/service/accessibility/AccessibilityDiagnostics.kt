package com.smartledger.service.accessibility

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicIntegerArray

/**
 * 无障碍采集诊断（C5，方案 6.3；debug.4 重建）。
 *
 * ## 为什么要重建
 * 旧诊断只存「最近一次扫描的前 30 条文本、每条截断 24 字」，且不含窗口信息。
 * debug.4 真机三场景全失效时，这份输出既看不出扫的是哪个窗口、也看不出
 * 那个窗口里到底有什么 —— 排查只能靠猜。
 *
 * 现在改为存**最近 [SCAN_RING_SIZE] 次完整扫描的完整观测**
 * （见 [ScanCapture]：每个候选窗口的 root 结构 + 完整节点列表 + 信号分项判定），
 * 外加一行**服务能力**（`serviceInfo.flags`）：`windows` 拿不到窗口时，
 * 一眼就能分清是 flag 没生效还是系统限制。
 *
 * ## 隐私与开关
 * 抓取只在「调试提示」（`debug_toasts`）开启时记录，内存 only、进程重启即清；
 * 关闭开关后不再记录。内容不落盘、不上传，只有用户主动点「复制全部」才会离开设备。
 *
 * 存储：抓取与最近事件走内存 ring；今日计数走 prefs 按日 key，跨日自动重置。
 */
object AccessibilityDiagnostics {

    private const val PREFS = "smart_ledger"
    private const val KEY_TODAY = "a11y_diag_day"
    private const val KEY_COUNTS = "a11y_diag_counts"

    /** counters 顺序，与 [buildSummary] 的展示一致 */
    private const val IDX_EVENTS = 0
    private const val IDX_PROBE = 1
    private const val IDX_SCAN = 2
    private const val IDX_RECOG = 3
    private const val IDX_DEDUP = 4
    private const val COUNT_FIELDS = 5

    private const val RING_SIZE = 12

    /** 保留多少次完整扫描的抓取（够覆盖一次转账流程：转账页 → 支付成功页 → 详情页） */
    private const val SCAN_RING_SIZE = 5

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    /**
     * 最近事件摘要 ring：Service 主线程与 IO 协程都写、设置页主线程读，
     * 改用并发安全的实现（performFullScan 的 IO 协程会调 onRecognized 等）
     */
    private val recentEvents = ConcurrentLinkedDeque<String>()

    /**
     * 最近几次完整扫描的抓取（debug.4，仅调试模式记录）。
     * Service 的 IO 线程写、设置页主线程读 → ConcurrentLinkedDeque 保证安全；
     * 整体快照读，不会读到写一半的列表。
     */
    private val scanCaptures = ConcurrentLinkedDeque<ScanCapture>()

    /** 服务能力摘要（onServiceConnected 时上报，用于判断 flag 是否真的生效） */
    @Volatile
    private var capabilities: String? = null

    /** 最近一次 `windows` 读取的结果（数量或报错），独立于单次扫描展示 */
    @Volatile
    private var lastWindowsNote: String? = null

    // ═══ 写入端（Service 调用） ═══

    /**
     * 上报无障碍服务的**实际生效**配置。
     *
     * `accessibilityFlags` 写在 XML 里不等于运行时生效（用户可能在系统设置里
     * 改过、厂商 ROM 可能裁剪）。`windows` 返回空时，这一行是唯一的判据。
     */
    fun onCapabilities(flags: Int, canRetrieveWindowContent: Boolean) {
        capabilities = "flags=0x${Integer.toHexString(flags)} " +
            "canRetrieveWindowContent=$canRetrieveWindowContent"
    }

    /** 记录一次完整扫描的完整观测；同时刷新 [lastWindowsNote] */
    fun onScanCapture(capture: ScanCapture) {
        lastWindowsNote = capture.windowsNote
        while (scanCaptures.size >= SCAN_RING_SIZE) scanCaptures.pollFirst()
        scanCaptures.addLast(capture)
    }

    /**
     * @param eventType AccessibilityEvent.eventType。debug.6 起进 ring ——
     *   `STATE` 才是**直接**触发完整扫描的那条路，`CONTENT` 要先过 quickProbe。
     *   debug.5 真机 172 个事件 / 0 次探测命中 / 19 次扫描，光看「事件」两个字
     *   分不清是「CONTENT 全被拦」还是「STATE 本来就少」，这条能一眼分开。
     */
    fun onEvent(pkg: String, eventType: Int) {
        pushRing("${packageAlias(pkg)} 事件 ${eventTypeLabel(eventType)} ${timeFmt.format(Date())}")
        bump(IDX_EVENTS)
    }

    fun onProbeHit(pkg: String) {
        pushRing("${packageAlias(pkg)} 探测命中 ${timeFmt.format(Date())}")
        bump(IDX_PROBE)
    }

    fun onFullScan(pkg: String) {
        pushRing("${packageAlias(pkg)} 完整扫描 ${timeFmt.format(Date())}")
        bump(IDX_SCAN)
    }

    fun onRecognized(type: String, amount: Double) {
        val label = if (type == "income") "收入" else "支出"
        pushRing("识别 $label ¥${"%.2f".format(Locale.US, amount)} ${timeFmt.format(Date())}")
        bump(IDX_RECOG)
    }

    fun onDeduped() {
        pushRing("指纹去重拦截 ${timeFmt.format(Date())}")
        bump(IDX_DEDUP)
    }

    /** 真机校准（9/10）：事件到了但 rootInActiveWindow 不可用 ——
     *  不计入扫描计数，但进 ring 让用户能在诊断里看到 */
    fun onRootUnavailable(pkg: String) {
        pushRing("${packageAlias(pkg)} 窗口不可用（页面切换间隙或系统限制） ${timeFmt.format(Date())}")
    }

    // ═══ 读取端（设置页调用） ═══

    /** 最近抓取的文本化输出（纯函数 [ScanCaptureFormatter] 负责格式） */
    fun captureSummary(): List<String> = scanCaptures.toList().reversed().flatMap {
        ScanCaptureFormatter.format(it)
    }

    /** 诊断摘要文本（设置页弹窗展示 + 「复制全部」共用同一份） */
    fun buildSummary(context: Context): String {
        val counts = readCounts(context)
        val events = recentEvents.toList()
        return buildString {
            // 生成时间必须打头：弹窗的文本是**打开那一刻**算一次的（SettingsScreen
            // 里的 remember），用户开着弹窗去转账、切回来看到的仍是旧快照。
            // debug.5 实测时用户前后发来的两份 dump 逐字相同、最近动态却停在
            // 转账之前 3 分钟 —— 没有这一行就分不清是「没记录」还是「没刷新」。
            appendLine("生成时间：${timeFmt.format(Date())}（关闭弹窗再打开可刷新）")
            appendLine()
            appendLine("今日计数：")
            appendLine("· Accessibility 事件：${counts[IDX_EVENTS]}")
            appendLine("· 探测命中：${counts[IDX_PROBE]}")
            appendLine("· 完整扫描：${counts[IDX_SCAN]}")
            appendLine("· 成功识别：${counts[IDX_RECOG]}")
            appendLine("· 指纹去重：${counts[IDX_DEDUP]}")
            appendLine()
            appendLine("服务能力：")
            appendLine("· ${capabilities ?: "尚未上报 —— 服务本次进程内未连接过"}")
            lastWindowsNote?.let { appendLine("· 最近一次 windows：$it") }
            appendLine()
            appendLine("最近动态：")
            if (events.isEmpty()) {
                appendLine("（暂无 —— 服务未收到任何微信/支付宝事件）")
            } else {
                events.reversed().forEach { appendLine("· $it") }
            }
            appendLine()
            appendLine("最近扫描抓取（调试模式，最近 $SCAN_RING_SIZE 次完整扫描）：")
            val captures = captureSummary()
            if (captures.isEmpty()) {
                appendLine("（暂无 —— 未开调试模式，或服务从未执行过完整扫描）")
            } else {
                captures.forEach { appendLine(it) }
            }
        }
    }

    // ═══ 计数（prefs，按日重置） ═══

    private fun bump(index: Int) {
        // Service 主线程与 IO 协程都会调 —— 原子操作
        memCounts.getAndIncrement(index)
    }

    private val memCounts = AtomicIntegerArray(COUNT_FIELDS)

    private fun readCounts(context: Context): IntArray {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = todayKey()
        val savedDay = prefs.getString(KEY_TODAY, null)
        if (savedDay == today) {
            val saved = prefs.getString(KEY_COUNTS, null)?.split(",")
                ?.mapNotNull { it.toIntOrNull() } ?: emptyList()
            val merged = IntArray(COUNT_FIELDS)
            for (i in 0 until COUNT_FIELDS) {
                merged[i] = (saved.getOrNull(i) ?: 0) + memCounts.getAndSet(i, 0)
            }
            // 读取即结算：把进程内累计并入 prefs 并清零内存。
            // 否则每次弹窗都会把同一批 memCounts 再加一遍，计数虚高。
            prefs.edit()
                .putString(KEY_COUNTS, merged.joinToString(","))
                .apply()
            return merged
        }
        // 跨日：只保留本次进程内的计数（今天的）
        val todayCounts = IntArray(COUNT_FIELDS) { memCounts.getAndSet(it, 0) }
        prefs.edit()
            .putString(KEY_TODAY, today)
            .putString(KEY_COUNTS, todayCounts.joinToString(","))
            .apply()
        return todayCounts
    }

    private fun todayKey(): String =
        SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    private fun pushRing(item: String) {
        while (recentEvents.size >= RING_SIZE) recentEvents.pollFirst()
        recentEvents.addLast(item)
    }
}
