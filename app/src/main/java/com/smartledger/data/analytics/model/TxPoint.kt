package com.smartledger.data.analytics.model

/**
 * 聚合用的轻量投影。
 *
 * 只取统计需要的 5 列，**刻意不取 note / notificationKey / paymentMethod**：
 *  - note 是用户备注原文，属于禁止上传的敏感内容；
 *  - notificationKey 是通知去重键，含包名与通知 tag；
 *  - 少取列也少占内存。
 *
 * 这样「不该出现在 Prompt 里的字段」在取数阶段就不存在，
 * 靠类型设计而不是靠开发者自觉来做脱敏。
 */
data class TxPoint(
    val amount: Double,
    /** "expense" / "income"，与 transactions.type 一致（小写） */
    val type: String,
    val categoryId: Long?,
    val merchant: String?,
    val transactionTime: Long
)
