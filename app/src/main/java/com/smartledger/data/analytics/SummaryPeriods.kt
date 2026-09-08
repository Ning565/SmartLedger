package com.smartledger.data.analytics

import com.smartledger.data.analytics.model.SummaryPeriod
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * 周期区间计算（纯函数，显式传时区，可单测）。
 *
 * 为什么单独抽出来：整个聚合链路里**最容易错的就是日期口径**，
 * 而日期错误不会崩、只会给出看似合理的错误结论（例如「本月支出下降 90%」），
 * 属于最难发现的 bug。集中在一处并显式传 [TimeZone]，才能覆盖单测。
 *
 * 时区纪律：项目里所有时间戳都是设备本地时区的 epoch millis，
 * 因此这里必须用**设备默认时区**做日历运算，绝不能用 UTC
 * （否则北京时间 23:30 的消费会被算进 UTC 的下午）。
 */
object SummaryPeriods {

    private const val DAY_MS = 86_400_000L

    data class Range(
        val start: Long,
        val end: Long,
        val label: String,
        val yearMonth: String          // "2026-09"，用于查 budgets
    )

    /** 当前周期的区间 */
    fun current(period: SummaryPeriod, now: Long, tz: TimeZone): Range = when (period) {
        SummaryPeriod.THIS_MONTH -> monthRange(now, 0, tz)
        SummaryPeriod.LAST_3_MONTHS -> {
            val start = monthRange(now, -2, tz).start
            val last = monthRange(now, 0, tz)
            Range(
                start = start,
                end = last.end,
                label = "${yearMonthLabel(start, tz)} ~ ${monthLabelOf(now, tz)}",
                yearMonth = last.yearMonth
            )
        }
    }

    /**
     * 上一周期的**同期**区间。
     *
     * 「同期」是关键：本月周期对比的必须是「上月 1 日 ~ 上月同一天」，
     * 而不是上月整月。否则月初（例如 9 月 3 日）拿 3 天的支出对比 8 月整月，
     * 结论恒为「支出大幅下降 90%」，毫无意义还会误导用户。
     *
     * 月末日期溢出要钳制：3 月 31 日的上一周期同期是 2 月 28（或 29）日。
     */
    fun previous(period: SummaryPeriod, now: Long, tz: TimeZone): Range? = when (period) {
        SummaryPeriod.THIS_MONTH -> {
            val prevMonth = monthRange(now, -1, tz)
            val prevCal = calendarAt(prevMonth.start, tz)
            val prevDaysInMonth = prevCal.getActualMaximum(Calendar.DAY_OF_MONTH)

            val curCal = calendarAt(now, tz)
            val day = curCal.get(Calendar.DAY_OF_MONTH).coerceAtMost(prevDaysInMonth)

            val endCal = calendarAt(prevMonth.start, tz).apply {
                set(Calendar.DAY_OF_MONTH, day)
                setEndOfDay()
            }
            Range(
                start = prevMonth.start,
                // 同期区间不能越过整个上月（钳制已经保证，这里再兜一层）
                end = minOf(endCal.timeInMillis, prevMonth.end),
                label = prevMonth.label,
                yearMonth = prevMonth.yearMonth
            )
        }

        SummaryPeriod.LAST_3_MONTHS -> {
            // 近 3 月的上一周期 = 再往前推 3 个整月
            val start = monthRange(now, -5, tz).start
            val end = monthRange(now, -3, tz).end
            Range(
                start = start,
                end = end,
                label = "${yearMonthLabel(start, tz)} ~ ${monthLabelOf(end, tz)}",
                yearMonth = monthRange(now, -3, tz).yearMonth
            )
        }
    }

    /** 周期总天数（含首尾） */
    fun daysTotal(range: Range, tz: TimeZone): Int =
        inclusiveDaySpan(range.start, range.end, tz)

    /**
     * 周期内已过天数（含今天）。
     *
     * 以 min(now, range.end) 为界：查看历史周期时它等于周期总天数，
     * 预测自然退化为「实际值」，不会算出荒谬的未来预测。
     */
    fun daysElapsed(range: Range, now: Long, tz: TimeZone): Int {
        val bound = minOf(now, range.end)
        if (bound < range.start) return 0
        return inclusiveDaySpan(range.start, bound, tz)
    }

