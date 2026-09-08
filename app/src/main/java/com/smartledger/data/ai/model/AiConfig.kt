package com.smartledger.data.ai.model

/**
 * AI 服务商预设。
 *
 * 全部走 **OpenAI Compatible Chat Completions** 协议，不引入各家 SDK。
 *
 * ⚠ 实测结论（DeepSeek，2026-09 验证）：
 *  - `deepseek-chat`            → 直接返回 `choices[0].message.content`，干净可用，额度最省。**默认**。
 *  - `deepseek-v4-flash/pro`    → 是**推理模型**：`content` 为空字符串，正文全在
 *                                 `reasoning_content` 里，且 `max_tokens` 会被推理过程吃光
 *                                 （小 max_tokens 时 `finish_reason=length` 而 content 仍为空）。
 *
 * 因此：
 *  1. 默认 model 用 `deepseek-chat`；
 *  2. [AiClientImpl] 对 `content` 为空但 `reasoning_content` 非空的情况做兜底，
 *     保证用户手选推理模型时功能不至于完全空白；
 *  3. UI 上对推理模型给出额度提示。
 *
 * 除 DeepSeek 外的预设为便利项，尚未逐一实测；用户始终可改 Base URL 与 Model。
 */
enum class AiProviderPreset(
    val label: String,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val apiKeyHint: String,
    /** 该预设下的可选模型，用于 UI 快捷选择 */
    val modelOptions: List<String> = listOf(defaultModel),
    /** 是否为推理模型（content 可能为空、额度消耗更高） */
    val reasoningModels: Set<String> = emptySet(),
    /** 未实测的预设，UI 上加提示 */
    val unverified: Boolean = false
) {
    DEEPSEEK(
        label = "DeepSeek",
        defaultBaseUrl = "https://api.deepseek.com/v1",
        defaultModel = "deepseek-chat",
        apiKeyHint = "sk-...",
        modelOptions = listOf(
            "deepseek-chat",
            "deepseek-v4-flash",
            "deepseek-v4-pro"
        ),
        reasoningModels = setOf("deepseek-v4-flash", "deepseek-v4-pro")
    ),

    KIMI(
        label = "Kimi（月之暗面）",
        defaultBaseUrl = "https://api.moonshot.cn/v1",
        defaultModel = "moonshot-v1-8k",
        apiKeyHint = "sk-...",
        unverified = true
    ),

    QWEN(
        label = "通义千问（百炼）",
        defaultBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
        defaultModel = "qwen-plus",
        apiKeyHint = "sk-...",
        unverified = true
    ),

    OPENAI(
        label = "OpenAI",
        defaultBaseUrl = "https://api.openai.com/v1",
        defaultModel = "gpt-4o-mini",
        apiKeyHint = "sk-...",
        unverified = true
    ),

    /** 任意 OpenAI 兼容服务（自建 / 代理 / 其他厂商） */
    CUSTOM(
        label = "自定义 OpenAI Compatible",
        defaultBaseUrl = "",
        defaultModel = "",
        apiKeyHint = "自定义 Key"
    );

    /** 按名称安全解析，未知值回落到 CUSTOM，避免存了脏数据后崩溃 */
    companion object {
        fun fromName(name: String?): AiProviderPreset =
            entries.firstOrNull { it.name == name } ?: CUSTOM
    }
}

/**
 * AI 服务配置。
 *
 * `apiKey` 只存在于内存与本对象中；持久化时由 [com.smartledger.data.security.SecureSecretStore]
 * 用 AndroidKeyStore 加密，其余字段存普通 SharedPreferences。
 */
data class AiConfig(
    val preset: AiProviderPreset = AiProviderPreset.DEEPSEEK,
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = ""
) {
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()

    /** 是否为推理模型（额度消耗更高，content 可能为空） */
    val isReasoningModel: Boolean
        get() = preset.reasoningModels.any { model.equals(it, ignoreCase = true) }

    /** 供 UI 展示的简短标识，如 "DeepSeek · deepseek-chat" */
    val displayLabel: String
        get() = if (isConfigured) "${preset.label} · $model" else "未配置"

    /**
     * 脱敏副本，用于日志与异常信息。
     * **任何日志都必须打印这个而不是原对象**，防止 Key 泄漏。
     */
    fun redacted(): AiConfig = copy(
        apiKey = if (apiKey.isBlank()) "" else maskSecret(apiKey)
    )

    override fun toString(): String =
        "AiConfig(preset=$preset, baseUrl=$baseUrl, apiKey=${maskSecret(apiKey)}, model=$model)"

    companion object {
        /** 保留前 3 后 4，如 sk-••••••••1a2b */
        fun maskSecret(raw: String): String {
            if (raw.isBlank()) return ""
            if (raw.length <= 8) return "•".repeat(raw.length)
            return raw.take(3) + "•".repeat(6) + raw.takeLast(4)
        }
    }
}
