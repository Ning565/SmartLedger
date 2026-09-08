package com.smartledger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.smartledger.data.db.entity.Category
import com.smartledger.data.db.entity.Transaction
import com.smartledger.ui.theme.AppRadius
import com.smartledger.ui.theme.AppSize
import com.smartledger.ui.theme.AppSpacing
import com.smartledger.ui.theme.AppType
import com.smartledger.ui.theme.SmartLedgerColors
import com.smartledger.util.CurrencyUtil
import com.smartledger.util.DateUtil

/**
 * 账单操作弹窗集合 —— 总览页与流水页共用。
 *
 * 原本是 `HomeScreen.kt` 里的 private composable，流水页需要完全相同的能力，
 * 抽出来避免复制 ~150 行（复制必然导致两页行为逐渐分叉）。
 */

/** 长按账单后的操作菜单：编辑 / 修改分类 / 删除 */
@Composable
fun TransactionActionSheet(
    onEdit: () -> Unit,
    onChangeCategory: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    SmartLedgerDialog(
        onDismissRequest = onDismiss,
        eyebrow = "ACTIONS",
        title = "账单操作",
        dismissText = "取消",
        onDismiss = onDismiss,
        content = {
            ActionRow(icon = Icons.Outlined.Edit, text = "编辑账单", onClick = onEdit)
            ActionRow(
                icon = Icons.Outlined.GridView,
                text = "修改分类",
                onClick = onChangeCategory
            )
            ActionRow(
                icon = Icons.Outlined.Delete,
                text = "删除账单",
                color = SmartLedgerColors.expense,
                onClick = onDelete
            )
        }
    )
}

@Composable
private fun ActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    onClick: () -> Unit,
    color: Color = SmartLedgerColors.fg
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = AppSize.minTouchTarget)
            .clip(RoundedCornerShape(AppRadius.field))
            .clickable(onClick = onClick)
            .padding(horizontal = AppSpacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(AppSpacing.md))
        Text(text = text, style = AppType.listPrimary, color = color)
    }
}

