package com.smartledger.util

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * releases 列表 → 该装哪一个包（debug.7）。
 *
 * 背景：`/releases/latest` 会排除 prerelease，而本仓库所有 Release 都是
 * prerelease → 该接口实测返回 404，应用内更新完全不可用。改用列表接口后，
 * 「挑哪一条」就成了新的责任点，必须有测试兜住。
 */
class UpdateCheckerReleasePickerTest {

    private fun asset(name: String) = JSONObject()
        .put("name", name)
        .put("browser_download_url", "https://example.test/$name")

    private fun release(
        tag: String,
        apk: String? = null,
        draft: Boolean = false,
        prerelease: Boolean = true
    ) = JSONObject()
        .put("tag_name", tag)
        .put("body", "notes for $tag")
        .put("html_url", "https://example.test/$tag")
        .put("draft", draft)
        .put("prerelease", prerelease)
        .put("assets", JSONArray().apply { apk?.let { put(asset(it)) } })

    private fun list(vararg r: JSONObject) = JSONArray().apply { r.forEach { put(it) } }

    @Test
    fun `按版本号取最大的一条 而不是列表第一条`() {
        // 列表按创建时间排；补发的旧包会让顺序与版本号不一致
        val best = UpdateChecker.pickBestRelease(
            list(
                release("v1.1.0-debug.5", "SmartLedger-v1.1.0-debug.5.apk"),
                release("v1.1.0-debug.6", "SmartLedger-v1.1.0-debug.6.apk"),
                release("v1.1.0-debug.4", "SmartLedger-v1.1.0-debug.4.apk")
            )
        )
        assertEquals("v1.1.0-debug.6", best?.tagName)
        assertEquals(
            "https://example.test/SmartLedger-v1.1.0-debug.6.apk",
            best?.apkUrl
        )
    }

    @Test
    fun `跳过 draft`() {
        val best = UpdateChecker.pickBestRelease(
            list(
                release("v1.1.0-debug.7", "SmartLedger-v1.1.0-debug.7.apk", draft = true),
                release("v1.1.0-debug.6", "SmartLedger-v1.1.0-debug.6.apk")
            )
        )
        assertEquals("v1.1.0-debug.6", best?.tagName)
    }

    @Test
    fun `跳过没有 apk 附件的 release —— 没有附件就没法在应用内装`() {
        val best = UpdateChecker.pickBestRelease(
            list(
                release("v1.1.0-debug.7"),
                release("v1.1.0-debug.6", "SmartLedger-v1.1.0-debug.6.apk")
            )
        )
        assertEquals("v1.1.0-debug.6", best?.tagName)
    }

    @Test
    fun `附件里混着其它文件时取 apk 那一个`() {
        val withExtra = JSONObject()
            .put("tag_name", "v1.1.0-debug.6")
            .put("body", "notes")
            .put("html_url", "https://example.test/tag")
            .put("draft", false)
            .put("assets", JSONArray().apply {
                put(asset("checksums.txt"))
                put(asset("SmartLedger-v1.1.0-debug.6.apk"))
            })
        val best = UpdateChecker.pickBestRelease(list(withExtra))
        assertEquals(
            "https://example.test/SmartLedger-v1.1.0-debug.6.apk",
            best?.apkUrl
        )
    }

    @Test
    fun `正式版比同核心的 debug 包高`() {
        val best = UpdateChecker.pickBestRelease(
            list(
                release("v1.1.0-debug.6", "SmartLedger-v1.1.0-debug.6.apk"),
                release("v1.1.0", "SmartLedger-v1.1.0.apk", prerelease = false)
            )
        )
        assertEquals("v1.1.0", best?.tagName)
    }

    @Test
    fun `一条可用都没有时返回 null 而不是抛异常`() {
        assertNull(UpdateChecker.pickBestRelease(JSONArray()))
        assertNull(UpdateChecker.pickBestRelease(list(release("v1.1.0-debug.6"))))
    }

    /**
     * 端到端语义：已装 debug.5、线上列表里有 debug.6 → 应当提示更新。
     *
     * 这正是改之前**一定不成立**的那条路径（旧逻辑剥掉 `-` 后缀后两边核心相同）。
     */
    @Test
    fun `已装 debug_5 时线上的 debug_6 会触发更新提示`() {
        val best = UpdateChecker.pickBestRelease(
            list(
                release("v1.1.0-debug.6", "SmartLedger-v1.1.0-debug.6.apk"),
                release("v1.1.0-debug.5", "SmartLedger-v1.1.0-debug.5.apk")
            )
        )!!
        assertEquals(true, isNewerVersion("1.1.0-debug.5", best.tagName))
    }

    @Test
    fun `已装 debug_6 时不会重复提示装 debug_6`() {
        val best = UpdateChecker.pickBestRelease(
            list(release("v1.1.0-debug.6", "SmartLedger-v1.1.0-debug.6.apk"))
        )!!
        assertEquals(false, isNewerVersion("1.1.0-debug.6", best.tagName))
    }
}
