package com.smartledger.service.accessibility

import com.smartledger.service.ParseConfidence
import com.smartledger.service.ParsedPayment

/**
 * 无障碍支付页面解析器接口（方案 5.7）。
 *
 * 实现必须是 object + 纯函数：只吃 [UiSnapshot]（纯数据），
 * 不依赖任何 Android API —— 这是能被纯 JVM 单测的前提（方案 6.4）。
 */
interface AccessibilityPaymentParser {
    fun parse(snapshot: UiSnapshot): ParsedPayment?
}

/**
 * 两个 Parser 的公共识别框架。
 *
 * 判定顺序是**刻意的**，任何一步不过就返回 null（方案 5：解析不确定时
 * 不要猜）：
 *
 * ```
 * 1. 排除词整页检查（C3）      待收款 / 对方已收款 / 已退还 …
 *    —— 必须先于状态词：「对方已收款」包含「已收款」，
 *       付款方视角的历史聊天绝不能记成自己的收入
 * 2. 状态词命中（expense / income 两张词表）
 * 3. 金额提取（ScreenAmountExtractor 打分制）
 * 4. 状态词-金额邻近性（M1）    距离 ≤ STATUS_AMOUNT_MAX_DISTANCE
 *    —— 历史聊天里「已收款」旧消息与新消息金额相距很远
 * 5. 方向唯一性：邻近的状态词同时含支出与收入语义 → 歧义 → null
 * 6. 商户提取（MerchantPicker，可空）
 * ```
 */
abstract class BasePaymentParser : AccessibilityPaymentParser {

    protected abstract val paymentMethod: String

    /** 命中即判定为支出的状态词 */
    protected abstract val expenseWords: List<String>

    /** 命中即判定为收入的状态词 */
    protected abstract val incomeWords: List<String>

    /** 整页出现任一词即放弃解析（C3 排除词表：交易未完成类） */
    protected abstract val excludedWords: List<String>

    /**
     * 收入方向的营销排除词（P0-3：积分/优惠券/理财等，N2：邻近性判断）。
     *
     * 只拦「收入」不拦「支出」：支付成功页常带「本单获得 X 积分」提示，
     * 若整页排除会误杀真实支出。
     *
     * N2 修复：营销词命中节点必须**邻近状态词命中节点**（≤2 节点）才判定为
     * 营销页 —— 收款成功页底部的积分/会员横幅离「收款成功」很远，不该连坐；
     * 而「积分已到账」这类页面营销词与状态词同节点或紧邻。
     */
    protected open val incomeExcludedWords: List<String> = emptyList()

    /**
     * 收入方向的**视角排除词**（真机校准 9/10，整页判断、无邻近约束）。
     *
     * 微信转出方的转账详情页在对方收款后状态变为「已收款」——
     * 「已收款」是收入词，但这是「我的转出被收到」，绝不能记成收入。
     * 页面上的「你发起了一笔转账」是转出方视角的铁证特征：
     * 只要它在页面上，income 方向一律拒绝（转出已在「支付成功」时记过）。
     */
    protected open val incomeViewExcludedWords: List<String> = emptyList()

    final override fun parse(snapshot: UiSnapshot): ParsedPayment? {
        val nodes = snapshot.nodes
        if (nodes.isEmpty()) return null

        // 1. 排除词优先（C3）
        if (nodes.any { n -> excludedWords.any { n.text.contains(it) } }) return null

        // 2. 状态词命中
        data class StatusHit(val word: String, val nodeIndex: Int, val type: String)

        val hits = buildList {
            nodes.forEach { node ->
                expenseWords.forEach { w -> if (node.text.contains(w)) add(StatusHit(w, node.index, "expense")) }
                incomeWords.forEach { w -> if (node.text.contains(w)) add(StatusHit(w, node.index, "income")) }
            }
        }
        if (hits.isEmpty()) return null

        // 3. 金额（打分制，失败即放弃 —— 「红包已领取」无金额不入账）。
        // 真机校准（9/10）：状态词命中索引起到裸数字加分作用 ——
        // 微信收款页「您已收款/0.01/零钱余额/123.45」里 0.01 紧贴状态词，
        // 余额数字不会，这是两者的关键区分器
        val statusIndexes = hits.map { it.nodeIndex }.toSet()
        val amountHit = ScreenAmountExtractor.extractBest(nodes, statusIndexes) ?: return null

        // 4. 邻近性（M1）：状态词必须贴近金额
        val nearHits = hits.filter {
            kotlin.math.abs(it.nodeIndex - amountHit.nodeIndex) <= STATUS_AMOUNT_MAX_DISTANCE
        }
        if (nearHits.isEmpty()) return null

        // 5. 方向唯一性
        val types = nearHits.map { it.type }.distinct()
        if (types.size != 1) return null
        val type = types.first()

        // 5.5 收入方向的营销排除（P0-3 + N2 邻近性）：
        // 「积分已到账 ¥20」里营销词与状态词同节点 → 排除；
        // 「收款成功 ¥100」+ 页面底部「本单获得5积分」横幅（距离远） → 正常记账
        if (type == "income" && incomeExcludedWords.isNotEmpty()) {
            val statusIndexes = nearHits.map { it.nodeIndex }
            val marketingNearStatus = nodes.any { n ->
                incomeExcludedWords.any { w -> n.text.contains(w) } &&
                    statusIndexes.any { s -> kotlin.math.abs(n.index - s) <= MARKETING_STATUS_MAX_DISTANCE }
            }
            if (marketingNearStatus) return null
        }

        // 5.6 收入方向的视角排除（真机校准 9/10）：整页含转出方视角词时，
        // income 一律拒绝 —— 对方收款后我再看的详情页显示「已收款」，
        // 但「你发起了一笔转账」证明这是我的转出而非我的收入
        if (type == "income" && incomeViewExcludedWords.isNotEmpty()) {
            val pageText = nodes.joinToString(" ") { it.text }
            if (incomeViewExcludedWords.any { pageText.contains(it) }) return null
        }

        // 6. 商户（可空）
        val merchant = MerchantPicker.pick(nodes, amountHit.nodeIndex)

        val amountNode = nodes.firstOrNull { it.index == amountHit.nodeIndex }
        val snippet = buildString {
            append(nearHits.first().word)
            amountNode?.let { append(" ").append(it.text) }
        }

        return ParsedPayment(
            amount = amountHit.amount,
            merchant = merchant,
            paymentMethod = paymentMethod,
            // 由 Service 层用 AccessibilityFingerprintBuilder 生成的 a11y: 前缀 key 回填
            notificationKey = "",
            type = type,
            confidence = ParseConfidence.HIGH,
            rawSnippet = snippet
        )
    }

    companion object {
        /** 状态词与金额允许的最大节点距离（M1） */
        const val STATUS_AMOUNT_MAX_DISTANCE = 4

        /** 营销词与状态词的邻近判定距离（N2） */
        const val MARKETING_STATUS_MAX_DISTANCE = 2
    }
}
