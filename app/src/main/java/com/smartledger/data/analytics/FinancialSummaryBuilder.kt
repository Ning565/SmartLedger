package com.smartledger.data.analytics

import com.smartledger.data.analytics.model.FinancialSummary
import com.smartledger.data.analytics.model.SummaryPeriod
import com.smartledger.data.db.dao.BudgetDao
import com.smartledger.data.db.dao.CategoryDao
import com.smartledger.data.db.dao.TransactionDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.TimeZone

/**
 * 聚合入口：负责取数，然后把计算全部交给纯函数 [SummaryAggregator]。
 *
 * 这样拆分的目的是让**所有会被单测覆盖的逻辑都不碰 DAO**：
 * Builder 只做 IO 编排（薄薄一层），Aggregator 做全部数学。
 */
class FinancialSummaryBuilder(
    private val transactionDao: TransactionDao,
    private val categoryDao: CategoryDao,
    private val budgetDao: BudgetDao
) {

    /**
     * @param now 显式传入便于测试；生产用默认值
     * @param tz  必须用设备默认时区，理由见 [SummaryPeriods]
     */
    suspend fun build(
        period: SummaryPeriod,
        now: Long = System.currentTimeMillis(),
        tz: TimeZone = TimeZone.getDefault()
    ): FinancialSummary = withContext(Dispatchers.IO) {
        val range = SummaryPeriods.current(period, now, tz)

        val points = transactionDao.getPointsInRange(range.start, range.end)
        val categoryNameById = categoryDao.getAllOnce()
            .associate { it.id to it.name }

        val budget = resolveBudget(period, range, now, tz)

        // 上一周期同期支出：只需要一个合计，不必把上期的点全捞出来
        val prevRange = SummaryPeriods.previous(period, now, tz)
        val previousExpense = prevRange
            ?.takeIf { it.end >= it.start }
            ?.let { transactionDao.getExpenseSumOnce(it.start, it.end) }
            ?.takeIf { it > 0.0 }

        SummaryAggregator.aggregate(
            period = period,
            range = range,
            now = now,
            tz = tz,
            points = points,
            categoryNameById = categoryNameById,
            budget = budget,
            previousPeriodExpense = previousExpense
        )
    }

    /**
     * 预算取值。
     *
     * 用的是 `totalExpenseLimit`（支出上限），**不是** `totalIncomeTarget`（收入目标）。
     *
     * 近 3 月周期的处理：只有当区间内**每个月都设了上限**时才求和返回。
     * 部分月份设了、部分没设时返回 null —— 否则「预算达成率」会拿
     * 3 个月的支出去除以 1 个月的预算，算出 300% 这种误导性数字，
     * AI 会据此写出一整段错误结论。宁可如实说「用户未设置预算」。
     */
    private suspend fun resolveBudget(
        period: SummaryPeriod,
        range: SummaryPeriods.Range,
        now: Long,
        tz: TimeZone
    ): Double? = when (period) {
        SummaryPeriod.THIS_MONTH ->
            budgetDao.getBudgetByMonth(range.yearMonth)?.totalExpenseLimit?.takeIf { it > 0 }

        SummaryPeriod.LAST_3_MONTHS -> {
            val months = (0..2).map { offset ->
                SummaryPeriods.yearMonthOf(
                    SummaryPeriods.current(SummaryPeriod.THIS_MONTH, now, tz)
                        .let { shiftMonth(it.start, -offset, tz) },
                    tz
                )
            }
            val budgets = budgetDao.getBudgetsByMonths(months)
            val byMonth = budgets.associateBy { it.yearMonth }
            if (months.all { (byMonth[it]?.totalExpenseLimit ?: 0.0) > 0 }) {
                months.sumOf { byMonth[it]!!.totalExpenseLimit!! }
            } else {
                null
            }
        }
    }

    /** 把某月的月初时间戳往前/往后推 n 个月，仍落在月初 */
    private fun shiftMonth(monthStartMs: Long, deltaMonths: Int, tz: TimeZone): Long {
        val cal = java.util.Calendar.getInstance(tz).apply {
            timeInMillis = monthStartMs
            add(java.util.Calendar.MONTH, deltaMonths)
        }
        return cal.timeInMillis
    }
}
