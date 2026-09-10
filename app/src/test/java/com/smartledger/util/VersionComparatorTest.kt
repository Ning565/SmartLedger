package com.smartledger.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 版本比较（debug.7）。
 *
 * 这组用例的每一条都对应一个真实踩过的坑：
 * - debug.3 的「永远提示有更新」误报
 * - 修误报时引入的「debug 包之间永远不提示更新」
 */
class VersionComparatorTest {

    private fun newer(local: String, remote: String) = isNewerVersion(local, remote)

    // ═══ 解析 ═══

    @Test
    fun `预发布后缀与核心段分开解析 而不是当成第四段数字`() {
        val k = parseVersion("v1.1.0-debug.6")
        assertEquals(listOf(1, 1, 0), k.core)
        assertTrue(k.isPrerelease)
        assertEquals(6, k.prereleaseNo)
    }

    @Test
    fun `正式版没有预发布后缀`() {
        val k = parseVersion("1.1.0")
        assertEquals(listOf(1, 1, 0), k.core)
        assertFalse(k.isPrerelease)
        assertEquals(null, k.prereleaseNo)
    }

    @Test
    fun `容忍 v 前缀 空白与构建号`() {
        assertEquals(listOf(1, 2, 0), parseVersion("  V1.2.0+build.7 ").core)
    }

    // ═══ debug 包之间（原先永远判为不更新） ═══

    @Test
    fun `debug_5 到 debug_6 判为有更新`() {
        assertTrue(newer("1.1.0-debug.5", "v1.1.0-debug.6"))
    }

    @Test
    fun `同号不更新`() {
        assertFalse(newer("1.1.0-debug.6", "v1.1.0-debug.6"))
    }

    @Test
    fun `线上更旧不更新 不允许降级`() {
        assertFalse(newer("1.1.0-debug.6", "v1.1.0-debug.5"))
    }

    @Test
    fun `debug_10 比 debug_9 新 不是字符串比较`() {
        assertTrue(newer("1.1.0-debug.9", "v1.1.0-debug.10"))
    }

    // ═══ 核心段（原先被 debug 后缀干扰） ═══

    @Test
    fun `核心段更高就是有更新 哪怕线上是预发布`() {
        assertTrue(newer("1.1.0-debug.6", "v1.1.1-debug.1"))
        assertTrue(newer("1.1.0-debug.6", "v1.2.0"))
    }

    @Test
    fun `核心段更低不更新`() {
        assertFalse(newer("1.2.0", "v1.1.9"))
        assertFalse(newer("1.2.0", "v1.1.9-debug.3"))
    }

    // ═══ 预发布与正式的关系（debug.3 误报的正解） ═══

    @Test
    fun `同核心下 debug_3 不比正式版 1_1_0 新 —— debug_3 误报的根因`() {
        // 旧逻辑把 debug.3 拆成 [1,1,0,3]，比 [1,1,0] 多一段 → 永远判为更新
        assertFalse(newer("1.1.0", "v1.1.0-debug.3"))
    }

    @Test
    fun `同核心下从 debug 包升级到正式版要提示`() {
        assertTrue(newer("1.1.0-debug.6", "v1.1.0"))
    }

    @Test
    fun `两个正式版相同时不更新`() {
        assertFalse(newer("1.1.0", "v1.1.0"))
    }

    @Test
    fun `段数不同按 0 补齐`() {
        assertTrue(newer("1.1", "v1.1.1"))
        assertFalse(newer("1.1.0", "v1.1"))
    }
}
