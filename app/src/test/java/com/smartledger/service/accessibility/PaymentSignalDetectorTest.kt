package com.smartledger.service.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentSignalDetectorTest {

    // ═══ 双条件同时满足 → true ═══

    @Test
    fun `支付成功加金额`() {
        assertTrue(PaymentSignalDetector.hasStrongSignal("支付成功 ¥32.00"))
    }

    @Test
    fun `已收款加元单位金额`() {
        assertTrue(PaymentSignalDetector.hasStrongSignal("已收款 500.00元"))
    }

    @Test
    fun `红包已领取加裸金额`() {
        assertTrue(PaymentSignalDetector.hasStrongSignal("红包已领取 12.88"))
    }

    @Test
    fun `收到转账加金额 - 场景G词形`() {
        // C10 回归：这个词不在表里时整条链路在信号层被拦死
        assertTrue(PaymentSignalDetector.hasStrongSignal("收到转账 ¥500 交易成功"))
    }

    // ═══ 只有金额 → false ═══

    @Test
    fun `只有金额不触发`() {
        assertFalse(PaymentSignalDetector.hasStrongSignal("¥500"))
        assertFalse(PaymentSignalDetector.hasStrongSignal("这个东西32元"))
    }

    // ═══ 只有状态词 → false ═══

    @Test
    fun `只有状态词不触发`() {
        assertFalse(PaymentSignalDetector.hasStrongSignal("红包已领取"))
        assertFalse(PaymentSignalDetector.hasStrongSignal("已收款"))
    }

    @Test
    fun `空文本不触发`() {
        assertFalse(PaymentSignalDetector.hasStrongSignal(""))
    }

    // ═══ 日常页面不触发 ═══

    @Test
    fun `聊天文本不触发`() {
        assertFalse(PaymentSignalDetector.hasStrongSignal("我给你500元"))
    }

    @Test
    fun `营销文案不触发`() {
        // 「最高领取500元」的「领取」不是状态词（词表里只有「红包已领取」）
        assertFalse(PaymentSignalDetector.hasStrongSignal("最高领取500元"))
        assertFalse(PaymentSignalDetector.hasStrongSignal("限时优惠 立省99元"))
    }

    // ═══ 词表查询 ═══

    @Test
    fun `strongWordsIn返回全部命中词`() {
        val words = PaymentSignalDetector.strongWordsIn("支付成功，已收款 ¥500")
        assertEquals(listOf("支付成功", "已收款"), words)
    }
}
