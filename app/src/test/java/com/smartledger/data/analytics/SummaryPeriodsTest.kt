package com.smartledger.data.analytics

import com.smartledger.data.analytics.model.SummaryPeriod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * 周期区间测试。
 *
 * 这是整个聚合链路里最容易出错、又最难被发现的一层：
 * 日期算错不会崩溃，只会给出「看起来合理但完全错误」的结论
 * （例如月初拿 3 天对比上月整月，得出「支出下降 90%」）。
 */
class SummaryPeriodsTest {

    private val sh = TimeZone.getTimeZone("Asia/Shanghai")

    private fun ts(y: Int, m: Int, d: Int, h: Int = 12, min: Int = 0, tz: TimeZone = sh): Long =
        Calendar.getInstance(tz).apply {
            set(y, m - 1, d, h, min, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun cal(ms: Long, tz: TimeZone = sh) =
        Calendar.getInstance(tz).apply { timeInMillis = ms }

    private fun ymd(ms: Long, tz: TimeZone = sh): String {
        val c = cal(ms, tz)
        return "%04d-%02d-%02d".format(
            c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH)
        )
    }

    private fun hms(ms: Long, tz: TimeZone = sh): String {
        val c = cal(ms, tz)
        return "%02d:%02d:%02d.%03d".format(
            c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE),
            c.get(Calendar.SECOND), c.get(Calendar.MILLISECOND)
        )
    }

    // ═══ 本月 ═══

    @Test
    fun `本月区间是 1 号 00 点到月末 2359`() {
        val now = ts(2026, 9, 7, 14, 30)
        val r = SummaryPeriods.current(SummaryPeriod.THIS_MONTH, now, sh)
        assertEquals("2026-09-01", ymd(r.start))
        assertEquals("00:00:00.000", hms(r.start))
        assertEquals("2026-09-30", ymd(r.end))
        assertEquals("23:59:59.999", hms(r.end))
        assertEquals("2026 年 9 月", r.label)
        assertEquals("2026-09", r.yearMonth)
    }

    @Test
    fun `本月区间在月初与月末都正确`() {
        val first = SummaryPeriods.current(SummaryPeriod.THIS_MONTH, ts(2026, 9, 1, 0, 1), sh)
        assertEquals("2026-09-01", ymd(first.start))
        assertEquals("2026-09-30", ymd(first.end))

        val last = SummaryPeriods.current(SummaryPeriod.THIS_MONTH, ts(2026, 9, 30, 23, 58), sh)
        assertEquals("2026-09-01", ymd(last.start))
        assertEquals("2026-09-30", ymd(last.end))
    }

    @Test
    fun `2 月区间不溢出到 3 月`() {
        val r = SummaryPeriods.current(SummaryPeriod.THIS_MONTH, ts(2026, 2, 15), sh)
        assertEquals("2026-02-01", ymd(r.start))
        assertEquals("2026-02-28", ymd(r.end))
        assertEquals("2026-02", r.yearMonth)
    }

    @Test
    fun `闰年 2 月有 29 天`() {
        val r = SummaryPeriods.current(SummaryPeriod.THIS_MONTH, ts(2028, 2, 10), sh)
        assertEquals("2028-02-29", ymd(r.end))
        assertEquals(29, SummaryPeriods.daysTotal(r, sh))
    }

    @Test
    fun `1 月与 12 月跨年正确`() {
        val jan = SummaryPeriods.current(SummaryPeriod.THIS_MONTH, ts(2026, 1, 15), sh)
        assertEquals("2026-01", jan.yearMonth)
        assertEquals("2026 年 1 月", jan.label)

        val dec = SummaryPeriods.current(SummaryPeriod.THIS_MONTH, ts(2026, 12, 15), sh)
        assertEquals("2026-12", dec.yearMonth)
        assertEquals("2026-12-31", ymd(dec.end))
    }

    // ═══ 本月天数 ═══

    @Test
    fun `daysTotal 与 daysElapsed`() {
        val now = ts(2026, 9, 7, 14, 30)
        val r = SummaryPeriods.current(SummaryPeriod.THIS_MONTH, now, sh)
        assertEquals(30, SummaryPeriods.daysTotal(r, sh))
        assertEquals(7, SummaryPeriods.daysElapsed(r, now, sh))
    }

