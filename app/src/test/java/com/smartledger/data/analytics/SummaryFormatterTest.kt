package com.smartledger.data.analytics

import com.smartledger.data.analytics.model.SummaryPeriod
import com.smartledger.data.analytics.model.TimeBucket
import com.smartledger.data.analytics.model.TxPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * 脱敏与 Prompt 文本测试。
 *
 * 这里守的是整个 AI 功能最硬的一条红线：
 * **真实商户名、备注、卡号、手机号绝不能出现在发给 AI 的文本里**。
 * 因此除了正向断言（格式对不对），更重要的是反向断言（敏感串有没有漏出去）。
 */
class SummaryFormatterTest {

    private val sh = TimeZone.getTimeZone("Asia/Shanghai")
    private val catNames = mapOf(1L to "餐饮", 2L to "交通", 3L to "购物")

    private fun ts(d: Int, h: Int = 12) = Calendar.getInstance(sh).apply {
        set(2026, Calendar.SEPTEMBER, d, h, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun build(
        points: List<TxPoint>,
        budget: Double? = 5000.0,
        previous: Double? = null,
        now: Long = ts(7, 14)
    ): com.smartledger.data.analytics.model.FinancialSummary {
        val range = SummaryPeriods.current(SummaryPeriod.THIS_MONTH, now, sh)
        return SummaryAggregator.aggregate(
            period = SummaryPeriod.THIS_MONTH,
            range = range,
            now = now,
            tz = sh,
            points = points,
            categoryNameById = catNames,
            budget = budget,
            previousPeriodExpense = previous
        )
    }

    private fun expense(
        amount: Double, day: Int, hour: Int = 12,
        categoryId: Long? = 1L, merchant: String? = null
    ) = TxPoint(amount, "expense", categoryId, merchant, ts(day, hour))

    // ═══════════════════════════════════════════════════
    // 脱敏红线
    // ═══════════════════════════════════════════════════

    @Test
    fun `真实商户名绝不出现在 Prompt 文本里`() {
        val realNames = listOf(
            "瑞幸咖啡科技园店", "麦当劳南山店", "淘宝XX数码旗舰店",
            "京东支付", "美团外卖", "滴滴出行", "招商银行信用卡还款"
        )
        val points = realNames.mapIndexed { i, name ->
            expense(100.0 + i, i + 1, categoryId = (i % 3 + 1).toLong(), merchant = name)
        }
        val text = SummaryFormatter.full(build(points))

        realNames.forEach { name ->
            assertFalse("真实商户名泄漏进 Prompt：$name", text.contains(name))
        }
        // 连片段都不该出现
        listOf("瑞幸", "麦当劳", "淘宝", "京东", "美团", "滴滴", "招商").forEach { frag ->
            assertFalse("商户名片段泄漏进 Prompt：$frag", text.contains(frag))
        }
        assertTrue("应出现匿名编号", text.contains("商户 #1"))
    }

    @Test
    fun `不做部分打码（半泄漏比全匿名更危险）`() {
        val points = listOf(expense(100.0, 1, merchant = "瑞幸咖啡科技园店"))
        val text = SummaryFormatter.full(build(points))
        // 「瑞*咖啡」这种部分打码等于泄漏一半，必须完全不出现
        assertFalse(text.contains("瑞"))
        assertFalse(text.contains("咖啡"))
        assertFalse(text.contains("*"))
    }

    @Test
    fun `卡号手机号订单号形态的数字串不会进入 Prompt`() {
        // 构造一笔商户名里塞了卡号的脏数据，验证脱敏后不外泄
        val points = listOf(
            expense(100.0, 1, merchant = "还款卡6222021234567890123"),
            expense(50.0, 2, merchant = "联系13800138000"),
            expense(30.0, 3, merchant = "订单2026090712345678901")
        )
        val text = SummaryFormatter.full(build(points))
        assertFalse(text.contains("6222021234567890123"))
        assertFalse(text.contains("13800138000"))
        assertFalse(text.contains("2026090712345678901"))
        // 通用防线：文本里不该有 11 位及以上连续数字（金额都带小数点）
        assertFalse(
            "Prompt 里出现了 11 位以上连续数字，可能是卡号/手机号/订单号",
            Regex("\\d{11,}").containsMatchIn(text)
        )
    }

    @Test
    fun `用户自定义分类名里的数字与非字母字符被清洗`() {
        // 用户可以建一个叫「星巴克2024专用」的分类，
        // 若原样拼进匿名前缀就等于泄漏了商户信息
        val leaky = mapOf(1L to "星巴克2024专用")
        val range = SummaryPeriods.current(SummaryPeriod.THIS_MONTH, ts(7, 14), sh)
        val s = SummaryAggregator.aggregate(
            period = SummaryPeriod.THIS_MONTH, range = range, now = ts(7, 14), tz = sh,
            points = listOf(expense(100.0, 1, merchant = "某店")),
            categoryNameById = leaky, budget = null, previousPeriodExpense = null
        )
        val anon = s.topMerchantStats.first().anonymizedName
        // 先把结尾的序号「 #1」剔掉再查数字：序号本身就是数字，
        // 不能把它当成泄漏
        val prefix = anon.substringBeforeLast(" #")
        assertFalse("匿名前缀泄漏了数字: $anon", prefix.any { it.isDigit() })
        assertFalse("分类名未被清洗: $anon", prefix.contains("2024"))
        assertFalse("匿名前缀过长: $anon", anon.length > 12)
    }

    // ═══════════════════════════════════════════════════
    // 文本内容
    // ═══════════════════════════════════════════════════

    @Test
    fun `完整文本包含全部必需段落`() {
        val points = listOf(
            expense(1797.0, 1, categoryId = 1L, merchant = "店A"),
            expense(1070.0, 2, categoryId = 3L, merchant = "店B"),
            expense(642.0, 3, categoryId = 2L, merchant = "店C"),
            expense(720.0, 5, hour = 23, categoryId = 3L, merchant = "店D")   // 夜间 + 周六
        )
        val text = SummaryFormatter.full(build(points, budget = 5000.0, previous = 3627.0))

        listOf(
            "周期：", "预算（支出上限）：", "实际支出：", "收入：", "记账笔数：",
            "主要支出分类：", "高频消费商户（已匿名）：", "工作日 / 周末：",
            "时段分布：", "夜间消费（22:00 ~ 次日 06:00）：", "历史对比："
        ).forEach { section ->
            assertTrue("缺少段落：$section\n实际文本：\n$text", text.contains(section))
        }
    }

    @Test
    fun `夜间消费段落给出笔数与金额`() {
        val points = listOf(
            expense(700.0, 5, hour = 23),
            expense(20.0, 6, hour = 1)
        )
        val text = SummaryFormatter.nightSection(build(points))
        assertTrue(text.contains("2 笔"))
        assertTrue(text.contains("720.00"))
        assertTrue(text.contains("占总支出"))
    }

    @Test
    fun `无夜间消费时如实说明`() {
        val text = SummaryFormatter.nightSection(build(listOf(expense(100.0, 1, hour = 12))))
        assertEquals("夜间消费（22:00 ~ 次日 06:00）：本周期无夜间消费", text)
    }

    @Test
    fun `未设预算时如实说明而不是编一个数`() {
        val text = SummaryFormatter.headerSection(build(listOf(expense(100.0, 1)), budget = null))
        assertTrue(text.contains("用户未设置"))
    }

    @Test
    fun `无历史数据时如实说明`() {
        val text = SummaryFormatter.historySection(build(listOf(expense(100.0, 1)), previous = null))
        assertEquals("历史对比：\n无可比历史数据", text)
    }

    @Test
    fun `小样本时追加谨慎结论提示`() {
        val text = SummaryFormatter.headerSection(build(listOf(expense(100.0, 1), expense(50.0, 2))))
        assertTrue("小样本必须提示 AI 谨慎", text.contains("样本量较小"))
    }

    @Test
    fun `样本充足时不追加小样本提示`() {
        val points = (1..6).map { expense(100.0, it) }
        assertFalse(SummaryFormatter.headerSection(build(points)).contains("样本量较小"))
    }

    @Test
    fun `环比增加与减少的措辞正确`() {
        assertTrue(SummaryFormatter.historySection(
            build(listOf(expense(4280.0, 1)), previous = 3627.0)
        ).contains("增加"))
        assertTrue(SummaryFormatter.historySection(
            build(listOf(expense(3000.0, 1)), previous = 3627.0)
        ).contains("减少"))
        assertTrue(SummaryFormatter.historySection(
            build(listOf(expense(3627.0, 1)), previous = 3627.0)
        ).contains("持平"))
    }

    @Test
    fun `空数据不崩且各段落都有内容`() {
        val text = SummaryFormatter.full(build(emptyList()))
        assertTrue(text.contains("本周期无支出记录"))
        assertTrue(text.contains("无可识别商户信息"))
        assertFalse(text.contains("NaN"))
        assertFalse(text.contains("Infinity"))
    }

    // ═══════════════════════════════════════════════════
    // 格式化口径
    // ═══════════════════════════════════════════════════

    @Test
    fun `金额固定两位小数且用美式分隔符`() {
        // 必须在非美式 Locale 下也稳定，否则中文/德文环境会产生不同 hash
        val saved = Locale.getDefault()
        try {
            listOf(Locale.CHINA, Locale.GERMANY, Locale.FRANCE).forEach { loc ->
                Locale.setDefault(loc)
                assertEquals("Locale=$loc", "4280.00", SummaryFormatter.money(4280.0))
                assertEquals("Locale=$loc", "1234.50", SummaryFormatter.money(1234.5))
                assertEquals("Locale=$loc", "18.0%", SummaryFormatter.pct(18.0))
            }
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun `NaN 与 Infinity 被格式化为安全值`() {
        assertEquals("0.00", SummaryFormatter.money(Double.NaN))
        assertEquals("0.00", SummaryFormatter.money(Double.POSITIVE_INFINITY))
        assertEquals("0.0%", SummaryFormatter.pct(Double.NaN))
    }

    // ═══════════════════════════════════════════════════
    // canonicalJson（决定 AI 报告缓存能否命中）
    // ═══════════════════════════════════════════════════

    @Test
    fun `同一数据两次序列化完全一致`() {
        val points = (1..10).map { expense(33.33 * it, it, merchant = "店$it") }
        val s = build(points)
        assertEquals(
            SummaryFormatter.canonicalJson(s),
            SummaryFormatter.canonicalJson(s)
        )
    }

    @Test
    fun `金额变化会改变序列化结果`() {
        val a = SummaryFormatter.canonicalJson(build(listOf(expense(100.0, 1))))
        val b = SummaryFormatter.canonicalJson(build(listOf(expense(100.01, 1))))
        assertNotEquals("0.01 元的差异必须让缓存失效", a, b)
    }

    @Test
    fun `序列化结果不含时间戳等易变字段`() {
        val json = SummaryFormatter.canonicalJson(build(listOf(expense(100.0, 1))))
        assertFalse("createdAt 会让 hash 每次都变", json.contains("createdAt"))
        assertFalse(json.contains("\"now\""))
    }

    @Test
    fun `序列化结果包含 daysElapsed（同样金额不同天数结论应不同）`() {
        val early = SummaryFormatter.canonicalJson(
            build(listOf(expense(100.0, 1)), now = ts(2, 10))
        )
        val late = SummaryFormatter.canonicalJson(
            build(listOf(expense(100.0, 1)), now = ts(20, 10))
        )
        assertNotEquals("daysElapsed 未参与 hash", early, late)
    }

    @Test
    fun `序列化结果不含真实商户名`() {
        val json = SummaryFormatter.canonicalJson(
            build(listOf(expense(100.0, 1, merchant = "瑞幸咖啡科技园店")))
        )
        assertFalse(json.contains("瑞幸"))
        assertTrue(json.contains("餐饮商户 #1"))
    }

    @Test
    fun `商户名含引号与换行时被正确转义`() {
        // canonicalJson 是手写拼接，必须自己处理转义，
        // 否则用户自定义分类名里的引号会产出破损 JSON（虽然只做 hash，
        // 但破损会让不同数据 hash 碰撞）
        val leaky = mapOf(1L to "分类\"带引号\n和换行")
        val range = SummaryPeriods.current(SummaryPeriod.THIS_MONTH, ts(7, 14), sh)
        val s = SummaryAggregator.aggregate(
            period = SummaryPeriod.THIS_MONTH, range = range, now = ts(7, 14), tz = sh,
            points = listOf(expense(100.0, 1)),
            categoryNameById = leaky, budget = null, previousPeriodExpense = null
        )
        val json = SummaryFormatter.canonicalJson(s)
        assertFalse("裸换行会破坏结构", json.contains("\n"))
        assertFalse("裸引号会破坏结构", json.contains("分类\"带"))
        assertTrue(json.contains("\\n"))
    }

    @Test
    fun `字段顺序固定（手写拼接不依赖 Map 遍历序）`() {
        val json = SummaryFormatter.canonicalJson(
            build((1..5).map { expense(10.0 * it, it, categoryId = (it % 3 + 1).toLong(), merchant = "店$it") })
        )
        val iPeriod = json.indexOf("\"period\"")
        val iExpense = json.indexOf("\"expense\"")
        val iCats = json.indexOf("\"categories\"")
        val iMerch = json.indexOf("\"merchants\"")
        val iBuckets = json.indexOf("\"buckets\"")
        assertTrue(iPeriod < iExpense)
        assertTrue(iExpense < iCats)
        assertTrue(iCats < iMerch)
        assertTrue(iMerch < iBuckets)
    }

    @Test
    fun `null 预算与 null 环比被稳定序列化`() {
        val json = SummaryFormatter.canonicalJson(
            build(listOf(expense(100.0, 1)), budget = null, previous = null)
        )
        assertTrue(json.contains("\"budget\":\"null\""))
        assertTrue(json.contains("\"prevExpense\":\"null\""))
        assertTrue(json.contains("\"change\":\"null\""))
    }

    @Test
    fun `时段桶按固定顺序序列化`() {
        val json = SummaryFormatter.canonicalJson(build(listOf(expense(100.0, 1))))
        val order = TimeBucket.entries.map { json.indexOf("\"n\":\"${it.name}\"") }
        assertEquals(order, order.sorted())
        assertTrue(order.all { it >= 0 })
    }
}
