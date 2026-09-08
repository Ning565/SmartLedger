package com.smartledger.data.analytics.model

/**
 * 本地财务统计聚合结果。
 *
 * 这是**唯一**允许被送进 Prompt 的数据结构。
 *
 * 脱敏靠类型设计而不是靠自觉：这个类里根本不存在能承载敏感信息的字段
 * —— 没有 note（备注原文）、没有真实 merchant（只有匿名编号）、
 * 没有 notificationKey、没有任何逐笔时间戳序列。
 * 只要 [com.smartledger.data.analytics.SummaryFormatter] 的输入类型是它，
 * 就不可能把原始通知 / 卡号 / 手机号带出去。
 *
 * 金额沿用项目的 Double（本阶段不为 AI 重构原表金额类型），
 * 对外展示与进 Prompt 时统一格式化为两位小数，
 * 月级几百笔的累加误差在 1e-9 量级，不影响任何结论。
 */

/** 分析周期。第一版只支持这两档，与统计页的「日/周/月/年」互不干扰。 */
enum class SummaryPeriod(val label: String) {
    THIS_MONTH("本月"),
    LAST_3_MONTHS("近 3 个月");

    companion object {
        fun fromName(name: String?): SummaryPeriod =
            entries.firstOrNull { it.name == name } ?: THIS_MONTH
    }
}

/** 分类统计（已解析成中文分类名，categoryId 不外泄） */
data class CategoryStat(
    val categoryName: String,
    val amount: Double,
    /** 占总支出百分比，0~100 */
    val percent: Double,
    val count: Int
)

/** 脱敏后的商户统计 */
data class MerchantStat(
    /** 形如「餐饮商户 #1」，绝无真实商户名 */
    val anonymizedName: String,
    val amount: Double,
    val count: Int
)

/**
 * 时段桶。
 *
 * 用 5 个桶取代 24 条逐小时统计：进 Prompt 的文本量减少约 80%，
 * 而对「消费行为画像」这个用途来说，逐小时粒度并没有额外价值。
 */
enum class TimeBucket(val label: String) {
    MORNING("早间 06-11"),
    NOON("午间 11-14"),
    AFTERNOON("下午 14-18"),
    EVENING("晚间 18-22"),

    /** 夜间 22:00 ~ 次日 06:00，跨日 */
    NIGHT("夜间 22-06");

    companion object {
        /**
         * 按本地小时归桶。
         *
         * 夜间跨日：h >= 22 或 h < 6 都算 NIGHT。
         * 这是「同一笔交易发生在几点」的判定，不涉及跨日归属，口径可解释。
         */
        fun ofHour(hour: Int): TimeBucket = when (hour) {
            in 6..10 -> MORNING
            in 11..13 -> NOON
            in 14..17 -> AFTERNOON
            in 18..21 -> EVENING
            else -> NIGHT      // 22,23,0,1,2,3,4,5
        }
    }
}

data class TimeBucketStat(
    val bucket: TimeBucket,
    val amount: Double,
    val count: Int
)

data class FinancialSummary(
    val period: SummaryPeriod,
    /** 展示标签，如「2026 年 9 月」/「2026 年 7 月 ~ 9 月」 */
    val periodLabel: String,
    val periodStart: Long,
    val periodEnd: Long,

    val totalIncome: Double,
    val totalExpense: Double,
    val transactionCount: Int,

    /** 来自 budgets.totalExpenseLimit（不是 totalIncomeTarget）；未设置为 null */
    val budget: Double?,
    /** 预算已用百分比；无预算时为 null */
    val budgetUsedPercent: Double?,

    /** 上一周期**同期**支出（不是上一周期整月，否则月初对比恒为大幅下降） */
    val previousPeriodExpense: Double?,
    val expenseChangePercent: Double?,

    val categoryStats: List<CategoryStat>,
    val topMerchantStats: List<MerchantStat>,

    val weekdayExpense: Double,
    val weekendExpense: Double,
    val weekdayDailyAverage: Double,
    val weekendDailyAverage: Double,

    /** 固定 5 项，按 TimeBucket 声明顺序 */
    val timeBucketStats: List<TimeBucketStat>,
    val nightTransactionCount: Int,
    val nightExpense: Double,

    /** 周期内已过天数（含今天） */
    val daysElapsed: Int,
    /** 周期总天数 */
    val daysTotal: Int,

    /** 样本量过小时需要在 Prompt 里提示 AI 谨慎下结论 */
    val isLowSample: Boolean
) {
    companion object {
        /** 少于这个笔数时，AI 不应给出「消费画像」这类强结论 */
        const val LOW_SAMPLE_THRESHOLD = 5

        /** 空数据占位，UI 用它判断是否允许发起 AI 请求 */
        fun empty(period: SummaryPeriod, label: String, start: Long, end: Long) = FinancialSummary(
            period = period,
            periodLabel = label,
            periodStart = start,
            periodEnd = end,
            totalIncome = 0.0,
            totalExpense = 0.0,
            transactionCount = 0,
            budget = null,
            budgetUsedPercent = null,
            previousPeriodExpense = null,
            expenseChangePercent = null,
            categoryStats = emptyList(),
            topMerchantStats = emptyList(),
            weekdayExpense = 0.0,
            weekendExpense = 0.0,
            weekdayDailyAverage = 0.0,
            weekendDailyAverage = 0.0,
            timeBucketStats = TimeBucket.entries.map { TimeBucketStat(it, 0.0, 0) },
            nightTransactionCount = 0,
            nightExpense = 0.0,
            daysElapsed = 0,
            daysTotal = 0,
            isLowSample = true
        )
    }
}
