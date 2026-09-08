package com.smartledger.data.db.entity

/**
 * 预算来源。
 *
 * 只有两种，且 **AI 永远不能自动写入**：
 *  - [MANUAL]       用户在预算页 / 首页预算卡手工设置
 *  - [AI_SUGGESTED] 用户在 AI 消费体检报告里点「采纳为下月预算」并二次确认，
 *                   且**未修改金额**（改过金额说明那已经是用户自己的决定，记 MANUAL）
 */
enum class BudgetSource {
    MANUAL,
    AI_SUGGESTED;

    companion object {
        /** 安全解析：脏数据一律回落 MANUAL，不让预算页崩 */
        fun fromName(name: String?): BudgetSource =
            entries.firstOrNull { it.name == name } ?: MANUAL
    }
}
