package com.smartledger.data.ai.model

/**
 * 分类引用（脱离 Room 的轻量结构）。
 *
 * 为什么不让 [com.smartledger.data.ai.AiTransactionParser] 直接收 `Category` 实体：
 *  1. `Category` 带 Room 注解，纯 JVM 单测的 classpath 上没有 room-common，
 *     一旦引用就测不了 —— 而解析器的校验规则恰恰是最需要单测的部分；
 *  2. 解析器根本不需要 icon / color / sortOrder，让它依赖整个实体是多余的耦合。
 *
 * ViewModel 侧一行 `categories.map { CategoryRef(it.id, it.name, it.type) }` 即可转换。
 */
data class CategoryRef(
    val id: Long,
    val name: String,
    /** "expense" / "income" */
    val type: String
)

/**
 * AI 解析出的交易草稿。
 *
 * **每个字段都可空**：AI 认不出来就留 null，由 UI 决定是保留用户已填内容
 * 还是提示「未识别」。绝不用「猜一个默认值」来填满，
 * 那会让用户误以为 AI 认出来了，反而记出错账。
 */
data class TransactionDraft(
    val amount: Double?,
    /** 已归一为小写 "expense" / "income" */
    val type: String?,
    /** 已映射为本地分类表的 id；映射不到为 null */
    val categoryId: Long?,
    /** 映射成功时回填分类名，供 UI 展示「已填入：餐饮」 */
    val categoryName: String?,
    val channel: String?,
    val merchant: String?,
    val note: String?,
    /** 由 date + time 合成的毫秒时间戳 */
    val transactionTime: Long?,
    /** 未能识别的字段中文名，用于「部分信息无法识别」提示 */
    val missingFields: List<String> = emptyList()
) {
    /** 只有金额可用才算解析成功 —— 没金额的账单没有意义 */
    val isUsable: Boolean get() = amount != null && amount > 0.0

    /** 完全没缺字段 */
    val isComplete: Boolean get() = missingFields.isEmpty()

    companion object {
        const val TYPE_EXPENSE = "expense"
        const val TYPE_INCOME = "income"

        // missingFields 用的中文名，与 RecordScreen 表单字段一一对应
        const val FIELD_AMOUNT = "金额"
        const val FIELD_TYPE = "收支类型"
        const val FIELD_CATEGORY = "分类"
        const val FIELD_CHANNEL = "支付渠道"
        const val FIELD_MERCHANT = "商户"
        const val FIELD_DATE = "日期"
    }
}
