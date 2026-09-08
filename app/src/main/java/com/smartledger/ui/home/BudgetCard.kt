package com.smartledger.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.TrendingUp
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.smartledger.ui.components.AuxText
import com.smartledger.ui.components.Eyebrow
import com.smartledger.ui.components.Hairline
import com.smartledger.ui.components.MetricCell
import com.smartledger.ui.components.PillTone
import com.smartledger.ui.components.PrimaryButton
import com.smartledger.ui.components.SectionCard
import com.smartledger.ui.components.SmartLedgerInputDialog
import com.smartledger.ui.components.StatusPill
import com.smartledger.ui.components.ThinProgressBar
import com.smartledger.ui.components.formatMoney
import com.smartledger.ui.theme.AppSpacing
import com.smartledger.ui.theme.AppType
import com.smartledger.ui.theme.SmartLedgerColors
import com.smartledger.util.BudgetPredictor

/**
 * 首页预算卡 —— 动态预算与超支预测。
 *
 * **完全本地计算，不调用 AI，飞行模式下也照常工作。**
 *
 * 四种形态（由 [BudgetPredictor.Result] 与「是否本月」共同决定）：
 *
 * 1. 本月 + 已设预算 + 已过 ≥5 天 → 完整形态（含月末预测与超支日期）
 * 2. 本月 + 已设预算 + 已过 <5 天 → **不显示预测**，只给提示
 *    月初 1 号交房租时，「日均 × 当月天数」会算出「预计月末支出 9 万」
 *    这种荒谬数字，用户会当成 bug。第一版不做加权算法，只在展示层挡住。
 * 3. 本月 + 未设预算 → 引导设置
 * 4. 历史月份 → 只显示「执行情况」，不显示今日建议与预测；
 *    历史月份未设预算时**整张卡不显示**（不要给用户一个「给三个月前
 *    补设预算」的入口，无意义且容易误操作）
 *
 * 配色只用三级：安全 Moss / 预警 Amber / 超支 Seal，未设预算用中性墨色。
 */
