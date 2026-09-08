package com.smartledger.util

import kotlin.math.ceil

/**
 * 动态预算与超支预测。
 *
 * **完全本地计算，不调用 AI，可离线。**
 *
 * 纯函数 object，不接 Context、不碰 DAO —— 这样它能被纯 JVM 单测完整覆盖，
 * 而预算计算恰恰是最容易在边界上出错（除零、月末、月初大额固定支出）的地方。
 */
object BudgetPredictor {

    /**
     * 月初护栏：已过天数少于这个值时不显示月末预测与超支日期。
     *
     * 原因：1 号交 3000 房租，按「平均日支出 × 当月天数」会算出
     * 「预计月末支出 90000 元」这种荒谬数字，用户会当成 bug。
     * 第一版不做加权算法（月初固定支出剔除），只在展示层挡住，
     * 成本极低但能避免上线即被吐槽。
     */
    const val MIN_DAYS_FOR_PROJECTION = 5

    enum class Status {
        /** 未设置预算（含预算 <= 0） */
        NO_BUDGET,

        /** 预测不超支 */
        SAFE,

        /** 当前未超，但按此节奏预计会超 */
        WARNING,

        /** 已经超支 */
        DANGER
    }

    data class Result(
        val status: Status,
        val budget: Double?,
        val currentExpense: Double,

        /** 剩余额度，负数钳到 0 */
        val remainingBudget: Double,

        /** 含今天的剩余可消费天数，恒 >= 1 */
        val remainingDays: Int,

        /** 今日建议可用；未设预算时为 null */
        val dailyAvailable: Double?,

        /** 已过天数（当月 = 今天几号） */
        val daysElapsed: Int,
        val daysTotal: Int,

        val averageDailyExpense: Double,
        val predictedMonthExpense: Double,

        /**
         * 预计达到预算上限的「日」。
         * 无预算 / 无超支风险 / 当月无支出时为 null；
         * 已超支时该值 <= daysElapsed。
         */
        val projectedBudgetDay: Int?,

        val usedPercent: Double?,

        /** UI 是否应展示月末预测与超支日期 */
        val showProjection: Boolean
    )

    /**
     * @param budget         月度支出上限（budgets.totalExpenseLimit）
     * @param currentExpense 当月累计支出
     * @param daysElapsed    已过天数，1..daysTotal。查看历史月份时应传 daysTotal，
     *                       预测会自然退化为实际值
     * @param daysTotal      当月总天数
     */
    fun predict(
        budget: Double?,
        currentExpense: Double,
        daysElapsed: Int,
        daysTotal: Int
    ): Result {
        // ── 入参防御：任何一路传入脏值都不能算出 NaN / Infinity ──
        val totalDays = if (daysTotal <= 0 || daysTotal > 366) 30 else daysTotal
        val elapsed = daysElapsed.coerceIn(1, totalDays)
        val expense = sanitize(currentExpense).coerceAtLeast(0.0)
        val limit = budget?.let { sanitize(it) }?.takeIf { it > 0.0 }

        // ── 未设置预算 ──
        if (limit == null) {
            val remainingDays = totalDays - elapsed + 1
            return Result(
                status = Status.NO_BUDGET,
                budget = budget?.let { sanitize(it) },
                currentExpense = expense,
                remainingBudget = 0.0,
                remainingDays = remainingDays.coerceAtLeast(1),
                dailyAvailable = null,
                daysElapsed = elapsed,
                daysTotal = totalDays,
                averageDailyExpense = round2(expense / elapsed),
                predictedMonthExpense = round2(expense / elapsed * totalDays),
                projectedBudgetDay = null,
                usedPercent = null,
                showProjection = false
            )
        }

        // ── 剩余额度与今日建议可用 ──
        val remainingBudget = (limit - expense).coerceAtLeast(0.0)
        // 今天也计入剩余可消费日：remainingDays = 总天数 - 今天几号 + 1
        val remainingDays = (totalDays - elapsed + 1).coerceAtLeast(1)
        val dailyAvailable = round2(remainingBudget / remainingDays)

        // ── 月末预测 ──
        // 第一版保持简单，不做加权（月初大额固定支出干扰问题用展示层护栏处理）
        val averageDailyExpense = round2(expense / elapsed)
        val predictedMonthExpense = round2(expense / elapsed * totalDays)

        // ── 预计超支日期 ──
        // 只有「日均 > 0 且预测确实超支」时才有意义：
        //  - 日均为 0（当月还没花钱）→ 无法预测，返回 null
        //  - 预测不超支 → 无风险，不该显示一个日期吓用户
        val projectedBudgetDay = if (averageDailyExpense <= 0.0 || predictedMonthExpense <= limit) {
            null
        } else {
            ceil(limit / averageDailyExpense).toInt().coerceIn(1, totalDays)
        }

        val status = when {
            expense > limit -> Status.DANGER
            predictedMonthExpense > limit -> Status.WARNING
            else -> Status.SAFE
        }

        return Result(
            status = status,
            budget = limit,
            currentExpense = expense,
            remainingBudget = round2(remainingBudget),
            remainingDays = remainingDays,
            dailyAvailable = dailyAvailable,
            daysElapsed = elapsed,
            daysTotal = totalDays,
            averageDailyExpense = averageDailyExpense,
            predictedMonthExpense = predictedMonthExpense,
            projectedBudgetDay = projectedBudgetDay,
            usedPercent = round1(expense / limit * 100.0),
            showProjection = elapsed >= MIN_DAYS_FOR_PROJECTION
        )
    }

    private fun sanitize(v: Double): Double =
        if (v.isNaN() || v.isInfinite()) 0.0 else v

    private fun round1(v: Double): Double =
        if (v.isNaN() || v.isInfinite()) 0.0 else Math.round(v * 10.0) / 10.0

    private fun round2(v: Double): Double =
        if (v.isNaN() || v.isInfinite()) 0.0 else Math.round(v * 100.0) / 100.0
}
