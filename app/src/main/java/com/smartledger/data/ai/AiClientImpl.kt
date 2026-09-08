package com.smartledger.data.ai

import com.smartledger.data.ai.http.HttpEngine
import com.smartledger.data.ai.http.HttpErrorStatusException
import com.smartledger.data.ai.http.HttpRequest
import com.smartledger.data.ai.model.AiConfig
import com.smartledger.data.ai.model.AiError
import com.smartledger.data.ai.model.AiMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import org.json.JSONArray
import org.json.JSONObject
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * [AiClient] 的生产实现。
 *
 * 三条设计红线：
 *  1. **不持有 Context**，因此可在纯 JVM 下单测（注入 FakeHttpEngine）；
 *  2. **请求体一律用 org.json 构造**，绝不用字符串模板拼接 ——
 *     用户备注 / 商户名里的引号、换行、反斜杠会直接破坏 JSON；
 *  3. **任何日志与错误信息都不含 API Key**，出口统一过 [SecretRedactor]。
 */
class AiClientImpl(
    private val engine: HttpEngine
) : AiClient {

    // ═══ 超时参数 ═══
    //
    // 不用 withTimeout 做整体墙钟限制：TimeoutCancellationException 是
    // CancellationException 的子类，会与「用户主动停止」混淆，导致上层
    // 把超时误判为取消（或反之）。改为在流式循环里手动比对 deadline，
    // 语义清晰且不会污染取消传播。

    private companion object {
        const val CONNECT_TIMEOUT_MS = 8_000

        const val TEST_READ_TIMEOUT_MS = 15_000
        const val CHAT_READ_TIMEOUT_MS = 30_000
        const val STREAM_READ_TIMEOUT_MS = 60_000

        /** 流式整体墙钟上限：超过即判定超时，保留已生成内容 */
        const val STREAM_OVERALL_MS = 120_000L

        const val TEST_MAX_TOKENS = 8
        const val USER_AGENT = "SmartLedger-Android"

        /** 连通性探测在「HTTP 200 但正文为空」时合成的回复 */
        const val PROBE_OK = "OK"
    }

    // ═══════════════════════════════════════════════════
    // 连通性测试
    // ═══════════════════════════════════════════════════

    override suspend fun testConnection(config: AiConfig): AiResult {
        val pre = preflight(config)
        if (pre != null) return AiResult.Failure(pre)

        // 极小请求，尽量少烧额度
        val messages = listOf(
            AiMessage.system("You are a connectivity probe. Reply with exactly: OK"),
            AiMessage.user("ping")
        )
        return chatInternal(
            config = config,
            messages = messages,
            temperature = 0.0,
            maxTokens = TEST_MAX_TOKENS,
            readTimeoutMs = TEST_READ_TIMEOUT_MS,
            // 探测时允许正文为空：推理模型会把 8 个 token 全用在思考上，
            // 这本身说明「连通性是好的」，不该报成失败。
            tolerateEmptyForReasoning = true
        )
    }

    // ═══════════════════════════════════════════════════
    // 一次性对话
    // ═══════════════════════════════════════════════════

    override suspend fun chat(
        config: AiConfig,
        messages: List<AiMessage>,
        temperature: Double,
        maxTokens: Int
    ): AiResult {
        val pre = preflight(config)
        if (pre != null) return AiResult.Failure(pre)

        return chatInternal(
            config = config,
            messages = messages,
            temperature = temperature,
            maxTokens = maxTokens,
            readTimeoutMs = CHAT_READ_TIMEOUT_MS,
            tolerateEmptyForReasoning = false
        )
    }

    private suspend fun chatInternal(
        config: AiConfig,
        messages: List<AiMessage>,
        temperature: Double,
        maxTokens: Int,
        readTimeoutMs: Int,
        tolerateEmptyForReasoning: Boolean
    ): AiResult {
        val url = BaseUrlNormalizer.chatCompletionsUrl(config.baseUrl)
        val body = buildRequestBody(config, messages, temperature, maxTokens, stream = false)

        return try {
            val response = engine.post(
                HttpRequest(
                    url = url,
                    headers = buildHeaders(config),
                    body = body,
                    connectTimeoutMs = CONNECT_TIMEOUT_MS,
                    readTimeoutMs = readTimeoutMs
                )
            )
            if (!response.isSuccessful) {
                return AiResult.Failure(classifyStatus(response.code, response.body))
            }

            val (text, fromReasoning) = SseParser.extractMessageContent(response.body)
            when {
                text != null && text.isNotBlank() ->
                    AiResult.Success(text, fromReasoning)

                // 探测特例：HTTP 200 且确实是 Chat Completion 结构，就已经证明
                // 「地址、Key、模型三者都是通的」。推理模型会把 8 个 token 全用在
                // 思考上导致 content 与 reasoning_content 都为空，这是额度上限的
                // 产物而不是连通性问题，因此合成一个 OK 作为探测成功。
                tolerateEmptyForReasoning && isChatCompletion(response.body) ->
                    AiResult.Success(PROBE_OK, fromReasoningFallback = true)

                isChatCompletion(response.body) ->
                    AiResult.Failure(AiError.EmptyReasoningOutput)

                else -> AiResult.Failure(AiError.Incompatible)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AiResult.Failure(classifyException(e))
        }
    }

    // ═══════════════════════════════════════════════════
    // 流式对话
    // ═══════════════════════════════════════════════════

    override fun streamChat(
        config: AiConfig,
        messages: List<AiMessage>,
        temperature: Double,
        maxTokens: Int
    ): Flow<AiStreamEvent> = channelFlow {
        val pre = preflight(config)
        if (pre != null) {
            send(AiStreamEvent.Failed(pre, ""))
            return@channelFlow
        }

        val url = BaseUrlNormalizer.chatCompletionsUrl(config.baseUrl)
        val body = buildRequestBody(config, messages, temperature, maxTokens, stream = true)

        /** 已收到的正文 */
        val content = StringBuilder()
        /** 已收到的推理内容，仅在正文为空时兜底 */
        val reasoning = StringBuilder()
        var sawDone = false

        try {
            val deadline = System.currentTimeMillis() + STREAM_OVERALL_MS

            engine.postStreaming(
                HttpRequest(
                    url = url,
                    headers = buildHeaders(config),
                    body = body,
                    connectTimeoutMs = CONNECT_TIMEOUT_MS,
                    readTimeoutMs = STREAM_READ_TIMEOUT_MS
                )
            ) { line ->
                when (val event = SseParser.parseLine(line)) {
                    is SseEvent.Content -> {
                        content.append(event.text)
                        send(AiStreamEvent.Delta(event.text))
                    }

                    is SseEvent.Reasoning -> reasoning.append(event.text)

                    is SseEvent.Done -> {
                        sawDone = true
                        // 收到 [DONE] 后不再继续读，尽快释放连接
                        throw StreamFinishedException()
                    }

                    is SseEvent.Ignore -> Unit
                }

                // 墙钟超时：保留已生成内容，不当作用户取消
                if (System.currentTimeMillis() > deadline) {
                    throw StreamTimeoutException()
                }
            }
        } catch (e: StreamFinishedException) {
            // 正常路径，下面统一收尾
        } catch (e: StreamTimeoutException) {
            sendPartialOrTimeout(content, reasoning, sawDone)
            return@channelFlow
        } catch (e: CancellationException) {
            // 用户主动停止 / 页面销毁：原样抛出，交给协程框架处理，
            // 不转成 Failed —— 那不是错误。
            throw e
        } catch (e: HttpErrorStatusException) {
            send(AiStreamEvent.Failed(classifyStatus(e.code, e.errorBody), content.toString()))
            return@channelFlow
        } catch (e: Exception) {
            val error = classifyException(e)
            // 已经产出过内容却中途断开：把已有内容带上，让上层决定保留
            send(AiStreamEvent.Failed(error, content.toString()))
            return@channelFlow
        }

        sendTail(content, reasoning)
    }

    /**
     * 正常收尾。
     *
     * 处理「推理模型正文为空」：把 reasoning 一次性作为正文补发，
     * 否则用户选了 deepseek-v4-flash 会看到进度条走完却一片空白。
     */
    private suspend fun SendChannel<AiStreamEvent>.sendTail(
        content: StringBuilder,
        reasoning: StringBuilder
    ) {
        if (content.isNotEmpty()) {
            send(AiStreamEvent.Completed(fromReasoningFallback = false))
            return
        }
        if (reasoning.isNotEmpty()) {
            send(AiStreamEvent.Delta(reasoning.toString()))
            send(AiStreamEvent.Completed(fromReasoningFallback = true))
            return
        }
        send(AiStreamEvent.Failed(AiError.EmptyReasoningOutput, ""))
    }

    private suspend fun SendChannel<AiStreamEvent>.sendPartialOrTimeout(
        content: StringBuilder,
        reasoning: StringBuilder,
        sawDone: Boolean
    ) {
        // 已经拿到完整正文：只补一个超时提示性收尾，内容不丢
        if (content.isNotEmpty()) {
            send(AiStreamEvent.Failed(AiError.Timeout, content.toString()))
            return
        }
        if (reasoning.isNotEmpty() && !sawDone) {
            send(AiStreamEvent.Delta(reasoning.toString()))
            send(AiStreamEvent.Completed(fromReasoningFallback = true))
            return
        }
        send(AiStreamEvent.Failed(AiError.Timeout, ""))
    }

    // ═══════════════════════════════════════════════════
    // 请求构造
    // ═══════════════════════════════════════════════════

    /**
     * 发请求前的本地拦截。
     * @return 非 null 表示直接失败，不必发起网络请求
     */
    private fun preflight(config: AiConfig): AiError? {
        if (!config.isConfigured) return AiError.NotConfigured
        if (BaseUrlNormalizer.isCleartext(config.baseUrl)) return AiError.Cleartext
        // 兜底校验：正常流程下 AiSettingsScreen 保存时已经拦住了，
        // 这里防的是历史脏数据（例如用户改了 prefs 文件）
        if (!BaseUrlNormalizer.looksValid(config.baseUrl)) {
            return AiError.Unknown("Base URL 格式不正确")
        }
        return null
    }

    private fun buildHeaders(config: AiConfig): Map<String, String> = mapOf(
        "Content-Type" to "application/json; charset=utf-8",
        "Authorization" to "Bearer ${config.apiKey}",
        "User-Agent" to USER_AGENT
    )

    /**
     * 用 org.json 构造请求体。
     *
     * **不要改成字符串模板**：messages 里含用户输入的商户名与备注，
     * 一旦出现 `"`、`\n`、`\` 就会产出非法 JSON，且这类 bug 只在
     * 特定输入下复现，极难排查。
     */
    private fun buildRequestBody(
        config: AiConfig,
        messages: List<AiMessage>,
        temperature: Double,
        maxTokens: Int,
        stream: Boolean
    ): String {
        val arr = JSONArray()
        messages.forEach { m ->
            arr.put(JSONObject().apply {
                put("role", m.role)
                put("content", m.content)
            })
        }
        return JSONObject().apply {
            put("model", config.model)
            put("messages", arr)
            // temperature 越界会被部分网关拒绝，钳到合法区间
            put("temperature", temperature.coerceIn(0.0, 2.0))
            put("max_tokens", maxTokens.coerceIn(1, 8192))
            put("stream", stream)
        }.toString()
    }

    // ═══════════════════════════════════════════════════
    // 错误分类
    // ═══════════════════════════════════════════════════

    private fun isChatCompletion(body: String?): Boolean =
        SseParser.isChatCompletionShape(body)

    /** HTTP 状态码 → 用户可读错误 */
    private fun classifyStatus(code: Int, body: String?): AiError = when (code) {
        401, 403 -> AiError.InvalidApiKey
        404 -> AiError.ModelNotFound
        429 -> AiError.RateLimited
        in 500..599 -> AiError.ServerError
        else -> {
            val detail = SseParser.extractErrorMessage(body)
                ?.let { SecretRedactor.redact(it, 120) }
                ?: "HTTP $code"
            AiError.Unknown(detail)
        }
    }

    /** 异常 → 用户可读错误。message 一律先脱敏再进文案。 */
    private fun classifyException(e: Exception): AiError = when (e) {
        // 流式路径已单独 catch，这里再兜一次，防止将来有人改动 catch 顺序
        is HttpErrorStatusException -> classifyStatus(e.code, e.errorBody)
        is SocketTimeoutException -> AiError.Timeout
        is UnknownHostException -> AiError.Network
        is ConnectException -> AiError.Network
        is SSLException -> AiError.Network
        /**
         * 其余 IOException 一律算网络问题。
         *
         * 这一条是实测补上的：流中途被对端断开时抛的是
         * `IOException("Connection reset by peer")`，不属于上面任何一个子类。
         * 若不兜住就会落到 Unknown，把英文技术细节直接怼到用户脸上
         * （「请求失败：Connection reset by peer」），既看不懂也没法自助。
         * 同类还有 broken pipe / EOFException / stream is closed 等。
         */
        is java.io.IOException -> AiError.Network
        else -> {
            // HttpURLConnection 对明文流量会抛 IllegalArgumentException，
            // message 形如 "CLEARTEXT communication to x not permitted"
            val msg = e.message.orEmpty()
            if (msg.contains("CLEARTEXT", ignoreCase = true)) {
                AiError.Cleartext
            } else {
                AiError.Unknown(SecretRedactor.redact(msg.ifBlank { e.javaClass.simpleName }, 120))
            }
        }
    }

    /** 内部控制流：收到 [DONE] 后跳出读取循环 */
    private class StreamFinishedException : Exception("stream done")

    /** 内部控制流：整体墙钟超时 */
    private class StreamTimeoutException : Exception("stream overall timeout")
}
