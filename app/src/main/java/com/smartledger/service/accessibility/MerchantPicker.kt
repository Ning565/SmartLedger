package com.smartledger.service.accessibility

/**
 * 商户提取（方案 5.6 + P1-4 修复，纯函数）。
 *
 * 商户不是自动入账的必填条件：识别不出就返回 null，
 * 照常入账（「微信支出 ¥32.00」），后续可人工补。
 *
 * 两级策略（P1-4）：
 * 1. **标签-值结构**：支付成功页是「标签： 值」交替排版
 *    （`收款方 / 某某便利店`、`付款方式 / 零钱`）。若金额附近出现已知标签
 *    （「收款方」「付款方」等），取它**紧邻的下一个节点**作为值 ——
 *    这才是支付页的真实语义结构，比按距离猜可靠得多。
 * 2. **距离回退**：无标签结构时，取金额前后 [SCAN_DISTANCE] 节点里
 *    未被排除且距离最近的文本。
 *
 * 排除规则：状态/操作/标签词、金额与时间形态、长度 2~16 之外、中文日期。
 * 商户名参与指纹计算（P0-2），提取稳定性直接影响去重正确性。
 */
object MerchantPicker {

    private const val SCAN_DISTANCE = 4
    private const val MIN_NAME_LEN = 2
    private const val MAX_NAME_LEN = 16

    /** 方案 5.6 排除词 + P1-4 补充的支付页标签词（用 contains 判断） */
    private val EXCLUDED_WORDS = listOf(
        "支付成功", "转账成功", "交易成功", "收款成功", "已收款", "已存入零钱",
        "红包已领取", "已领取", "付款方式", "零钱", "银行卡", "完成", "返回",
        "查看账单", "交易详情", "付款成功", "退款成功", "当前心情",
        "微信支付", "支付宝", "收钱码", "付款码", "转账", "红包",
        // P1-4：支付结果页的「标签： 值」结构里的标签词（review 实测漏网）
        "收款方", "付款方", "交易时间", "账单详情",
        "支付方式", "当前状态", "转账时间", "收款时间", "转账留言",
        "商户单号", "创建时间", "付款时间"
    )

    /**
     * 通用词标签（P1-4 次要修复）：只做**整节点相等**判断。
     * 「商品」「订单」「备注」这类词用 contains 会误杀真实商户名
     * （商品街便利店 / 订单来了餐厅）；只有整节点恰好是标签时才是标签。
     */
    private val GENERIC_LABELS = setOf(
        "商品", "商品说明", "订单", "订单号", "订单名称", "备注"
    )

    /**
     * 标签-值结构识别（P1-4）：这些词**整节点**出现时，
     * 紧邻的下一个节点就是它对应的值（商户名/收款人）。
     */
    private val VALUE_LABELS = setOf(
        "收款方", "付款方", "商户", "对方", "收款人", "转账给", "收款账户", "商家"
    )

    /**
     * **反义标签**（N4）：这些标签的**值不是商户名**，而是支付方式 / 时间等，
     * 其紧邻的下一个节点整体排除。
     *
     * 为什么不靠堆词表：支付方式名称无穷无尽
     * （余额 / 花呗 / 余额宝 / 招商银行信用卡(1234) / 数字人民币 / 网商银行 …），
     * 逐个补词是打地鼠，且新出现的名称必然漏网。改成「这些标签后面的值
     * 一律不要」后，**无论值叫什么名字都自动排除**。
     *
     * 与 [EXCLUDED_WORDS] 的 contains 判断**互补**，覆盖两种页面结构：
     * - 标签与值同节点（「付款方式 余额」）→ contains 命中「付款方式」，整节点排除；
     * - 标签与值**分**节点（本清单负责）→ 排除值所在的那个节点。
     *
     * 注：与 [VALUE_LABELS] 无交集，且两者都是**整节点相等**判断，
     * 因此「付款方」（取值）与「付款方式」（排除值）不会混淆。
     */
    private val ANTI_VALUE_LABELS = setOf(
        "付款方式", "支付方式", "付款渠道", "扣款方式",
        "交易时间", "付款时间", "创建时间", "到账时间", "转账时间", "收款时间"
    )

