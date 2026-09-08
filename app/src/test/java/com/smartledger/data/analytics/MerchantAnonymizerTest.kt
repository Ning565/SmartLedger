package com.smartledger.data.analytics

import com.smartledger.data.analytics.model.SummaryPeriod
import com.smartledger.data.analytics.model.TxPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * 商户脱敏测试。
 *
 * 核心断言有两类：
 *  1. 行为正确 —— 同名合并、按分类编号、Top N 截断；
 *  2. **不泄漏** —— 输出里绝不能出现输入商户名的任何片段。
 * 第 2 类比第 1 类更重要，因此单独用一组用例守住。
 */
class MerchantAnonymizerTest {

    private fun row(
        merchant: String,
        amount: Double,
        count: Int = 1,
        category: String? = "餐饮"
    ) = MerchantAnonymizer.MerchantRow(merchant, amount, count, category)

    // ═══ 编号规则 ═══

    @Test
    fun `按输入顺序在每个分类内独立编号`() {
        val out = MerchantAnonymizer.anonymize(
            listOf(
                row("瑞幸咖啡科技园店", 326.0, 12, "餐饮"),
                row("麦当劳南山店", 210.0, 5, "餐饮"),
                row("淘宝XX数码旗舰店", 860.0, 4, "购物"),
                row("滴滴出行", 120.0, 8, "交通")
            )
        )
        assertEquals(
            listOf("餐饮商户 #1", "餐饮商户 #2", "购物商户 #1", "交通商户 #1"),
            out.map { it.anonymizedName }
        )
    }

    @Test
    fun `金额与笔数原样保留（AI 需要这些做行为诊断）`() {
        val out = MerchantAnonymizer.anonymize(listOf(row("瑞幸咖啡", 326.0, 12, "餐饮")))
        assertEquals(326.0, out[0].amount, 1e-9)
        assertEquals(12, out[0].count)
    }

    @Test
    fun `同一真实商户名映射到同一编号`() {
        val out = MerchantAnonymizer.anonymize(
            listOf(
                row("瑞幸咖啡", 100.0, 3, "餐饮"),
                row("瑞幸咖啡", 50.0, 2, "餐饮")
            )
        )
        assertEquals(out[0].anonymizedName, out[1].anonymizedName)
        assertEquals("餐饮商户 #1", out[0].anonymizedName)
    }

    @Test
    fun `商户名只差空白时视为同一家`() {
        val out = MerchantAnonymizer.anonymize(
            listOf(
                row("  瑞幸咖啡 ", 100.0, 1, "餐饮"),
                row("瑞幸  咖啡", 50.0, 1, "餐饮")
            )
        )
        assertEquals(out[0].anonymizedName, out[1].anonymizedName)
    }

