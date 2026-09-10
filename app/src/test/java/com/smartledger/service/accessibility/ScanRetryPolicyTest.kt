package com.smartledger.service.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * debug.6 的两个纯函数：空树重试判据 + 事件类型标签。
 *
 * 两者都是从真机 dump 里长出来的 —— 前者决定「要不要再扫一次」，
 * 后者决定下一份 dump 能不能区分「STATE 本来就少」与「CONTENT 全被拦」。
 */
class ScanRetryPolicyTest {

    private fun retry(
        textNodeTotal: Int,
        windowCount: Int,
        failed: Boolean = false,
        attempt: Int = 0,
        maxRetries: Int = 2
    ) = shouldRetryEmptyTree(textNodeTotal, windowCount, failed, attempt, maxRetries)

    // ═══ 空树重试 ═══

    @Test
    fun `扫到窗口但一个文本节点都没有 —— 这正是 debug_5 的形状 要重试`() {
        assertTrue(retry(textNodeTotal = 0, windowCount = 1))
    }

    @Test
    fun `抓到内容就不重试`() {
        assertFalse(retry(textNodeTotal = 12, windowCount = 1))
    }

    @Test
    fun `多窗口里有一个抓到内容也不重试`() {
        assertFalse(retry(textNodeTotal = 13, windowCount = 2))
    }

    @Test
    fun `一个窗口都没拿到不重试 —— 那是 rootAvailable等于false 那条路`() {
        // 问题在「拿不到窗口」而不是「窗口是空的」，重扫解决不了
        assertFalse(retry(textNodeTotal = 0, windowCount = 0))
    }

    @Test
    fun `遍历抛异常不重试 —— 再走一遍多半还是抛`() {
        assertFalse(retry(textNodeTotal = 0, windowCount = 1, failed = true))
    }

    @Test
    fun `用满次数就停 不会无限重扫`() {
        assertTrue(retry(0, 1, attempt = 1, maxRetries = 2))
        assertFalse(retry(0, 1, attempt = 2, maxRetries = 2))
    }

    @Test
    fun `默认配置下最多重试两次 即一次逻辑扫描最多三次尝试`() {
        // attempt 0 → 重试, 1 → 重试, 2 → 停 = 3 次尝试 2 次重试
        val fired = (0..5).filter { retry(0, 1, attempt = it) }
        assertEquals(listOf(0, 1), fired)
    }

    // ═══ 事件类型标签 ═══

    @Test
    fun `两种被监听的事件类型各有短标签`() {
        assertEquals("STATE", eventTypeLabel(0x20))
        assertEquals("CONTENT", eventTypeLabel(0x800))
    }

    @Test
    fun `未知类型带出十六进制原值而不是被吞掉`() {
        // window 服务里 xml 只声明了这两种，真出现第三种说明配置和运行时不符
        assertEquals("其他(0x4)", eventTypeLabel(0x4))
    }
}
