package com.smartledger.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 月度预算。
 *
 * v2 变更（Migration 1→2）：
 *  1. 新增 [source]：区分「用户手工设置」与「采纳 AI 建议」；
 *  2. `yearMonth` 加**唯一索引**。
 *
 * 关于唯一索引：v1 的写入路径（`insertBudget`，已随 v2 删除）用了
 * `OnConflictStrategy.REPLACE`，但 `yearMonth` 上没有唯一约束，
 * SQLite 只会按主键 `id` 判冲突 —— 也就是说 REPLACE 实际上永远不生效，
 * 同一个月可以被插入多行。
 * `getBudgetByMonth(... LIMIT 1)` 掩盖了这个问题（读的时候只取一条），
 * 但「采纳 AI 建议为下月预算」这条新链路会直接命中：并发或多入口写入时
 * 会产生重复月份行，之后读到的额度取决于 id 顺序，行为不可预期。
 * v2 起唯一写入入口是 `upsertExpenseLimit`（UPSERT）；原 REPLACE 方法
 * 已彻底移除 —— 保留它的话，将来一旦被误用，REPLACE 命中唯一索引会删旧行
 * 并触发 `category_budgets` 的 ON DELETE CASCADE，静默删光该月分类预算。
 *
 * 关于 [source] 为什么是**可空**：
 * SQLite 给已有行的表 `ADD COLUMN` 一个 `NOT NULL` 列时必须带 `DEFAULT`，
 * 而 Room 导出的期望 schema 里 `source` 是 `TEXT NOT NULL` 且**无 defaultValue**
 * —— 两边一旦不一致，Room 在打开数据库时会抛
 * `IllegalStateException: Migration didn't properly handle budgets`，
 * 表现为**所有升级用户一启动就崩**。
 *
 * 常规解法是「建新表 → 拷数据 → DROP 旧表 → RENAME」，但 `category_budgets`
 * 对 `budgets(id)` 有 `ON DELETE CASCADE` 外键，DROP 父表在 FK 开启时会
 * **连带删光所有分类预算**，属于不可接受的数据损失。
 *
 * 因此改成可空列：`ALTER TABLE budgets ADD COLUMN source TEXT` 无需 DEFAULT，
 * 产出的 schema 与 Room 期望逐列一致，既不碰 DROP 也不引入默认值歧义。
 * 语义上也正确 —— 老数据为 NULL，[budgetSource] 会把它读成 [BudgetSource.MANUAL]。
 */
@Entity(
    tableName = "budgets",
    indices = [Index(value = ["yearMonth"], unique = true)]
)
data class Budget(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** 形如 "2026-09" */
    val yearMonth: String,

    val totalIncomeTarget: Double? = null,

    /** 支出上限 —— 「本月可用总额度」用的是这个字段，不是 totalIncomeTarget */
    val totalExpenseLimit: Double? = null,

    /**
     * 来源，取 [BudgetSource] 的 name。
     *
     * 可空是为了让迁移能安全地 ADD COLUMN（见类注释）。
     * Kotlin 侧默认给 MANUAL，因此**新写入的行一定非空**；
     * 只有 v1 遗留的老行会是 NULL。
     */
    val source: String? = BudgetSource.MANUAL.name,

    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 类型安全的来源读取，NULL 与脏数据一律回落 MANUAL。
 *
 * 写成扩展属性而不是类体成员：Room 的 @Entity 只把构造参数当列，
 * 类体里再放属性虽然通常会被忽略，但没必要给 KSP 校验留任何不确定性。
 */
val Budget.budgetSource: BudgetSource
    get() = BudgetSource.fromName(source)