    @Test
    fun `回归：聚合层与匿名层的归一化必须一致，不得出现两行同名`() {
        // 这是一个真实存在过的 bug：
        // SummaryAggregator 原本把空白折叠成单个空格（「瑞幸咖啡 X」），
        // 而 MerchantAnonymizer 去掉全部空白（「瑞幸咖啡X」）。
        // 结果聚合层分成两组、匿名层又归为同一编号，
        // Prompt 里出现两行一模一样的「餐饮商户 #1」，
        // AI 看到会直接得出错误结论。
        val sh = TimeZone.getTimeZone("Asia/Shanghai")
        val cal = Calendar.getInstance(sh).apply {
            set(2026, Calendar.SEPTEMBER, 3, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val points = listOf(
            TxPoint(100.0, "expense", 1L, "瑞幸咖啡 科技园店", cal.timeInMillis),
            TxPoint(50.0, "expense", 1L, "瑞幸咖啡科技园店", cal.timeInMillis + 1000),
            TxPoint(30.0, "expense", 1L, "  瑞幸咖啡科技园店  ", cal.timeInMillis + 2000)
        )
        val range = SummaryPeriods.current(SummaryPeriod.THIS_MONTH, cal.timeInMillis, sh)
        val s = SummaryAggregator.aggregate(
            period = SummaryPeriod.THIS_MONTH, range = range, now = cal.timeInMillis, tz = sh,
            points = points, categoryNameById = mapOf(1L to "餐饮"),
            budget = null, previousPeriodExpense = null
        )
        val names = s.topMerchantStats.map { it.anonymizedName }
        assertEquals("同一家店被拆成了多行：$names", 1, names.size)
        assertEquals("餐饮商户 #1", names[0])
        assertEquals(180.0, s.topMerchantStats[0].amount, 1e-9)
        assertEquals(3, s.topMerchantStats[0].count)
        // 通用不变量：任何输入下匿名编号都不应重复
        assertEquals(names.size, names.distinct().size)
    }

    @Test
    fun `分类为空时归入其他商户`() {
        val out = MerchantAnonymizer.anonymize(listOf(row("某店", 100.0, 1, null)))
        assertEquals("其他商户 #1", out[0].anonymizedName)
    }

    @Test
    fun `分类为空白串时归入其他商户`() {
        assertEquals(
            "其他商户 #1",
            MerchantAnonymizer.anonymize(listOf(row("某店", 100.0, 1, "   ")))[0].anonymizedName
        )
    }

    @Test
    fun `商户名为空或全空白的行被丢弃`() {
        val out = MerchantAnonymizer.anonymize(
            listOf(row("", 100.0, 1), row("   ", 50.0, 1), row("有名字", 30.0, 1))
        )
        assertEquals(1, out.size)
        assertEquals("餐饮商户 #1", out[0].anonymizedName)
    }

    @Test
    fun `空输入返回空列表`() {
        assertTrue(MerchantAnonymizer.anonymize(emptyList()).isEmpty())
    }

    // ═══ 不泄漏 ═══

    @Test
    fun `输出不含真实商户名的任何片段`() {
        val names = listOf(
            "瑞幸咖啡科技园店", "麦当劳南山店", "淘宝XX数码旗舰店",
            "星巴克(海岸城店)", "7-ELEVEN 便利店", "盒马鲜生"
        )
        val out = MerchantAnonymizer.anonymize(
            names.mapIndexed { i, n -> row(n, 100.0 - i, 1, "餐饮") }
        )
        val joined = out.joinToString("|") { it.anonymizedName }
        names.forEach { n ->
            assertFalse("泄漏完整商户名：$n", joined.contains(n))
            // 逐字检查：任何一个字出现都算泄漏
            n.toCharArray().filter { !it.isWhitespace() }.forEach { ch ->
                assertFalse("泄漏商户名字符 '$ch'（来自 $n）：$joined", joined.contains(ch))
            }
        }
    }

    @Test
    fun `不做部分打码`() {
        val out = MerchantAnonymizer.anonymize(listOf(row("瑞幸咖啡科技园店", 100.0, 1, "餐饮")))
        assertFalse(out[0].anonymizedName.contains("*"))
        assertFalse(out[0].anonymizedName.contains("…"))
        assertEquals("餐饮商户 #1", out[0].anonymizedName)
    }

    @Test
    fun `商户名里的数字不会带进编号`() {
        val out = MerchantAnonymizer.anonymize(
            listOf(row("7-11便利店2024", 100.0, 1, "餐饮"))
        )
        // 除了序号 #1 之外不该有别的数字
        val digits = out[0].anonymizedName.filter { it.isDigit() }
        assertEquals("1", digits)
    }

    // ═══ 分类名清洗（用户可自定义分类，需要设边界）═══

    @Test
    fun `超长自定义分类名被截断`() {
        val longName = "这是一个非常非常非常长的用户自定义分类名称用于测试截断行为"
        val out = MerchantAnonymizer.anonymize(listOf(row("某店", 100.0, 1, longName)))
        assertTrue(
            "匿名前缀过长：${out[0].anonymizedName}",
            out[0].anonymizedName.length <= 12
        )
    }

    @Test
    fun `分类名里的数字被剔除`() {
        val out = MerchantAnonymizer.anonymize(listOf(row("某店", 100.0, 1, "星巴克2024专用")))
        assertFalse(out[0].anonymizedName.contains("2024"))
    }

    @Test
    fun `分类名全是数字或符号时回落到其他`() {
        assertEquals(
            "其他商户 #1",
            MerchantAnonymizer.anonymize(listOf(row("某店", 100.0, 1, "2024")))[0].anonymizedName
        )
        assertEquals(
            "其他商户 #1",
            MerchantAnonymizer.anonymize(listOf(row("某店", 100.0, 1, "###")))[0].anonymizedName
        )
    }

    // ═══ 与聚合链路的衔接 ═══

    @Test
    fun `聚合后的 Top 商户数量被限制在 10 条`() {
        val sh = TimeZone.getTimeZone("Asia/Shanghai")
        val points = (1..30).map { i ->
            TxPoint(
                amount = 100.0 + i,
                type = "expense",
                categoryId = 1L,
                merchant = "商户$i",
                transactionTime = Calendar.getInstance(sh).apply {
                    set(2026, Calendar.SEPTEMBER, (i % 28) + 1, 12, 0, 0)
                }.timeInMillis
            )
        }
        val range = SummaryPeriods.current(
            SummaryPeriod.THIS_MONTH,
            points.first().transactionTime, sh
        )
        val s = SummaryAggregator.aggregate(
            period = SummaryPeriod.THIS_MONTH,
            range = range,
            now = points.last().transactionTime,
            tz = sh,
            points = points,
            categoryNameById = mapOf(1L to "餐饮"),
            budget = null,
            previousPeriodExpense = null
        )
        assertEquals(10, s.topMerchantStats.size)
        // 金额最大的排第一：商户30 = 130 元
        assertEquals(130.0, s.topMerchantStats[0].amount, 1e-9)
        assertEquals("餐饮商户 #1", s.topMerchantStats[0].anonymizedName)
    }
}