    @Test
    fun `daysElapsed 在 1 号是 1 而不是 0`() {
        val now = ts(2026, 9, 1, 0, 5)
        val r = SummaryPeriods.current(SummaryPeriod.THIS_MONTH, now, sh)
        assertEquals(1, SummaryPeriods.daysElapsed(r, now, sh))
    }

    @Test
    fun `daysElapsed 在月末等于 daysTotal`() {
        val now = ts(2026, 9, 30, 23, 0)
        val r = SummaryPeriods.current(SummaryPeriod.THIS_MONTH, now, sh)
        assertEquals(30, SummaryPeriods.daysElapsed(r, now, sh))
        assertEquals(SummaryPeriods.daysTotal(r, sh), SummaryPeriods.daysElapsed(r, now, sh))
    }

    @Test
    fun `已过工作日与周末天数（2026-09-01 至 09-07）`() {
        // 9/1 二、9/2 三、9/3 四、9/4 五、9/5 六、9/6 日、9/7 一
        val now = ts(2026, 9, 7, 20, 0)
        val r = SummaryPeriods.current(SummaryPeriod.THIS_MONTH, now, sh)
        assertEquals(5, SummaryPeriods.elapsedWeekdayCount(r, now, sh))
        assertEquals(2, SummaryPeriods.elapsedWeekendCount(r, now, sh))
        assertEquals(7, SummaryPeriods.elapsedWeekdayCount(r, now, sh) +
                SummaryPeriods.elapsedWeekendCount(r, now, sh))
    }

    @Test
    fun `月初没有已过周末时计数为 0`() {
        val now = ts(2026, 9, 1, 20, 0)     // 周二
        val r = SummaryPeriods.current(SummaryPeriod.THIS_MONTH, now, sh)
        assertEquals(0, SummaryPeriods.elapsedWeekendCount(r, now, sh))
        assertEquals(1, SummaryPeriods.elapsedWeekdayCount(r, now, sh))
    }

    // ═══ 上一周期同期（环比口径）═══

    @Test
    fun `本月周期的上一周期是上月同期而不是上月整月`() {
        // 关键：9 月 7 日看环比，必须对比 8/1~8/7，
        // 若对比 8 月整月，月初永远显示「支出大幅下降」，结论毫无意义
        val now = ts(2026, 9, 7, 14, 30)
        val prev = SummaryPeriods.previous(SummaryPeriod.THIS_MONTH, now, sh)!!
        assertEquals("2026-08-01", ymd(prev.start))
        assertEquals("2026-08-07", ymd(prev.end))
        assertEquals("23:59:59.999", hms(prev.end))
        assertEquals("2026-08", prev.yearMonth)
    }

    @Test
    fun `月末日期溢出时钳制到上月最后一天`() {
        // 3 月 31 日的上一周期同期是 2 月 28 日（2026 非闰年）
        val now = ts(2026, 3, 31, 10, 0)
        val prev = SummaryPeriods.previous(SummaryPeriod.THIS_MONTH, now, sh)!!
        assertEquals("2026-02-01", ymd(prev.start))
        assertEquals("2026-02-28", ymd(prev.end))
    }

    @Test
    fun `闰年 3 月 31 日钳制到 2 月 29 日`() {
        val now = ts(2028, 3, 31, 10, 0)
        val prev = SummaryPeriods.previous(SummaryPeriod.THIS_MONTH, now, sh)!!
        assertEquals("2028-02-29", ymd(prev.end))
    }

    @Test
    fun `5 月 31 日钳制到 4 月 30 日`() {
        val now = ts(2026, 5, 31, 10, 0)
        val prev = SummaryPeriods.previous(SummaryPeriod.THIS_MONTH, now, sh)!!
        assertEquals("2026-04-30", ymd(prev.end))
    }

