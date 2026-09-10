package com.smartledger.service.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 支付宝页面解析测试（方案 6.4 支付宝测试集：明确单笔成功页面识别，模糊页面不自动记账）。
 */
class AlipayAccessibilityParserTest {

    private fun snapshot(vararg texts: String): UiSnapshot {
        val nodes = texts.mapIndexed { i, t -> UiTextNode(text = t, viewId = null, className = null, depth = 0, index = i) }
        return UiSnapshot("com.eg.android.AlipayGphone", nodes, capturedAt = 0L)
    }

    // ═══ 必须正确识别（5 正） ═══

    @Test
    fun `场景E - 付款成功记支出`() {
        val p = AlipayAccessibilityParser.parse(snapshot("支付成功", "¥32.00", "某商户"))!!
        assertEquals(32.0, p.amount, 1e-9)
        assertEquals("expense", p.type)
        assertEquals("支付宝", p.paymentMethod)
    }

    @Test
    fun `场景F - 主动转账成功记支出`() {
        val p = AlipayAccessibilityParser.parse(snapshot("转账成功", "500.00元", "李四"))!!
        assertEquals(500.0, p.amount, 1e-9)
        assertEquals("expense", p.type)
    }

    @Test
    fun `场景G - 收款成功记收入`() {
        val p = AlipayAccessibilityParser.parse(snapshot("收款成功", "¥500.00"))!!
        assertEquals(500.0, p.amount, 1e-9)
        assertEquals("income", p.type)
    }

    @Test
    fun `场景G变体 - 收到转账交易成功记收入`() {
        val p = AlipayAccessibilityParser.parse(snapshot("收到转账", "¥500.00", "交易成功"))!!
        assertEquals(500.0, p.amount, 1e-9)
        assertEquals("income", p.type)
    }

    @Test
    fun `场景H - 退款成功记收入`() {
        val p = AlipayAccessibilityParser.parse(snapshot("退款成功", "¥68.00"))!!
        assertEquals(68.0, p.amount, 1e-9)
        assertEquals("income", p.type)
    }

    // ═══ 不得自动入账（负例） ═══

    @Test
    fun `无金额的转账通知词形不记`() {
        assertNull(AlipayAccessibilityParser.parse(snapshot("张三成功向你转了一笔钱")))
    }

    @Test
    fun `营销文案不记`() {
        assertNull(AlipayAccessibilityParser.parse(snapshot("活动", "最高领取500元")))
    }

    @Test
    fun `余额页面不记`() {
        assertNull(
            AlipayAccessibilityParser.parse(
                snapshot("我的余额", "¥3,200.50", "余额宝", "¥12,000.00")
            )
        )
    }

    @Test
    fun `账单列表多个金额不记`() {
        // 多个裸金额互相竞争且无标签支撑，打分全部不过线
        assertNull(
            AlipayAccessibilityParser.parse(
                snapshot("9月账单", "-32.00", "-45.00", "更多")
            )
        )
    }

    // ═══ 排除词（C3） ═══

    @Test
    fun `待收款页面不记`() {
        assertNull(AlipayAccessibilityParser.parse(snapshot("待收款", "¥500.00")))
    }

    @Test
    fun `等待对方收款不记`() {
        assertNull(AlipayAccessibilityParser.parse(snapshot("等待对方收款", "¥500.00")))
    }

    @Test
    fun `处理中页面不记`() {
        assertNull(AlipayAccessibilityParser.parse(snapshot("处理中", "¥500.00")))
    }

    @Test
    fun `裸金额唯一但无状态词不记`() {
        assertNull(AlipayAccessibilityParser.parse(snapshot("32.00")))
    }

    // ═══ P0-3：营销/积分页面不误记收入 ═══

    @Test
    fun `积分兑换到账不记收入`() {
        assertNull(AlipayAccessibilityParser.parse(snapshot("积分兑换", "到账成功", "¥5.00")))
    }

    @Test
    fun `余额宝收益页不记收入`() {
        assertNull(AlipayAccessibilityParser.parse(snapshot("收益", "到账成功", "¥3.20", "余额宝")))
    }

    @Test
    fun `签到奖励页不记收入`() {
        assertNull(AlipayAccessibilityParser.parse(snapshot("签到", "到账成功", "¥0.10", "奖励")))
    }

    @Test
    fun `提现页不记收入`() {
        // 提现是内部资金转移，不是收入
        assertNull(AlipayAccessibilityParser.parse(snapshot("提现", "到账成功", "¥500.00")))
    }

    @Test
    fun `带积分提示的支出页仍正常记支出`() {
        val p = AlipayAccessibilityParser.parse(
            snapshot("支付成功", "¥32.00", "某商户", "本单获得 32 积分")
        )!!
        assertEquals(32.0, p.amount, 1e-9)
        assertEquals("expense", p.type)
    }

    // ═══ N2/N3：真实收入不被误杀 + 真实到账不漏记（对抗2/3 复现） ═══

    @Test
    fun `收款页底部营销横幅不误杀真实收款`() {
        // N2：收款成功页底部常带积分/会员横幅，营销词距离状态词远（≥3）不连坐
        val p = AlipayAccessibilityParser.parse(
            snapshot(
                "收款成功", "¥100.00", "张三", "查看详情",
                "本单获得5积分", "领取会员权益"
            )
        )!!
        assertEquals(100.0, p.amount, 1e-9)
        assertEquals("income", p.type)
    }

    @Test
    fun `工资已到账正常记收入 - N3加回词形`() {
        val p = AlipayAccessibilityParser.parse(
            snapshot("工资已到账", "¥8,000.00", "某公司")
        )!!
        assertEquals(8000.0, p.amount, 1e-9)
        assertEquals("income", p.type)
    }

    @Test
    fun `余额宝收益已到账不记收入 - 邻近营销词拦截`() {
        // N3 双向锁定：「收益」「余额宝」与「已到账」同节点/紧邻 → 排除
        assertNull(AlipayAccessibilityParser.parse(snapshot("余额宝收益已到账", "¥3.20")))
    }
}
