package com.smartledger.data.repository

import com.smartledger.data.db.dao.BudgetDao
import com.smartledger.data.db.entity.Budget
import com.smartledger.data.db.entity.BudgetSource
import com.smartledger.data.db.entity.CategoryBudget
import kotlinx.coroutines.flow.Flow

class BudgetRepository(private val dao: BudgetDao) {

    suspend fun getBudgetByMonth(yearMonth: String): Budget? = dao.getBudgetByMonth(yearMonth)

    fun observeBudgetByMonth(yearMonth: String): Flow<Budget?> = dao.observeBudgetByMonth(yearMonth)

    /**
     * 设置某月的「可用总额度」（支出上限），原子写入。
     *
     * 走 UPSERT 而不是「先查再插/改」：首页预算卡、预算页、AI 采纳弹窗
     * 三个入口都可能写同一个月，两步操作会互相覆盖或插出重复月份行。
     *
     * @param source 采纳 AI 建议且用户**未改金额**时传 [BudgetSource.AI_SUGGESTED]；
     *               改过金额说明那已经是用户自己的决定，应传 MANUAL
     */
    suspend fun setExpenseLimit(
        yearMonth: String,
        expenseLimit: Double?,
        source: BudgetSource = BudgetSource.MANUAL
    ) = dao.upsertExpenseLimit(yearMonth, expenseLimit, source.name)

    suspend fun getBudgetsByMonths(yearMonths: List<String>): List<Budget> =
        dao.getBudgetsByMonths(yearMonths)

    fun observeAllBudgets(): Flow<List<Budget>> = dao.observeAllBudgets()

    suspend fun insertCategoryBudget(categoryBudget: CategoryBudget): Long =
        dao.insertCategoryBudget(categoryBudget)

    suspend fun deleteCategoryBudget(categoryBudget: CategoryBudget) =
        dao.deleteCategoryBudget(categoryBudget)

    fun getCategoryBudgets(budgetId: Long): Flow<List<CategoryBudget>> =
        dao.getCategoryBudgets(budgetId)

    suspend fun getCategoryBudgetsList(budgetId: Long): List<CategoryBudget> =
        dao.getCategoryBudgetsList(budgetId)
}
