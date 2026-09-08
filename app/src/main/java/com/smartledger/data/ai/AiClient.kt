package com.smartledger.data.ai

import com.smartledger.data.ai.model.AiConfig
import com.smartledger.data.ai.model.AiError
import com.smartledger.data.ai.model.AiMessage
import kotlinx.coroutines.flow.Flow

/**
 * AI 调用结果。
 *
 * 用密封类而不是 `kotlin.Result`：
 *  - `Result` 会把「用户主动取消」和「网络失败」混成同一种 failure，
 *    上层需要靠异常类型二次判断，容易漏；
 *  - 密封类让 when 分支穷尽，编译期就能保证所有失败态都被处理。
 */
sealed interface AiResult {
    /**
     * @param text 正文
     * @param fromReasoningFallback 正文是否来自推理模型的 `reasoning_content` 兜底。
     *        为 true 时应提示用户「该模型为推理模型，建议改用 deepseek-chat」，
     *        因为推理内容不是为最终用户写的，可读性差。
     */
    data class Success(
        val text: String,
        val fromReasoningFallback: Boolean = false
    ) : AiResult

    data class Failure(val error: AiError) : AiResult
}

/** 流式事件。携带 partialText，失败时上层仍能把已生成内容落库。 */
sealed interface AiStreamEvent {
    data class Delta(val text: String) : AiStreamEvent

    /**
     * 正常结束。
     * @param fromReasoningFallback 正文是否由推理内容兜底而来
     */
    data class Completed(val fromReasoningFallback: Boolean = false) : AiStreamEvent

    /**
     * 失败。
     * @param partialText 失败前已经收到的正文，上层应保留而不是丢弃
     */
    data class Failed(val error: AiError, val partialText: String) : AiStreamEvent
}

/**
 * 统一 AI 客户端。
 *
 * 全阶段只使用 **OpenAI Compatible Chat Completions** 协议，不引入各家 SDK。
 *
 * 用途分工：
 * ```
 * chat()       → 自然语言记账字段抽取（低 temperature，短输出，要稳定）
 * streamChat() → AI 消费体检（较高 temperature，长输出，要实时观感）
 * ```
 *
 * 实现类 [AiClientImpl] 不持有 Context，可在纯 JVM 下配合
 * [com.smartledger.data.ai.http.HttpEngine] 的 Fake 实现做完整单测。
 */
interface AiClient {

    /**
     * 连通性探测：发送极小请求（max_tokens=8，"只回复 OK"），
     * 尽量少消耗用户额度。
     *
     * @return Success 时 text 为模型回显内容，供 UI 展示「模型回复：OK」
     */
    suspend fun testConnection(config: AiConfig): AiResult

    /** 一次性对话 */
    suspend fun chat(
        config: AiConfig,
        messages: List<AiMessage>,
        temperature: Double = 0.2,
        maxTokens: Int = 512
    ): AiResult

    /**
     * 流式对话。
     *
     * 取消语义：collect 所在协程被取消时（用户点「停止生成」/ 页面销毁），
     * Flow 正常结束，底层连接被断开。取消**不会**转成 [AiStreamEvent.Failed]，
     * 因为「用户主动停止」不是错误，已生成内容应由上层决定如何保留。
     */
    fun streamChat(
        config: AiConfig,
        messages: List<AiMessage>,
        temperature: Double = 0.7,
        maxTokens: Int = 2048
    ): Flow<AiStreamEvent>
}
