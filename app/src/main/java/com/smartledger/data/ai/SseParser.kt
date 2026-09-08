package com.smartledger.data.ai

import org.json.JSONObject

/**
 * SSE（Server-Sent Events）行解析。
 *
 * 实测 DeepSeek 的流式响应形态：
 *
 * ```
 * data: {"choices":[{"index":0,"delta":{"role":"assistant","content":""},"finish_reason":null}]}
 *
 * data: {"choices":[{"index":0,"delta":{"content":"蓝色"},"finish_reason":null}]}
 *
 * data: {"choices":[{"index":0,"delta":{"content":""},"finish_reason":"stop"}],"usage":{...}}
 *
 * data: [DONE]
 * ```
 *
 * 几个必须处理的坑：
 *  1. **首包 content 是空字符串**（只带 role），末包 content 也是空字符串（只带 finish_reason）。
 *     不能把空串当正文发出去，否则会在 UI 上产生大量无意义重组。
 *  2. **推理模型**（deepseek-v4-flash / pro）会先推一串 `delta.reasoning_content`，
 *     那是思考过程，不能当正文渲染。
 *  3. 事件之间有空行；有些网关还会插 `: ping` 心跳注释行。
 *  4. 单行 JSON 损坏时**不能中断整个流**，跳过即可。
 *  5. `event:` / `id:` / `retry:` 行不是数据，忽略。
 */
object SseParser {

    private const val DATA_PREFIX = "data:"
    private const val DONE_TOKEN = "[DONE]"

    fun parseLine(line: String?): SseEvent {
        if (line == null) return SseEvent.Ignore

        // BufferedReader.readLine() 已去掉 \n，但有的服务端会多给一个 \r
        val trimmed = line.trimEnd('\r', '\n')
        if (trimmed.isBlank()) return SseEvent.Ignore

        // SSE 注释行 / 心跳，形如 ": ping"
        if (trimmed.startsWith(":")) return SseEvent.Ignore

        // 只认 data: 字段（大小写不敏感，个别实现会写 Data:）
        if (!trimmed.regionMatches(0, DATA_PREFIX, 0, DATA_PREFIX.length, ignoreCase = true)) {
            return SseEvent.Ignore
        }

        val payload = trimmed.substring(DATA_PREFIX.length).trim()
        if (payload.isEmpty()) return SseEvent.Ignore
        if (payload.equals(DONE_TOKEN, ignoreCase = true)) return SseEvent.Done
        if (!payload.startsWith("{")) return SseEvent.Ignore

        return try {
            parseChunk(JSONObject(payload))
        } catch (_: Exception) {
            // 坏包不致命，跳过继续读后面的
            SseEvent.Ignore
        }
    }

    private fun parseChunk(root: JSONObject): SseEvent {
        val choices = root.optJSONArray("choices") ?: return SseEvent.Ignore
        if (choices.length() == 0) return SseEvent.Ignore

        val first = choices.optJSONObject(0) ?: return SseEvent.Ignore

        // 标准流式是 delta；个别兼容层在流里也发 message，两者都接
        val delta = first.optJSONObject("delta") ?: first.optJSONObject("message")
            ?: return SseEvent.Ignore

        // 正文优先：一个 chunk 同时带 content 和 reasoning_content 时，只取 content
        val content = if (delta.has("content")) delta.optString("content", "") else ""
        if (content.isNotEmpty()) return SseEvent.Content(content)

        val reasoning =
            if (delta.has("reasoning_content")) delta.optString("reasoning_content", "") else ""
        if (reasoning.isNotEmpty()) return SseEvent.Reasoning(reasoning)

        return SseEvent.Ignore
    }

    /**
     * 从非流式响应里取正文。
     *
     * 同样处理推理模型：`content` 为空但 `reasoning_content` 有值时，
     * 退回用 reasoning_content —— 否则用户选了 v4 系列会看到一片空白，
     * 以为是功能坏了。
     *
     * @return Pair(正文, 是否来自 reasoning 兜底)；都取不到返回 Pair(null, false)
     */
    fun extractMessageContent(body: String?): Pair<String?, Boolean> {
        if (body.isNullOrBlank()) return null to false
        return try {
            val root = JSONObject(body)
            val choices = root.optJSONArray("choices") ?: return null to false
            if (choices.length() == 0) return null to false
            val first = choices.optJSONObject(0) ?: return null to false
            val message = first.optJSONObject("message") ?: return null to false

            val content = message.optString("content", "").orEmpty()
            if (content.isNotBlank()) return content to false

            val reasoning = message.optString("reasoning_content", "").orEmpty()
            if (reasoning.isNotBlank()) return reasoning to true

            null to false
        } catch (_: Exception) {
            null to false
        }
    }

    /** 判断响应体是否是 OpenAI Chat Completions 结构（用于 Incompatible 错误分类） */
    fun isChatCompletionShape(body: String?): Boolean {
        if (body.isNullOrBlank()) return false
        return try {
            val root = JSONObject(body)
            root.has("choices") || root.has("object") || root.has("error")
        } catch (_: Exception) {
            false
        }
    }

    /** 从错误响应体里取人类可读信息（已做长度限制，调用方需再脱敏） */
    fun extractErrorMessage(body: String?): String? {
        if (body.isNullOrBlank()) return null
        return try {
            val root = JSONObject(body)
            val err = root.optJSONObject("error")
            val msg = err?.optString("message")?.takeIf { it.isNotBlank() }
                ?: root.optString("message").takeIf { it.isNotBlank() }
            msg
        } catch (_: Exception) {
            // 不是 JSON（例如网关返回的 HTML 错误页），截一段原文供排查
            body.take(160)
        }
    }
}

/** 单行 SSE 的解析结果 */
sealed interface SseEvent {
    /** 正文增量 */
    data class Content(val text: String) : SseEvent

    /** 推理过程增量（不作为正文展示，仅兜底） */
    data class Reasoning(val text: String) : SseEvent

    /** 收到 [DONE] */
    data object Done : SseEvent

    /** 该行无需处理 */
    data object Ignore : SseEvent
}
