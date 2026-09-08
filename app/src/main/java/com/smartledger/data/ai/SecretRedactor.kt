package com.smartledger.data.ai

/**
 * 敏感信息脱敏。
 *
 * 任何可能进入日志、Toast、崩溃堆栈的字符串都必须先过一遍这里。
 * AI 场景下最危险的是 **API Key 泄漏**：它可能出现在
 * 服务端返回的错误体（部分网关会回显 Authorization 头）、
 * 异常 message（HttpURLConnection 的报错有时带 URL 与请求片段）、
 * 以及调试时不小心打印的请求体里。
 */
object SecretRedactor {

    /**
     * 常见 Key 形态：
     *  - OpenAI / DeepSeek / Moonshot / 百炼：`sk-` 开头
     *  - Google：`AIza` 开头
     *  - Bearer 头整体
     * 长度下限设 8，避免把 "sk-" 这类正常词误伤。
     */
    private val KEY_PATTERNS = listOf(
        Regex("""(?i)\bsk-[A-Za-z0-9_\-]{8,}"""),
        Regex("""\bAIza[A-Za-z0-9_\-]{8,}"""),
        Regex("""(?i)Bearer\s+[A-Za-z0-9._\-]{8,}""")
    )

    /** Authorization / api_key 之类的键值对整体打掉 */
    private val FIELD_PATTERNS = listOf(
        Regex("""(?i)("|')?(authorization|api[_-]?key|apikey|token|secret)("|')?\s*[:=]\s*("|')?[^\s"',}]{4,}""")
    )

    /**
     * 把疑似密钥替换为 `***REDACTED***`，并截断长度。
     *
     * @param maxLength 结果最大长度，防止把整个响应体塞进日志
     */
    fun redact(raw: String?, maxLength: Int = 200): String {
        if (raw == null) return ""
        // 显式声明非空类型：isNullOrBlank() 的 contract 在赋值给 var 后
        // 不一定能传递智能转换，写成两段最稳妥。
        var s: String = raw
        if (s.isBlank()) return ""
        KEY_PATTERNS.forEach { s = it.replace(s, "***REDACTED***") }
        FIELD_PATTERNS.forEach { s = it.replace(s, "***REDACTED***") }
        // 压掉换行，日志里更好读，也避免多行绕过审查
        s = s.replace("\r", " ").replace("\n", " ").trim()
        return if (s.length > maxLength) s.take(maxLength) + "…" else s
    }

    /** 快速自检：文本里是否仍残留疑似密钥（用于 DEBUG 断言） */
    fun containsSecret(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        return KEY_PATTERNS.any { it.containsMatchIn(text) }
    }
}
