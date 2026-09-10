package com.smartledger.service.accessibility

import com.smartledger.service.ParseConfidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 微信页面解析测试（方案 6.4 微信测试集：前 5 个必须识别，后 5 个不得自动入账）。
 */
class WeChatAccessibilityParserTest {

    private fun snapshot(vararg texts: String): UiSnapshot {
        val nodes = texts.mapIndexed { i, t -> UiTextNode(text = t, viewId = null, className = null, depth = 0, index = i) }
        return UiSnapshot("com.tencent.mm", nodes, capturedAt = 0L)
    }

    // ═══ 必须正确识别（5 正） ═══

    @Test
    fun `场景A - 支付成功页记支出`() {
        val p = WeChatAccessibilityParser.parse(snapshot("支付成功", "¥32.00", "老乡鸡"))!!
        assertEquals(32.0, p.amount, 1e-9)
        assertEquals("expense", p.type)
        assertEquals("微信", p.paymentMethod)
        assertEquals("老乡鸡", p.merchant)
        assertEquals(ParseConfidence.HIGH, p.confidence)
    }

    @Test
    fun `场景B - 主动转账成功记支出`() {
        val p = WeChatAccessibilityParser.parse(snapshot("转账成功", "¥500.00", "张三"))!!
        assertEquals(500.0, p.amount, 1e-9)
        assertEquals("expense", p.type)
        assertEquals("张三", p.merchant)
    }

    @Test
    fun `场景C - 已收款记收入`() {
        val p = WeChatAccessibilityParser.parse(snapshot("微信转账", "已收款", "¥500.00"))!!
        assertEquals(500.0, p.amount, 1e-9)
        assertEquals("income", p.type)
    }

    @Test
    fun `场景D - 红包已领取带金额记收入`() {
        val p = WeChatAccessibilityParser.parse(snapshot("红包已领取", "12.88元"))!!
        assertEquals(12.88, p.amount, 1e-9)
        assertEquals("income", p.type)
    }

    @Test
    fun `场景D变体 - 已存入零钱带裸金额记收入`() {
        val p = WeChatAccessibilityParser.parse(snapshot("12.88", "已存入零钱"))!!
        assertEquals(12.88, p.amount, 1e-9)
        assertEquals("income", p.type)
    }

    // ═══ 不得自动入账（5 负） ═══

    @Test
    fun `只有红包已领取无金额不记`() {
        assertNull(WeChatAccessibilityParser.parse(snapshot("红包已领取")))
    }

    @Test
    fun `只有已收款无金额不记`() {
        assertNull(WeChatAccessibilityParser.parse(snapshot("已收款")))
    }

    @Test
    fun `普通聊天我给你500元不记`() {
        assertNull(WeChatAccessibilityParser.parse(snapshot("张三", "我给你500元", "2026-09-08")))
    }

    @Test
    fun `普通聊天这个东西32元不记`() {
        assertNull(WeChatAccessibilityParser.parse(snapshot("这个东西¥32", "挺好用的")))
    }

    @Test
    fun `公众号文章优惠价不记`() {
        assertNull(WeChatAccessibilityParser.parse(snapshot("限时活动", "优惠价¥99", "点击参与")))
    }

    // ═══ 词包含陷阱与邻近性（C3 / M1 回归） ═══

    @Test
    fun `对方已收款即使带金额也不记`() {
        // 「对方已收款」包含「已收款」——不先查排除词就会把付款方视角记成收入
        assertNull(WeChatAccessibilityParser.parse(snapshot("张三", "对方已收款", "¥500.00")))
    }

    @Test
    fun `待收款页面不记`() {
        assertNull(WeChatAccessibilityParser.parse(snapshot("待收款", "¥500.00")))
    }

    @Test
    fun `已退还页面不记`() {
        assertNull(WeChatAccessibilityParser.parse(snapshot("已退还", "¥12.88")))
    }

    @Test
    fun `历史聊天状态词与金额距离过远不记`() {
        // 「已收款」是几条消息前的历史记录，新消息里只有金额
        assertNull(
            WeChatAccessibilityParser.parse(
                snapshot("已收款", "在吗", "明天一起吃饭", "好的", "收到", "¥500.00")
            )
        )
    }

    @Test
    fun `空快照不记`() {
        assertNull(WeChatAccessibilityParser.parse(snapshot()))
    }

    // ═══ P0-3：营销/积分页面不误记收入 ═══

    @Test
    fun `积分到账页不记收入`() {
        // review 实测场景：原实现会记一笔「收入 ¥20，商户=查看积分」且静默入账
        assertNull(WeChatAccessibilityParser.parse(snapshot("积分", "已领取", "¥20.00", "查看积分")))
    }

    @Test
    fun `理财收益页不记收入`() {
        assertNull(WeChatAccessibilityParser.parse(snapshot("已存入零钱", "¥5.20", "理财收益", "余额宝")))
    }

    @Test
    fun `优惠券红包封面页不记收入`() {
        assertNull(WeChatAccessibilityParser.parse(snapshot("红包封面", "已领取", "¥1.00")))
    }

    @Test
    fun `签到奖励页不记收入`() {
        assertNull(WeChatAccessibilityParser.parse(snapshot("签到成功", "已存入零钱", "¥0.50", "奖励")))
    }

    @Test
    fun `带积分提示的支出页仍正常记支出`() {
        // P0-3 分层设计：营销词只拦收入方向，
        // 支付成功页常带「本单获得 X 积分」提示，不能误杀
        val p = WeChatAccessibilityParser.parse(
            snapshot("支付成功", "¥32.00", "某某便利店", "本单获得 32 积分")
        )!!
        assertEquals(32.0, p.amount, 1e-9)
        assertEquals("expense", p.type)
    }

    // ═══ N2/N3：真实收入不被误杀 + 真实到账不漏记（对抗2/3 复现） ═══

    @Test
    fun `收款页底部营销横幅不误杀真实收款`() {
        // N2：营销词与状态词距离远（≥3 节点）时不是营销页 ——
        // 收款成功页底部常带积分/会员横幅，不能连坐整笔 100 元收入
        val p = WeChatAccessibilityParser.parse(
            snapshot(
                "已收款", "¥100.00", "张三", "查看详情",
                "本单获得5积分", "领取会员权益"
            )
        )!!
        assertEquals(100.0, p.amount, 1e-9)
        assertEquals("income", p.type)
    }

    @Test
    fun `转账已到账正常记收入 - N3加回词形`() {
        // N3：「已到账」是转账/工资到账的高频词形，移除会漏记真实收入
        val p = WeChatAccessibilityParser.parse(
            snapshot("转账已到账", "¥500.00", "李四")
        )!!
        assertEquals(500.0, p.amount, 1e-9)
        assertEquals("income", p.type)
    }

    @Test
    fun `积分已到账不记收入 - 同节点营销词拦截`() {
        // N3 双向锁定：营销词与状态词同节点（「积分已到账」）→ 距离 0 → 排除
        assertNull(WeChatAccessibilityParser.parse(snapshot("积分已到账", "¥20.00", "查看积分")))
    }
}
