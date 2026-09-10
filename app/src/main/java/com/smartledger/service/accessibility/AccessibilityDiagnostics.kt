package com.smartledger.service.accessibility

import android.content.Context
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 无障碍采集诊断（C5，方案 6.3）。
 *
 * 只在 debug_toasts 开启或 BuildConfig.DEBUG 时由设置页展示；
 * 数据本身只记**数量与极短摘要**（包名 + 结果），不落页面全文 ——
 * 与方案 6.3 的 Release 日志红线一致。
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

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    /** 最近事件摘要 ring：Service 主线程写、设置页主线程读，同线程模型无需加锁 */
    private val recentEvents = ArrayDeque<String>()

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
        }
    }

    // ═══ 计数（prefs，按日重置） ═══

    private fun bump(index: Int) {
        // 计数只在 Service 进程里发生；无 Context 的内存兜底计数，读时合并 prefs
        memCounts[index]++
    }

    private val memCounts = IntArray(COUNT_FIELDS)

    private fun readCounts(context: Context): IntArray {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = todayKey()
        val savedDay = prefs.getString(KEY_TODAY, null)
        if (savedDay == today) {
            val saved = prefs.getString(KEY_COUNTS, null)?.split(",")
                ?.mapNotNull { it.toIntOrNull() } ?: emptyList()
            val merged = IntArray(COUNT_FIELDS)
            for (i in 0 until COUNT_FIELDS) {
                merged[i] = (saved.getOrNull(i) ?: 0) + memCounts[i]
            }
            // 读取即结算：把进程内累计并入 prefs 并清零内存。
            // 否则每次弹窗都会把同一批 memCounts 再加一遍，计数虚高。
            memCounts.fill(0)
            prefs.edit()
                .putString(KEY_COUNTS, merged.joinToString(","))
                .apply()
            return merged
        }
        // 跨日：只保留本次进程内的计数（今天的）
        return memCounts.copyOf().also {
            memCounts.fill(0)
            prefs.edit()
                .putString(KEY_TODAY, today)
                .putString(KEY_COUNTS, it.joinToString(","))
                .apply()
        }
    }

    private fun todayKey(): String =
        SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    private fun pushRing(item: String) {
        while (recentEvents.size >= RING_SIZE) recentEvents.removeFirst()
        recentEvents.addLast(item)
    }

    private fun alias(pkg: String): String = when (pkg) {
        AccessibilityFingerprintBuilder.WECHAT_PACKAGE -> "微信"
        AccessibilityFingerprintBuilder.ALIPAY_PACKAGE -> "支付宝"
        else -> pkg.substringAfterLast('.')
    }
}
