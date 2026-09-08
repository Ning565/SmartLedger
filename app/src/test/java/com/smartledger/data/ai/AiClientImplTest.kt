package com.smartledger.data.ai

import com.smartledger.data.ai.model.AiConfig
import com.smartledger.data.ai.model.AiError
import com.smartledger.data.ai.model.AiMessage
import com.smartledger.data.ai.model.AiProviderPreset
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/**
 * AiClient 测试。
 *
 * 覆盖方案文档要求的 9 类场景：
 * 200 普通 JSON / 200 Streaming / 401 / 404 / 429 / 500 / 超时 / 非法 JSON / Streaming 中断，
 * 外加推理模型空正文、取消传播、请求体转义、Key 不泄漏等实测发现的坑。
 */
class AiClientImplTest {

    private val config = AiConfig(
        preset = AiProviderPreset.DEEPSEEK,
        baseUrl = "https://api.deepseek.com/v1",
        apiKey = "sk-test-0123456789abcdef",
        model = "deepseek-chat"
    )

    private val messages = listOf(
        AiMessage.system("你是助手"),
        AiMessage.user("你好")
    )

    private fun client(engine: FakeHttpEngine) = AiClientImpl(engine)

    // ═══════════════════════════════════════════════════
    // 请求构造
    // ═══════════════════════════════════════════════════