@Composable
fun BudgetCard(
    prediction: BudgetPredictor.Result?,
    isCurrentMonth: Boolean,
    yearMonthLabel: String,
    onSetLimit: (Double) -> Unit,
    modifier: Modifier = Modifier
) {
    // 历史月份且未设预算：整张卡不渲染
    if (!isCurrentMonth && (prediction == null || prediction.status == BudgetPredictor.Status.NO_BUDGET)) {
        return
    }
    if (prediction == null) return

    var showEditDialog by remember { mutableStateOf(false) }

    val tone = when (prediction.status) {
        BudgetPredictor.Status.NO_BUDGET -> PillTone.NEUTRAL
        BudgetPredictor.Status.SAFE -> PillTone.POSITIVE
        BudgetPredictor.Status.WARNING -> PillTone.WARNING
        BudgetPredictor.Status.DANGER -> PillTone.DANGER
    }
    val accent: Color = when (prediction.status) {
        BudgetPredictor.Status.NO_BUDGET -> SmartLedgerColors.fgSecondary
        BudgetPredictor.Status.SAFE -> SmartLedgerColors.income
        BudgetPredictor.Status.WARNING -> SmartLedgerColors.warning
        BudgetPredictor.Status.DANGER -> SmartLedgerColors.expense
    }

    SectionCard(modifier = modifier, large = true) {
        // ═══ 卡头 ═══
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Eyebrow(text = if (isCurrentMonth) "BUDGET PACE" else "BUDGET RESULT")
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (isCurrentMonth) "本月可用额度" else "$yearMonthLabel 预算执行",
                    style = AppType.listPrimary,
                    color = SmartLedgerColors.fg
                )
            }
            StatusPill(text = statusLabel(prediction.status), tone = tone)
        }

        Spacer(Modifier.height(AppSpacing.lg))

        // ═══ 未设预算：引导 ═══
        if (prediction.status == BudgetPredictor.Status.NO_BUDGET) {
            AuxText(
                text = if (isCurrentMonth) {
                    "设置后可查看每日建议额度、月末预测与超支日期。"
                } else {
                    "该月未设置可用额度。"
                }
            )
            if (isCurrentMonth) {
                Spacer(Modifier.height(AppSpacing.md))
                PrimaryButton(
                    text = "设置本月可用额度",
                    onClick = { showEditDialog = true }
                )
            }
            if (prediction.currentExpense > 0) {
                Spacer(Modifier.height(AppSpacing.md))
                Hairline()
                Spacer(Modifier.height(AppSpacing.md))
                MetricCell(
                    label = "已支出",
                    value = formatMoney(prediction.currentExpense),
                    valueColor = SmartLedgerColors.expense
                )
            }
        } else {
            // ═══ 主进度：已用 / 上限 ═══
            val budget = prediction.budget ?: 0.0
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = formatMoney(prediction.currentExpense),
                    style = AppType.cardNumber,
                    color = SmartLedgerColors.fg
                )
                Spacer(Modifier.width(AppSpacing.sm))
                Text(
                    text = "/ ${formatMoney(budget)}",
                    style = AppType.listSecondary,
                    color = SmartLedgerColors.fgSecondary,
                    modifier = Modifier.padding(bottom = 3.dp)
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${prediction.usedPercent?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "0"}%",
                    style = AppType.listAmount,
                    color = accent
                )
            }

            Spacer(Modifier.height(AppSpacing.sm))
            ThinProgressBar(
                // 超支时进度条拉满，用颜色而不是溢出长度表达
                progress = ((prediction.usedPercent ?: 0.0) / 100.0).toFloat(),
                color = accent
            )

            Spacer(Modifier.height(AppSpacing.lg))

            // ═══ 剩余额度 / 今日建议可用 ═══
            Row(modifier = Modifier.fillMaxWidth()) {
                MetricCell(
                    label = "剩余额度",
                    value = formatMoney(prediction.remainingBudget),
                    valueColor = if (prediction.remainingBudget > 0) SmartLedgerColors.fg
                    else SmartLedgerColors.expense,
                    modifier = Modifier.weight(1f)
                )
                if (isCurrentMonth) {
                    MetricCell(
                        label = "今日建议可用",
                        value = formatMoney(prediction.dailyAvailable ?: 0.0),
                        valueColor = accent,
                        sub = "含今天还剩 ${prediction.remainingDays} 天",
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    MetricCell(
                        label = if (prediction.currentExpense > budget) "超支" else "结余",
                        value = formatMoney(
                            kotlin.math.abs(budget - prediction.currentExpense)
                        ),
                        valueColor = accent,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // ═══ 月末预测（仅本月，且过了月初护栏）═══
            if (isCurrentMonth) {
                Spacer(Modifier.height(AppSpacing.lg))
                Hairline()
                Spacer(Modifier.height(AppSpacing.md))

                if (!prediction.showProjection) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Icon(
                            Icons.Outlined.TrendingUp,
                            contentDescription = null,
                            tint = SmartLedgerColors.fgTertiary,
                            modifier = Modifier.width(16.dp).height(16.dp)
                        )
                        Spacer(Modifier.width(AppSpacing.sm))
                        AuxText(
                            text = "月初数据较少，支出预测将在 ${BudgetPredictor.MIN_DAYS_FOR_PROJECTION} 日后显示",
                            color = SmartLedgerColors.fgTertiary
                        )
                    }
                } else {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        MetricCell(
                            label = "日均支出",
                            value = formatMoney(prediction.averageDailyExpense),
                            valueColor = SmartLedgerColors.fgSecondary,
                            modifier = Modifier.weight(1f)
                        )
                        MetricCell(
                            label = "预计月底",
                            value = formatMoney(prediction.predictedMonthExpense),
                            valueColor = accent,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(Modifier.height(AppSpacing.md))
                    when {
                        prediction.status == BudgetPredictor.Status.DANGER ->
                            WarnLine(
                                text = "本月预算已超支 ${formatMoney(prediction.currentExpense - budget)}",
                                color = SmartLedgerColors.expense
                            )

                        prediction.projectedBudgetDay != null ->
                            WarnLine(
                                text = "按当前节奏，预计 ${monthDayText(prediction.projectedBudgetDay)} 达到预算上限",
                                color = SmartLedgerColors.warning
                            )

                        else ->
                            AuxText(
                                text = "按当前节奏，本月不会超出预算",
                                color = SmartLedgerColors.income
                            )
                    }
                }
            } else {
                // 历史月份：只给一句结论
                Spacer(Modifier.height(AppSpacing.lg))
                Hairline()
                Spacer(Modifier.height(AppSpacing.md))
                AuxText(
                    text = if (prediction.currentExpense > budget) {
                        "该月超支 ${formatMoney(prediction.currentExpense - budget)}"
                    } else {
                        "该月未超支，结余 ${formatMoney(budget - prediction.currentExpense)}"
                    },
                    color = accent
                )
            }

            // ═══ 修改入口 ═══
            if (isCurrentMonth) {
                Spacer(Modifier.height(AppSpacing.md))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    com.smartledger.ui.components.QuietButton(
                        text = "修改额度",
                        onClick = { showEditDialog = true },
                        color = SmartLedgerColors.fgSecondary
                    )
                }
            }
        }
    }

    if (showEditDialog) {
        var input by remember {
            mutableStateOf(
                prediction.budget?.let { trimAmount(it) } ?: ""
            )
        }
        var error by remember { mutableStateOf<String?>(null) }

        SmartLedgerInputDialog(
            onDismissRequest = { showEditDialog = false },
            eyebrow = "BUDGET",
            title = if (isCurrentMonth) "本月可用总额度" else "设置可用总额度",
            label = "月度支出上限",
            value = input,
            prefix = "¥",
            helper = "用于计算今日建议可用额度与月末超支预测，全部本地计算。",
            error = error,
            confirmText = "保存",
            onValueChange = { raw ->
                if (raw.isEmpty() || raw.matches(Regex("^\\d{0,7}(\\.\\d{0,2})?$"))) {
                    input = raw
                    error = null
                }
            },
            onConfirm = {
                val v = input.toDoubleOrNull()
                if (v == null || v <= 0 || v > 1_000_000) {
                    error = "请输入 0 ～ 1,000,000 之间的金额"
                    return@SmartLedgerInputDialog
                }
                onSetLimit(v)
                showEditDialog = false
            }
        )
    }
}

@Composable
private fun WarnLine(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Icon(
            Icons.Outlined.TrendingUp,
            contentDescription = null,
            tint = color,
            modifier = Modifier
                .width(16.dp)
                .height(16.dp)
        )
        Spacer(Modifier.width(AppSpacing.sm))
        AuxText(text = text, color = color)
    }
}

private fun statusLabel(status: BudgetPredictor.Status): String = when (status) {
    BudgetPredictor.Status.NO_BUDGET -> "未设置"
    BudgetPredictor.Status.SAFE -> "节奏正常"
    BudgetPredictor.Status.WARNING -> "有超支风险"
    BudgetPredictor.Status.DANGER -> "已超支"
}

/** 把「第几天」渲染成「9 月 27 日」 */
private fun monthDayText(day: Int): String {
    val cal = java.util.Calendar.getInstance()
    return "${cal.get(java.util.Calendar.MONTH) + 1} 月 ${day} 日"
}

private fun trimAmount(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString()
    else String.format(java.util.Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
