package com.smartledger.service.accessibility

import com.smartledger.service.ParsedPayment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityFingerprintBuilderTest {

    private fun snapshot(
        vararg texts: String,
        pkg: String = "com.tencent.mm",
        capturedAt: Long = 0L
    ): UiSnapshot {
        val nodes = texts.mapIndexed { i, t -> UiTextNode(text = t, viewId = null, className = null, depth = 0, index = i) }
        return UiSnapshot(pkg, nodes, capturedAt = capturedAt)
    }

    private fun parsed(
        amount: Double,
        type: String = "expense",
        merchant: String? = "老乡鸡"
    ) = ParsedPayment(
        amount = amount,
        merchant = merchant,
        paymentMethod = "微信",
        notificationKey = "",
        type = type
    )

    @Test
    fun `同输入产出相同key`() {
        val key1 = AccessibilityFingerprintBuilder.build(
            "com.tencent.mm", snapshot("支付成功", "¥32.00"), parsed(32.0)
        )
        val key2 = AccessibilityFingerprintBuilder.build(
            "com.tencent.mm", snapshot("支付成功", "¥32.00"), parsed(32.0)
        )
        assertEquals(key1, key2)
    }

    @Test
    fun `key带渠道别名前缀`() {
        val wechat = AccessibilityFingerprintBuilder.build(
            "com.tencent.mm", snapshot("支付成功", "¥32.00"), parsed(32.0)
        )
        val alipay = AccessibilityFingerprintBuilder.build(
            "com.eg.android.AlipayGphone",
            snapshot("支付成功", "¥32.00", pkg = "com.eg.android.AlipayGphone"),
            parsed(32.0)
        )
        assertTrue(wechat.startsWith("a11y:wechat:"))
        assertTrue(alipay.startsWith("a11y:alipay:"))
        assertNotEquals(wechat, alipay)
    }

    @Test
    fun `金额或商户变化产生不同key`() {
        val s = snapshot("支付成功", "¥32.00")
        val k1 = AccessibilityFingerprintBuilder.build("com.tencent.mm", s, parsed(32.0))
        val k2 = AccessibilityFingerprintBuilder.build("com.tencent.mm", s, parsed(33.0))
        val k3 = AccessibilityFingerprintBuilder.build("com.tencent.mm", s, parsed(32.0, merchant = null))
        assertNotEquals(k1, k2)
        assertNotEquals(k1, k3)
    }

    @Test
    fun `状态词命中顺序不影响key - sorted稳定性`() {
        // 同一页面两次扫描节点顺序可能不同，命中词集合一致时 key 必须一致
        val k1 = AccessibilityFingerprintBuilder.build(
            "com.tencent.mm", snapshot("支付成功", "已收款", "¥500.00"), parsed(500.0, type = "income")
        )
        val k2 = AccessibilityFingerprintBuilder.build(
            "com.tencent.mm", snapshot("已收款", "支付成功", "¥500.00"), parsed(500.0, type = "income")
        )
        assertEquals(k1, k2)
    }

    // ═══ P0-2：交易时间纳入指纹（区分重开历史页 vs 新的同额交易） ═══

    @Test
    fun `同商户同金额的两笔不同交易产生不同key`() {
        // 每天在同一家便利店买 8 元早餐：页面交易时间不同 → 不再被终身去重吞掉
        val day1 = AccessibilityFingerprintBuilder.build(
            "com.tencent.mm",
            snapshot("支付成功", "¥8.00", "某某便利店", "交易时间", "2026-09-08 08:15:00"),
            parsed(8.0)
        )
        val day2 = AccessibilityFingerprintBuilder.build(
            "com.tencent.mm",
            snapshot("支付成功", "¥8.00", "某某便利店", "交易时间", "2026-09-09 08:16:00"),
            parsed(8.0)
        )
        org.junit.Assert.assertNotEquals(day1, day2)
    }

    @Test
    fun `重开同一张历史支付页产生相同key`() {
        // 页面交易时间不变 → 同 key → 终身去重生效（不重复记）
        val k1 = AccessibilityFingerprintBuilder.build(
            "com.tencent.mm",
            snapshot("支付成功", "¥8.00", "某某便利店", "交易时间", "2026-09-08 08:15:00"),
            parsed(8.0)
        )
        val k2 = AccessibilityFingerprintBuilder.build(
            "com.tencent.mm",
            snapshot("支付成功", "¥8.00", "某某便利店", "交易时间", "2026-09-08 08:15:00"),
            parsed(8.0)
        )
        assertEquals(k1, k2)
    }

    @Test
    fun `无时间文本时隔天产生不同key - 退化到天`() {
        // 红包页无时间文本：capturedAt 归一到天，隔天不再互相拦截
        val day1 = AccessibilityFingerprintBuilder.build(
            "com.tencent.mm",
            snapshot("红包已领取", "12.88", capturedAt = 1_760_000_000_000L),
            parsed(12.88, type = "income")
        )
        val k1 = AccessibilityFingerprintBuilder.build(
            "com.tencent.mm",
            snapshot("红包已领取", "12.88", capturedAt = 1_760_000_000_000L),
            parsed(12.88, type = "income")
        )
        val k2 = AccessibilityFingerprintBuilder.build(
            "com.tencent.mm",
            snapshot("红包已领取", "12.88", capturedAt = 1_760_086_400_000L),
            parsed(12.88, type = "income")
        )
        // 同天同页面 → 同 key；隔天（+86400000ms）→ 不同 key
        assertEquals(k1, day1)
        org.junit.Assert.assertNotEquals(k1, k2)
    }

    // ═══ N1：聊天页红包随滚动不漂移指纹（对抗1 复现） ═══

    @Test
    fun `聊天页红包滚动前后同key - 分隔符变化不漂移`() {
        // 场景：聊天页领红包后继续聊天并滚动，
        // 时间分隔符「14:22」滑出、新分隔符「14:35」进入视野。
        // 孤立 HH:mm 不是交易时间（N1），两次都退化到按天 → 同 key 不重复记账
        val before = AccessibilityFingerprintBuilder.build(
            "com.tencent.mm",
            snapshot("14:22", "红包已领取", "12.88", "你好"),
            parsed(12.88, type = "income")
        )
        val after = AccessibilityFingerprintBuilder.build(
            "com.tencent.mm",
            snapshot("红包已领取", "12.88", "你好", "14:35"),
            parsed(12.88, type = "income")
        )
        assertEquals(before, after)
    }

    @Test
    fun `跨天分隔符变化也不漂移`() {
        // 微信跨天分隔符「9月8日 10:00」与「9月9日 11:00」都是孤立月日时间，
        // 同样不采用（N1）；同一天内指纹稳定
        val before = AccessibilityFingerprintBuilder.build(
            "com.tencent.mm",
            snapshot("9月8日 10:00", "红包已领取", "12.88"),
            parsed(12.88, type = "income")
        )
        val after = AccessibilityFingerprintBuilder.build(
            "com.tencent.mm",
            snapshot("红包已领取", "12.88", "9月9日 11:00"),
            parsed(12.88, type = "income")
        )
        assertEquals(before, after)
    }
}
