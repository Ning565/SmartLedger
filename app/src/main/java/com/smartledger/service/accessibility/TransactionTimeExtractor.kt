package com.smartledger.service.accessibility

/**
 * 从页面节点提取交易时间文本（P0-2 + N1 修复，纯函数）。
 *
 * 支付成功页 / 账单详情页通常带交易时间（「交易时间 2026-09-09 14:22:35」）。
 * 把它纳入指纹后，「重开昨天的历史支付页」（时间不变 → 指纹相同 → 去重）
 * 与「今天新的一笔同额交易」（时间变了 → 指纹不同 → 正常记账）可以被区分，
 * 解决终身去重把高频同额交易（每天 8 元早餐 / 3 元地铁）永久吞掉的问题。
 *
 * ## N1：时间是「指纹稳定性」问题，不只是「提取」问题
 *
 * 微信聊天页的时间分隔符恰好是「14:22」（当天）与「9月9日 14:22」（跨天）
 * 形态 —— 与交易时间无法用正则区分。若不加约束地采用：
 * 用户领红包后继续聊天并滚动，分隔符「14:22」滑出、新分隔符「14:35」出现，
 * 同一笔红包的指纹随滚动漂移 → 超过 2 分钟去重窗口后**重复记账**。
 *
 * 因此三级形态的信任度不同：
 * - [FULL_DATETIME]（含年份）：聊天分隔符不含年份，整页搜索即安全；
 * - [MONTH_DAY_TIME] / [TIME_ONLY]：**必须邻近时间标签**（≤2 节点，
 *   或同节点含标签，如「交易时间 14:22」）才认定为交易时间 ——
 *   分隔符、倒计时（23:59:59 后过期）、活动时钟都不满足；
 * - 页面无可用时间（如红包页）→ null，由指纹构建方退化到「按天」
 *   （capturedAt 的 yyyyMMdd）：同天内同商户同金额仍去重，隔天正常。
 */
object TransactionTimeExtractor {

    /** 2026-09-09 14:22:35 / 2026年9月9日 14:22 / 2026/9/9 14:22 */
    private val FULL_DATETIME = Regex(
        """\d{4}[-/年]\d{1,2}[-/月]\d{1,2}日?\s+\d{1,2}:\d{2}(:\d{2})?"""
    )

    /** 9月9日 14:22（微信聊天跨天分隔符也是这个形态 → 必须标签邻近） */
    private val MONTH_DAY_TIME = Regex("""\d{1,2}月\d{1,2}日\s+\d{1,2}:\d{2}""")

    /** 14:22 / 14:22:35（聊天当天分隔符也是这个形态 → 必须标签邻近） */
    private val TIME_ONLY = Regex("""\d{1,2}:\d{2}(:\d{2})?""")

    /** 时间标签词（N1）：月日/纯时间形态必须贴近这些标签才被认定 */
    private val TIME_LABELS = listOf(
        "交易时间", "付款时间", "转账时间", "收款时间", "创建时间", "到账时间", "时间", "日期"
    )

    /** 时间标签与时间文本的允许距离（节点数） */
    private const val TIME_LABEL_MAX_DISTANCE = 2

    /**
     * @return 规范化后的时间文本（仅用于指纹区分，不做绝对时间解析）；
     *         页面无可用时间形态返回 null（调用方退化到按天）
     */
    fun extract(nodes: List<UiTextNode>): String? {
        // 1. 强特征：完整日期时间（含年份）整页搜索 —— 聊天分隔符不含年份
        val pageText = nodes.joinToString(" ") { it.text }
        FULL_DATETIME.find(pageText)?.let { return normalize(it.value) }

        // 2. 弱特征：月日+时间 / 纯时间，只在时间标签邻近时采用（N1）
        val ordered = nodes.sortedBy { it.index }
        for (node in ordered) {
            val weak = MONTH_DAY_TIME.find(node.text) ?: TIME_ONLY.find(node.text) ?: continue
            if (isNearTimeLabel(ordered, node)) {
                return normalize(weak.value)
            }
        }
        return null
    }

    /**
     * 时间文本是否邻近时间标签：同节点含标签（「交易时间 14:22」），
     * 或前后 [TIME_LABEL_MAX_DISTANCE] 节点内存在整节点等于标签词的节点
     * （「交易时间」节点后跟「14:22」节点的典型支付页结构）。
     */
    private fun isNearTimeLabel(ordered: List<UiTextNode>, timeNode: UiTextNode): Boolean {
        // 同节点：标签与时间在一条文本里
        if (TIME_LABELS.any { timeNode.text.contains(it) }) return true

        // 相邻节点：标签是独立节点
        return ordered.any { other ->
            val clean = other.text.trimEnd(':')
            TIME_LABELS.any { clean == it } &&
                kotlin.math.abs(other.index - timeNode.index) <= TIME_LABEL_MAX_DISTANCE
        }
    }

    /** 统一空白形态，避免「14:22」与「14:22 」产生不同指纹 */
    private fun normalize(raw: String): String =
        raw.replace(Regex("\\s+"), " ").trim()
}
