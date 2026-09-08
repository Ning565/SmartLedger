package com.smartledger.data.db.dao

import androidx.room.*
import com.smartledger.data.db.entity.Budget
import com.smartledger.data.db.entity.CategoryBudget
import kotlinx.coroutines.flow.Flow

/**
 * 预算表 DAO。
 *
 * 刻意**不提供**整行 insert/update：`yearMonth` 上有唯一索引（v2 起），
 * `OnConflictStrategy.REPLACE` 命中唯一索引时会删旧行并触发
 * `category_budgets` 的 `ON DELETE CASCADE`，静默删光该月分类预算。
 * 唯一的写入入口是 [upsertExpenseLimit]（SQLite UPSERT，原子且不碰外键）。
 */
@Dao
interface BudgetDao {

    @Query("SELECT * FROM budgets WHERE yearMonth = :yearMonth LIMIT 1")
    suspend fun getBudgetByMonth(yearMonth: String): Budget?

    @Query("SELECT * FROM budgets WHERE yearMonth = :yearMonth LIMIT 1")
    fun observeBudgetByMonth(yearMonth: String): Flow<Budget?>

    /**
     * 只更新「支出上限 + 来源」，一次写完。
     *
     * 为什么不用「先 getBudgetByMonth 再 insert/update」：
     * 那是两步操作，首页预算卡、预算页、AI 采纳弹窗三个入口都可能并发写，
     * 会出现互相覆盖或插入重复月份行。用 SQLite 的 UPSERT 让数据库保证原子性。
     *
     * 注意 `DO UPDATE` 里**刻意不动 totalIncomeTarget** —— 用户设的收入目标
     * 不该因为改了一次支出上限就被清空。
     *
     * `ON CONFLICT(yearMonth)` 依赖 v2 迁移建好的唯一索引。
     */
    @Query(
        """
        INSERT INTO budgets (yearMonth, totalIncomeTarget, totalExpenseLimit, source, createdAt)
        VALUES (:yearMonth, NULL, :expenseLimit, :source, :createdAt)
        ON CONFLICT(yearMonth) DO UPDATE SET
            totalExpenseLimit = :expenseLimit,
            source = :source
        """
    )
    suspend fun upsertExpenseLimit(
        yearMonth: String,
        expenseLimit: Double?,
        source: String,
        createdAt: Long = System.currentTimeMillis()
    )

    /** 批量读取（历史月份执行情况、AI 报告需要上月预算时用） */
    @Query("SELECT * FROM budgets WHERE yearMonth IN (:yearMonths)")
    suspend fun getBudgetsByMonths(yearMonths: List<String>): List<Budget>

    @Query("SELECT * FROM budgets ORDER BY yearMonth DESC")
    fun observeAllBudgets(): Flow<List<Budget>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategoryBudget(categoryBudget: CategoryBudget): Long

    @Delete
    suspend fun deleteCategoryBudget(categoryBudget: CategoryBudget)

    @Query("SELECT * FROM category_budgets WHERE budgetId = :budgetId")
    fun getCategoryBudgets(budgetId: Long): Flow<List<CategoryBudget>>

    @Query("SELECT * FROM category_budgets WHERE budgetId = :budgetId")
    suspend fun getCategoryBudgetsList(budgetId: Long): List<CategoryBudget>
}