    /**
     * 营销文案形态（P1-4，review 实测「满200元可用」漏网）：
     * 「满 X 元（可）用」「满 X 减 Y」「立减」「券后」「X 折」——
     * 含数字与营销词的混合文本不是商户名。
     */
    private val MARKETING_PATTERN = Regex(
        """满\d+(?:\.\d+)?元?[^\s]{0,4}(?:可用|使用|减\d+)|满\d+减\d+|立减\d*|券后|\d+折"""
    )

    /** 操作按钮文案（P1-4）：页面操作项不是商户名 */
    private val ACTION_WORDS = listOf("立即使用", "点击参与", "立即领取", "去使用", "去看看")

    /**
     * @return 商户名；无法稳定识别返回 null
     */
    fun pick(nodes: List<UiTextNode>, amountNodeIndex: Int): String? {
        // 显式按 index 排序：标签-值结构依赖「下一节点」语义，
        // 不依赖调用方传入顺序的隐式契约（P1-4 次要修复）
        val ordered = nodes.sortedBy { it.index }

        // 1. 标签-值结构优先（P1-4）：只认金额前后 4 节点窗口内的标签，
        //    避免抓到页面底部无关的标签行
        val window = ordered.filter {
            it.index != amountNodeIndex &&
                kotlin.math.abs(it.index - amountNodeIndex) <= SCAN_DISTANCE
        }

        // N4：反义标签的「值」节点，下面两条路径都要跳过
        // （商户名参与指纹计算，取错值不只是显示难看，还会影响去重稳定性）
        val blocked = antiValueIndexes(ordered, amountNodeIndex)

        for ((i, node) in window.withIndex()) {
            val clean = node.text.trimEnd(':')
            if (clean in VALUE_LABELS) {
                val next = window.getOrNull(i + 1) ?: ordered.firstOrNull { it.index == node.index + 1 }
                next?.let {
                    if (it.index in blocked) return@let
                    val value = it.text.trimEnd(':')
                    if (isPlausibleMerchant(value)) return value
                }
            }
        }

        // 2. 距离回退：金额附近未排除且最近的文本
        val sorted = ordered.sortedBy { kotlin.math.abs(it.index - amountNodeIndex) }
        for (node in sorted) {
            if (node.index == amountNodeIndex) continue
            if (kotlin.math.abs(node.index - amountNodeIndex) > SCAN_DISTANCE) break
            if (node.index in blocked) continue
            if (isPlausibleMerchant(node.text)) return node.text
        }
        return null
    }

    /**
     * 收集「反义标签的值」所在节点的 index（N4）。
     *
     * 值取标签**紧邻的下一个节点**（index + 1），与 [VALUE_LABELS] 的取值语义对称。
     * 同样限制在金额附近 [SCAN_DISTANCE] 内，避免页面底部的无关标签行误伤。
     */
    private fun antiValueIndexes(ordered: List<UiTextNode>, amountNodeIndex: Int): Set<Int> {
        val blocked = mutableSetOf<Int>()
        for (node in ordered) {
            if (kotlin.math.abs(node.index - amountNodeIndex) > SCAN_DISTANCE) continue
            if (node.text.trimEnd(':') !in ANTI_VALUE_LABELS) continue
            ordered.firstOrNull { it.index == node.index + 1 }?.let { blocked += it.index }
        }
        return blocked
    }

    /** 排除词 / 形态 / 长度综合判定 */
    private fun isPlausibleMerchant(text: String): Boolean {
        if (text.length !in MIN_NAME_LEN..MAX_NAME_LEN) return false
        if (EXCLUDED_WORDS.any { text.contains(it) }) return false
        // 通用词标签：整节点相等才算标签（「商品街便利店」不是「商品」标签）
        if (text.trimEnd(':') in GENERIC_LABELS) return false
        if (ACTION_WORDS.any { text.contains(it) }) return false
        if (MARKETING_PATTERN.containsMatchIn(text)) return false
        if (looksLikeAmountOrTime(text)) return false
        // 中文日期形态（2026年9月9日 / 9月9日）
        if (text.contains("年") && text.contains("月")) return false
        return true
    }

    /**
     * 纯金额 / 数字 / 时间 / 日期形态：
     * 「¥32.00」「500元」「12:30」「2026-09-09」这类
     * 全部由数字与标点构成的文本不是商户名。
     */
    private fun looksLikeAmountOrTime(text: String): Boolean {
        return text.all {
            it.isDigit() || it == '¥' || it == '.' || it == ',' ||
                it == ':' || it == '-' || it == '/' || it == '元' || it == ' '
        }
    }
}
