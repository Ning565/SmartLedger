package com.smartledger.service.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 多窗口选窗规则（debug.4）。
 *
 * 背景：真机上微信聊天页（活跃窗口）与支付结果页常常并存，
 * 聊天页本身也含「你发起了一笔转账」+「¥0.02」—— 两者都在强词表里。
 * 选错窗口的后果是「一次转出记成两笔」或「扫不到支付页」。
 */
class AccessibilityWindowSelectorTest {

    private fun candidate(
        index: Int,
        hit: Boolean,
        nodes: Int,
        activeRoot: Boolean = false
    ) = WindowCandidate(
        index = index,
        signalHit = hit,
        textNodeCount = nodes,
        isActiveRoot = activeRoot
    )

    @Test
    fun `候选为空返回null`() {
        assertNull(AccessibilityWindowSelector.selectBest(emptyList()))
    }

    @Test
    fun `命中窗口优先于未命中窗口`() {
        // windows 拿不到时只有活动窗口（未命中），此时必须仍能选出它
        val chosen = AccessibilityWindowSelector.selectBest(
            listOf(
                candidate(0, hit = false, nodes = 300, activeRoot = true),
                candidate(1, hit = true, nodes = 40)
            )
        )
        assertEquals(1, chosen)
    }

    @Test
    fun `多个命中窗口取文本节点最少的 - 聊天页与支付页并存`() {
        // 最关键的用例：两个窗口都命中（聊天页含「你发起了一笔转账 + ¥0.02」），
        // 必须选小而聚焦的支付结果页，而不是又大又吵的活动窗口
        val chosen = AccessibilityWindowSelector.selectBest(
            listOf(
                candidate(0, hit = true, nodes = 210, activeRoot = true),
                candidate(1, hit = true, nodes = 9)
            )
        )
        assertEquals(1, chosen)
    }

    @Test
    fun `都没命中时取节点最多的用于诊断样本`() {
        // 未命中的快照不会入库，选大的只是为了让诊断里有东西可看
        val chosen = AccessibilityWindowSelector.selectBest(
            listOf(
                candidate(0, hit = false, nodes = 3, activeRoot = true),
                candidate(1, hit = false, nodes = 190)
            )
        )
        assertEquals(1, chosen)
    }

    @Test
    fun `命中窗口平票时activeRoot优先`() {
        val chosen = AccessibilityWindowSelector.selectBest(
            listOf(
                candidate(0, hit = true, nodes = 12),
                candidate(1, hit = true, nodes = 12, activeRoot = true)
            )
        )
        assertEquals(1, chosen)
    }

    @Test
    fun `未命中且平票时取靠前窗口`() {
        val chosen = AccessibilityWindowSelector.selectBest(
            listOf(
                candidate(0, hit = false, nodes = 5, activeRoot = true),
                candidate(1, hit = false, nodes = 5)
            )
        )
        assertEquals(0, chosen)
    }

    @Test
    fun `只有活动窗口一个候选时选它`() {
        val chosen = AccessibilityWindowSelector.selectBest(
            listOf(candidate(0, hit = false, nodes = 0, activeRoot = true))
        )
        assertEquals(0, chosen)
    }
}
