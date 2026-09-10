package com.smartledger.service.accessibility

/**
 * 支付强信号探测（Level 1，纯函数）。
 *
 * 双条件缺一不可（方案 4.3）：
 *  1. 交易状态词（支付成功 / 已收款 / 红包已领取 …）
 *  2. 金额形态
 *
 * 只满足其一 → 不进入完整扫描。理由：
 * - 只有金额（"¥500"、聊天里"这个东西32元"）：不是交易完成的页面
 * - 只有状态词（"红包已领取"无金额）：知道发生了交易但不知道金额，
 *   方案 6.2 明确规定这类**不创建账目**，也不值得为它跑完整解析
 */
object PaymentSignalDetector {

    /** 交易完成状态词（探测层，比 Parser 的词表窄且强特异） */
    private val STRONG_WORDS = listOf(
        "支付成功",
        "付款成功",
        "已支付",
        "转账成功",
        "收款成功",
        "已收款",
        "到账成功",
        "退款成功",
        "已退款",
        "红包已领取",
        "已存入零钱",
        // C10：场景 G「收到转账 ¥500 交易成功」的页面没有其它状态词，
        // 不加这个词整条链路会在信号层被拦死
        "收到转账",
        // N3 加回：「已到账」是转账/工资/退款到账的高频词形，移除会漏记真实收入；
        // 积分/理财页的误记由 Parser 层的营销词邻近性判断（N2）拦住
        "已到账"
    )

    /**
     * 金额存在性探测（宽松版）。
     *
     * 这里只回答「页面上有没有金额形态」，不做精确提取 ——
     * 精确提取（含打分与排除）是 [ScreenAmountExtractor] 的职责（C7：
     * 两套正则分离，宽松版绝不用于入账数值）。
     */
    private val AMOUNT_PROBE_REGEX = Regex("""[¥￥]|\d+(?:\.\d{1,2})?\s*元|\d+\.\d{2}""")

    /** 整页文本（或 quickProbe 拼接文本）是否具备支付强信号 */
    fun hasStrongSignal(text: String): Boolean {
        val normalized = UiTreeTextNormalizer.normalize(text)
        if (normalized.isEmpty()) return false
        return strongWordsIn(normalized).isNotEmpty() &&
            AMOUNT_PROBE_REGEX.containsMatchIn(normalized)
    }

    /** 命中的状态词列表（供指纹构建复用，保证探测与指纹用同一张词表） */
    fun strongWordsIn(text: String): List<String> {
        val normalized = UiTreeTextNormalizer.normalize(text)
        return STRONG_WORDS.filter { normalized.contains(it) }
    }
}
