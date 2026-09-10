package com.smartledger.service.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenAmountExtractorTest {

    private fun nodes(vararg texts: String): List<UiTextNode> =
        texts.mapIndexed { i, t -> UiTextNode(text = t, viewId = null, className = null, depth = 0, index = i) }

    // ═══ 正例：各基础分档 ═══

    @Test
    fun `货币符前缀金额`() {
        val hit = ScreenAmountExtractor.extractBest(nodes("支付成功", "¥32.00", "老乡鸡"))
        assertEquals(32.0, hit!!.amount, 1e-9)
        assertEquals(1, hit.nodeIndex)
    }

    @Test
    fun `带元单位金额`() {
        val hit = ScreenAmountExtractor.extractBest(nodes("转账成功", "500.00元", "张三"))
        assertEquals(500.0, hit!!.amount, 1e-9)
    }

    @Test
    fun `裸数字唯一候选压线通过 - 红包页形态`() {
        // 红包详情页金额无 ¥ 无 元：靠「唯一候选 +60」恰好达到 MIN_SCORE
        val hit = ScreenAmountExtractor.extractBest(nodes("已存入零钱", "12.88"))
        assertEquals(12.88, hit!!.amount, 1e-9)
        assertEquals(60, hit.score)
    }

    @Test
    fun `裸数字配合实付金额标签`() {
        val hit = ScreenAmountExtractor.extractBest(nodes("实付金额", "32.00"))
        assertEquals(32.0, hit!!.amount, 1e-9)
    }

    @Test
    fun `千分位金额`() {
        val hit = ScreenAmountExtractor.extractBest(nodes("支付成功", "¥1,234.56"))
        assertEquals(1234.56, hit!!.amount, 1e-9)
    }

    // ═══ 负分与过滤 ═══

    @Test
    fun `长裸数字被丢弃 - 订单号形态`() {
        // 12 位裸数字不该和 ¥32.00 竞争，也不影响 ¥ 金额的唯一性加分
        val hit = ScreenAmountExtractor.extractBest(nodes("订单号", "202609091234", "¥32.00"))
        assertEquals(32.0, hit!!.amount, 1e-9)
    }

    @Test
    fun `时间标签不连坐货币符金额`() {
        // 「付款时间 14:22」旁边的 ¥32.00 是真金额
        val hit = ScreenAmountExtractor.extractBest(nodes("付款时间 14:22", "¥32.00"))
        assertEquals(32.0, hit!!.amount, 1e-9)
    }

    @Test
    fun `裸数字邻近时间标签被压线拒绝`() {
        // 0 + 60(唯一) - 80(时间邻近) = -20 < 60
        assertNull(ScreenAmountExtractor.extractBest(nodes("时间", "500")))
    }

    @Test
    fun `裸数字邻近订单号标签被拒绝`() {
        // 0 + 60(唯一) - 100(订单号邻近) = -40 < 60
        assertNull(ScreenAmountExtractor.extractBest(nodes("订单号", "500")))
    }

    @Test
    fun `多个裸数字无标签全部拒绝`() {
        assertNull(ScreenAmountExtractor.extractBest(nodes("32.00", "45.00")))
    }

    // ═══ 值域 ═══

    @Test
    fun `零金额被拒绝`() {
        assertNull(ScreenAmountExtractor.extractBest(nodes("支付成功", "¥0.00")))
    }

    @Test
    fun `超上限金额被拒绝`() {
        assertNull(ScreenAmountExtractor.extractBest(nodes("支付成功", "¥2000000.00")))
    }

    @Test
    fun `无任何候选返回null`() {
        assertNull(ScreenAmountExtractor.extractBest(nodes("支付成功", "老乡鸡")))
        assertNull(ScreenAmountExtractor.extractBest(emptyList()))
    }

    // ═══ 打分竞争 ═══

    @Test
    fun `高优先级形态胜出`() {
        // 页面同时有 ¥ 金额和带元金额：¥ 前缀(100) 高于 元(80)
        val hit = ScreenAmountExtractor.extractBest(nodes("支付成功", "¥32.00", "合计 100元"))
        assertEquals(32.0, hit!!.amount, 1e-9)
    }

    // ═══ P2-9：畸形千分位不被拼接 ═══

    @Test
    fun `畸形千分位只取数字部分`() {
        // ¥12,34 不是合法千分位：第二分支匹配 12，逗号后的 34 不拼进来
        val hit = ScreenAmountExtractor.extractBest(nodes("支付成功", "¥12,34"))
        assertEquals(12.0, hit!!.amount, 1e-9)
    }

    @Test
    fun `畸形千分位带元同样处理`() {
        // 「12,34元」：前缀「12」后紧跟逗号无法接「元」，
        // 正则回退到后面的「34元」—— 关键是绝不拼成 1234
        val hit = ScreenAmountExtractor.extractBest(nodes("合计", "12,34元"))
        assertEquals(34.0, hit!!.amount, 1e-9)
    }
}
