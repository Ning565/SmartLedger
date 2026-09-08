package com.smartledger.data.analytics

import com.smartledger.data.analytics.model.FinancialSummary
import com.smartledger.data.analytics.model.TimeBucket
import java.util.Locale

/**
 * 把 [FinancialSummary] 渲染成进 Prompt 的纯文本。
 *
 * 这是**唯一**能产出 Prompt 数据段的地方，且输入类型只能是 FinancialSummary
 * —— 类型层面就杜绝了「有人图省事直接把 Transaction 列表塞进 Prompt」。
 *
 * 同时负责 [canonicalJson]：给 FinancialSummary 做稳定序列化，
 * 用于计算 dataHash 判断 AI 报告缓存能否复用。
 */
object SummaryFormatter {

    // ═══════════════════════════════════════════════════
    // Prompt 文本
    // ═══════════════════════════════════════════════════

    /** 完整数据段，即真正发给 AI 的内容 */
    fun full(s: FinancialSummary): String = buildString {
        appendLine(headerSection(s))
        appendLine()
        appendLine(categorySection(s))
        appendLine()
        appendLine(merchantSection(s))
        appendLine()
        appendLine(weekdayWeekendSection(s))
        appendLine()
        appendLine(timeBucketSection(s))
        appendLine()
        appendLine(nightSection(s))
        appendLine()
        append(historySection(s))
    }.trim()

    fun headerSection(s: FinancialSummary): String = buildString {
        append("周期：").append(s.periodLabel)
        if (s.daysTotal > 0 && s.daysElapsed > 0 && s.daysElapsed < s.daysTotal) {
            append("（已过 ").append(s.daysElapsed).append(" 天 / 共 ").append(s.daysTotal).append(" 天）")
        } else if (s.daysTotal > 0) {
            append("（共 ").append(s.daysTotal).append(" 天）")
        }
        append('\n')

        if (s.budget != null && s.budget > 0) {
            append("预算（支出上限）：").append(money(s.budget)).append(" 元")
            if (s.budgetUsedPercent != null) {
                append("，已用 ").append(pct(s.budgetUsedPercent))
            }
            append('\n')
        } else {
            append("预算（支出上限）：用户未设置\n")
        }

        append("实际支出：").append(money(s.totalExpense)).append(" 元\n")
        append("收入：").append(money(s.totalIncome)).append(" 元\n")

        val expenseCount = s.categoryStats.sumOf { it.count }
        append("记账笔数：").append(s.transactionCount).append(" 笔")
        append("（其中支出 ").append(expenseCount).append(" 笔）")
        if (s.isLowSample) {
            append("\n注意：本周期支出样本量较小（仅 ").append(expenseCount)
                .append(" 笔），请谨慎给出结论，不要据此做强断言。")
        }
    }

    fun categorySection(s: FinancialSummary): String {
        if (s.categoryStats.isEmpty()) return "主要支出分类：\n本周期无支出记录"
        return buildString {
            appendLine("主要支出分类：")
            s.categoryStats.forEach { c ->
                append(c.categoryName).append(' ')
                    .append(money(c.amount)).append(" 元 / ")
                    .append(pct(c.percent)).append(" / ")
                    .append(c.count).append(" 笔")
                appendLine()
            }
        }.trimEnd()
    }

    fun merchantSection(s: FinancialSummary): String {
        if (s.topMerchantStats.isEmpty()) return "高频消费商户（已匿名）：\n无可识别商户信息"
        return buildString {
            appendLine("高频消费商户（已匿名）：")
            s.topMerchantStats.forEach { m ->
                append(m.anonymizedName).append("：")
                    .append(m.count).append(" 笔，共 ")
                    .append(money(m.amount)).append(" 元")
                appendLine()
            }
        }.trimEnd()
    }

    fun weekdayWeekendSection(s: FinancialSummary): String = buildString {
        appendLine("工作日 / 周末：")
        val wd = weekendWorkdayDays(s, weekend = false)
        val we = weekendWorkdayDays(s, weekend = true)
        append("工作日支出 ").append(money(s.weekdayExpense)).append(" 元")
        if (wd != null) append("（").append(wd).append(" 天，日均 ").append(money(s.weekdayDailyAverage)).append(" 元）")
        appendLine()
        append("周末支出 ").append(money(s.weekendExpense)).append(" 元")
        if (we != null) append("（").append(we).append(" 天，日均 ").append(money(s.weekendDailyAverage)).append(" 元）")
        appendLine()
        val total = s.weekdayExpense + s.weekendExpense
        if (total > 0) {
            append("周末支出占比 ")
                .append(pct(SummaryAggregator.round1(s.weekendExpense / total * 100.0)))
        } else {
            append("周末支出占比：无数据")
        }
    }.trimEnd()

    fun timeBucketSection(s: FinancialSummary): String {
        if (s.totalExpense <= 0) return "时段分布：\n无支出记录"
        return buildString {
            appendLine("时段分布：")
            s.timeBucketStats.forEach { b ->
                append(b.bucket.label).append("：")
                    .append(money(b.amount)).append(" 元 / ")
                    .append(b.count).append(" 笔")
                appendLine()
            }
        }.trimEnd()
    }

    fun nightSection(s: FinancialSummary): String {
        val night = s.timeBucketStats.firstOrNull { it.bucket == TimeBucket.NIGHT }
        return if (night == null || night.count == 0) {
            "夜间消费（22:00 ~ 次日 06:00）：本周期无夜间消费"
        } else {
            "夜间消费（22:00 ~ 次日 06:00）：${night.count} 笔，共 ${money(night.amount)} 元" +
                    if (s.totalExpense > 0) {
                        "，占总支出 ${pct(SummaryAggregator.round1(night.amount / s.totalExpense * 100.0))}"
                    } else ""
        }
    }

