package com.smartledger.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Base URL 归一化测试。
 *
 * 这是 AI 配置最高频的出错点：用户填的形态五花八门，
 * 必须都能拼出唯一正确的 endpoint。
 */
class BaseUrlNormalizerTest {

    @Test
    fun `已含 v1 的标准地址原样保留`() {
        assertEquals(
            "https://api.deepseek.com/v1",
            BaseUrlNormalizer.normalize("https://api.deepseek.com/v1")
        )
    }

    @Test
    fun `缺少协议头时自动补 https`() {
        assertEquals(
            "https://api.deepseek.com/v1",
            BaseUrlNormalizer.normalize("api.deepseek.com/v1")
        )
    }

    @Test
    fun `尾斜杠被去掉`() {
        assertEquals(
            "https://api.deepseek.com/v1",
            BaseUrlNormalizer.normalize("https://api.deepseek.com/v1/")
        )
        assertEquals(
            "https://api.deepseek.com/v1",
            BaseUrlNormalizer.normalize("https://api.deepseek.com/v1///")
        )
    }

    @Test
    fun `用户误贴完整 endpoint 时剥掉尾巴`() {
        assertEquals(
            "https://api.deepseek.com/v1",
            BaseUrlNormalizer.normalize("https://api.deepseek.com/v1/chat/completions")
        )
        assertEquals(
            "https://api.deepseek.com/v1",
            BaseUrlNormalizer.normalize("https://api.deepseek.com/v1/chat/completions/")
        )
        assertEquals(
            "https://api.deepseek.com/v1",
            BaseUrlNormalizer.normalize("https://api.deepseek.com/v1/completions")
        )
    }

    @Test
    fun `首尾空白被清理`() {
        assertEquals(
            "https://api.deepseek.com/v1",
            BaseUrlNormalizer.normalize("  https://api.deepseek.com/v1  \n")
        )
    }

    @Test
    fun `空串原样返回`() {
        assertEquals("", BaseUrlNormalizer.normalize(""))
        assertEquals("", BaseUrlNormalizer.normalize("   "))
    }

    @Test
    fun `大小写协议头也能识别`() {
        assertEquals(
            "HTTPS://api.deepseek.com/v1",
            BaseUrlNormalizer.normalize("HTTPS://api.deepseek.com/v1")
        )
    }

    // ═══ chatCompletionsUrl ═══

    @Test
    fun `含 v1 时不重复补版本段`() {
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            BaseUrlNormalizer.chatCompletionsUrl("https://api.deepseek.com/v1")
        )
    }

    @Test
    fun `无版本段时自动补 v1`() {
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            BaseUrlNormalizer.chatCompletionsUrl("https://api.deepseek.com")
        )
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            BaseUrlNormalizer.chatCompletionsUrl("api.deepseek.com")
        )
    }

    @Test
    fun `阿里云百炼 compatible-mode 路径不补 v1`() {
        assertEquals(
            "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
            BaseUrlNormalizer.chatCompletionsUrl(
                "https://dashscope.aliyuncs.com/compatible-mode/v1"
            )
        )
    }

    @Test
    fun `Gemini openai 兼容端点不补 v1`() {
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
            BaseUrlNormalizer.chatCompletionsUrl(
                "https://generativelanguage.googleapis.com/v1beta/openai"
            )
        )
    }

    @Test
    fun `v1beta 这类带后缀的版本段被识别`() {
        assertEquals(
            "https://example.com/v1beta/chat/completions",
            BaseUrlNormalizer.chatCompletionsUrl("https://example.com/v1beta")
        )
    }

    @Test
    fun `自建代理带端口与子路径`() {
        assertEquals(
            "https://llm.internal.corp:8443/gateway/v1/chat/completions",
            BaseUrlNormalizer.chatCompletionsUrl("https://llm.internal.corp:8443/gateway/v1")
        )
    }

    @Test
    fun `空 base 返回空串而不是拼出垃圾地址`() {
        assertEquals("", BaseUrlNormalizer.chatCompletionsUrl(""))
    }

    // ═══ 明文检测 ═══

    @Test
    fun `明文 http 被识别`() {
        assertTrue(BaseUrlNormalizer.isCleartext("http://api.deepseek.com/v1"))
        assertTrue(BaseUrlNormalizer.isCleartext("HTTP://localhost:8080/v1"))
        assertFalse(BaseUrlNormalizer.isCleartext("https://api.deepseek.com/v1"))
        // 没写协议时我们会补 https，因此不算明文
        assertFalse(BaseUrlNormalizer.isCleartext("api.deepseek.com/v1"))
    }

    // ═══ 合法性粗校验 ═══

    @Test
    fun `looksValid 判定`() {
        assertTrue(BaseUrlNormalizer.looksValid("api.deepseek.com"))
        assertTrue(BaseUrlNormalizer.looksValid("https://api.deepseek.com/v1"))
        assertFalse(BaseUrlNormalizer.looksValid(""))
        assertFalse(BaseUrlNormalizer.looksValid("   "))
        // host 里没有点，不是合法域名
        assertFalse(BaseUrlNormalizer.looksValid("https://localhost"))
        assertFalse(BaseUrlNormalizer.looksValid("https://.com/v1"))
        assertFalse(BaseUrlNormalizer.looksValid("https://api./v1"))
    }

    @Test
    fun `幂等：归一化两次结果一致`() {
        val inputs = listOf(
            "api.deepseek.com",
            "https://api.deepseek.com/v1/",
            "https://api.deepseek.com/v1/chat/completions",
            "https://dashscope.aliyuncs.com/compatible-mode/v1"
        )
        inputs.forEach { raw ->
            val once = BaseUrlNormalizer.normalize(raw)
            assertEquals("归一化不幂等: $raw", once, BaseUrlNormalizer.normalize(once))
        }
    }

    @Test
    fun `归一化结果绝不以斜杠结尾且不含 endpoint 尾巴`() {
        val raw = "https://api.deepseek.com/v1/chat/completions//"
        val out = BaseUrlNormalizer.normalize(raw)
        assertFalse(out.endsWith("/"))
        assertFalse(out.contains("chat/completions"))
        assertEquals("https://api.deepseek.com/v1", out)
    }
}