    /** 周期内已过的日期里，周一~周五的天数 */
    fun elapsedWeekdayCount(range: Range, now: Long, tz: TimeZone): Int =
        countByDayType(range, now, tz, weekend = false)

    /** 周期内已过的日期里，周六/周日的天数 */
    fun elapsedWeekendCount(range: Range, now: Long, tz: TimeZone): Int =
        countByDayType(range, now, tz, weekend = true)

    private fun countByDayType(
        range: Range,
        now: Long,
        tz: TimeZone,
        weekend: Boolean
    ): Int {
        val bound = minOf(now, range.end)
        if (bound < range.start) return 0
        val cal = calendarAt(startOfDay(range.start, tz), tz)
        var count = 0
        // 上限兜底：跨 3 个月最多 ~92 天，防御性设 400 天避免异常数据导致死循环
        var guard = 0
        while (cal.timeInMillis <= bound && guard < 400) {
            val dow = cal.get(Calendar.DAY_OF_WEEK)
            val isWeekend = dow == Calendar.SATURDAY || dow == Calendar.SUNDAY
            if (isWeekend == weekend) count++
            cal.add(Calendar.DAY_OF_MONTH, 1)
            guard++
        }
        return count
    }

    // ═══ 内部工具 ═══

    /** 相对当前月偏移 delta 个月的整月区间 */
    private fun monthRange(now: Long, deltaMonths: Int, tz: TimeZone): Range {
        val cal = calendarAt(now, tz).apply {
            set(Calendar.DAY_OF_MONTH, 1)
            setStartOfDay()
            add(Calendar.MONTH, deltaMonths)
            // add(MONTH) 可能把日改写成月末，重新钉到 1 号最稳妥
            set(Calendar.DAY_OF_MONTH, 1)
            setStartOfDay()
        }
        val start = cal.timeInMillis
        val endCal = calendarAt(start, tz).apply {
            set(Calendar.DAY_OF_MONTH, getActualMaximum(Calendar.DAY_OF_MONTH))
            setEndOfDay()
        }
        val ym = String.format(
            Locale.US, "%04d-%02d",
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1
        )
        return Range(start, endCal.timeInMillis, monthLabelOf(start, tz), ym)
    }

    private fun inclusiveDaySpan(startMs: Long, endMs: Long, tz: TimeZone): Int {
        val a = startOfDay(startMs, tz)
        val b = startOfDay(endMs, tz)
        if (b < a) return 0
        // 用 round 而不是 floor：DST 切换会让差值不是 86400000 的整数倍
        return Math.round((b - a).toDouble() / DAY_MS).toInt() + 1
    }

    private fun startOfDay(ms: Long, tz: TimeZone): Long =
        calendarAt(ms, tz).apply { setStartOfDay() }.timeInMillis

    private fun calendarAt(ms: Long, tz: TimeZone): Calendar =
        Calendar.getInstance(tz).apply { timeInMillis = ms }

    private fun Calendar.setStartOfDay() {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    private fun Calendar.setEndOfDay() {
        set(Calendar.HOUR_OF_DAY, 23)
        set(Calendar.MINUTE, 59)
        set(Calendar.SECOND, 59)
        set(Calendar.MILLISECOND, 999)
    }

    /** "2026 年 9 月" */
    fun monthLabelOf(ms: Long, tz: TimeZone): String {
        val cal = calendarAt(ms, tz)
        return "${cal.get(Calendar.YEAR)} 年 ${cal.get(Calendar.MONTH) + 1} 月"
    }

    private fun yearMonthLabel(ms: Long, tz: TimeZone): String = monthLabelOf(ms, tz)

    /** "2026-09" */
    fun yearMonthOf(ms: Long, tz: TimeZone): String {
        val cal = calendarAt(ms, tz)
        return String.format(
            Locale.US, "%04d-%02d",
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1
        )
    }

    /** "9 月 7 日" */
    fun monthDayLabel(ms: Long, tz: TimeZone): String {
        val cal = calendarAt(ms, tz)
        return "${cal.get(Calendar.MONTH) + 1} 月 ${cal.get(Calendar.DAY_OF_MONTH)} 日"
    }
}
