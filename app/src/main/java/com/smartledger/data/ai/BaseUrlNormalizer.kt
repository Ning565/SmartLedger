package com.smartledger.data.ai

/**
 * Base URL 归一化。
 *
 * 用户会填出各种形态，这是 AI 配置里**最高频的错误来源**：
 *
 * ```
 * api.deepseek.com                          ← 没写协议
 * https://api.deepseek.com                  ← 没写 /v1
 * https://api.deepseek.com/v1               ← 正确
 * https://api.deepseek.com/v1/              ← 多一个斜杠
 * https://api.deepseek.com/v1/chat/completions  ← 把完整 endpoint 贴进来了
 * ```
 *
 * 纯函数，不依赖 Android，可单测。
 */
object BaseUrlNormalizer {

    /** 已经隐含 OpenAI 兼容路径、不需要再补 /v1 的后缀 */
    private val VERSIONED_SUFFIXES = listOf(
        "/compatible-mode/v1",   // 阿里云百炼
        "/openai"                // Gemini OpenAI 兼容端点
    )

    /** 用户可能整段贴进来的 endpoint 尾巴，需要剥掉 */
    private val ENDPOINT_SUFFIXES = listOf(
        "/chat/completions",
        "/completions"
    )

    private val VERSION_SEGMENT = Regex("""/v\d+[A-Za-z0-9]*$""")

    /**
     * 归一化：补协议、去尾斜杠、剥掉用户误贴的 endpoint 尾巴。
     * 空串原样返回空串（交给调用方判空）。
     */
    fun normalize(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) return s

        if (!s.startsWith("http://", ignoreCase = true) &&
            !s.startsWith("https://", ignoreCase = true)
        ) {
            s = "https://$s"
        }

        // 反复剥尾斜杠与 endpoint 尾巴：
        // ".../v1/chat/completions/" 这种混合形态也要能处理干净
        var changed = true
        while (changed) {
            changed = false
            val before = s
            s = s.trimEnd('/')
            ENDPOINT_SUFFIXES.forEach { suffix ->
                if (s.endsWith(suffix, ignoreCase = true)) {
                    s = s.substring(0, s.length - suffix.length).trimEnd('/')
                }
            }
            if (s != before) changed = true
        }
        return s
    }

    /** 拼出最终请求地址；base 没有版本段时自动补 /v1 */
    fun chatCompletionsUrl(baseUrl: String): String {
        val base = normalize(baseUrl)
        if (base.isEmpty()) return base
        val needsVersion = !VERSION_SEGMENT.containsMatchIn(base) &&
                VERSIONED_SUFFIXES.none { base.endsWith(it, ignoreCase = true) }
        return if (needsVersion) "$base/v1/chat/completions" else "$base/chat/completions"
    }

    /**
     * 是否为明文 HTTP。
     *
     * Android 9（API 28）起默认禁止 cleartext，这类地址必然连接失败。
     * 提前拦下来给出明确提示，比让用户看 "CLEARTEXT communication not permitted" 友好得多。
     * 注意：本项目**不**为此放开全局 usesCleartextTraffic，那会削弱整个 App 的网络安全。
     */
    fun isCleartext(raw: String): Boolean {
        val s = raw.trim()
        return s.startsWith("http://", ignoreCase = true)
    }

    /** 只做合法性粗校验，供「保存」按钮的即时反馈使用 */
    fun looksValid(raw: String): Boolean {
        val s = normalize(raw)
        if (s.isBlank()) return false
        if (!s.startsWith("https://") && !s.startsWith("http://")) return false
        // 至少要有一个 host 段
        val host = s.removePrefix("https://").removePrefix("http://").substringBefore('/')
        return host.contains('.') && !host.startsWith('.') && !host.endsWith('.')
    }
}
