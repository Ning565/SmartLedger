package com.smartledger.service.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransactionTimeExtractorTest {

    private fun nodes(vararg texts: String): List<UiTextNode> =
        texts.mapIndexed { i, t -> UiTextNode(text = t, viewId = null, className = null, depth = 0, index = i) }

    @Test
    fun `完整日期时间 - 横杠格式`() {
        assertEquals(
            "2026-09-09 14:22:35",
            TransactionTimeExtractor.extract(nodes("支付成功", "¥32.00", "交易时间", "2026-09-09 14:22:35"))
        )
    }

    @Test
    fun `完整日期时间 - 中文年月日`() {
        // 含年份的完整日期时间是强特征：聊天分隔符不含年份，整页搜索即安全
        assertEquals(
            "2026年9月9日 14:22",
            TransactionTimeExtractor.extract(nodes("2026年9月9日 14:22"))
        )
    }

    @Test
    fun `完整日期优先于弱特征`() {
        // 页面同时有交易时间与其它时间形态时，取信息量最大的
        assertEquals(
            "2026-09-09 14:22:35",
            TransactionTimeExtractor.extract(nodes("14:22", "2026-09-09 14:22:35"))
        )
    }

    // ═══ N1：孤立的 HH:mm / 月日时间不是交易时间（聊天分隔符防指纹漂移） ═══

    @Test
    fun `孤立纯时间不采用 - 聊天当天分隔符`() {
        // 聊天页时间分隔符就是「14:22」，与交易时间正则无法区分；
        // 采用它会导致同一笔红包随滚动漂移指纹 → 重复记账
        assertNull(TransactionTimeExtractor.extract(nodes("14:22", "交易成功", "16:30")))
        assertNull(TransactionTimeExtractor.extract(nodes("红包已领取", "12.88", "14:22")))
    }

    @Test
    fun `孤立月日时间不采用 - 聊天跨天分隔符`() {
        // 微信聊天跨天分隔符恰好是「9月9日 14:22」形态，同样只认标签邻近
        assertNull(TransactionTimeExtractor.extract(nodes("9月8日 10:00", "红包已领取", "12.88")))
    }

    @Test
    fun `倒计时等非交易时间不采用`() {
        assertNull(TransactionTimeExtractor.extract(nodes("优惠券", "23:59:59", "后过期")))
    }

    // ═══ N1：标签邻近的时间被认定（支付页「标签： 值」结构） ═══

    @Test
    fun `标签节点后跟纯时间采用`() {
        // 典型支付页结构：独立「付款时间」标签节点 + 时间值节点
        assertEquals(
            "14:22",
            TransactionTimeExtractor.extract(nodes("支付成功", "¥32.00", "付款时间", "14:22"))
        )
    }

    @Test
    fun `同节点标签时间采用`() {
        // 标签与时间在一条文本里
        assertEquals(
            "14:22",
            TransactionTimeExtractor.extract(nodes("交易时间 14:22"))
        )
    }

    @Test
    fun `标签后跟月日时间采用`() {
        assertEquals(
            "9月9日 14:22",
            TransactionTimeExtractor.extract(nodes("交易时间", "9月9日 14:22"))
        )
    }

    @Test
    fun `标签带冒号形态采用`() {
        assertEquals(
            "14:22",
            TransactionTimeExtractor.extract(nodes("付款时间:", "14:22"))
        )
    }

    @Test
    fun `无时间文本返回null`() {
        assertNull(TransactionTimeExtractor.extract(nodes("红包已领取", "12.88")))
        assertNull(TransactionTimeExtractor.extract(emptyList()))
    }

    @Test
    fun `空白归一`() {
        assertEquals(
            "2026-09-09 14:22:35",
            TransactionTimeExtractor.extract(nodes("2026-09-09   14:22:35"))
        )
    }
}
