package com.smartledger.service.accessibility

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicIntegerArray

/**
 * 无障碍采集诊断（C5，方案 6.3）。
 *
 * 只在 debug_toasts 开启或 BuildConfig.DEBUG 时由设置页展示；
 * 计数与摘要不落页面全文 —— 与方案 6.3 的 Release 日志红线一致。
 *
 * 唯一例外是 [lastScanSample]（真机校准 9/10）：调试模式下保存
 * **最近一次完整扫描**的前 30 条节点文本（每条截断 24 字，内存 only，
 * 进程重启即清，关闭调试开关后不再记录）。它的唯一用途是让用户
 * 能直接看到真实页面的无障碍文本 —— 用于校准词表（预设词形与
 * 真实页面不符是首轮真机测试三场景全部失效的根因）。
 *
 * 存储：最近事件走内存 ring（进程重启即清，够诊断用）；
 * 今日计数走 prefs 按日 key，跨日自动重置。
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

    private const val RING_SIZE = 8

    /** 扫描样本上限（条数）与单条截断长度 */
    private const val SAMPLE_MAX_ITEMS = 30
    private const val SAMPLE_MAX_CHARS = 24

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    /**
     * 最近事件摘要 ring：Service 主线程与 IO 协程都写、设置页主线程读，
     * 改用并发安全的实现（performFullScan 的 IO 协程会调 onRecognized 等）
     */
    private val recentEvents = ConcurrentLinkedDeque<String>()

    /**
     * 最近一次完整扫描的页面文本样本（真机校准，仅调试模式记录）。
     * Service 的 IO 线程写、设置页主线程读 → @Volatile 保证可见性；
     * 整体替换而非逐条修改，读到的要么是旧快照要么是新快照，不会交错。
     */
    @Volatile
    private var lastScanSample: List<String> = emptyList()

    /** 调试模式下记录最近一次完整扫描的节点文本（信号是否命中都记） */
    fun onScanTexts(texts: List<String>, signalHit: Boolean) {
        lastScanSample = buildList {
            add((if (signalHit) "信号命中" else "信号未命中") + "，节点文本：")
            texts.take(SAMPLE_MAX_ITEMS).forEach { add("· " + it.take(SAMPLE_MAX_CHARS)) }
        }
    }

    /** 最近扫描样本（设置页诊断弹窗展示） */
    fun sampleSummary(): List<String> = lastScanSample

    fun onEvent(pkg: String) {
        pushRing("${alias(pkg)} 事件 ${timeFmt.format(Date())}")
        bump(IDX_EVENTS)
    }

    fun onProbeHit(pkg: String) {
        pushRing("${alias(pkg)} 探测命中 ${timeFmt.format(Date())}")
        bump(IDX_PROBE)
    }

    fun onFullScan(pkg: String) {
        pushRing("${alias(pkg)} 完整扫描 ${timeFmt.format(Date())}")
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
        pushRing("${alias(pkg)} 窗口不可用（页面切换间隙或系统限制） ${timeFmt.format(Date())}")
    }

    /**
     * 真机校准（9/10 debug.4）：完整扫描拿到**空节点树** ——
     * rootInfo 区分「指错窗口」（root 是密码键盘等壳）与「自绘页面」
     * （root 正常但无文本）；备选窗口数 > 0 时多窗口遍历已尝试。
     */
    fun onEmptyTree(pkg: String, rootInfo: String, altWindows: Int) {
        pushRing("${alias(pkg)} 树为空 $rootInfo 备选窗口$altWindows ${timeFmt.format(Date())}")
    }

    /** 诊断摘要文本（设置页弹窗展示） */
    fun buildSummary(context: Context): String {
        val counts = readCounts(context)
        val events = recentEvents.toList()
        return buildString {
            appendLine("今日计数：")
            appendLine("· Accessibility 事件：${counts[IDX_EVENTS]}")
            appendLine("· 探测命中：${counts[IDX_PROBE]}")
            appendLine("· 完整扫描：${counts[IDX_SCAN]}")
            appendLine("· 成功识别：${counts[IDX_RECOG]}")
            appendLine("· 指纹去重：${counts[IDX_DEDUP]}")
            appendLine()
            appendLine("最近动态：")
            if (events.isEmpty()) {
                appendLine("（暂无 —— 服务未收到任何微信/支付宝事件）")
            } else {
                events.reversed().forEach { appendLine("· $it") }
            }
            appendLine()
            appendLine("最近扫描样本（调试模式，最多30条）：")
            val sample = lastScanSample
            if (sample.isEmpty()) {
                appendLine("（暂无 —— 未开调试模式，或服务从未执行过完整扫描）")
            } else {
                sample.forEach { appendLine(it) }
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

    private fun alias(pkg: String): String = when (pkg) {
        AccessibilityFingerprintBuilder.WECHAT_PACKAGE -> "微信"
        AccessibilityFingerprintBuilder.ALIPAY_PACKAGE -> "支付宝"
        else -> pkg.substringAfterLast('.')
    }
}
