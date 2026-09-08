package com.smartledger.data.analytics

import com.smartledger.data.analytics.model.MerchantStat

/**
 * 商户脱敏。
 *
 * 上传给 AI 之前，把真实商户名替换成「分类商户 #序号」：
 *
 * ```
 * 瑞幸咖啡科技园店  →  餐饮商户 #1
 * 麦当劳南山店      →  餐饮商户 #2
 * 淘宝 XX 数码旗舰店 →  购物商户 #1
 * ```
 *
 * 这样 AI 仍然能看出「同一家店消费了 12 次共 326 元」这种**行为模式**
 * （这对消费诊断是核心信息），但拿不到任何可定位到用户真实生活的标识。
 *
 * 安全红线：
 *  - **不做部分打码**（「瑞*咖啡」这种）。部分打码反而更容易被还原，
 *    而且会让 AI 产生「这是一家咖啡店」的具体联想，等于泄漏了一半。
 *  - 编号只在单次调用内有意义，**不持久化映射表** ——
 *    否则就等于在本地又存了一份「匿名编号 ↔ 真实商户」的对照，
 *    脱敏形同虚设。
 */
object MerchantAnonymizer {

    /** 分类名缺失时使用的兜底前缀 */
    private const val UNKNOWN_PREFIX = "其他"

    data class MerchantRow(
        val merchant: String,
        val amount: Double,
        val count: Int,
        /** 该商户最主要的分类名，可为空 */
        val categoryName: String?
    )

    /**
     * @param rows 建议按金额降序传入，这样 #1 总是消费最多的那家
     * @return 与输入同序的脱敏结果
     */
    fun anonymize(rows: List<MerchantRow>): List<MerchantStat> {
        if (rows.isEmpty()) return emptyList()

        // 同一真实商户名映射到同一编号
        val assigned = HashMap<String, String>(rows.size)
        // 每个分类前缀各自的计数器
        val counters = HashMap<String, Int>()
        val out = ArrayList<MerchantStat>(rows.size)

        rows.forEach { row ->
            val key = normalizeKey(row.merchant)
            if (key.isEmpty()) return@forEach

            val existing = assigned[key]
            if (existing != null) {
                out += MerchantStat(existing, row.amount, row.count)
                return@forEach
            }

            val prefix = sanitizeCategory(row.categoryName)
            val next = (counters[prefix] ?: 0) + 1
            counters[prefix] = next

            // 必须写 ${prefix}：Kotlin 的标识符允许 CJK 字符，
            // "$prefix商户" 会被当成一个叫 prefix商户 的变量而编译失败。
            val anon = "${prefix}商户 #$next"
            assigned[key] = anon
            out += MerchantStat(anon, row.amount, row.count)
        }
        return out
    }

    /**
     * 归一化商户名作为映射键。
     *
     * **必须公开**：[SummaryAggregator] 分组时要用同一套归一化规则。
     * 两边不一致会产生真 bug：聚合层把「瑞幸咖啡 X」与「瑞幸咖啡X」
     * 当成两组，而匿名层又把它们归为同一编号，
     * 结果 Prompt 里出现**两行同名的「餐饮商户 #1」**。
     *
     * **去掉全部空白**（而不是只折叠成单个空格）：
     * 自动记账的商户名来自通知文本解析，经常带多余空格，
     * 「瑞幸咖啡 科技园店」与「瑞幸咖啡科技园店」、「7 ELEVEN」与「7ELEVEN」
     * 几乎必然是同一家。合并它们能得到更准确的高频商户排名（这是这个榜单的全部价值）。
     * 两个**真的不同**的商户仅靠空格位置区分的概率几乎为零，因此过度合并风险可忽略。
     *
     * 但**不做**模糊匹配（例如去掉「科技园店」后缀、去掉括号内容），
     * 那属于猜测：猜错会把两家不同店的消费合成一条，污染统计，
     * 而且无法向用户解释。宁可分得细一点。
     */
    fun normalizeKey(raw: String?): String = raw?.replace(WS, "").orEmpty()

    private val WS = Regex("\\s+")

    /**
     * 分类名清洗：只允许安全字符进入匿名前缀。
     *
     * 分类名是用户可自定义的，理论上可能含数字甚至商户名片段
     * （例如用户建了个分类叫「星巴克」）。这里限制为中文/字母，
     * 并截断长度，避免用户自定义分类名把敏感信息带进 Prompt。
     */
    private fun sanitizeCategory(name: String?): String {
        if (name.isNullOrBlank()) return UNKNOWN_PREFIX
        val cleaned = name.filter { it.isLetter() }.take(6)
        return cleaned.ifBlank { UNKNOWN_PREFIX }
    }
}
