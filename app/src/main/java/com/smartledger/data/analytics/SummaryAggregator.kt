package com.smartledger.data.analytics

import com.smartledger.data.analytics.model.CategoryStat
import com.smartledger.data.analytics.model.FinancialSummary
import com.smartledger.data.analytics.model.SummaryPeriod
import com.smartledger.data.analytics.model.TimeBucket
import com.smartledger.data.analytics.model.TimeBucketStat
import com.smartledger.data.analytics.model.TxPoint
import java.util.Calendar
import java.util.TimeZone

/**
 * 聚合核心（纯函数，不接 Context，可完整单测）。
 *
 * ## 为什么金额用「分（Long）」累加而不是 Double
 *
 * 两个理由，第二个尤其隐蔽：
 *
 * 1. **精度**：`amount` 是 Double，0.1 这类值在二进制下不能精确表示，
 *    几百笔累加会漂出 `4279.999999999998` 这种尾数。
 *
 * 2. **顺序无关性 —— 直接决定 AI 报告缓存能否命中**。
 *    DAO 的投影查询没有 ORDER BY，SQLite 不保证行序稳定；
 *    而浮点加法不满足结合律，`(a+b)+c != a+(b+c)`。
 *    这意味着同样的数据、两次查询、累加顺序不同，
 *    就可能得到最后一位不同的总额 → `canonicalJson` 不同 → `dataHash` 不同
 *    → **缓存永远命不中，用户每次点开都重新烧一次 token**。
 *
 *    转成整数分之后求和是精确且可交换的，彻底消除这个问题。
 */
object SummaryAggregator {

    /** 未分类交易的展示名 */
    const val UNCATEGORIZED = "未分类"

    fun aggregate(
        period: SummaryPeriod,
        range: SummaryPeriods.Range,
        now: Long,
        tz: TimeZone,
        points: List<TxPoint>,
        categoryNameById: Map<Long, String>,
        budget: Double?,
        previousPeriodExpense: Double?,
        topMerchantLimit: Int = 10
    ): FinancialSummary {

        val daysTotal = SummaryPeriods.daysTotal(range, tz)
        val daysElapsed = SummaryPeriods.daysElapsed(range, now, tz)

        if (points.isEmpty()) {
            return FinancialSummary.empty(period, range.label, range.start, range.end)
                .copy(
                    budget = budget,
                    daysTotal = daysTotal,
                    daysElapsed = daysElapsed
                )
        }

        val cal = Calendar.getInstance(tz)

        // ── 单趟遍历，把所有维度一次算完 ──
        var incomeCents = 0L
        var expenseCents = 0L

        val categoryCents = HashMap<String, Long>()
        val categoryCount = HashMap<String, Int>()

        /** merchant -> [cents, count, 最大单笔 cents, 该笔的 categoryId] */
        val merchantAgg = HashMap<String, MerchantAcc>()

        val bucketCents = TimeBucket.entries.associateWith { 0L }.toMutableMap()
        val bucketCount = TimeBucket.entries.associateWith { 0 }.toMutableMap()

        var weekendCents = 0L
        var weekdayCents = 0L

        points.forEach { p ->
            val cents = toCents(p.amount)
            if (cents <= 0L) return@forEach      // 0 元或负数不进统计，避免污染占比

            if (p.type == TYPE_INCOME) {
                incomeCents += cents
                return@forEach
            }
            if (p.type != TYPE_EXPENSE) return@forEach   // 脏数据（大小写异常等）直接跳过

            expenseCents += cents

            // 分类
            val catName = p.categoryId?.let { categoryNameById[it] }
                ?.takeIf { it.isNotBlank() } ?: UNCATEGORIZED
            categoryCents[catName] = (categoryCents[catName] ?: 0L) + cents
            categoryCount[catName] = (categoryCount[catName] ?: 0) + 1

            // 商户：归一化规则必须与 MerchantAnonymizer 完全一致，
            // 否则聚合层分成两组、匿名层又归为同一编号，
            // Prompt 里会出现两行同名的「餐饮商户 #1」。
            val merchantKey = MerchantAnonymizer.normalizeKey(p.merchant)
            if (merchantKey.isNotEmpty()) {
                val acc = merchantAgg.getOrPut(merchantKey) { MerchantAcc() }
                acc.cents += cents
                acc.count += 1
                // 主导分类 = 金额最大的那一笔所属分类；
                // 平手时按 categoryId 升序，保证与行序无关
                if (cents > acc.maxCents ||
                    (cents == acc.maxCents && smallerCategory(p.categoryId, acc.maxCategoryId))
                ) {
                    acc.maxCents = cents
                    acc.maxCategoryId = p.categoryId
                }
            }

            // 时段桶 + 工作日/周末：必须用设备本地时区
            cal.timeInMillis = p.transactionTime
            val hour = cal.get(Calendar.HOUR_OF_DAY)
            val bucket = TimeBucket.ofHour(hour)
            bucketCents[bucket] = (bucketCents[bucket] ?: 0L) + cents
            bucketCount[bucket] = (bucketCount[bucket] ?: 0) + 1

            val dow = cal.get(Calendar.DAY_OF_WEEK)
            if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) {
                weekendCents += cents
            } else {
                weekdayCents += cents
            }
        }