/** 修改分类：只列与账单 type 匹配的分类（分类是按 type 分组的，混列会选错） */
@Composable
fun CategoryPickDialog(
    transaction: Transaction,
    categories: List<Category>,
    onSelect: (Long?) -> Unit,
    onDismiss: () -> Unit
) {
    val typeCategories = remember(categories, transaction.type) {
        categories.filter { it.type == transaction.type }
    }
    SmartLedgerDialog(
        onDismissRequest = onDismiss,
        eyebrow = "CATEGORY",
        title = "选择分类",
        dismissText = "取消",
        onDismiss = onDismiss,
        content = {
            if (typeCategories.isEmpty()) {
                AuxText(text = "该收支类型下暂无分类，请到「分类管理」中添加")
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    typeCategories.forEach { category ->
                        val selected = category.id == transaction.categoryId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = AppSize.minTouchTarget)
                                .clip(RoundedCornerShape(AppRadius.field))
                                .background(
                                    if (selected) SmartLedgerColors.accentDim else Color.Transparent
                                )
                                .clickable { onSelect(category.id) }
                                .padding(horizontal = AppSpacing.md),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                categoryIcon(category.name),
                                contentDescription = null,
                                tint = if (selected) SmartLedgerColors.fg
                                else SmartLedgerColors.fgSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(AppSpacing.md))
                            Text(
                                text = category.name,
                                style = AppType.listPrimary,
                                color = SmartLedgerColors.fg,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )
                            if (selected) {
                                Icon(
                                    Icons.Outlined.Check,
                                    contentDescription = null,
                                    tint = SmartLedgerColors.fg,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    )
}

/**
 * 编辑账单。
 *
 * 相对原实现补了两处：
 *  1. **日期与时刻可改** —— 原来只能改金额/商户/备注/渠道/分类，
 *     自动记错了时间的账单没法修正；
 *  2. **金额非法时给出提示**，原实现是 `if (amount != null && amount > 0)`
 *     不满足就静默什么都不做，用户点了保存没反应也不知道为什么。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditTransactionDialog(
    transaction: Transaction,
    categories: List<Category>,
    onSave: (Transaction) -> Unit,
    onDismiss: () -> Unit
) {
    var amountText by remember { mutableStateOf(CurrencyUtil.toEditableString(transaction.amount)) }
    var merchant by remember { mutableStateOf(transaction.merchant ?: "") }
    var note by remember { mutableStateOf(transaction.note ?: "") }
    var paymentMethod by remember {
        mutableStateOf(
            transaction.paymentMethod?.takeIf { it.isNotBlank() }
                ?: com.smartledger.util.PaymentMethods.PRESETS.first()
        )
    }
    var selectedCategoryId by remember { mutableStateOf(transaction.categoryId) }
    // 刻意不叫 `time`：这个变量会在 Calendar.apply { } 里被读写，
    // 而 Calendar 本身就有 time 属性（Date 类型）。虽然 Kotlin 的解析规则是
    // 局部变量优先于隐式接收者成员，能编译过也是对的，但这种遮蔽是维护陷阱 ——
    // 一旦有人把它挪进另一个 Calendar 作用域，语义就会静默改变。
    var txTime by remember { mutableStateOf(transaction.transactionTime) }
    var error by remember { mutableStateOf<String?>(null) }

    val typeCategories = remember(categories, transaction.type) {
        categories.filter { it.type == transaction.type }
    }
    val context = androidx.compose.ui.platform.LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(AppRadius.cardLarge),
        containerColor = SmartLedgerColors.surface,
        tonalElevation = 0.dp,
        titleContentColor = SmartLedgerColors.fg,
        title = {
            Column {
                Eyebrow(text = "EDIT")
                Spacer(Modifier.height(2.dp))
                Text("编辑账单", style = AppType.sectionTitle, color = SmartLedgerColors.fg)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.md)
            ) {
                AppTextField(
                    value = amountText,
                    onValueChange = { raw ->
                        // 只接受金额形态，避免把非法字符串带进数据库
                        if (raw.isEmpty() || raw.matches(Regex("^\\d{0,7}(\\.\\d{0,2})?$"))) {
                            amountText = raw
                            error = null
                        }
                    },
                    label = "金额",
                    placeholder = "0.00"
                )
                AppTextField(
                    value = merchant,
                    onValueChange = { merchant = it },
                    label = "商户名称",
                    placeholder = "如：蒙牛、美团"
                )
                AppTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = "备注",
                    placeholder = "可选"
                )

                // 日期与时刻
                Column {
                    Eyebrow(text = "DATE")
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = AppSize.buttonHeightCompact)
                            .clip(RoundedCornerShape(AppRadius.field))
                            .background(SmartLedgerColors.surfaceHover)
                            .clickable {
                                val cal = java.util.Calendar.getInstance().apply { timeInMillis = txTime }
                                android.app.DatePickerDialog(
                                    context,
                                    { _, y, m, d ->
                                        val c = java.util.Calendar.getInstance().apply {
                                            timeInMillis = txTime
                                            set(y, m, d)
                                        }
                                        // 不允许改到未来
                                        if (c.timeInMillis <= System.currentTimeMillis()) {
                                            txTime = c.timeInMillis
                                        }
                                    },
                                    cal.get(java.util.Calendar.YEAR),
                                    cal.get(java.util.Calendar.MONTH),
                                    cal.get(java.util.Calendar.DAY_OF_MONTH)
                                ).apply {
                                    datePicker.maxDate = System.currentTimeMillis()
                                }.show()
                            }
                            .padding(horizontal = AppSpacing.md),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = DateUtil.formatDate(txTime),
                            style = AppType.body,
                            color = SmartLedgerColors.fg,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = DateUtil.getDayOfWeek(txTime),
                            style = AppType.aux,
                            color = SmartLedgerColors.fgTertiary
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = AppSize.buttonHeightCompact)
                            .clip(RoundedCornerShape(AppRadius.field))
                            .background(SmartLedgerColors.surfaceHover)
                            .clickable {
                                val cal = java.util.Calendar.getInstance().apply { timeInMillis = txTime }
                                android.app.TimePickerDialog(
                                    context,
                                    { _, h, min ->
                                        val c = java.util.Calendar.getInstance().apply {
                                            timeInMillis = txTime
                                            set(java.util.Calendar.HOUR_OF_DAY, h)
                                            set(java.util.Calendar.MINUTE, min)
                                            set(java.util.Calendar.SECOND, 0)
                                        }
                                        txTime = c.timeInMillis
                                    },
                                    cal.get(java.util.Calendar.HOUR_OF_DAY),
                                    cal.get(java.util.Calendar.MINUTE),
                                    true
                                ).show()
                            }
                            .padding(horizontal = AppSpacing.md),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = DateUtil.formatTime(txTime),
                            style = AppType.body,
                            color = SmartLedgerColors.fg,
                            modifier = Modifier.weight(1f)
                        )
                        AuxText(text = "时刻会影响夜间消费统计", color = SmartLedgerColors.fgTertiary)
                    }
                }

                Column {
                    Eyebrow(text = "PAYMENT CHANNEL")
                    Spacer(Modifier.height(6.dp))
                    PaymentChannelPicker(
                        selected = paymentMethod,
                        onSelected = { paymentMethod = it },
                        label = null
                    )
                }

                Column {
                    Eyebrow(text = "CATEGORY")
                    Spacer(Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
                        verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
                    ) {
                        typeCategories.forEach { category ->
                            FilterChipItem(
                                text = category.name,
                                selected = category.id == selectedCategoryId,
                                onClick = { selectedCategoryId = category.id }
                            )
                        }
                    }
                }

                if (!error.isNullOrBlank()) {
                    AuxText(text = error!!, color = SmartLedgerColors.expense)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val amount = amountText.toDoubleOrNull()
                when {
                    amount == null || amount <= 0 ->
                        error = "请输入大于 0 的金额"

                    amount > 1_000_000 ->
                        error = "金额过大，请检查"

                    else -> {
                        onSave(
                            transaction.copy(
                                amount = amount,
                                merchant = merchant.trim().ifBlank { null },
                                note = note.trim().ifBlank { null },
                                paymentMethod = paymentMethod,
                                categoryId = selectedCategoryId,
                                transactionTime = txTime
                            )
                        )
                        onDismiss()
                    }
                }
            }) {
                Text("保存", style = AppType.button, color = SmartLedgerColors.fg)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", style = AppType.button, color = SmartLedgerColors.fgSecondary)
            }
        }
    )
}

/** 删除确认 */
@Composable
fun DeleteTransactionDialog(
    transaction: Transaction,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    SmartLedgerDialog(
        onDismissRequest = onDismiss,
        icon = Icons.Outlined.Delete,
        iconTint = SmartLedgerColors.expense,
        eyebrow = "DELETE",
        title = "删除账单",
        text = "确定要删除这条 ${if (transaction.type == "expense") "支出" else "收入"} " +
                "${formatMoney(transaction.amount)} 的记录吗？\n\n" +
                "${transaction.merchant?.takeIf { it.isNotBlank() } ?: "无商户"} · " +
                DateUtil.formatDateTime(transaction.transactionTime) + "\n\n删除后无法恢复。",
        confirmText = "删除",
        confirmColor = SmartLedgerColors.expense,
        onConfirm = onConfirm,
        dismissText = "取消",
        onDismiss = onDismiss
    )
}