    @Test
    fun `请求地址由 baseUrl 正确拼出`() = runBlocking {
        val engine = FakeHttpEngine(postBody = okBody("hi"))
        client(engine).chat(config, messages)
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            engine.lastPost?.url
        )
    }

    @Test
    fun `baseUrl 未带 v1 时自动补齐`() = runBlocking {
        val engine = FakeHttpEngine(postBody = okBody("hi"))
        client(engine).chat(config.copy(baseUrl = "https://api.deepseek.com"), messages)
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            engine.lastPost?.url
        )
    }

    @Test
    fun `Authorization 头携带 Bearer Key`() = runBlocking {
        val engine = FakeHttpEngine(postBody = okBody("hi"))
        client(engine).chat(config, messages)
        assertEquals(
            "Bearer sk-test-0123456789abcdef",
            engine.lastPost?.headers?.get("Authorization")
        )
    }

    @Test
    fun `请求体是合法 JSON 且字段完整`() = runBlocking {
        val engine = FakeHttpEngine(postBody = okBody("hi"))
        client(engine).chat(config, messages, temperature = 0.3, maxTokens = 256)

        val body = JSONObject(engine.lastPost!!.body)
        assertEquals("deepseek-chat", body.getString("model"))
        assertEquals(0.3, body.getDouble("temperature"), 1e-9)
        assertEquals(256, body.getInt("max_tokens"))
        assertFalse("chat() 不应开启流式", body.getBoolean("stream"))

        val msgs = body.getJSONArray("messages")
        assertEquals(2, msgs.length())
        assertEquals("system", msgs.getJSONObject(0).getString("role"))
        assertEquals("你是助手", msgs.getJSONObject(0).getString("content"))
        assertEquals("user", msgs.getJSONObject(1).getString("role"))
    }

    @Test
    fun `streamChat 请求体开启 stream`() = runBlocking {
        val engine = FakeHttpEngine(streamLines = listOf("data: [DONE]"))
        client(engine).streamChat(config, messages).toList()
        assertTrue(JSONObject(engine.lastStream!!.body).getBoolean("stream"))
    }

    @Test
    fun `用户输入含引号换行反斜杠时请求体仍是合法 JSON`() = runBlocking {
        // 这是「用字符串模板拼 JSON」必然会踩的坑：
        // 商户名/备注里一个引号就能让请求体变成非法 JSON，
        // 且只在特定输入下复现，极难排查。
        val evil = """他说："金额是 32\n元"\ 备注 ${'$'}{x} 结束"""
        val engine = FakeHttpEngine(postBody = okBody("hi"))
        client(engine).chat(config, listOf(AiMessage.user(evil)))

        val raw = engine.lastPost!!.body
        val parsed = JSONObject(raw)   // 非法 JSON 会在这里抛异常
        assertEquals(evil, parsed.getJSONArray("messages").getJSONObject(0).getString("content"))
    }

    @Test
    fun `temperature 与 maxTokens 越界被钳制`() = runBlocking {
        val engine = FakeHttpEngine(postBody = okBody("hi"))
        client(engine).chat(config, messages, temperature = 9.9, maxTokens = 999_999)
        val body = JSONObject(engine.lastPost!!.body)
        assertEquals(2.0, body.getDouble("temperature"), 1e-9)
        assertEquals(8192, body.getInt("max_tokens"))

        client(engine).chat(config, messages, temperature = -3.0, maxTokens = 0)
        val body2 = JSONObject(engine.lastPost!!.body)
        assertEquals(0.0, body2.getDouble("temperature"), 1e-9)
        assertEquals(1, body2.getInt("max_tokens"))
    }

    // ═══════════════════════════════════════════════════
    // 本地前置拦截：不发无谓请求
    // ═══════════════════════════════════════════════════

    @Test
    fun `未配置时直接失败且不发起请求`() = runBlocking {
        val engine = FakeHttpEngine()
        val result = client(engine).chat(AiConfig(), messages)
        assertEquals(AiError.NotConfigured, (result as AiResult.Failure).error)
        assertTrue("不该发出任何请求", engine.postRequests.isEmpty())
    }

    @Test
    fun `缺 API Key 视为未配置`() = runBlocking {
        val engine = FakeHttpEngine()
        val result = client(engine).chat(config.copy(apiKey = "  "), messages)
        assertEquals(AiError.NotConfigured, (result as AiResult.Failure).error)
        assertTrue(engine.postRequests.isEmpty())
    }

    @Test
    fun `明文 http 被拦下且不发起请求`() = runBlocking {
        val engine = FakeHttpEngine()
        val result = client(engine).chat(
            config.copy(baseUrl = "http://api.deepseek.com/v1"), messages
        )
        assertEquals(AiError.Cleartext, (result as AiResult.Failure).error)
        assertTrue(engine.postRequests.isEmpty())
    }

    @Test
    fun `非法 baseUrl 被拦下`() = runBlocking {
        val engine = FakeHttpEngine()
        val result = client(engine).chat(config.copy(baseUrl = "https://localhost"), messages)
        assertTrue(result is AiResult.Failure)
        assertTrue(engine.postRequests.isEmpty())
    }

    @Test
    fun `流式请求同样做前置拦截`() = runBlocking {
        val engine = FakeHttpEngine()
        val events = client(engine).streamChat(AiConfig(), messages).toList()
        assertEquals(1, events.size)
        val failed = events.first() as AiStreamEvent.Failed
        assertEquals(AiError.NotConfigured, failed.error)
        assertEquals("", failed.partialText)
        assertTrue(engine.streamRequests.isEmpty())
    }

    // ═══════════════════════════════════════════════════
    // chat() 正常路径
    // ═══════════════════════════════════════════════════

    @Test
    fun `200 正常 JSON 返回正文`() = runBlocking {
        val engine = FakeHttpEngine(postBody = okBody("本月餐饮占比偏高"))
        val result = client(engine).chat(config, messages)
        val ok = result as AiResult.Success
        assertEquals("本月餐饮占比偏高", ok.text)
        assertFalse(ok.fromReasoningFallback)
    }

    @Test
    fun `真实 DeepSeek 响应体可被正确解析`() = runBlocking {
        // 直接取自实测抓包
        val real = """{"id":"d2dd390a-53eb-4b2d-a00a-f59caa1f5d57","object":"chat.completion",
            |"created":1788780479,"model":"deepseek-v4-flash",
            |"choices":[{"index":0,"message":{"role":"assistant","content":"OK"},
            |"logprobs":null,"finish_reason":"stop"}],
            |"usage":{"prompt_tokens":9,"completion_tokens":1,"total_tokens":10,
            |"prompt_tokens_details":{"cached_tokens":0},"prompt_cache_hit_tokens":0}}""".trimMargin()
        val engine = FakeHttpEngine(postBody = real)
        val ok = client(engine).chat(config, messages) as AiResult.Success
        assertEquals("OK", ok.text)
        assertFalse(ok.fromReasoningFallback)
    }

    @Test
    fun `推理模型正文为空时回退 reasoning_content 并标记`() = runBlocking {
        // 实测：deepseek-v4-flash 的 content 是空串，正文全在 reasoning_content
        val body = """{"model":"deepseek-v4-flash","choices":[{"index":0,
            |"message":{"role":"assistant","content":"","reasoning_content":"思考过程……"},
            |"finish_reason":"length"}]}""".trimMargin()
        val engine = FakeHttpEngine(postBody = body)
        val ok = client(engine).chat(config, messages) as AiResult.Success
        assertEquals("思考过程……", ok.text)
        assertTrue("必须标记兜底来源，供 UI 提示换模型", ok.fromReasoningFallback)
    }

    @Test
    fun `推理模型正文与推理都为空时报 EmptyReasoningOutput`() = runBlocking {
        // 实测：max_tokens 太小时 v4 系列会出现 content="" 且 reasoning 被截断为空
        val body = """{"object":"chat.completion","choices":[{"index":0,
            |"message":{"role":"assistant","content":"","reasoning_content":""},
            |"finish_reason":"length"}]}""".trimMargin()
        val engine = FakeHttpEngine(postBody = body)
        val failure = client(engine).chat(config, messages) as AiResult.Failure
        assertEquals(AiError.EmptyReasoningOutput, failure.error)
    }

    @Test
    fun `200 但结构不兼容时报 Incompatible`() = runBlocking {
        // 用户把 baseUrl 填成了非 OpenAI 兼容端点时的典型返回
        val engine = FakeHttpEngine(postBody = """{"result":"hello"}""")
        val failure = client(engine).chat(config, messages) as AiResult.Failure
        assertEquals(AiError.Incompatible, failure.error)
    }

    @Test
    fun `200 但返回 HTML 时报 Incompatible`() = runBlocking {
        val engine = FakeHttpEngine(postBody = "<html><body>200 OK</body></html>")
        val failure = client(engine).chat(config, messages) as AiResult.Failure
        assertEquals(AiError.Incompatible, failure.error)
    }

    // ═══════════════════════════════════════════════════
    // chat() 错误分类
    // ═══════════════════════════════════════════════════

    @Test
    fun `401 与 403 映射为 InvalidApiKey`() = runBlocking {
        listOf(401, 403).forEach { code ->
            val engine = FakeHttpEngine(
                postCode = code,
                postBody = """{"error":{"message":"Authentication Fails","type":"authentication_error"}}"""
            )
            val failure = client(engine).chat(config, messages) as AiResult.Failure
            assertEquals("HTTP $code", AiError.InvalidApiKey, failure.error)
        }
    }

    @Test
    fun `404 映射为 ModelNotFound`() = runBlocking {
        val engine = FakeHttpEngine(
            postCode = 404,
            postBody = """{"error":{"message":"Model Not Exist"}}"""
        )
        val failure = client(engine).chat(config, messages) as AiResult.Failure
        assertEquals(AiError.ModelNotFound, failure.error)
    }

    @Test
    fun `429 映射为 RateLimited`() = runBlocking {
        val engine = FakeHttpEngine(
            postCode = 429,
            postBody = """{"error":{"message":"Rate limit reached"}}"""
        )
        val failure = client(engine).chat(config, messages) as AiResult.Failure
        assertEquals(AiError.RateLimited, failure.error)
    }

    @Test
    fun `5xx 映射为 ServerError`() = runBlocking {
        listOf(500, 502, 503, 599).forEach { code ->
            val engine = FakeHttpEngine(postCode = code, postBody = "Internal Error")
            val failure = client(engine).chat(config, messages) as AiResult.Failure
            assertEquals("HTTP $code", AiError.ServerError, failure.error)
        }
    }

    @Test
    fun `400 映射为 Unknown 且带上服务端错误信息`() = runBlocking {
        val engine = FakeHttpEngine(
            postCode = 400,
            postBody = """{"error":{"message":"max_tokens exceeds limit"}}"""
        )
        val failure = client(engine).chat(config, messages) as AiResult.Failure
        val unknown = failure.error as AiError.Unknown
        assertTrue(unknown.detail.contains("max_tokens exceeds limit"))
    }

    @Test
    fun `错误信息里若含 Key 必须被脱敏`() = runBlocking {
        // 部分网关会在错误里回显 Authorization，这是最危险的泄漏路径
        val engine = FakeHttpEngine(
            postCode = 400,
            postBody = """{"error":{"message":"invalid key sk-test-0123456789abcdef provided"}}"""
        )
        val failure = client(engine).chat(config, messages) as AiResult.Failure
        val unknown = failure.error as AiError.Unknown
        assertFalse("错误文案不得含 Key 明文: ${unknown.detail}", unknown.detail.contains("sk-test-0123456789abcdef"))
        assertTrue(unknown.detail.contains("REDACTED"))
        // userMessage 会直接展示给用户，同样不能泄漏
        assertFalse(failure.error.userMessage.contains("sk-test-0123456789abcdef"))
    }

    @Test
    fun `超时异常映射为 Timeout`() = runBlocking {
        val engine = FakeHttpEngine(throwOnPost = SocketTimeoutException("Read timed out"))
        val failure = client(engine).chat(config, messages) as AiResult.Failure
        assertEquals(AiError.Timeout, failure.error)
    }

    @Test
    fun `DNS 失败映射为 Network`() = runBlocking {
        val engine = FakeHttpEngine(
            throwOnPost = UnknownHostException("Unable to resolve host \"api.deepseek.com\"")
        )
        val failure = client(engine).chat(config, messages) as AiResult.Failure
        assertEquals(AiError.Network, failure.error)
    }

    @Test
    fun `连接被拒映射为 Network`() = runBlocking {
        val engine = FakeHttpEngine(throwOnPost = ConnectException("Connection refused"))
        val failure = client(engine).chat(config, messages) as AiResult.Failure
        assertEquals(AiError.Network, failure.error)
    }

    @Test
    fun `TLS 失败映射为 Network`() = runBlocking {
        val engine = FakeHttpEngine(throwOnPost = SSLHandshakeException("Handshake failed"))
        val failure = client(engine).chat(config, messages) as AiResult.Failure
        assertEquals(AiError.Network, failure.error)
    }

    @Test
    fun `系统明文拦截异常映射为 Cleartext`() = runBlocking {
        val engine = FakeHttpEngine(
            throwOnPost = IllegalArgumentException(
                "CLEARTEXT communication to api.example.com not permitted by network security policy"
            )
        )
        // 绕过 preflight：baseUrl 写 https 但底层被系统策略拦成 CLEARTEXT
        val failure = client(engine).chat(config, messages) as AiResult.Failure
        assertEquals(AiError.Cleartext, failure.error)
    }

    @Test
    fun `未知异常的 message 被脱敏并截断`() = runBlocking {
        val engine = FakeHttpEngine(
            throwOnPost = RuntimeException("boom sk-test-0123456789abcdef " + "x".repeat(500))
        )
        val failure = client(engine).chat(config, messages) as AiResult.Failure
        val unknown = failure.error as AiError.Unknown
        assertFalse(unknown.detail.contains("sk-test-0123456789abcdef"))
        assertTrue(unknown.detail.length <= 121)   // 120 + 省略号
    }

    @Test
    fun `异常 message 为 null 时用类名兜底而不是崩溃`() = runBlocking {
        val engine = FakeHttpEngine(throwOnPost = RuntimeException())
        val failure = client(engine).chat(config, messages) as AiResult.Failure
        assertTrue(failure.error is AiError.Unknown)
        assertTrue((failure.error as AiError.Unknown).detail.contains("RuntimeException"))
    }

    @Test
    fun `请求体中不含 API Key`() = runBlocking {
        val engine = FakeHttpEngine(postBody = okBody("hi"))
        client(engine).chat(config, messages)
        assertFalse(engine.keyLeakedIntoBody(config.apiKey))
    }

    // ═══════════════════════════════════════════════════
    // testConnection()
    // ═══════════════════════════════════════════════════

    @Test
    fun `测试连接使用极小 max_tokens 以节省额度`() = runBlocking {
        val engine = FakeHttpEngine(postBody = okBody("OK"))
        client(engine).testConnection(config)
        val body = JSONObject(engine.lastPost!!.body)
        assertEquals(8, body.getInt("max_tokens"))
        assertEquals(0.0, body.getDouble("temperature"), 1e-9)
        assertFalse(body.getBoolean("stream"))
    }

    @Test
    fun `测试连接成功返回模型回显`() = runBlocking {
        val engine = FakeHttpEngine(postBody = okBody("OK"))
        val ok = client(engine).testConnection(config) as AiResult.Success
        assertEquals("OK", ok.text)
    }

    @Test
    fun `测试连接遇到推理模型空正文仍算连通成功`() = runBlocking {
        // 实测 deepseek-v4-flash + max_tokens=8 → content 与 reasoning 都为空，
        // 但 HTTP 200 已证明地址/Key/模型三者可用，不该报成失败
        val body = """{"object":"chat.completion","choices":[{"index":0,
            |"message":{"role":"assistant","content":"","reasoning_content":"We need"},
            |"finish_reason":"length"}]}""".trimMargin()
        val engine = FakeHttpEngine(postBody = body)
        val result = client(engine).testConnection(config)
        assertTrue("应判定为连通成功，实际=$result", result is AiResult.Success)
    }

    @Test
    fun `测试连接失败时给出对应错误`() = runBlocking {
        val engine = FakeHttpEngine(postCode = 401, postBody = """{"error":{"message":"bad key"}}""")
        val failure = client(engine).testConnection(config) as AiResult.Failure
        assertEquals(AiError.InvalidApiKey, failure.error)
    }

    // ═══════════════════════════════════════════════════
    // streamChat()
    // ═══════════════════════════════════════════════════

    @Test
    fun `流式正常：逐包 Delta 后 Completed`() = runBlocking {
        val engine = FakeHttpEngine(
            streamLines = listOf(
                """data: {"choices":[{"delta":{"role":"assistant","content":""}}]}""",
                """data: {"choices":[{"delta":{"content":"本月"}}]}""",
                """data: {"choices":[{"delta":{"content":"餐饮"}}]}""",
                """data: {"choices":[{"delta":{"content":"占比 42%"}}]}""",
                """data: {"choices":[{"delta":{"content":""},"finish_reason":"stop"}]}""",
                "data: [DONE]"
            )
        )
        val events = client(engine).streamChat(config, messages).toList()

        val deltas = events.filterIsInstance<AiStreamEvent.Delta>().map { it.text }
        assertEquals(listOf("本月", "餐饮", "占比 42%"), deltas)
        assertTrue(events.last() is AiStreamEvent.Completed)
        assertFalse((events.last() as AiStreamEvent.Completed).fromReasoningFallback)
        // 空 content 包不该产生 Delta，否则 UI 会做大量无意义重组
        assertEquals(3, deltas.size)
    }

    @Test
    fun `收到 DONE 后立即停止读取`() = runBlocking {
        val engine = FakeHttpEngine(
            streamLines = listOf(
                """data: {"choices":[{"delta":{"content":"甲"}}]}""",
                "data: [DONE]",
                """data: {"choices":[{"delta":{"content":"不该出现"}}]}"""
            )
        )
        val events = client(engine).streamChat(config, messages).toList()
        assertEquals(2, engine.linesDelivered)   // 第三行没被读
        val text = events.filterIsInstance<AiStreamEvent.Delta>().joinToString("") { it.text }
        assertEquals("甲", text)
        assertFalse(text.contains("不该出现"))
    }

    @Test
    fun `流式遇到推理模型：正文为空时用推理内容兜底`() = runBlocking {
        val engine = FakeHttpEngine(
            streamLines = listOf(
                """data: {"choices":[{"delta":{"role":"assistant","content":"","reasoning_content":"先算"}}]}""",
                """data: {"choices":[{"delta":{"reasoning_content":"一下比例"}}]}""",
                """data: {"choices":[{"delta":{"content":""},"finish_reason":"length"}]}""",
                "data: [DONE]"
            )
        )
        val events = client(engine).streamChat(config, messages).toList()
        val deltas = events.filterIsInstance<AiStreamEvent.Delta>().map { it.text }
        // 推理过程在流式途中不应作为正文推给用户
        assertEquals(listOf("先算一下比例"), deltas)
        val completed = events.last() as AiStreamEvent.Completed
        assertTrue(completed.fromReasoningFallback)
    }

    @Test
    fun `流式正文与推理都为空时报 EmptyReasoningOutput`() = runBlocking {
        val engine = FakeHttpEngine(
            streamLines = listOf(
                """data: {"choices":[{"delta":{"role":"assistant","content":""}}]}""",
                """data: {"choices":[{"delta":{"content":""},"finish_reason":"length"}]}""",
                "data: [DONE]"
            )
        )
        val events = client(engine).streamChat(config, messages).toList()
        val failed = events.last() as AiStreamEvent.Failed
        assertEquals(AiError.EmptyReasoningOutput, failed.error)
        assertEquals("", failed.partialText)
    }

    @Test
    fun `服务端不发 DONE 而是直接 EOF 时仍算正常完成`() = runBlocking {
        // 部分兼容层不发 [DONE]，直接关流
        val engine = FakeHttpEngine(
            streamLines = listOf(
                """data: {"choices":[{"delta":{"content":"内容"}}]}"""
            )
        )
        val events = client(engine).streamChat(config, messages).toList()
        assertTrue(events.last() is AiStreamEvent.Completed)
        assertEquals("内容", events.filterIsInstance<AiStreamEvent.Delta>().joinToString("") { it.text })
    }

    @Test
    fun `流式 401 映射为 Failed 且不带正文`() = runBlocking {
        val engine = FakeHttpEngine(
            streamCode = 401,
            streamErrorBody = """{"error":{"message":"Authentication Fails"}}"""
        )
        val events = client(engine).streamChat(config, messages).toList()
        assertEquals(1, events.size)
        val failed = events.first() as AiStreamEvent.Failed
        assertEquals(AiError.InvalidApiKey, failed.error)
        assertEquals("", failed.partialText)
    }

    @Test
    fun `流式 429 映射为 RateLimited`() = runBlocking {
        val engine = FakeHttpEngine(streamCode = 429, streamErrorBody = """{"error":{"message":"limit"}}""")
        val failed = client(engine).streamChat(config, messages).toList().first() as AiStreamEvent.Failed
        assertEquals(AiError.RateLimited, failed.error)
    }

    @Test
    fun `流中断时保留已生成的部分内容`() = runBlocking {
        // 关键：网络抖动导致流断掉，已经生成的半篇报告不能白扔
        val engine = FakeHttpEngine(
            streamLines = listOf(
                """data: {"choices":[{"delta":{"content":"第一段"}}]}""",
                """data: {"choices":[{"delta":{"content":"第二段"}}]}""",
                """data: {"choices":[{"delta":{"content":"第三段"}}]}"""
            ),
            streamBreakAfterLines = 2      // 推完 2 行后连接被重置
        )
        val events = client(engine).streamChat(config, messages).toList()
        val failed = events.last() as AiStreamEvent.Failed
        assertEquals(AiError.Network, failed.error)
        assertEquals("第一段第二段", failed.partialText)
        // 已经推给 UI 的 Delta 不应被回收
        assertEquals(2, events.filterIsInstance<AiStreamEvent.Delta>().size)
    }

    @Test
    fun `流式连接建立即失败时映射为 Network`() = runBlocking {
        val engine = FakeHttpEngine(throwOnStream = UnknownHostException("no host"))
        val failed = client(engine).streamChat(config, messages).toList().first() as AiStreamEvent.Failed
        assertEquals(AiError.Network, failed.error)
    }

    @Test
    fun `流式读超时映射为 Timeout`() = runBlocking {
        val engine = FakeHttpEngine(throwOnStream = SocketTimeoutException("timeout"))
        val failed = client(engine).streamChat(config, messages).toList().first() as AiStreamEvent.Failed
        assertEquals(AiError.Timeout, failed.error)
    }

    @Test
    fun `坏包不中断整个流`() = runBlocking {
        val engine = FakeHttpEngine(
            streamLines = listOf(
                """data: {"choices":[{"delta":{"content":"甲"}}]}""",
                "data: {broken",
                ": ping",
                "",
                "event: message",
                """data: {"choices":[{"delta":{"content":"乙"}}]}""",
                "data: [DONE]"
            )
        )
        val events = client(engine).streamChat(config, messages).toList()
        val text = events.filterIsInstance<AiStreamEvent.Delta>().joinToString("") { it.text }
        assertEquals("甲乙", text)
        assertTrue(events.last() is AiStreamEvent.Completed)
    }

    @Test
    fun `用户取消时按取消传播而不是转成 Failed 事件`() = runBlocking {
        // 「停止生成」不是错误。若把 CancellationException 吞成 Failed，
        // 上层就会把用户主动停止记成一次失败，还会丢掉已生成内容。
        //
        // 注意：必须有第二行才能触发挂起（hangAfterLines 是「索引 >= N 时挂起」），
        // 只给一行的话流会正常结束，测不到取消路径。
        val engine = FakeHttpEngine(
            streamLines = listOf(
                """data: {"choices":[{"delta":{"content":"甲"}}]}""",
                """data: {"choices":[{"delta":{"content":"乙"}}]}"""
            ),
            streamHangAfterLines = 1     // 推完第 1 行后永久挂起
        )
        val collected = withTimeoutOrNull(1_500) {
            client(engine).streamChat(config, messages).toList()
        }
        assertNull("取消必须向上传播，toList() 不应正常返回", collected)
        assertEquals("只应推送挂起前的那一行", 1, engine.linesDelivered)
    }

    @Test
    fun `非流式路径的通用 IOException 也归为 Network`() = runBlocking {
        // 回归用例：Connection reset by peer 不属于任何 IOException 子类，
        // 曾经落到 Unknown，把英文技术细节直接展示给用户
        val engine = FakeHttpEngine(throwOnPost = java.io.IOException("Connection reset by peer"))
        val failure = client(engine).chat(config, messages) as AiResult.Failure
        assertEquals(AiError.Network, failure.error)
        assertFalse(failure.error.userMessage.contains("Connection reset"))
    }

    @Test
    fun `broken pipe 与 EOF 同样归为 Network`() = runBlocking {
        listOf(
            java.io.IOException("Broken pipe"),
            java.io.EOFException("unexpected end of stream"),
            java.io.IOException("stream is closed")
        ).forEach { ex ->
            val engine = FakeHttpEngine(throwOnPost = ex)
            val failure = client(engine).chat(config, messages) as AiResult.Failure
            assertEquals("${ex.javaClass.simpleName}: ${ex.message}", AiError.Network, failure.error)
        }
    }

    @Test
    fun `流式请求体与头和非流式一致`() = runBlocking {
        val engine = FakeHttpEngine(streamLines = listOf("data: [DONE]"))
        client(engine).streamChat(config, messages, temperature = 0.7, maxTokens = 2048).toList()
        val req = engine.lastStream!!
        assertEquals("Bearer sk-test-0123456789abcdef", req.headers["Authorization"])
        assertEquals("text/event-stream 由 engine 负责设置，这里只校验业务头", "SmartLedger-Android", req.headers["User-Agent"])
        val body = JSONObject(req.body)
        assertEquals(0.7, body.getDouble("temperature"), 1e-9)
        assertEquals(2048, body.getInt("max_tokens"))
        assertFalse(engine.keyLeakedIntoBody(config.apiKey))
    }

    // ═══ 辅助 ═══

    private fun okBody(content: String): String =
        JSONObject().apply {
            put("object", "chat.completion")
            put("choices", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("index", 0)
                    put("message", JSONObject().apply {
                        put("role", "assistant")
                        put("content", content)
                    })
                    put("finish_reason", "stop")
                })
            })
        }.toString()
}