    fun historySection(s: FinancialSummary): String {
        val prev = s.previousPeriodExpense
        if (prev == null || prev <= 0) {
            return "历史对比：\n无可比历史数据"
        }
        val change = s.expenseChangePercent
        return buildString {
            appendLine("历史对比：")
            append("上一周期同期支出 ").append(money(prev)).append(" 元")
            if (change != null) {
                appendLine()
                append("本周期较上一周期")
                append(
                    when {
                        change > 0 -> "增加 ${pct(change)}"
                        change < 0 -> "减少 ${pct(-change)}"
                        else -> "持平"
                    }
                )
            }
        }.trimEnd()
    }

    // ═══════════════════════════════════════════════════
    // 稳定序列化（用于 dataHash）
    // ═══════════════════════════════════════════════════

    /**
     * 规范化 JSON，用于计算 dataHash 判断缓存能否复用。
     *
     * 稳定性要求（任何一条被破坏都会导致缓存永不命中、白白烧 token）：
     *  1. 字段顺序在代码里**硬编码**，不用反射、不遍历 Map；
     *  2. 所有金额统一 `%.2f` + [Locale.US]，避免 `4280.0` 与 `4280.00` 产生不同 hash，
     *     也避免中文 Locale 下小数点变成逗号；
     *  3. 不含 now / createdAt 等随调用变化的字段；
     *  4. **要**含 daysElapsed —— 同样的金额、不同的已过天数，结论理应不同。
     *
     * 手写拼接而不是用 JSONObject：JSONObject 内部是 HashMap，
     * 键序在不同 JVM/Android 版本上不保证一致，会让 hash 无端漂移。
     */
    fun canonicalJson(s: FinancialSummary): String = buildString {
        append('{')
        kv("period", s.period.name); append(',')
        kv("label", s.periodLabel); append(',')
        kv("start", s.periodStart.toString()); append(',')
        kv("end", s.periodEnd.toString()); append(',')
        kv("income", money(s.totalIncome)); append(',')
        kv("expense", money(s.totalExpense)); append(',')
        kv("count", s.transactionCount.toString()); append(',')
        kv("budget", s.budget?.let { money(it) } ?: "null"); append(',')
        kv("budgetUsed", s.budgetUsedPercent?.let { pct(it) } ?: "null"); append(',')
        kv("prevExpense", s.previousPeriodExpense?.let { money(it) } ?: "null"); append(',')
        kv("change", s.expenseChangePercent?.let { pct(it) } ?: "null"); append(',')
        kv("daysElapsed", s.daysElapsed.toString()); append(',')
        kv("daysTotal", s.daysTotal.toString()); append(',')
        kv("weekday", money(s.weekdayExpense)); append(',')
        kv("weekend", money(s.weekendExpense)); append(',')
        kv("weekdayAvg", money(s.weekdayDailyAverage)); append(',')
        kv("weekendAvg", money(s.weekendDailyAverage)); append(',')
        kv("nightCount", s.nightTransactionCount.toString()); append(',')
        kv("nightAmount", money(s.nightExpense)); append(',')
        kv("lowSample", s.isLowSample.toString()); append(',')

        append("\"categories\":[")
        s.categoryStats.forEachIndexed { i, c ->
            if (i > 0) append(',')
            append('{')
            kv("n", c.categoryName); append(',')
            kv("a", money(c.amount)); append(',')
            kv("p", pct(c.percent)); append(',')
            kv("c", c.count.toString())
            append('}')
        }
        append("],")

        append("\"merchants\":[")
        s.topMerchantStats.forEachIndexed { i, m ->
            if (i > 0) append(',')
            append('{')
            kv("n", m.anonymizedName); append(',')
            kv("a", money(m.amount)); append(',')
            kv("c", m.count.toString())
            append('}')
        }
        append("],")

        append("\"buckets\":[")
        s.timeBucketStats.forEachIndexed { i, b ->
            if (i > 0) append(',')
            append('{')
            kv("n", b.bucket.name); append(',')
            kv("a", money(b.amount)); append(',')
            kv("c", b.count.toString())
            append('}')
        }
        append(']')

        append('}')
    }

    private fun StringBuilder.kv(key: String, value: String) {
        append('"').append(key).append("\":\"").append(escape(value)).append('"')
    }

    private fun escape(v: String): String = v
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")

    // ═══════════════════════════════════════════════════
    // 格式化（全 App 统一口径）
    // ═══════════════════════════════════════════════════

    /** 金额固定两位小数，用 Locale.US 保证分隔符稳定 */
    fun money(v: Double): String {
        if (v.isNaN() || v.isInfinite()) return "0.00"
        return String.format(Locale.US, "%.2f", v)
    }

    /** 百分比固定一位小数 */
    fun pct(v: Double): String {
        if (v.isNaN() || v.isInfinite()) return "0.0%"
        return String.format(Locale.US, "%.1f%%", v)
    }

    /**
     * 工作日 / 周末的天数（仅用于文案补充）。
     *
     * FinancialSummary 里没有直接存这两个天数，是为了让 dataHash 少一个字段；
     * 这里用「日均 × 天数 = 总额」反推，日均为 0 时说明没有该类天数，返回 null。
     */
    private fun weekendWorkdayDays(s: FinancialSummary, weekend: Boolean): Int? {
        val total = if (weekend) s.weekendExpense else s.weekdayExpense
        val avg = if (weekend) s.weekendDailyAverage else s.weekdayDailyAverage
        if (avg <= 0.0 || total <= 0.0) return null
        return SummaryAggregator.round1(total / avg).toInt().takeIf { it > 0 }
    }
}
