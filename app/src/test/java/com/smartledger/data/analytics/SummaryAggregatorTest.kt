package com.smartledger.data.analytics

import com.smartledger.data.analytics.model.SummaryPeriod
import com.smartledger.data.analytics.model.TimeBucket
import com.smartledger.data.analytics.model.TxPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * 聚合层测试。
 *
 * 时区纪律：所有用例都**显式传 TimeZone**，并且专门有一组用例
 * 验证「同一笔墙钟时间在 Asia/Shanghai 与 UTC 下归入不同时段桶」——
 * 这正是原方案里想用 SQLite strftime 做小时聚合会踩的坑
 * （strftime 默认按 UTC，北京时间 23:30 会被算成下午）。
 */
class SummaryAggregatorTest {

    private val sh = TimeZone.getTimeZone("Asia/Shanghai")

    /** 构造 Asia/Shanghai 墙钟时间对应的时间戳 */
    private fun ts(
        y: Int, m: Int, d: Int,
        h: Int = 12, min: Int = 0,
        tz: TimeZone = sh
    ): Long = Calendar.getInstance(tz).apply {
        set(y, m - 1, d, h, min, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun range(period: SummaryPeriod = SummaryPeriod.THIS_MONTH, now: Long) =
        SummaryPeriods.current(period, now, sh)

    private val catNames = mapOf(1L to "餐饮", 2L to "交通", 3L to "购物")

    private fun expense(
        amount: Double,
        day: Int,
        hour: Int = 12,
        categoryId: Long? = 1L,
        merchant: String? = null
    ) = TxPoint(
        amount = amount,
        type = "expense",
        categoryId = categoryId,
        merchant = merchant,
        transactionTime = ts(2026, 9, day, hour)
    )

    private fun income(amount: Double, day: Int, hour: Int = 12) = TxPoint(
        amount = amount,
        type = "income",
        categoryId = 11L,
        merchant = null,
        transactionTime = ts(2026, 9, day, hour)
    )

    private fun agg(
        points: List<TxPoint>,
        now: Long = ts(2026, 9, 7, 14, 30),
        budget: Double? = null,
        previous: Double? = null,
        period: SummaryPeriod = SummaryPeriod.THIS_MONTH
    ) = SummaryAggregator.aggregate(
        period = period,
        range = range(period, now),
        now = now,
        tz = sh,
        points = points,
        categoryNameById = catNames,
        budget = budget,
        previousPeriodExpense = previous
    )

    // ═══════════════════════════════════════════════════
    // 分类
    // ═══════════════════════════════════════════════════

    @Test
    fun `分类金额、笔数与占比正确`() {
        val points = listOf(
            expense(100.0, 1, categoryId = 1L),
            expense(50.0, 2, categoryId = 1L),
            expense(30.0, 3, categoryId = 2L),
            expense(20.0, 4, categoryId = 3L)
        )
        val s = agg(points)
        assertEquals(200.0, s.totalExpense, 1e-9)
        assertEquals(3, s.categoryStats.size)

        val food = s.categoryStats.first { it.categoryName == "餐饮" }
        assertEquals(150.0, food.amount, 1e-9)
        assertEquals(2, food.count)
        assertEquals(75.0, food.percent, 1e-9)

        val traffic = s.categoryStats.first { it.categoryName == "交通" }
        assertEquals(15.0, traffic.percent, 1e-9)
    }

    @Test
    fun `分类按金额降序`() {
        val points = listOf(
            expense(10.0, 1, categoryId = 2L),
            expense(90.0, 2, categoryId = 3L),
            expense(50.0, 3, categoryId = 1L)
        )
        assertEquals(
            listOf("购物", "餐饮", "交通"),
            agg(points).categoryStats.map { it.categoryName }
        )
    }

    @Test
    fun `categoryId 为空归入未分类`() {
        val points = listOf(expense(88.0, 1, categoryId = null))
        val s = agg(points)
        assertEquals(1, s.categoryStats.size)
        assertEquals(SummaryAggregator.UNCATEGORIZED, s.categoryStats[0].categoryName)
        assertEquals(100.0, s.categoryStats[0].percent, 1e-9)
    }

    @Test
    fun `分类 id 在本地表中不存在时归入未分类而不是崩溃`() {
        val points = listOf(expense(88.0, 1, categoryId = 999L))
        assertEquals(SummaryAggregator.UNCATEGORIZED, agg(points).categoryStats[0].categoryName)
    }

    @Test
    fun `收入不进分类统计但计入总收入`() {
        val points = listOf(expense(100.0, 1), income(12000.0, 5))
        val s = agg(points)
        assertEquals(12000.0, s.totalIncome, 1e-9)
        assertEquals(100.0, s.totalExpense, 1e-9)
        assertEquals(1, s.categoryStats.size)
        assertEquals(2, s.transactionCount)
    }

    @Test
    fun `分类占比之和为 100`() {
        val points = (1..7).map { expense(33.33, it, categoryId = (it % 3 + 1).toLong()) }
        val total = agg(points).categoryStats.sumOf { it.percent }
        // 各项保留 1 位小数后求和允许 ±0.2 的舍入误差
        assertTrue("占比之和=$total", Math.abs(total - 100.0) < 0.2)
    }

    // ═══════════════════════════════════════════════════
    // 脏数据
    // ═══════════════════════════════════════════════════

    @Test
    fun `0 元与负数金额不进统计`() {
        val points = listOf(
            expense(0.0, 1),
            expense(-50.0, 2),
            expense(100.0, 3)
        )
        val s = agg(points)
        assertEquals(100.0, s.totalExpense, 1e-9)
        assertEquals(1, s.categoryStats.sumOf { it.count })
    }

    @Test
    fun `type 大小写异常的行被跳过而不是算进支出`() {
        val points = listOf(
            TxPoint(100.0, "EXPENSE", 1L, null, ts(2026, 9, 1)),   // 大写脏数据
            TxPoint(50.0, "expense", 1L, null, ts(2026, 9, 2))
        )
        val s = agg(points)
        assertEquals(50.0, s.totalExpense, 1e-9)
        assertEquals(0.0, s.totalIncome, 1e-9)
    }

    @Test
    fun `NaN 金额不会污染合计`() {
        val points = listOf(
            TxPoint(Double.NaN, "expense", 1L, null, ts(2026, 9, 1)),
            expense(100.0, 2)
        )
        val s = agg(points)
        assertEquals(100.0, s.totalExpense, 1e-9)
        assertFalse(s.totalExpense.isNaN())
    }

    // ═══════════════════════════════════════════════════
    // 时段桶（时区正确性）
    // ═══════════════════════════════════════════════════

    @Test
    fun `时段桶边界归属正确`() {
        // 05:59 夜间、06:00 早间、10:59 早间、11:00 午间、13:59 午间、
        // 14:00 下午、17:59 下午、18:00 晚间、21:59 晚间、22:00 夜间、23:30 夜间
        val cases = listOf(
            5 to TimeBucket.NIGHT,
            6 to TimeBucket.MORNING,
            10 to TimeBucket.MORNING,
            11 to TimeBucket.NOON,
            13 to TimeBucket.NOON,
            14 to TimeBucket.AFTERNOON,
            17 to TimeBucket.AFTERNOON,
            18 to TimeBucket.EVENING,
            21 to TimeBucket.EVENING,
            22 to TimeBucket.NIGHT,
            23 to TimeBucket.NIGHT,
            0 to TimeBucket.NIGHT,
            2 to TimeBucket.NIGHT
        )
        cases.forEach { (hour, expected) ->
            assertEquals(
                "$hour 点应归入 ${expected.label}",
                expected,
                TimeBucket.ofHour(hour)
            )
        }
    }

    @Test
    fun `夜间消费笔数与金额（22 点到次日 6 点）`() {
        val points = listOf(
            expense(100.0, 1, hour = 23),
            expense(50.0, 2, hour = 1),
            expense(30.0, 3, hour = 5),
            expense(200.0, 4, hour = 20)     // 晚间，不算夜间
        )
        val s = agg(points)
        assertEquals(3, s.nightTransactionCount)
        assertEquals(180.0, s.nightExpense, 1e-9)
        assertEquals(180.0, s.timeBucketStats.first { it.bucket == TimeBucket.NIGHT }.amount, 1e-9)
        assertEquals(200.0, s.timeBucketStats.first { it.bucket == TimeBucket.EVENING }.amount, 1e-9)
    }

    @Test
    fun `时段桶固定 5 项且顺序稳定`() {
        val s = agg(listOf(expense(10.0, 1)))
        assertEquals(5, s.timeBucketStats.size)
        assertEquals(TimeBucket.entries, s.timeBucketStats.map { it.bucket })
    }

    @Test
    fun `同一墙钟时间在不同时区归入不同桶（证明没有用 UTC）`() {
        // 北京 23:30 = UTC 15:30。若误用 UTC，会被算成「下午」而不是「夜间」
        val beijingNight = ts(2026, 9, 1, 23, 30, TimeZone.getTimeZone("Asia/Shanghai"))

        val inShanghai = SummaryAggregator.aggregate(
            period = SummaryPeriod.THIS_MONTH,
            range = SummaryPeriods.current(SummaryPeriod.THIS_MONTH, beijingNight, sh),
            now = beijingNight,
            tz = sh,
            points = listOf(TxPoint(100.0, "expense", 1L, null, beijingNight)),
            categoryNameById = catNames, budget = null, previousPeriodExpense = null
        )
        assertEquals(
            "北京时间 23:30 必须算夜间",
            1, inShanghai.nightTransactionCount
        )

        val utc = TimeZone.getTimeZone("UTC")
        val inUtc = SummaryAggregator.aggregate(
            period = SummaryPeriod.THIS_MONTH,
            range = SummaryPeriods.current(SummaryPeriod.THIS_MONTH, beijingNight, utc),
            now = beijingNight,
            tz = utc,
            points = listOf(TxPoint(100.0, "expense", 1L, null, beijingNight)),
            categoryNameById = catNames, budget = null, previousPeriodExpense = null
        )
        assertEquals(
            "同一时间戳在 UTC 下是 15:30，应算下午",
            0, inUtc.nightTransactionCount
        )
        assertEquals(
            1, inUtc.timeBucketStats.first { it.bucket == TimeBucket.AFTERNOON }.count
        )
    }

    // ═══════════════════════════════════════════════════
    // 工作日 / 周末
    // ═══════════════════════════════════════════════════

    @Test
    fun `工作日与周末金额分开统计`() {
        // 2026-09-05 是周六，09-06 是周日，09-07 是周一
        val points = listOf(
            expense(100.0, 5),      // 周六
            expense(200.0, 6),      // 周日
            expense(50.0, 7)        // 周一
        )
        val s = agg(points, now = ts(2026, 9, 7, 20, 0))
        assertEquals(300.0, s.weekendExpense, 1e-9)
        assertEquals(50.0, s.weekdayExpense, 1e-9)
    }

    @Test
    fun `日均的分母是已过的对应天数而不是整个周期`() {
        // now = 2026-09-07（周一）。9/1~9/7 里周末是 9/5、9/6 → 2 天；工作日 5 天
        val points = listOf(
            expense(100.0, 5),      // 周六
            expense(200.0, 6),      // 周日
            expense(500.0, 7)       // 周一
        )
        val s = agg(points, now = ts(2026, 9, 7, 20, 0))
        assertEquals("周末日均 = 300 / 2", 150.0, s.weekendDailyAverage, 1e-9)
        assertEquals("工作日日均 = 500 / 5", 100.0, s.weekdayDailyAverage, 1e-9)
    }

    @Test
    fun `没有已过的周末天数时日均为 0 而不是除零`() {
        // now = 2026-09-01（周二），9/1 之前没有已过周末
        val s = agg(listOf(expense(100.0, 1)), now = ts(2026, 9, 1, 20, 0))
        assertEquals(0.0, s.weekendDailyAverage, 1e-9)
        assertFalse(s.weekendDailyAverage.isNaN())
        assertFalse(s.weekendDailyAverage.isInfinite())
    }

    // ═══════════════════════════════════════════════════
    // 预算与环比
    // ═══════════════════════════════════════════════════

    @Test
    fun `预算达成率`() {
        val s = agg(listOf(expense(4280.0, 1)), budget = 5000.0)
        assertEquals(85.6, s.budgetUsedPercent!!, 1e-9)
    }

    @Test
    fun `未设预算时达成率为 null`() {
        assertNull(agg(listOf(expense(100.0, 1)), budget = null).budgetUsedPercent)
    }

    @Test
    fun `预算为 0 时达成率为 null 而不是 Infinity`() {
        val s = agg(listOf(expense(100.0, 1)), budget = 0.0)
        assertNull(s.budgetUsedPercent)
    }

    @Test
    fun `环比增加`() {
        val s = agg(listOf(expense(4280.0, 1)), previous = 3627.0)
        assertEquals(18.0, s.expenseChangePercent!!, 0.05)
    }

    @Test
    fun `环比减少为负数`() {
        val s = agg(listOf(expense(800.0, 1)), previous = 1000.0)
        assertEquals(-20.0, s.expenseChangePercent!!, 1e-9)
    }

    @Test
    fun `上期为 0 或 null 时环比为 null`() {
        assertNull(agg(listOf(expense(100.0, 1)), previous = 0.0).expenseChangePercent)
        assertNull(agg(listOf(expense(100.0, 1)), previous = null).expenseChangePercent)
    }

    @Test
    fun `上期为负数时环比为 null`() {
        assertNull(agg(listOf(expense(100.0, 1)), previous = -50.0).expenseChangePercent)
    }

    // ═══════════════════════════════════════════════════
    // 空数据与小样本
    // ═══════════════════════════════════════════════════

    @Test
    fun `空数据返回全 0 且不崩`() {
        val s = agg(emptyList())
        assertEquals(0.0, s.totalExpense, 1e-9)
        assertEquals(0, s.transactionCount)
        assertTrue(s.categoryStats.isEmpty())
        assertTrue(s.topMerchantStats.isEmpty())
        assertEquals(5, s.timeBucketStats.size)
        assertTrue(s.isLowSample)
        // 天数仍应正确，UI 要显示周期信息
        assertEquals(30, s.daysTotal)
        assertEquals(7, s.daysElapsed)
    }

    @Test
    fun `少于 5 笔支出标记为小样本`() {
        assertTrue(agg(listOf(expense(10.0, 1), expense(20.0, 2))).isLowSample)
        assertFalse(agg((1..5).map { expense(10.0, it) }).isLowSample)
    }

    @Test
    fun `只有收入没有支出也算小样本`() {
        assertTrue(agg(listOf(income(12000.0, 1))).isLowSample)
        assertEquals(0.0, agg(listOf(income(12000.0, 1))).totalExpense, 1e-9)
    }

    // ═══════════════════════════════════════════════════
    // 商户脱敏
    // ═══════════════════════════════════════════════════

    @Test
    fun `商户 Top10 且已匿名`() {
        val points = (1..15).map { i ->
            expense(1000.0 - i, 1, categoryId = 1L, merchant = "商户$i")
        }
        val s = agg(points)
        assertEquals(10, s.topMerchantStats.size)
        // 金额最大的排第一
        assertEquals("餐饮商户 #1", s.topMerchantStats[0].anonymizedName)
        assertEquals(999.0, s.topMerchantStats[0].amount, 1e-9)
        s.topMerchantStats.forEach { m ->
            assertFalse("匿名结果不得含真实商户名: ${m.anonymizedName}", m.anonymizedName.contains("商户1"))
        }
    }

    @Test
    fun `同一商户多笔合并`() {
        val points = listOf(
            expense(30.0, 1, merchant = "瑞幸咖啡"),
            expense(28.0, 2, merchant = "瑞幸咖啡"),
            expense(32.0, 3, merchant = "瑞幸咖啡")
        )
        val s = agg(points)
        assertEquals(1, s.topMerchantStats.size)
        assertEquals(90.0, s.topMerchantStats[0].amount, 1e-9)
        assertEquals(3, s.topMerchantStats[0].count)
    }

    @Test
    fun `商户名为空或全空白时不进商户榜`() {
        val points = listOf(
            expense(100.0, 1, merchant = null),
            expense(100.0, 2, merchant = ""),
            expense(100.0, 3, merchant = "   "),
            expense(100.0, 4, merchant = "有名字")
        )
        val s = agg(points)
        assertEquals(1, s.topMerchantStats.size)
        assertEquals(400.0, s.totalExpense, 1e-9)   // 金额仍全部计入
    }

    // ═══════════════════════════════════════════════════
    // 缓存稳定性（决定 AI 报告缓存能否命中）
    // ═══════════════════════════════════════════════════

    @Test
    fun `打乱行序后 canonicalJson 完全一致`() {
        // DAO 的投影查询没有 ORDER BY，SQLite 不保证行序。
        // 若用 Double 累加，浮点加法不满足结合律，行序不同会得到
        // 最后一位不同的总额 → dataHash 不同 → 缓存永不命中、白烧 token。
        // 改用整数分累加后必须与顺序完全无关。
        val base = (1..40).map { i -> expense(33.33 + i * 0.07, (i % 28) + 1, categoryId = (i % 3 + 1).toLong(), merchant = "店${i % 7}") }
        val shuffled = base.shuffled(java.util.Random(42))
        val reversed = base.reversed()

        val now = ts(2026, 9, 28, 20, 0)
        val a = SummaryFormatter.canonicalJson(agg(base, now = now))
        val b = SummaryFormatter.canonicalJson(agg(shuffled, now = now))
        val c = SummaryFormatter.canonicalJson(agg(reversed, now = now))

        assertEquals("打乱行序后 hash 输入不一致，缓存会永不命中", a, b)
        assertEquals("反序后 hash 输入不一致", a, c)
    }

    @Test
    fun `小数金额累加不产生浮点尾巴`() {
        // 0.1 + 0.2 这类经典浮点误差：100 笔 33.33 必须是 3333.00 而不是 3332.9999...
        val points = (1..28).map { expense(33.33, it) }
        val s = agg(points, now = ts(2026, 9, 28, 20, 0))
        assertEquals(933.24, s.totalExpense, 1e-9)
        assertEquals("933.24", SummaryFormatter.money(s.totalExpense))
    }

    @Test
    fun `大额与小额混合不丢精度`() {
        val points = listOf(
            expense(1234567.89, 1),
            expense(0.01, 2),
            expense(0.02, 3)
        )
        assertEquals(1234567.92, agg(points).totalExpense, 1e-9)
    }

    // ═══════════════════════════════════════════════════
    // 天数口径
    // ═══════════════════════════════════════════════════

    @Test
    fun `daysElapsed 与 daysTotal（本月 9 月 7 日）`() {
        val s = agg(listOf(expense(10.0, 1)), now = ts(2026, 9, 7, 14, 30))
        assertEquals(30, s.daysTotal)
        assertEquals(7, s.daysElapsed)
    }

    @Test
    fun `近 3 月周期的天数与区间`() {
        val now = ts(2026, 9, 7, 14, 30)
        val points = listOf(expense(10.0, 1))
        val s = SummaryAggregator.aggregate(
            period = SummaryPeriod.LAST_3_MONTHS,
            range = SummaryPeriods.current(SummaryPeriod.LAST_3_MONTHS, now, sh),
            now = now,
            tz = sh,
            points = points,
            categoryNameById = catNames,
            budget = null,
            previousPeriodExpense = null
        )
        // 7 月 31 + 8 月 31 + 9 月 30 = 92 天；已过 = 31 + 31 + 7 = 69 天
        assertEquals(92, s.daysTotal)
        assertEquals(69, s.daysElapsed)
    }
}
