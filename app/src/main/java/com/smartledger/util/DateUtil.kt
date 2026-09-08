package com.smartledger.util

import java.text.SimpleDateFormat
import java.util.*

/**
 * 日期格式化 / 解析工具。
 *
 * ## 线程安全
 * `SimpleDateFormat` **不是线程安全**的：多个线程共用同一个实例并发 format/parse，
 * 会读到错乱的结果甚至抛异常。本项目里 DateUtil 同时被以下并发路径调用：
 *  - UI 层多个 ViewModel 的 Flow（主线程 / 默认调度器）；
 *  - 本次二开新增的 AI 聚合链路（`Dispatchers.IO`）；
 *  - 自动记账主链路的时间计算。
 *
 * 因此这里用 `ThreadLocal` 让每个线程各持一份 formatter：既保持了「复用实例、
 * 不每次 new」的性能，又彻底消除并发错乱。输出格式与口径与改造前**完全一致**。
 *
 * 用 [fmt] 扩展取值而不是直接 `get()`：JDK 的 `ThreadLocal.get()` 返回被标注为可空，
 * 而 `withInitial` 已保证初始值非空，`!!` 在此是安全断言，可避免每个调用点都出可空告警。
 */
object DateUtil {

    private val dateFormat = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd", Locale.CHINA) }
    private val dateTimeFormat = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA) }
    private val monthFormat = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM", Locale.CHINA) }
    private val dayFormat = ThreadLocal.withInitial { SimpleDateFormat("MM-dd", Locale.CHINA) }
    private val timeFormat = ThreadLocal.withInitial { SimpleDateFormat("HH:mm", Locale.CHINA) }

    /** 取当前线程的 formatter；withInitial 保证非空，故 `!!` 安全。 */
    private val ThreadLocal<SimpleDateFormat>.fmt: SimpleDateFormat get() = get()!!

    fun formatDate(timestamp: Long): String = dateFormat.fmt.format(Date(timestamp))

    fun formatDateTime(timestamp: Long): String = dateTimeFormat.fmt.format(Date(timestamp))

    fun formatMonth(timestamp: Long): String = monthFormat.fmt.format(Date(timestamp))

    fun formatDay(timestamp: Long): String = dayFormat.fmt.format(Date(timestamp))

    fun formatTime(timestamp: Long): String = timeFormat.fmt.format(Date(timestamp))

    fun getCurrentYearMonth(): String = monthFormat.fmt.format(Date())

    /** 月份加减，如 "2026-08" + (-1) → "2026-07" */
    fun shiftYearMonth(yearMonth: String, deltaMonths: Int): String {
        val cal = Calendar.getInstance()
        val date = monthFormat.fmt.parse(yearMonth) ?: return yearMonth
        cal.time = date
        cal.add(Calendar.MONTH, deltaMonths)
        return monthFormat.fmt.format(cal.time)
    }

    fun getMonthStartTime(yearMonth: String): Long {
        val cal = Calendar.getInstance()
        val date = monthFormat.fmt.parse(yearMonth)!!
        cal.time = date
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    fun getMonthEndTime(yearMonth: String): Long {
        val cal = Calendar.getInstance()
        val date = monthFormat.fmt.parse(yearMonth)!!
        cal.time = date
        cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH))
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
        return cal.timeInMillis
    }

    fun getTodayStartTime(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    fun getTodayEndTime(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
        return cal.timeInMillis
    }

    fun isToday(timestamp: Long): Boolean {
        val today = Calendar.getInstance()
        val target = Calendar.getInstance().apply { timeInMillis = timestamp }
        return today.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
                today.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)
    }

    fun getDayOfWeek(timestamp: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
        return when (cal.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> "周一"
            Calendar.TUESDAY -> "周二"
            Calendar.WEDNESDAY -> "周三"
            Calendar.THURSDAY -> "周四"
            Calendar.FRIDAY -> "周五"
            Calendar.SATURDAY -> "周六"
            Calendar.SUNDAY -> "周日"
            else -> ""
        }
    }

    // ═══ 周时间范围（周一 00:00 ～ 周日 23:59:59，不受 firstDayOfWeek 影响）═══
    fun getWeekStartTime(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        // Calendar.SUNDAY=1 ... SATURDAY=7；周一向前偏移天数
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
        val daysFromMonday = if (dayOfWeek == Calendar.SUNDAY) 6 else dayOfWeek - Calendar.MONDAY
        cal.add(Calendar.DAY_OF_MONTH, -daysFromMonday)
        return cal.timeInMillis
    }

    fun getWeekEndTime(): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = getWeekStartTime()
        cal.add(Calendar.DAY_OF_MONTH, 6)
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
        return cal.timeInMillis
    }

    // ═══ 年时间范围 ═══
    fun getYearStartTime(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.MONTH, Calendar.JANUARY)
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    fun getYearEndTime(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.MONTH, Calendar.DECEMBER)
        cal.set(Calendar.DAY_OF_MONTH, 31)
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
        return cal.timeInMillis
    }
}
