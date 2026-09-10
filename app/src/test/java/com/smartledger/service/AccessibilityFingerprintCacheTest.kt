package com.smartledger.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AccessibilityFingerprintCacheTest {

    @Before
    fun reset() {
        AccessibilityFingerprintCache.clear()
    }

    @Test
    fun `同一指纹首次出现应处理`() {
        assertTrue(AccessibilityFingerprintCache.shouldProcess("a11y:wechat:abc", now = 1_000L))
    }

    @Test
    fun `TTL内重复出现跳过`() {
        AccessibilityFingerprintCache.shouldProcess("a11y:wechat:abc", now = 1_000L)
        assertFalse(AccessibilityFingerprintCache.shouldProcess("a11y:wechat:abc", now = 1_000L + 14_999L))
    }

    @Test
    fun `TTL过期后允许重新处理`() {
        AccessibilityFingerprintCache.shouldProcess("a11y:wechat:abc", now = 1_000L)
        assertTrue(AccessibilityFingerprintCache.shouldProcess("a11y:wechat:abc", now = 1_000L + 15_001L))
    }

    @Test
    fun `不同指纹互不影响`() {
        AccessibilityFingerprintCache.shouldProcess("a11y:wechat:abc", now = 1_000L)
        assertTrue(AccessibilityFingerprintCache.shouldProcess("a11y:alipay:def", now = 1_000L + 1_000L))
    }

    @Test
    fun `过期条目被清理且不阻塞新键`() {
        // 大量过期键后继续正常工作（removeIf 清理路径）
        for (i in 0 until 100) {
            AccessibilityFingerprintCache.shouldProcess("k$i", now = 0L)
        }
        assertTrue(AccessibilityFingerprintCache.shouldProcess("k-new", now = 100_000L))
    }
}
