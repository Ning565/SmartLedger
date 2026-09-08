package com.smartledger.ui.statistics

import android.widget.Toast
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.smartledger.data.analytics.model.SummaryPeriod
import com.smartledger.data.db.dao.CategoryTotal
import com.smartledger.data.db.entity.Category
import com.smartledger.ui.components.AuxText
import com.smartledger.ui.components.EmptyState
import com.smartledger.ui.components.Eyebrow
import com.smartledger.ui.components.SectionCard
import com.smartledger.ui.components.SectionHeader
import com.smartledger.ui.components.SegmentedTabs
import com.smartledger.ui.components.ThinDonut
import com.smartledger.ui.components.ThinProgressBar
import com.smartledger.ui.components.UiTokens
import com.smartledger.ui.components.categoryIcon
import com.smartledger.ui.components.formatMoney
import com.smartledger.ui.components.formatPercent
import com.smartledger.ui.theme.AppRadius
import com.smartledger.ui.theme.AppSpacing
import com.smartledger.ui.theme.AppType
import com.smartledger.ui.theme.SmartLedgerColors

/**
 * 统计页。
 *
 * 结构：页面标题 → 周期切换 → **AI 消费体检卡** → 环形图 → 分类排行。
 *
 * AI 卡放在周期切换之后、图表之前：它是这一页信息密度最高、
 * 也最需要用户主动触发的部分，放在顶部才符合「✨ 本月 AI 消费体检」的定位。
 * 但它**不跟随**日/周/月/年 Tab —— 「日」维度得不出消费画像，
 * 「年」又会把 Prompt 撑得很大，所以 AI 卡自带「本月 / 近 3 月」二选一。
 */
