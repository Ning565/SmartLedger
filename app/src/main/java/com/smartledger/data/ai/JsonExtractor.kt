package com.smartledger.data.ai

/**
 * 从模型原始输出中抽出第一个完整的 JSON 对象。
 *
 * 为什么需要：即使 Prompt 明确要求「只输出 JSON，不要 Markdown」，
 * 实测各家模型仍会时不时返回下面这些形态：
 *
 * ```
 * ```json
 * {"amount":32}
 * ```
 * ```
 *
 * ```
 * 好的，解析结果如下：
 * {"amount":32}
 * 希望对你有帮助！
 * ```
 *
 * 直接 `JSONObject(raw)` 会抛异常，导致「AI 解析」在用户看来随机失败。
 * 这里做健壮的括号配对提取，把失败率压到最低。
 *
 * 纯字符串处理，不做 JSON 语义解析，因此不依赖 org.json，可在纯 JVM 下测试。
 */
object JsonExtractor {

    /**
     * @return 第一个完整 JSON 对象的子串；找不到完整对象时返回 null
     */
    fun extractObject(raw: String?): String? {
        if (raw.isNullOrBlank()) return null

        // 1. 先剥代码围栏。允许 ```json / ```JSON / ``` 三种写法，
        //    也允许围栏前后有别的文字。
        val unfenced = stripCodeFence(raw)

        // 2. 从第一个 '{' 开始做括号配对
        val start = unfenced.indexOf('{')
        if (start < 0) return null

        var depth = 0
        var inString = false
        var escaped = false

        for (i in start until unfenced.length) {
            val c = unfenced[i]

            if (inString) {
                when {
                    // 反斜杠转义：下一个字符不参与结构判断
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }

            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return unfenced.substring(start, i + 1)
                    }
                }
            }
        }
        // 括号没配平（输出被截断 / max_tokens 不够），返回 null 让上层降级
        return null
    }

    /**
     * 剥掉 ``` 围栏。
     * 只做「移除围栏标记」，不假设 JSON 一定独占一行。
     */
    private fun stripCodeFence(raw: String): String {
        if (!raw.contains("```")) return raw
        return raw
            .replace(Regex("""```[a-zA-Z]*"""), "")
            .replace("```", "")
    }

    /**
     * 便捷方法：抽取并判断是否看起来像个对象。
     * 用于「AI 返回了文本但完全不是 JSON」的快速失败路径。
     */
    fun looksLikeJson(raw: String?): Boolean = extractObject(raw) != null
}