        // ── 分类统计（金额降序，平手按名字保证稳定）──
        val categoryStats = categoryCents.entries
            .map { (name, cents) ->
                CategoryStat(
                    categoryName = name,
                    amount = fromCents(cents),
                    percent = percentOf(cents, expenseCents),
                    count = categoryCount[name] ?: 0
                )
            }
            .sortedWith(compareByDescending<CategoryStat> { it.amount }.thenBy { it.categoryName })

        // ── 商户脱敏 Top N ──
        val merchantRows = merchantAgg.entries
            .map { (name, acc) ->
                MerchantAnonymizer.MerchantRow(
                    merchant = name,
                    amount = fromCents(acc.cents),
                    count = acc.count,
                    categoryName = acc.maxCategoryId?.let { categoryNameById[it] }
                )
            }
            .sortedWith(
                compareByDescending<MerchantAnonymizer.MerchantRow> { it.amount }
                    .thenBy { it.merchant }
            )
            .take(topMerchantLimit.coerceAtLeast(0))
        val topMerchantStats = MerchantAnonymizer.anonymize(merchantRows)

        // ── 工作日 / 周末日均 ──
        //
        // 分母是「周期内**已过**的工作日 / 周末天数」，不是整个周期的天数。
        // 否则月初算出来的日均会被大量尚未到来的日子摊薄，
        // 「周末日均 1177 元」会变成「周末日均 100 元」这种毫无意义的数字。
        val elapsedWeekdays = SummaryPeriods.elapsedWeekdayCount(range, now, tz)
        val elapsedWeekends = SummaryPeriods.elapsedWeekendCount(range, now, tz)

        val timeBucketStats = TimeBucket.entries.map { b ->
            TimeBucketStat(
                bucket = b,
                amount = fromCents(bucketCents[b] ?: 0L),
                count = bucketCount[b] ?: 0
            )
        }
        val nightCents = bucketCents[TimeBucket.NIGHT] ?: 0L

        val expenseCount = points.count { it.type == TYPE_EXPENSE && toCents(it.amount) > 0L }

        return FinancialSummary(
            period = period,
            periodLabel = range.label,
            periodStart = range.start,
            periodEnd = range.end,
            totalIncome = fromCents(incomeCents),
            totalExpense = fromCents(expenseCents),
            transactionCount = points.size,
            budget = budget,
            budgetUsedPercent = budget?.takeIf { it > 0 }
                ?.let { round1(fromCents(expenseCents) / it * 100.0) },
            previousPeriodExpense = previousPeriodExpense,
            expenseChangePercent = changePercent(expenseCents, previousPeriodExpense),
            categoryStats = categoryStats,
            topMerchantStats = topMerchantStats,
            weekdayExpense = fromCents(weekdayCents),
            weekendExpense = fromCents(weekendCents),
            weekdayDailyAverage = dailyAverage(weekdayCents, elapsedWeekdays),
            weekendDailyAverage = dailyAverage(weekendCents, elapsedWeekends),
            timeBucketStats = timeBucketStats,
            nightTransactionCount = bucketCount[TimeBucket.NIGHT] ?: 0,
            nightExpense = fromCents(nightCents),
            daysElapsed = daysElapsed,
            daysTotal = daysTotal,
            isLowSample = expenseCount < FinancialSummary.LOW_SAMPLE_THRESHOLD
        )
    }

    // ═══ 内部 ═══

    private const val TYPE_EXPENSE = "expense"
    private const val TYPE_INCOME = "income"

    private class MerchantAcc {
        var cents: Long = 0L
        var count: Int = 0
        var maxCents: Long = -1L
        var maxCategoryId: Long? = null
    }

    private fun smallerCategory(candidate: Long?, current: Long?): Boolean {
        if (candidate == null) return false
        if (current == null) return true
        return candidate < current
    }

    /** Double 金额 → 整数分。用 round 而不是 toLong，避免 32.29 → 3228 的截断误差 */
    fun toCents(amount: Double): Long {
        if (amount.isNaN() || amount.isInfinite()) return 0L
        return Math.round(amount * 100.0)
    }

    fun fromCents(cents: Long): Double = cents / 100.0

    private fun percentOf(part: Long, total: Long): Double =
        if (total <= 0L) 0.0 else round1(part.toDouble() / total.toDouble() * 100.0)

    private fun dailyAverage(cents: Long, days: Int): Double =
        if (days <= 0) 0.0 else round2(fromCents(cents) / days)

    /**
     * 环比变化百分比。
     *
     * 上期为 0 / null / 负数时返回 null（而不是 Infinity 或 100%），
     * 由 Formatter 输出「无可比历史数据」。
     */
    private fun changePercent(currentCents: Long, previous: Double?): Double? {
        if (previous == null || previous.isNaN() || previous.isInfinite()) return null
        val prevCents = toCents(previous)
        if (prevCents <= 0L) return null
        return round1((currentCents - prevCents).toDouble() / prevCents.toDouble() * 100.0)
    }

    fun round1(v: Double): Double = Math.round(v * 10.0) / 10.0
    fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0
}
