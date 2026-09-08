package com.smartledger.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SSE 行解析测试。
 *
 * 用例里的 payload **直接取自 DeepSeek 真实抓包**（2026-09 实测），
 * 不是凭空构造的理想数据，因此能覆盖真实服务的边界形态。
 */
class SseParserTest {

    // ═══ 真实抓包：非推理模型 deepseek-chat ═══

    @Test
    fun `真实首包 role+空content 被忽略`() {
        val line = "data: {\"id\":\"f5cf8794\",\"object\":\"chat.completion.chunk\"," +
                "\"created\":1788780507,\"model\":\"deepseek-v4-flash\"," +
                "\"system_fingerprint\":\"a26a7955944dc5c60445bff77fac9c8e\"," +
                "\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"\"}," +
                "\"logprobs\":null,\"finish_reason\":null}]}"
        assertEquals(SseEvent.Ignore, SseParser.parseLine(line))
    }

    @Test
    fun `真实正文包被解析出中文增量`() {
        val line = "data: {\"id\":\"f5cf8794\",\"object\":\"chat.completion.chunk\"," +
                "\"model\":\"deepseek-v4-flash\"," +
                "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"蓝色\"}," +
                "\"logprobs\":null,\"finish_reason\":null}]}"
        assertEquals(SseEvent.Content("蓝色"), SseParser.parseLine(line))
    }

    @Test
    fun `真实末包 finish_reason=stop 且空content 被忽略`() {
        val line = "data: {\"id\":\"f5cf8794\",\"choices\":[{\"index\":0," +
                "\"delta\":{\"content\":\"\"},\"logprobs\":null,\"finish_reason\":\"stop\"}]," +
                "\"usage\":{\"prompt_tokens\":14,\"completion_tokens\":1,\"total_tokens\":15}}"
        assertEquals(SseEvent.Ignore, SseParser.parseLine(line))
    }

    @Test
    fun `真实 DONE 哨兵`() {
        assertEquals(SseEvent.Done, SseParser.parseLine("data: [DONE]"))
    }

    // ═══ 协议容错 ═══

    @Test
    fun `data 冒号后无空格也能解析`() {
        val line = "data:{\"choices\":[{\"delta\":{\"content\":\"甲\"}}]}"
        assertEquals(SseEvent.Content("甲"), SseParser.parseLine(line))
    }

    @Test
    fun `DONE 无空格且大小写不同也能识别`() {
        assertEquals(SseEvent.Done, SseParser.parseLine("data:[done]"))
        assertEquals(SseEvent.Done, SseParser.parseLine("data:[DONE]"))
    }

    @Test
    fun `空行是事件分隔符不是内容`() {
        assertEquals(SseEvent.Ignore, SseParser.parseLine(""))
        assertEquals(SseEvent.Ignore, SseParser.parseLine("   "))
        assertEquals(SseEvent.Ignore, SseParser.parseLine("\t"))
    }

    @Test
    fun `null 行安全返回 Ignore`() {
        assertEquals(SseEvent.Ignore, SseParser.parseLine(null))
    }

    @Test
    fun `SSE 注释与心跳行被忽略`() {
        assertEquals(SseEvent.Ignore, SseParser.parseLine(": ping"))
        assertEquals(SseEvent.Ignore, SseParser.parseLine(":"))
        assertEquals(SseEvent.Ignore, SseParser.parseLine(":keep-alive"))
    }

    @Test
    fun `event id retry 字段行被忽略`() {
        assertEquals(SseEvent.Ignore, SseParser.parseLine("event: message"))
        assertEquals(SseEvent.Ignore, SseParser.parseLine("id: 12345"))
        assertEquals(SseEvent.Ignore, SseParser.parseLine("retry: 3000"))
    }

    @Test
    fun `行尾 CR 被清理`() {
        assertEquals(
            SseEvent.Content("乙"),
            SseParser.parseLine("data: {\"choices\":[{\"delta\":{\"content\":\"乙\"}}]}\r")
        )
        assertEquals(SseEvent.Done, SseParser.parseLine("data: [DONE]\r"))
    }

    // ═══ 坏包不能中断流 ═══

    @Test
    fun `非法 JSON 返回 Ignore 而不是抛异常`() {
        assertEquals(SseEvent.Ignore, SseParser.parseLine("data: {broken json"))
        assertEquals(SseEvent.Ignore, SseParser.parseLine("data: not-json-at-all"))
        assertEquals(SseEvent.Ignore, SseParser.parseLine("data: <html>502</html>"))
    }

    @Test
    fun `结构缺失时安全降级`() {
        assertEquals(SseEvent.Ignore, SseParser.parseLine("data: {}"))
        assertEquals(SseEvent.Ignore, SseParser.parseLine("data: {\"choices\":[]}"))
        assertEquals(SseEvent.Ignore, SseParser.parseLine("data: {\"choices\":[{}]}"))
        assertEquals(SseEvent.Ignore, SseParser.parseLine("data: {\"choices\":[{\"delta\":{}}]}"))
        assertEquals(
            SseEvent.Ignore,
            SseParser.parseLine("data: {\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}")
        )
    }

    // ═══ 推理模型（实测 deepseek-v4-flash 的致命坑）═══

