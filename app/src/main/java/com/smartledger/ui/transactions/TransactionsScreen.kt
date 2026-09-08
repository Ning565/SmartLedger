package com.smartledger.ui.transactions

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.smartledger.data.db.entity.Transaction
import com.smartledger.ui.components.AuxText
import com.smartledger.ui.components.CategoryPickDialog
import com.smartledger.ui.components.DeleteTransactionDialog
import com.smartledger.ui.components.EditTransactionDialog
import com.smartledger.ui.components.EmptyState
import com.smartledger.ui.components.Eyebrow
import com.smartledger.ui.components.FilterChipItem
import com.smartledger.ui.components.Hairline
import com.smartledger.ui.components.IconButtonQuiet
import com.smartledger.ui.components.QuietButton
import com.smartledger.ui.components.SectionCard
import com.smartledger.ui.components.TransactionActionSheet
import com.smartledger.ui.components.TransactionDateHeader
import com.smartledger.ui.components.TransactionRow
import com.smartledger.ui.components.UiTokens
import com.smartledger.ui.components.formatMoney
import com.smartledger.ui.theme.AppSpacing
import com.smartledger.ui.theme.AppType
import com.smartledger.ui.theme.SmartLedgerColors
import com.smartledger.util.DateUtil

/**
 * 流水页。
 *
 * 规范要求：按日期分组的紧凑列表，商户名称为主信息，分类与支付来源为次信息，
 * 金额右对齐（支出 Seal / 收入 Moss），筛选条件用轻量 Chip。
 *
 * 底部导航去掉「记账」Tab 之后，这一页与总览页的 FAB 是记账的主入口，
 * 因此 FAB 在这一页也必须常驻。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TransactionsScreen(
    onNavigateToRecord: () -> Unit = {},
    onNavigateToSearch: () -> Unit = {},
    viewModel: TransactionsViewModel = viewModel()
) {
    val transactions by viewModel.transactions.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val selectedYearMonth by viewModel.selectedYearMonth.collectAsState()
    val typeFilter by viewModel.typeFilter.collectAsState()
    val monthExpense by viewModel.monthExpense.collectAsState()
    val monthIncome by viewModel.monthIncome.collectAsState()

    val isCurrentMonth = selectedYearMonth == DateUtil.getCurrentYearMonth()
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    var editing by remember { mutableStateOf<Transaction?>(null) }
    var showActions by remember { mutableStateOf(false) }
    var showEdit by remember { mutableStateOf(false) }
    var showCategory by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onResume(owner: androidx.lifecycle.LifecycleOwner) {
                viewModel.refreshDateRange()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 按日期分组；列表本身已按 transactionTime DESC，分组后顺序保持
    val grouped = remember(transactions) {
        transactions.groupBy { DateUtil.formatDate(it.transactionTime) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SmartLedgerColors.bg)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = UiTokens.pagePadding,
                end = UiTokens.pagePadding,
                top = AppSpacing.lg,
                bottom = 96.dp
            )
        ) {
            // ═══ 页面标题 + 搜索 ═══
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Eyebrow(text = "TRANSACTIONS")
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "流水",
                            style = AppType.pageTitle,
                            color = SmartLedgerColors.fg
                        )
                    }
                    IconButtonQuiet(
                        icon = Icons.Outlined.Search,
                        contentDescription = "搜索账单",
                        onClick = onNavigateToSearch
                    )
                }
            }

            item { Spacer(Modifier.height(AppSpacing.md)) }

            // ═══ 月份切换 + 收支小计 ═══
            item {
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButtonQuiet(
                            icon = Icons.Outlined.ChevronLeft,
                            contentDescription = "上个月",
                            onClick = { viewModel.previousMonth() }
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = selectedYearMonth,
                                style = AppType.listPrimary,
                                color = SmartLedgerColors.fg
                            )
                            if (!isCurrentMonth) {
                                QuietButton(
                                    text = "回到本月",
                                    onClick = { viewModel.goToCurrentMonth() },
                                    color = SmartLedgerColors.info
                                )
                            }
                        }
                        IconButtonQuiet(
                            icon = Icons.Outlined.ChevronRight,
                            contentDescription = "下个月",
                            onClick = { viewModel.nextMonth() },
                            enabled = !isCurrentMonth
                        )
                    }

                    Spacer(Modifier.height(AppSpacing.sm))
                    Hairline()
                    Spacer(Modifier.height(AppSpacing.md))

                    Row(modifier = Modifier.fillMaxWidth()) {
                        SummaryCell(
                            label = "支出",
                            value = formatMoney(monthExpense),
                            color = SmartLedgerColors.expense,
                            modifier = Modifier.weight(1f)
                        )
                        SummaryCell(
                            label = "收入",
                            value = formatMoney(monthIncome),
                            color = SmartLedgerColors.income,
                            modifier = Modifier.weight(1f)
                        )
                        SummaryCell(
                            label = "笔数",
                            value = "${transactions.size}",
                            color = SmartLedgerColors.fg,
                            modifier = Modifier.weight(1f),
                            alignEnd = true
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(AppSpacing.cardGap)) }

            // ═══ 筛选 Chip ═══
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                    TxTypeFilter.entries.forEach { f ->
                        FilterChipItem(
                            text = f.label,
                            selected = typeFilter == f,
                            onClick = { viewModel.setTypeFilter(f) }
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(AppSpacing.sm)) }

            // ═══ 列表 ═══
            if (transactions.isEmpty()) {
                item {
                    EmptyState(
                        title = if (typeFilter == TxTypeFilter.ALL) "本月还没有账单"
                        else "本月没有${typeFilter.label}记录",
                        description = if (typeFilter == TxTypeFilter.ALL) {
                            "记一笔账，或开启通知监听让支付自动入账"
                        } else {
                            "切换筛选条件看看其他类型的账单"
                        },
                        icon = Icons.Outlined.ReceiptLong
                    )
                }
            } else {
                grouped.forEach { (date, list) ->
                    item(key = "date-$date") {
                        // 分组标题做成「日期条」，与卡片区分开，保持列表紧凑
                        TransactionDateHeader(
                            dateLabel = date,
                            weekday = DateUtil.getDayOfWeek(list.first().transactionTime),
                            dayExpense = list.filter { it.type == "expense" }.sumOf { it.amount },
                            dayIncome = list.filter { it.type == "income" }.sumOf { it.amount },
                            modifier = Modifier.padding(horizontal = AppSpacing.xs)
                        )
                    }
                    item(key = "card-$date") {
                        SectionCard(
                            contentPadding = PaddingValues(
                                horizontal = AppSpacing.xs,
                                vertical = AppSpacing.xs
                            )
                        ) {
                            list.forEachIndexed { index, tx ->
                                if (index > 0) {
                                    Hairline(
                                        modifier = Modifier.padding(
                                            start = 58.dp,
                                            top = 2.dp,
                                            bottom = 2.dp
                                        )
                                    )
                                }
                                TransactionRow(
                                    transaction = tx,
                                    categoryName = viewModel.getCategoryName(tx.categoryId, categories),
                                    categoryColor = viewModel.getCategoryColor(tx.categoryId, categories)
                                        ?.let { Color(it) },
                                    onClick = {
                                        editing = tx
                                        showEdit = true
                                    },
                                    onLongClick = {
                                        editing = tx
                                        showActions = true
                                    }
                                )
                            }
                        }
                    }
                    item(key = "gap-$date") {
                        Spacer(Modifier.height(AppSpacing.cardGap))
                    }
                }
            }

            item { Spacer(Modifier.height(AppSpacing.lg)) }
        }

        FloatingActionButton(
            onClick = onNavigateToRecord,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 24.dp),
            containerColor = SmartLedgerColors.accent,
            contentColor = SmartLedgerColors.onAccent,
            shape = CircleShape
        ) {
            Icon(Icons.Filled.Add, contentDescription = "记一笔")
        }
    }

    // ═══ 弹窗（与总览页共用同一套组件）═══
    if (showActions && editing != null) {
        TransactionActionSheet(
            onEdit = { showActions = false; showEdit = true },
            onChangeCategory = { showActions = false; showCategory = true },
            onDelete = { showActions = false; showDelete = true },
            onDismiss = { showActions = false }
        )
    }

    if (showEdit && editing != null) {
        EditTransactionDialog(
            transaction = editing!!,
            categories = categories,
            onSave = { updated -> viewModel.updateTransaction(updated) },
            onDismiss = { showEdit = false; editing = null }
        )
    }

    if (showCategory && editing != null) {
        CategoryPickDialog(
            transaction = editing!!,
            categories = categories,
            onSelect = { categoryId ->
                viewModel.updateCategory(editing!!, categoryId)
                showCategory = false
                editing = null
            },
            onDismiss = { showCategory = false; editing = null }
        )
    }

    if (showDelete && editing != null) {
        DeleteTransactionDialog(
            transaction = editing!!,
            onConfirm = {
                viewModel.deleteTransaction(editing!!)
                showDelete = false
                editing = null
            },
            onDismiss = { showDelete = false; editing = null }
        )
    }
}

@Composable
private fun SummaryCell(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier,
    alignEnd: Boolean = false
) {
    Column(
        modifier = modifier,
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start
    ) {
        Eyebrow(text = label)
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            style = AppType.cardNumberSmall,
            color = color,
            maxLines = 1
        )
    }
}
