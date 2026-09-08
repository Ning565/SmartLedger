package com.smartledger.data.ai.model

/**
 * Chat Completions 消息。role 取值：system / user / assistant。
 */
data class AiMessage(val role: String, val content: String) {
    companion object {
        fun system(content: String) = AiMessage("system", content)
        fun user(content: String) = AiMessage("user", content)
    }
}

/**
 * 流式响应分片。
 *
 * 刻意不把「推理过程」当成正文：DeepSeek 推理模型的 SSE 会先推
 * `delta.reasoning_content`，那部分是思考过程，不应展示给用户，
 * 也不能计入报告正文（否则 Markdown 渲染会混入大段自言自语）。
 */
sealed interface AiChunk {
    /** 正文增量 */
    data class Delta(val text: String) : AiChunk

    /**
     * 推理过程增量。仅用于「模型只输出推理、正文为空」时的兜底，
     * 默认 UI 不展示。
     */
    data class Reasoning(val text: String) : AiChunk

    /** 流正常结束 */
    data object Done : AiChunk
}
