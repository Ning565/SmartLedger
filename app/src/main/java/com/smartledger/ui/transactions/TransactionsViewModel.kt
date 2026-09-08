package com.smartledger.ui.transactions

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.smartledger.SmartLedgerApp
import com.smartledger.data.db.entity.Category
import com.smartledger.data.db.entity.Transaction
import com.smartledger.service.SmartCategorizer
import com.smartledger.util.DateUtil
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 收支筛选。流水页用轻量 Chip 呈现。 */
enum class TxTypeFilter(val label: String) {
    ALL("全部"),
    EXPENSE("支出"),
    INCOME("收入");

    val dbValue: String?
        get() = when (this) {
            ALL -> null
            EXPENSE -> "expense"
            INCOME -> "income"
        }

    companion object {
        fun fromName(name: String?): TxTypeFilter =
            entries.firstOrNull { it.name == name } ?: ALL
    }
}

/**
 * 流水页 ViewModel。
 *
 * 与 HomeViewModel 刻意分开：首页展示「当月概览 + 最近交易」，
 * 流水页是**完整的按月账单列表**，带收支筛选。
 * 两者都订阅 Room Flow，互不影响。
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class TransactionsViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        /** 与 HomeViewModel 一致：合并短时间多次 Room 失效，减轻删改后的卡顿 */
        private const val UI_DEBOUNCE_MS = 120L
    }

    private val repo = (application as SmartLedgerApp).transactionRepository
    private val categoryRepo = (application as SmartLedgerApp).categoryRepository

    /** 跨日打开仍刷新当月区间 */
    private val dateTick = MutableStateFlow(System.currentTimeMillis())

    private val _selectedYearMonth = MutableStateFlow(DateUtil.getCurrentYearMonth())
    val selectedYearMonth: StateFlow<String> = _selectedYearMonth.asStateFlow()

    private val _typeFilter = MutableStateFlow(TxTypeFilter.ALL)
    val typeFilter: StateFlow<TxTypeFilter> = _typeFilter.asStateFlow()

    /** 当月全部交易（未筛选），用于算小计与判断空态 */
    private val monthTransactions = combine(dateTick, _selectedYearMonth) { _, ym -> ym }
        .flatMapLatest { ym ->
            repo.getByTimeRange(DateUtil.getMonthStartTime(ym), DateUtil.getMonthEndTime(ym))
        }
        .debounce(UI_DEBOUNCE_MS)
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 按筛选条件过滤后的列表 */
    val transactions: StateFlow<List<Transaction>> =
        combine(monthTransactions, _typeFilter) { list, filter ->
            val v = filter.dbValue
            if (v == null) list else list.filter { it.type == v }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val monthExpense: StateFlow<Double> = combine(dateTick, _selectedYearMonth) { _, ym -> ym }
        .flatMapLatest { ym ->
            repo.getExpenseSum(DateUtil.getMonthStartTime(ym), DateUtil.getMonthEndTime(ym))
        }
        .debounce(UI_DEBOUNCE_MS)
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0.0)

    val monthIncome: StateFlow<Double> = combine(dateTick, _selectedYearMonth) { _, ym -> ym }
        .flatMapLatest { ym ->
            repo.getIncomeSum(DateUtil.getMonthStartTime(ym), DateUtil.getMonthEndTime(ym))
        }
        .debounce(UI_DEBOUNCE_MS)
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0.0)

    val categories: StateFlow<List<Category>> = categoryRepo.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun refreshDateRange() {
        dateTick.value = System.currentTimeMillis()
    }

    fun setTypeFilter(filter: TxTypeFilter) {
        _typeFilter.value = filter
    }

    fun previousMonth() {
        _selectedYearMonth.value = DateUtil.shiftYearMonth(_selectedYearMonth.value, -1)
    }

    fun nextMonth() {
        val next = DateUtil.shiftYearMonth(_selectedYearMonth.value, 1)
        // 不允许翻到未来月，与首页一致
        if (next <= DateUtil.getCurrentYearMonth()) {
            _selectedYearMonth.value = next
        }
    }

    fun goToCurrentMonth() {
        _selectedYearMonth.value = DateUtil.getCurrentYearMonth()
    }

    fun updateTransaction(transaction: Transaction) {
        viewModelScope.launch { repo.update(transaction) }
    }

    fun deleteTransaction(transaction: Transaction) {
        viewModelScope.launch { repo.delete(transaction) }
    }

    /**
     * 修改分类，并把「商户 → 分类」写进 SmartCategorizer。
     *
     * 这是原 HomeViewModel 已有的行为，保持一致：用户手动纠正过一次分类后，
     * 之后同名商户的自动记账就能直接归对类。
     */
    fun updateCategory(transaction: Transaction, categoryId: Long?) {
        viewModelScope.launch {
            repo.update(transaction.copy(categoryId = categoryId))
            if (categoryId != null && !transaction.merchant.isNullOrBlank()) {
                SmartCategorizer.saveMerchantCategory(
                    getApplication(), transaction.merchant, categoryId
                )
            }
        }
    }

    fun getCategoryName(categoryId: Long?, categories: List<Category>): String =
        categories.find { it.id == categoryId }?.name ?: "未分类"

    fun getCategoryColor(categoryId: Long?, categories: List<Category>): Long? =
        categories.find { it.id == categoryId }?.color
}
