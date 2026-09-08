package com.smartledger.data.ai.model

/**
 * AI 请求错误。
 *
 * 每个子类自带**可直接展示给用户**的中文文案，UI 层不需要再做映射，
 * 避免各处各写一套提示导致口径不一致。
 *
 * 所有可能携带敏感信息的分支（如 [Unknown]）在构造前必须经过
 * [com.smartledger.data.ai.SecretRedactor.redact] 处理。
 */
sealed class AiError(val userMessage: String) {

    /** 未配置 API Key / Base URL / Model */
    data object NotConfigured : AiError("请先在设置中配置 AI 服务")

    /** 401 / 403 */
    data object InvalidApiKey : AiError("API Key 无效或已过期")

    /** 404：模型名错，或 Base URL 路径不对 */
    data object ModelNotFound : AiError("模型不存在，请检查 Model 名称或 Base URL 路径")

    /** 429 */
    data object RateLimited : AiError("请求过于频繁或额度不足，请稍后重试")

    /** 5xx */
    data object ServerError : AiError("AI 服务暂时不可用，请稍后重试")

    /** 超时 */
    data object Timeout : AiError("请求超时，请检查网络后重试")

    /** DNS / 连接 / TLS 失败 */
    data object Network : AiError("网络连接失败，请检查网络")

    /** 明文 http：Android 9+ 默认禁止 */
    data object Cleartext : AiError("系统禁止明文 HTTP 请求，请改用 https 地址")

    /** 200 但结构不是 OpenAI Chat Completions */
    data object Incompatible : AiError("接口返回格式不兼容，请确认是 OpenAI 兼容接口")

    /** 用户主动停止 */
    data object Cancelled : AiError("已停止生成")

    /**
     * 推理模型返回空正文：`content` 与 `reasoning_content` 都为空，
     * 通常是 `max_tokens` 被推理过程吃光（实测 DeepSeek v4 系列会出现）。
     */
    data object EmptyReasoningOutput :
        AiError("该模型是推理模型，返回正文为空。建议改用 deepseek-chat，或提高输出长度上限")

    /** 400 及其他未归类错误。detail 必须已脱敏。 */
    data class Unknown(val detail: String) : AiError("请求失败：$detail")
}
