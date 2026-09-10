package com.smartledger.service.accessibility

/**
 * 屏幕金额提取（打分制，方案 5.5，纯函数）。
 *
 * 「页面第一个数字」绝不可直接当金额：支付成功页常见干扰项有
 * 订单号、时间、日期、银行卡尾号、优惠券码。这里对每个候选打分：
 *
 * ```
 * +100  ¥32.00 / ￥32.00（货币符前缀，最强特征）
 * +80   32.00元 / 500元（带单位）
 * +80   与「实付 / 金额 / 收款 / 转账金额」标签距离 ≤ 2 节点
 * +60   全页唯一金额候选
 *
 * -100  与「订单号 / 交易号 / 单号」标签距离 ≤ 2 节点
 * -100  裸数字整数部分 ≥ 7 位（订单号 / 券码形态，金额极少裸写到十万级）
 * -80   裸数字与「时间 / 日期」标签距离 ≤ 2 节点
 * ```
 *
 * 最高分 < [MIN_SCORE]（60）→ 返回 null（宁可漏记不可误记）。
 * 注意负分规则里「订单号」对所有候选生效、「时间」只对裸数字生效：
 * 「付款时间 14:22」旁边的「¥32.00」仍是真金额，不能被时间标签连坐。
 */
object ScreenAmountExtractor {

    /** 录取分数线：红包页的裸数字「12.88」靠「唯一候选 +60」恰好压线 */
    const val MIN_SCORE = 60

    /** 合理金额上限：超过视为页面噪声或幻觉（与通知解析口径一致） */
    private const val MAX_REASONABLE = 1_000_000.0

    private const val SCORE_PREFIX = 100
    private const val SCORE_UNIT = 80
    private const val SCORE_NEAR_LABEL = 80
    private const val SCORE_UNIQUE = 60
    private const val PENALTY_NEAR_ORDER = -100
    private const val PENALTY_NEAR_TIME = -80
    private const val PENALTY_BARE_LONG = -100

    /** 邻近判定的节点距离 */
    private const val NEAR_DISTANCE = 2

    // P2-9：千分位必须带完整组（[0-9,]* 会把 ¥12,34 解析成 1234），
    // 与 BARE_AMOUNT / SuggestedBudgetExtractor 修复后的形式统一
    private val AMOUNT_WITH_PREFIX = Regex(
        """[¥]\s*([0-9]{1,3}(?:,[0-9]{3})+(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)"""
    )
    private val AMOUNT_WITH_UNIT = Regex(
        """([0-9]{1,3}(?:,[0-9]{3})+(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)\s*元"""
    )
    /** 裸金额：整节点必须是纯金额形态（含千分位），混合文本里的数字不收 */
    private val BARE_AMOUNT = Regex("""^([0-9]{1,3}(?:,[0-9]{3})+(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)$""")

    private val POSITIVE_LABELS = listOf("实付", "金额", "收款", "转账金额")
    private val ORDER_LABELS = listOf("订单号", "交易号", "单号")
    private val TIME_LABELS = listOf("时间", "日期")

    data class ScreenAmountHit(
        val amount: Double,
        val nodeIndex: Int,
        val score: Int
    )

    private data class Candidate(
        val amount: Double,
        val nodeIndex: Int,
        val baseScore: Int,
        /** 是否为裸数字候选（无 ¥ 无 元）——时间标签只连坐它 */
        val bare: Boolean
    )

    /**
     * @return 得分最高且 ≥ [MIN_SCORE] 的金额；无合格候选返回 null
     */
    fun extractBest(nodes: List<UiTextNode>): ScreenAmountHit? {
        val candidates = collectCandidates(nodes)
        if (candidates.isEmpty()) return null

        val positiveIdx = labelIndices(nodes, POSITIVE_LABELS)
        val orderIdx = labelIndices(nodes, ORDER_LABELS)
        val timeIdx = labelIndices(nodes, TIME_LABELS)
        val unique = candidates.size == 1

        var best: ScreenAmountHit? = null
        for (c in candidates) {
            var score = c.baseScore
            if (unique) score += SCORE_UNIQUE
            if (nearAny(c.nodeIndex, positiveIdx)) score += SCORE_NEAR_LABEL
            if (nearAny(c.nodeIndex, orderIdx)) score += PENALTY_NEAR_ORDER
            if (c.bare && nearAny(c.nodeIndex, timeIdx)) score += PENALTY_NEAR_TIME
            if (c.bare && integerDigits(c) >= 7) score += PENALTY_BARE_LONG

            if (score >= MIN_SCORE) {
                val hit = ScreenAmountHit(c.amount, c.nodeIndex, score)
                // 平分取节点序靠前的（页面自上而下，金额通常在状态词附近靠前位置）
                if (best == null || hit.score > best.score) best = hit
            }
        }
        return best
    }

    private fun collectCandidates(nodes: List<UiTextNode>): List<Candidate> {
        val result = mutableListOf<Candidate>()
        for (node in nodes) {
            extractFromNode(node.text, node.index)?.let { result += it }
        }
        return result
    }

    /**
     * 单节点提取，优先级：¥ 前缀 > 数字+元 > 整节点裸金额。
     * 同一节点只取最高优先级的一个候选（「实付金额¥32.00」不会同时算出两个）。
     */
    private fun extractFromNode(text: String, index: Int): Candidate? {
        AMOUNT_WITH_PREFIX.find(text)?.let { m ->
            val v = m.groupValues[1].replace(",", "").toDoubleOrNull()
            if (v != null && v > 0.0 && v <= MAX_REASONABLE) {
                return Candidate(v, index, SCORE_PREFIX, bare = false)
            }
        }

        AMOUNT_WITH_UNIT.find(text)?.let { m ->
            val v = m.groupValues[1].replace(",", "").toDoubleOrNull()
            if (v != null && v > 0.0 && v <= MAX_REASONABLE) {
                return Candidate(v, index, SCORE_UNIT, bare = false)
            }
        }

        if (BARE_AMOUNT.matches(text)) {
            val v = text.replace(",", "").toDoubleOrNull()
            if (v != null && v > 0.0 && v <= MAX_REASONABLE) {
                return Candidate(v, index, 0, bare = true)
            }
        }
        return null
    }

    private fun labelIndices(nodes: List<UiTextNode>, labels: List<String>): List<Int> =
        nodes.filter { n -> labels.any { n.text.contains(it) } }.map { it.index }

    private fun nearAny(index: Int, labelIndices: List<Int>): Boolean =
        labelIndices.any { kotlin.math.abs(index - it) <= NEAR_DISTANCE }

    private fun integerDigits(c: Candidate): Int {
        val whole = kotlin.math.abs(c.amount).toLong().toString()
        return whole.length
    }
}
