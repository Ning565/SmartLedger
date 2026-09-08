package com.smartledger.data.ai

/**
 * 从 AI 报告正文里提取「建议可用总额度」。
 *
 * Prompt 要求最后一行固定输出 `建议可用总额度：¥4800`，
 * 但模型实际输出会漂移。实测/常见的偏移形态：
 *
 * ```
 * 建议可用总额度：¥4800          ← 标准
 * 建议可用总额度：￥4,800         ← 全角货币符 + 千分位
 * 建议可用总额度: 4800 元         ← 半角冒号 + 单位
 * **建议可用总额度：¥4800.50**    ← 被加粗包裹
 * 建议可用总额度：约 5000元       ← 加了「约」
 * 建议可用总额度：¥4800。         ← 中文句号结尾
 * ```
 *
 * 因此正则必须放宽，并且**取最后一次匹配** —— 正文里可能先讨论过一次
 * 「上月建议可用总额度：¥5000」，最后那行才是本期的结论。
 *
 * 该值只作为建议展示，**任何情况下都不自动写入 budgets 表**，
 * 必须用户在弹窗里确认（可改金额）后才生效。
 */
object SuggestedBudgetExtractor {

    /** 金额上限：超过视为解析异常（模型幻觉出天文数字），丢弃 */
    private const val MAX_REASONABLE = 1_000_000.0

    private val REGEX = Regex(
        "建议可用总额度" +          // 标签
                "\\s*[：:]" +       // 全角或半角冒号
                "\\s*\\*{0,2}" +    // 可能的加粗前缀
                "\\s*[¥￥]?" +      // 半角或全角货币符
                "\\s*约?\\s*" +     // 可能的「约」
                // 交替分支是「左优先」：千分位组若是 *（零个也成功），
                // 不带千分位的 4 位以上数字会在第一分支被 [0-9]{1,3} 匹配满 3 位
                // 就提前成功，剩余位被丢弃（¥4800 截成 480、¥12000 截成 120）。
                // 因此千分位分支必须用 +（至少一个 ,xxx），让无千分位数字
                // 落到第二分支贪婪匹配完整数字。
                "([0-9]{1,3}(?:,[0-9]{3})+(?:\\.[0-9]{1,2})?" +  // 带千分位（≥1 个 ,xxx）
                "|[0-9]+(?:\\.[0-9]{1,2})?)"                      // 不带千分位
    )

    /**
     * @return 解析出的建议额度；未找到或不合理时返回 null
     */
    fun extract(markdown: String?): Double? {
        if (markdown.isNullOrBlank()) return null
        val last = REGEX.findAll(markdown).lastOrNull() ?: return null
        val raw = last.groupValues.getOrNull(1) ?: return null
        val value = raw.replace(",", "").toDoubleOrNull() ?: return null
        if (value.isNaN() || value.isInfinite()) return null
        if (value <= 0.0) return null
        if (value > MAX_REASONABLE) return null
        // 归一到两位小数，避免 4800.005 这种浮点尾巴进数据库
        return Math.round(value * 100.0) / 100.0
    }
}
