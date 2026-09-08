package com.smartledger.ui.record

/**
 * 记账页表单状态。
 *
 * ## 为什么必须做状态提升
 *
 * 原实现里 6 个字段全是 Composable 内部的 `remember`，**外部没有任何写入口**，
 * 因此「AI 解析后回填表单」在结构上根本做不到。
 * 同时原实现**没有日期字段** —— 界面上那个「9月7日」是 Composable 里
 * 直接 `Calendar.getInstance()` 渲染出来的死值，
 * `saveTransaction()` 也没有时间参数，硬编码 `System.currentTimeMillis()`，
 * 所以 AI 解析出的「昨天」即使拿到了也存不进去。
 *
 * 提升到 ViewModel 之后：
 *  - AI 草稿可以整体回填；
 *  - 日期成为一等字段，支持补记历史账单；
 *  - 附带收益：横竖屏切换、后台回前台时表单内容不再丢失。
 */
data class RecordUiState(
    /** "expense" / "income"，与 transactions.type 一致（小写） */
    val transactionType: String = TYPE_EXPENSE,
    /** 金额文本，与数字键盘共用；初始 "0" 与原实现一致 */
    val amountText: String = "0",
    val selectedCategoryId: Long? = null,
    val merchant: String = "",
    val note: String = "",
    /** 保留上次渠道，连续记账更省事（原有行为） */
    val paymentMethod: String = DEFAULT_CHANNEL,
    /** 交易发生时间；默认「今天 + 当前时刻」 */
    val transactionTime: Long = System.currentTimeMillis()
) {
    val isExpense: Boolean get() = transactionType == TYPE_EXPENSE

    /** 金额是否可保存（与原实现的静默判断一致，但改为显式以便给出提示） */
    val amountValue: Double? get() = amountText.toDoubleOrNull()?.takeIf { it > 0 }

    companion object {
        const val TYPE_EXPENSE = "expense"
        const val TYPE_INCOME = "income"
        const val DEFAULT_CHANNEL = "微信"

        /** 金额上限，与 AmountKeypad 的 9 位整数上限保持一致 */
        const val MAX_AMOUNT = 1_000_000.0
    }
}

/**
 * 自然语言 / 语音解析的 UI 状态。
 *
 * [Hidden] 单独存在的原因：AI 未配置时**不显示**这个入口，
 * 而不是显示一个点了就报错的按钮。
 */
sealed interface NlParseState {
    data object Hidden : NlParseState
    data object Idle : NlParseState
    data object Running : NlParseState

    /**
     * @param summary  「昨天 · 餐饮 · 老乡鸡 · ¥32 · 微信」这类回填摘要
     * @param missing  未识别的字段名；为空表示全部识别
     */
    data class Filled(val summary: String, val missing: List<String>) : NlParseState

    data class Failed(val message: String) : NlParseState
}