    @Test
    fun `1 月的上一周期是去年 12 月`() {
        val now = ts(2026, 1, 15, 10, 0)
        val prev = SummaryPeriods.previous(SummaryPeriod.THIS_MONTH, now, sh)!!
        assertEquals("2025-12-01", ymd(prev.start))
        assertEquals("2025-12-15", ymd(prev.end))
        assertEquals("2025-12", prev.yearMonth)
    }

    @Test
    fun `上一周期同期区间不会越过上月月末`() {
        val now = ts(2026, 9, 30, 10, 0)
        val prev = SummaryPeriods.previous(SummaryPeriod.THIS_MONTH, now, sh)!!
        val prevMonthEnd = SummaryPeriods.current(
            SummaryPeriod.THIS_MONTH, ts(2026, 8, 15), sh
        ).end
        assertTrue(prev.end <= prevMonthEnd)
        assertEquals("2026-08-30", ymd(prev.end))
    }

    // ═══ 近 3 个月 ═══

    @Test
    fun `近 3 月区间覆盖含当月在内的三个月`() {
        val now = ts(2026, 9, 7, 14, 30)
        val r = SummaryPeriods.current(SummaryPeriod.LAST_3_MONTHS, now, sh)
        assertEquals("2026-07-01", ymd(r.start))
        assertEquals("2026-09-30", ymd(r.end))
        assertEquals("2026 年 7 月 ~ 2026 年 9 月", r.label)
        // yearMonth 用于查当月预算
        assertEquals("2026-09", r.yearMonth)
    }

    @Test
    fun `近 3 月天数 = 三个月天数之和`() {
        val now = ts(2026, 9, 7, 14, 30)
        val r = SummaryPeriods.current(SummaryPeriod.LAST_3_MONTHS, now, sh)
        assertEquals(92, SummaryPeriods.daysTotal(r, sh))      // 31+31+30
        assertEquals(69, SummaryPeriods.daysElapsed(r, now, sh)) // 31+31+7
    }

    @Test
    fun `近 3 月的上一周期是再往前推三个整月`() {
        val now = ts(2026, 9, 7, 14, 30)
        val prev = SummaryPeriods.previous(SummaryPeriod.LAST_3_MONTHS, now, sh)!!
        assertEquals("2026-04-01", ymd(prev.start))
        assertEquals("2026-06-30", ymd(prev.end))
        assertEquals(91, SummaryPeriods.daysTotal(prev, sh))   // 30+31+30
    }

    @Test
    fun `近 3 月跨年正确`() {
        val now = ts(2026, 2, 10, 10, 0)
        val r = SummaryPeriods.current(SummaryPeriod.LAST_3_MONTHS, now, sh)
        assertEquals("2025-12-01", ymd(r.start))
        assertEquals("2026-02-28", ymd(r.end))
        assertEquals("2025 年 12 月 ~ 2026 年 2 月", r.label)
    }

    // ═══ 时区 ═══

    @Test
    fun `不同时区下同一时间戳的月份归属可能不同`() {
        // 北京时间 2026-09-01 00:30 = UTC 2026-08-31 16:30
        val instant = ts(2026, 9, 1, 0, 30, sh)
        val inSh = SummaryPeriods.current(SummaryPeriod.THIS_MONTH, instant, sh)
        val inUtc = SummaryPeriods.current(
            SummaryPeriod.THIS_MONTH, instant, TimeZone.getTimeZone("UTC")
        )
        assertEquals("2026-09", inSh.yearMonth)
        assertEquals("若按 UTC 算，这一刻还属于 8 月", "2026-08", inUtc.yearMonth)
    }

    @Test
    fun `yearMonthOf 与 monthDayLabel 格式正确`() {
        val now = ts(2026, 9, 7, 14, 30)
        assertEquals("2026-09", SummaryPeriods.yearMonthOf(now, sh))
        assertEquals("9 月 7 日", SummaryPeriods.monthDayLabel(now, sh))
        assertEquals("2026 年 9 月", SummaryPeriods.monthLabelOf(now, sh))
    }

    @Test
    fun `个位数月份与日期补零`() {
        assertEquals("2026-01", SummaryPeriods.yearMonthOf(ts(2026, 1, 5), sh))
        assertEquals("2026-11", SummaryPeriods.yearMonthOf(ts(2026, 11, 25), sh))
    }
}
