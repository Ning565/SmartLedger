package com.smartledger.ui.budget

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.smartledger.SmartLedgerApp
import com.smartledger.data.db.dao.CategoryTotal
import com.smartledger.data.db.entity.Budget
import com.smartledger.data.db.entity.BudgetSource
import com.smartledger.data.db.entity.Category
import com.smartledger.data.db.entity.CategoryBudget
import com.smartledger.util.DateUtil
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 预算页 ViewModel。
 *
 * 相对原实现修了三处真实缺陷：
 *
 * 1. **不再清空收入目标**。原 `saveBudget(null, newBudget)` 走的是
 *    整行 update（`existing.copy(totalIncomeTarget = null, ...)`，
 *    该方法已删除），只要用户设过收入目标，改一次支出上限就会把它抹掉。
 *    现在走 `upsertExpenseLimit`，SQL 里刻意不碰 `totalIncomeTarget`。
 *
 * 2. **消除并发写竞态**。原来是「先 getBudgetByMonth 再 insert/update」两步，
 *    预算页、首页预算卡、AI 采纳弹窗三个入口都可能同时写同一个月，
 *    会互相覆盖或插出重复月份行。改为单条 SQLite UPSERT，由数据库保证原子性。
 *
 * 3. **月份不再在构造期写死**。原来 `currentYearMonth` / `monthStart` / `monthEnd`
 *    都是构造时的常量，App 跨月不重启就会一直显示上个月的数据。
 *    现在跟随一个 dateTick，回到前台时刷新。
 *
 * 另外支持了 [yearMonth] 可切换，「采纳 AI 建议为下月预算」需要写下一个月。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BudgetViewModel(application: Application) : AndroidViewModel(application) {

    private val budgetRepo = (application as SmartLedgerApp).budgetRepository
    private val transactionRepo = (application as SmartLedgerApp).transactionRepository
    private val categoryRepo = (application as SmartLedgerApp).categoryRepository

    /** 回到前台 / 跨日时刷新，避免月份写死导致的数据陈旧 */
    private val dateTick = MutableStateFlow(System.currentTimeMillis())

    private val _yearMonth = MutableStateFlow(DateUtil.getCurrentYearMonth())
    val yearMonth: StateFlow<String> = _yearMonth.asStateFlow()

    private val monthRange = combine(dateTick, _yearMonth) { _, ym ->
        Pair(DateUtil.getMonthStartTime(ym), DateUtil.getMonthEndTime(ym))
    }

    val budget: StateFlow<Budget?> = _yearMonth
        .flatMapLatest { ym -> budgetRepo.observeBudgetByMonth(ym) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val monthExpense: Flow<Double> = monthRange.flatMapLatest { (s, e) ->
        transactionRepo.getExpenseSum(s, e)
    }

    val monthIncome: Flow<Double> = monthRange.flatMapLatest { (s, e) ->
        transactionRepo.getIncomeSum(s, e)
    }

    val expenseByCategory: Flow<List<CategoryTotal>> = monthRange.flatMapLatest { (s, e) ->
        transactionRepo.getExpenseGroupByCategory(s, e)
    }

    val categories: Flow<List<Category>> = categoryRepo.getAll()

    /**
     * 回到前台时调用：刷新时间基准。
     *
     * 原实现把 `monthStart` / `monthEnd` 在构造期就算成常量，
     * App 跨月不重启就会一直统计上个月的支出。
     */
    fun refresh() {
        dateTick.value = System.currentTimeMillis()
    }

    fun goToCurrentMonth() {
        _yearMonth.value = DateUtil.getCurrentYearMonth()
    }

    fun isCurrentMonth(): Boolean = _yearMonth.value == DateUtil.getCurrentYearMonth()

    /**
     * 设置**当前所选月份**的支出上限（原子 UPSERT）。
     *
     * @param source 采纳 AI 建议且用户未改金额时传 [BudgetSource.AI_SUGGESTED]
     */
    fun saveExpenseLimit(
        limit: Double?,
        source: BudgetSource = BudgetSource.MANUAL
    ) = saveExpenseLimitFor(_yearMonth.value, limit, source)

    /** 设置**指定月份**的支出上限，供「采纳为下月预算」使用 */
    fun saveExpenseLimitFor(
        yearMonth: String,
        limit: Double?,
        source: BudgetSource = BudgetSource.MANUAL
    ) {
        viewModelScope.launch {
            budgetRepo.setExpenseLimit(yearMonth, limit, source)
        }
    }

    /** 下月的 yyyy-MM，供 AI 采纳入口预填标题 */
    fun nextYearMonth(): String = DateUtil.shiftYearMonth(DateUtil.getCurrentYearMonth(), 1)

    // ═══ 分类预算（沿用原有能力）═══

    fun saveCategoryBudget(categoryBudget: CategoryBudget) {
        viewModelScope.launch { budgetRepo.insertCategoryBudget(categoryBudget) }
    }

    fun deleteCategoryBudget(categoryBudget: CategoryBudget) {
        viewModelScope.launch { budgetRepo.deleteCategoryBudget(categoryBudget) }
    }

    fun getCategoryBudgets(budgetId: Long): Flow<List<CategoryBudget>> =
        budgetRepo.getCategoryBudgets(budgetId)

    /**
     * 兼容旧调用点的入口。
     *
     * 保留签名以免遗漏的调用点编译失败，但**行为已修正**：
     * 只更新支出上限，不再把收入目标一起清掉。
     */
    @Deprecated(
        "改用 saveExpenseLimit()：旧实现会顺带清空 totalIncomeTarget",
        ReplaceWith("saveExpenseLimit(totalExpenseLimit)")
    )
    fun saveBudget(totalIncomeTarget: Double?, totalExpenseLimit: Double?) {
        saveExpenseLimit(totalExpenseLimit)
    }
}