    @Test
    fun `推理过程走 Reasoning 通道不污染正文`() {
        val line = "data: {\"choices\":[{\"index\":0," +
                "\"delta\":{\"role\":\"assistant\",\"content\":\"\"," +
                "\"reasoning_content\":\"We need to reply exactly \\\"OK\\\".\"}}]}"
        val event = SseParser.parseLine(line)
        assertTrue("推理内容必须识别为 Reasoning，实际=$event", event is SseEvent.Reasoning)
        assertEquals("We need to reply exactly \"OK\".", (event as SseEvent.Reasoning).text)
    }

    @Test
    fun `同一包同时带 content 与 reasoning 时优先取 content`() {
        val line = "data: {\"choices\":[{\"delta\":{" +
                "\"content\":\"正文\",\"reasoning_content\":\"思考\"}}]}"
        assertEquals(SseEvent.Content("正文"), SseParser.parseLine(line))
    }

    // ═══ 非流式正文提取 ═══

    @Test
    fun `非流式响应正常提取 content`() {
        val body = """{"id":"d2dd390a","object":"chat.completion","model":"deepseek-v4-flash",
            |"choices":[{"index":0,"message":{"role":"assistant","content":"OK"},
            |"logprobs":null,"finish_reason":"stop"}],
            |"usage":{"prompt_tokens":9,"completion_tokens":1,"total_tokens":10}}""".trimMargin()
        val (text, fromReasoning) = SseParser.extractMessageContent(body)
        assertEquals("OK", text)
        assertFalse(fromReasoning)
    }

    @Test
    fun `推理模型 content 为空时回退 reasoning_content 并标记来源`() {
        // 这是实测抓到的真实形态：content 是空串，正文全在 reasoning_content
        val body = """{"model":"deepseek-v4-flash","choices":[{"index":0,
            |"message":{"role":"assistant","content":"",
            |"reasoning_content":"We need to reply exactly \"OK\"."},
            |"logprobs":null,"finish_reason":"length"}]}""".trimMargin()
        val (text, fromReasoning) = SseParser.extractMessageContent(body)
        assertEquals("We need to reply exactly \"OK\".", text)
        assertTrue("必须标记为 reasoning 兜底，供上层提示用户", fromReasoning)
    }

    @Test
    fun `两者都为空时返回 null`() {
        val body = """{"choices":[{"message":{"role":"assistant","content":""}}]}"""
        val (text, fromReasoning) = SseParser.extractMessageContent(body)
        assertEquals(null, text)
        assertFalse(fromReasoning)
    }

    @Test
    fun `畸形响应体不抛异常`() {
        assertEquals(null, SseParser.extractMessageContent(null).first)
        assertEquals(null, SseParser.extractMessageContent("").first)
        assertEquals(null, SseParser.extractMessageContent("<html>502 Bad Gateway</html>").first)
        assertEquals(null, SseParser.extractMessageContent("{}").first)
    }

    // ═══ 错误体解析 ═══

    @Test
    fun `错误响应结构识别`() {
        assertTrue(SseParser.isChatCompletionShape("""{"choices":[]}"""))
        assertTrue(SseParser.isChatCompletionShape("""{"object":"error"}"""))
        assertTrue(SseParser.isChatCompletionShape("""{"error":{"message":"x"}}"""))
        assertFalse(SseParser.isChatCompletionShape("<html>not found</html>"))
        assertFalse(SseParser.isChatCompletionShape(null))
        assertFalse(SseParser.isChatCompletionShape(""))
    }

    @Test
    fun `错误信息优先取 error message`() {
        val body = """{"error":{"message":"Authentication Fails","type":"authentication_error"}}"""
        assertEquals("Authentication Fails", SseParser.extractErrorMessage(body))
    }

    @Test
    fun `无 error 字段时退到顶层 message`() {
        assertEquals("bad request", SseParser.extractErrorMessage("""{"message":"bad request"}"""))
    }

    @Test
    fun `非 JSON 错误页截断返回原文片段`() {
        val html = "<html>" + "x".repeat(500) + "</html>"
        val out = SseParser.extractErrorMessage(html)
        assertTrue(out != null && out.length <= 160)
    }

    // ═══ 多字节 / 转义 ═══

    @Test
    fun `JSON 转义与 unicode 正确还原`() {
        val line = """data: {"choices":[{"delta":{"content":"他说：\"你好\"\n换行"}}]}"""
        assertEquals(SseEvent.Content("他说：\"你好\"\n换行"), SseParser.parseLine(line))
    }

    @Test
    fun `unicode 转义序列被还原为中文`() {
        val line = """data: {"choices":[{"delta":{"content":"\u84dd\u8272"}}]}"""
        assertEquals(SseEvent.Content("蓝色"), SseParser.parseLine(line))
    }

    @Test
    fun `增量片段可拼接成完整句子`() {
        // 模拟真实流：把逐包 content 拼起来应等于完整回答
        val lines = listOf(
            """data: {"choices":[{"delta":{"role":"assistant","content":""}}]}""",
            """data: {"choices":[{"delta":{"content":"本月"}}]}""",
            """data: {"choices":[{"delta":{"content":"餐饮"}}]}""",
            """data: {"choices":[{"delta":{"content":"占比 42%"}}]}""",
            """data: {"choices":[{"delta":{"content":""},"finish_reason":"stop"}]}""",
            "data: [DONE]"
        )
        val sb = StringBuilder()
        var done = false
        lines.forEach { l ->
            when (val e = SseParser.parseLine(l)) {
                is SseEvent.Content -> sb.append(e.text)
                is SseEvent.Done -> done = true
                else -> Unit
            }
        }
        assertEquals("本月餐饮占比 42%", sb.toString())
        assertTrue(done)
    }
}