@Composable
fun StatisticsScreen(
    onNavigateToAiSettings: () -> Unit = {},
    viewModel: StatisticsViewModel = viewModel()
) {
    val selectedPeriod by viewModel.selectedPeriod.collectAsState(initial = "month")
    val periodExpense by viewModel.periodExpense.collectAsState(initial = 0.0)
    val periodIncome by viewModel.periodIncome.collectAsState(initial = 0.0)
    val expenseByCategory by viewModel.expenseByCategory.collectAsState(initial = emptyList())
    val categories by viewModel.categories.collectAsState(initial = emptyList())

    val aiState by viewModel.aiState.collectAsState()
    val aiPeriod by viewModel.aiPeriod.collectAsState()
    val reportExpanded by viewModel.reportExpanded.collectAsState()
    val adoptPrompt by viewModel.adoptPrompt.collectAsState()

    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    // 回到前台时刷新「日/周」等相对今天的区间，并重估 AI 卡的缓存新鲜度
    DisposableEffect(lifecycleOwner) {
        val observer = object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onResume(owner: androidx.lifecycle.LifecycleOwner) {
                viewModel.refreshTimeRange()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val categoryMap = categories.associateBy { it.id }
    val chartColors = SmartLedgerColors.chartColors

    val periodLabel = when (selectedPeriod) {
        "day" -> "今日支出"
        "week" -> "本周支出"
        "year" -> "本年支出"
        else -> "本月支出"
    }

    // eyebrow 用纯英文，不做中英混拼（"本月EXPENSE" 这种很难看）
    val periodEyebrow = when (selectedPeriod) {
        "day" -> "TODAY"
        "week" -> "THIS WEEK"
        "year" -> "THIS YEAR"
        else -> "THIS MONTH"
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
            ),
            verticalArrangement = Arrangement.spacedBy(UiTokens.cardGap)
        ) {
            // ═══ 页面标题 ═══
            item {
                Column {
                    Eyebrow(text = "STATISTICS")
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "统计",
                        style = AppType.pageTitle,
                        color = SmartLedgerColors.fg
                    )
                }
            }

            // ═══ 周期切换 ═══
            item {
                SegmentedTabs(
                    items = listOf(
                        "日" to "day", "周" to "week", "月" to "month", "年" to "year"
                    ),
                    selected = selectedPeriod,
                    onSelect = viewModel::setPeriod
                )
            }

            // ═══ ✨ AI 消费体检 ═══
            item {
                AiReportCard(
                    state = aiState,
                    period = aiPeriod,
                    expanded = reportExpanded,
                    adoptPrompt = adoptPrompt,
                    onPeriodChange = viewModel::setAiPeriod,
                    onGenerate = { viewModel.generateReport(force = false) },
                    onRegenerate = { viewModel.generateReport(force = true) },
                    onViewCached = viewModel::viewCachedReport,
                    onStop = viewModel::stopGeneration,
                    onRetry = viewModel::retry,
                    onDismissReport = viewModel::dismissReport,
                    onToggleExpanded = viewModel::toggleReportExpanded,
                    onOpenAdopt = viewModel::openAdoptPrompt,
                    onDismissAdopt = viewModel::dismissAdoptPrompt,
                    onAdoptConfirm = { amount, suggested, done ->
                        viewModel.adoptSuggestedBudget(amount, suggested) { msg ->
                            done(msg)
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        }
                    },
                    onNavigateToAiSettings = onNavigateToAiSettings
                )
            }

            // ═══ 环形图 + 总支出 ═══
            item {
                DonutSection(
                    periodExpense = periodExpense,
                    periodIncome = periodIncome,
                    periodLabel = periodLabel,
                    periodEyebrow = periodEyebrow,
                    expenseByCategory = expenseByCategory,
                    categoryMap = categoryMap,
                    chartColors = chartColors
                )
            }

            // ═══ 分类排行 ═══
            item {
                SectionHeader(
                    eyebrow = "CATEGORY RANKING",
                    title = "分类排行",
                    trailing = {
                        if (expenseByCategory.isNotEmpty()) {
                            AuxText(
                                text = "${expenseByCategory.size} 类",
                                color = SmartLedgerColors.fgTertiary
                            )
                        }
                    }
                )
            }

            if (expenseByCategory.isEmpty()) {
                item {
                    EmptyState(
                        title = "暂无支出数据",
                        description = "记一笔账后，这里会显示分类占比与排行",
                        icon = Icons.Outlined.PieChart
                    )
                }
            } else {
                item {
                    SectionCard(contentPadding = PaddingValues(vertical = AppSpacing.sm)) {
                        val maxExpense = expenseByCategory.firstOrNull()?.total ?: 1.0
                        expenseByCategory.take(8).forEachIndexed { index, ct ->
                            if (index > 0) {
                                Spacer(Modifier.height(AppSpacing.sm))
                            }
                            CategoryRankingRow(
                                categoryTotal = ct,
                                categoryName = categoryMap[ct.categoryId]?.name ?: "未分类",
                                color = chartColors[index % chartColors.size],
                                totalExpense = periodExpense,
                                maxExpense = maxExpense
                            )
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(AppSpacing.lg)) }
        }
    }
}

// ═══════════════════════════════════════════════════════
// 环形图区域
// ═══════════════════════════════════════════════════════

/**
 * 细环形进度 + 中心总额 + 图例。
 *
 * 二维、平面、低饱和：线宽细、无立体、无渐变、端点不做圆角
 * （圆角端点会让相邻扇区视觉上互相渗透，读数不准）。
 */
@Composable
private fun DonutSection(
    periodExpense: Double,
    periodIncome: Double,
    periodLabel: String,
    periodEyebrow: String,
    expenseByCategory: List<CategoryTotal>,
    categoryMap: Map<Long, Category>,
    chartColors: List<Color>
) {
    SectionCard(large = true) {
        Eyebrow(text = periodEyebrow)
        Spacer(Modifier.height(2.dp))
        Text(
            text = periodLabel,
            style = AppType.listPrimary,
            color = SmartLedgerColors.fg
        )
        Spacer(Modifier.height(AppSpacing.md))

        Row(verticalAlignment = Alignment.CenterVertically) {
            ThinDonut(
                segments = expenseByCategory.take(8).map { it.total.toFloat() }
                    .zip(chartColors),
                modifier = Modifier.size(140.dp),
                strokeWidth = 10.dp,
                center = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = formatMoney(periodExpense),
                            style = AppType.cardNumber,
                            color = SmartLedgerColors.expense,
                            maxLines = 1
                        )
                        AuxText(
                            text = "支出",
                            color = SmartLedgerColors.fgTertiary
                        )
                    }
                }
            )

            Spacer(Modifier.width(AppSpacing.lg))

            Column(modifier = Modifier.weight(1f)) {
                MetricLine("收入", formatMoney(periodIncome), SmartLedgerColors.income)
                Spacer(Modifier.height(AppSpacing.sm))
                MetricLine(
                    "结余",
                    formatMoney(periodIncome - periodExpense),
                    if (periodIncome - periodExpense >= 0) SmartLedgerColors.income
                    else SmartLedgerColors.expense
                )
                if (expenseByCategory.isNotEmpty()) {
                    Spacer(Modifier.height(AppSpacing.md))
                    // 只列前 4 项，其余归入「其他」，避免图例挤爆卡片
                    expenseByCategory.take(4).forEachIndexed { i, ct ->
                        LegendRow(
                            color = chartColors[i % chartColors.size],
                            name = categoryMap[ct.categoryId]?.name ?: "未分类",
                            percent = if (periodExpense > 0) ct.total / periodExpense * 100 else 0.0
                        )
                    }
                    if (expenseByCategory.size > 4) {
                        val restTotal = expenseByCategory.drop(4).sumOf { it.total }
                        LegendRow(
                            color = chartColors[4 % chartColors.size],
                            name = "其他 ${expenseByCategory.size - 4} 类",
                            percent = if (periodExpense > 0) restTotal / periodExpense * 100 else 0.0
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricLine(label: String, value: String, color: Color) {
    Column {
        Eyebrow(text = label)
        Text(
            text = value,
            style = AppType.cardNumberSmall,
            color = color,
            maxLines = 1
        )
    }
}

@Composable
private fun LegendRow(color: Color, name: String, percent: Double) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = name,
            style = AppType.aux,
            color = SmartLedgerColors.fgSecondary,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
        AuxText(text = formatPercent(percent), color = SmartLedgerColors.fgTertiary)
    }
}

// ═══════════════════════════════════════════════════════
// 分类排行行（横向条形图）
// ═══════════════════════════════════════════════════════

@Composable
private fun CategoryRankingRow(
    categoryTotal: CategoryTotal,
    categoryName: String,
    color: Color,
    totalExpense: Double,
    maxExpense: Double
) {
    val percentage = if (totalExpense > 0) categoryTotal.total / totalExpense * 100 else 0.0
    val fraction = if (maxExpense > 0) (categoryTotal.total / maxExpense).toFloat() else 0f

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.lg, vertical = AppSpacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(AppRadius.small))
                .background(SmartLedgerColors.surfaceHover),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                categoryIcon(categoryName),
                contentDescription = categoryName,
                tint = color,
                modifier = Modifier.size(18.dp)
            )
        }

        Spacer(Modifier.width(AppSpacing.md))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = categoryName,
                    style = AppType.listPrimary,
                    color = SmartLedgerColors.fg,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = formatMoney(categoryTotal.total),
                    style = AppType.listAmount,
                    color = SmartLedgerColors.fg,
                    maxLines = 1
                )
            }
            Spacer(Modifier.height(6.dp))
            ThinProgressBar(
                progress = fraction.coerceIn(0f, 1f),
                color = color,
                thickness = 4.dp
            )
            Spacer(Modifier.height(4.dp))
            AuxText(text = formatPercent(percentage), color = SmartLedgerColors.fgTertiary)
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, widthDp = 393, heightDp = 852)
@Composable
private fun StatisticsScreenPreview() {
    com.smartledger.ui.theme.SmartLedgerTheme {
        StatisticsScreen()
    }
}
