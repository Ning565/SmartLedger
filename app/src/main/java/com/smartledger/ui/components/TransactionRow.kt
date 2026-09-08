package com.smartledger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smartledger.data.db.entity.Transaction
import com.smartledger.ui.theme.AppRadius
import com.smartledger.ui.theme.AppSize
import com.smartledger.ui.theme.AppSpacing
import com.smartledger.ui.theme.AppType
import com.smartledger.ui.theme.SmartLedgerColors
import com.smartledger.util.DateUtil

/**
 * 紧凑流水行 —— 总览页与流水页共用。
 *
 * 信息层级严格按规范：
 *  - **商户名称为主信息**（第一行，15sp Medium）
 *  - **分类与支付来源为次信息**（第二行，13sp，Ink Soft）
 *  - **金额右对齐**，支出用 Seal、收入用 Moss
 *
 * 商户为空时退化显示分类名 —— 自动记账偶尔解析不出商户，
 * 若主信息留空整行会显得很破。
 */
@androidx.compose.foundation.ExperimentalFoundationApi
@Composable
fun TransactionRow(
    transaction: Transaction,
    categoryName: String,
    categoryColor: Color?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** 显示日期时用不上（已按日期分组），显示时刻更有用 */
    showTime: Boolean = true
) {
    val isExpense = transaction.type == "expense"
    val amountColor = if (isExpense) SmartLedgerColors.expense else SmartLedgerColors.income
    val primary = transaction.merchant?.takeIf { it.isNotBlank() } ?: categoryName

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = AppSize.listItemMinHeight)
            .clip(RoundedCornerShape(AppRadius.card))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = AppSpacing.md, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 分类色块：小尺寸圆角方块，不用图标底色，避免整页都是彩色圆圈
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(AppRadius.small))
                .background(
                    (categoryColor ?: SmartLedgerColors.info).copy(alpha = 0.12f)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                categoryIcon(categoryName),
                contentDescription = null,
                tint = categoryColor ?: SmartLedgerColors.info,
                modifier = Modifier.size(18.dp)
            )
        }

        Spacer(Modifier.width(AppSpacing.md))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = primary,
                style = AppType.listPrimary,
                color = SmartLedgerColors.fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            // 次信息：分类 · 支付来源 · 时刻
            Text(
                text = buildString {
                    append(categoryName)
                    val channel = transaction.paymentMethod?.takeIf { it.isNotBlank() }
                    if (channel != null) {
                        append(" · ").append(channel)
                    }
                    if (showTime) {
                        append(" · ").append(DateUtil.formatTime(transaction.transactionTime))
                    }
                    val note = transaction.note?.takeIf { it.isNotBlank() }
                    if (note != null) {
                        append(" · ").append(note)
                    }
                },
                style = AppType.listSecondary,
                color = SmartLedgerColors.fgSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.width(AppSpacing.sm))

        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = (if (isExpense) "-" else "+") + formatMoney(transaction.amount),
                style = AppType.listAmount,
                color = amountColor,
                maxLines = 1
            )
            // 自动记账的账单标一个来源点，手工/AI 填写的不标，避免视觉噪声
            if (transaction.source == "auto" || transaction.source == "sms") {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (transaction.source == "sms") "短信" else "自动",
                    style = AppType.eyebrow,
                    color = SmartLedgerColors.fgTertiary
                )
            }
        }
    }
}

/** 日期分组标题：紧凑、左对齐、带当日小计 */
@Composable
fun TransactionDateHeader(
    dateLabel: String,
    weekday: String,
    dayExpense: Double,
    dayIncome: Double,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = AppSpacing.md,
                end = AppSpacing.md,
                top = AppSpacing.md,
                bottom = AppSpacing.xs
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = dateLabel,
                style = AppType.listPrimary,
                color = SmartLedgerColors.fgSecondary
            )
            Spacer(Modifier.width(AppSpacing.sm))
            Text(
                text = weekday,
                style = AppType.aux,
                color = SmartLedgerColors.fgTertiary
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (dayIncome > 0) {
                Text(
                    text = "+${formatMoney(dayIncome, withSymbol = false)}",
                    style = AppType.aux,
                    color = SmartLedgerColors.income
                )
                Spacer(Modifier.width(AppSpacing.sm))
            }
            if (dayExpense > 0) {
                Text(
                    text = "-${formatMoney(dayExpense, withSymbol = false)}",
                    style = AppType.aux,
                    color = SmartLedgerColors.expense
                )
            }
        }
    }
}
