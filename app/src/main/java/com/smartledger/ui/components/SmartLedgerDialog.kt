package com.smartledger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.smartledger.ui.theme.AppRadius
import com.smartledger.ui.theme.AppSize
import com.smartledger.ui.theme.AppSpacing
import com.smartledger.ui.theme.AppType
import com.smartledger.ui.theme.SmartLedgerColors

/**
 * SmartLedger 统一弹窗。
 *
 * 所有弹窗都走这里，保证风格一致：纸面底 + 1dp Ink 细边框 + 24dp 圆角，
 * **阴影极轻**（靠 tonalElevation 而不是 shadowElevation 撑层次），
 * 与「学术、安静」的整体基调一致。
 *
 * 签名与旧版保持兼容（所有新增参数都有默认值），因此既有调用点无需改动。
 */
@Composable
fun SmartLedgerDialog(
    onDismissRequest: () -> Unit,
    title: String,
    text: String? = null,
    icon: ImageVector? = null,
    iconTint: Color? = null,
    confirmText: String? = null,
    confirmColor: Color = SmartLedgerColors.accent,
    onConfirm: (() -> Unit)? = null,
    dismissText: String = "取消",
    onDismiss: (() -> Unit)? = null,
    /** 英文 kicker，如 "AI ADVISOR"；不传则不显示 */
    eyebrow: String? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        shape = RoundedCornerShape(AppRadius.cardLarge),
        containerColor = SmartLedgerColors.surface,
        // tonalElevation = 0：不让 Material 再叠一层色调提升，
        // 弹窗层次完全靠容器色（PaperStrong）与背后的 scrim 区分，
        // 与「阴影极轻、靠背景层级和留白分层」的整体基调一致。
        //
        // 注意：material3 1.3.1 的 AlertDialog **既没有 border 也没有 shadowElevation**
        // （两者都是 1.4.0 之后才加的），不要照着新版文档传，会直接编译失败。
        tonalElevation = 0.dp,
        titleContentColor = SmartLedgerColors.fg,
        textContentColor = SmartLedgerColors.fgSecondary,
        icon = icon?.let {
            {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(AppRadius.card))
                        .background(SmartLedgerColors.surfaceHover),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        it,
                        contentDescription = null,
                        tint = iconTint ?: SmartLedgerColors.fg,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        },
        title = {
            Column {
                if (!eyebrow.isNullOrBlank()) {
                    Eyebrow(text = eyebrow)
                    Spacer(Modifier.height(2.dp))
                }
                Text(
                    title,
                    style = AppType.sectionTitle,
                    color = SmartLedgerColors.fg
                )
            }
        },
        text = {
            // 必须包一层 Column：AlertDialog 的 text 槽位不是纵向布局，
            // 否则文案与 progress 等 content 会叠在一起被「压扁」
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.md)
            ) {
                if (text != null) {
                    Text(
                        text,
                        style = AppType.body,
                        color = SmartLedgerColors.fgSecondary
                    )
                }
                if (content != null) {
                    content()
                }
            }
        },
        confirmButton = {
            if (confirmText != null && onConfirm != null) {
                Button(
                    onClick = onConfirm,
                    shape = RoundedCornerShape(AppRadius.pill),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = confirmColor,
                        contentColor = SmartLedgerColors.onAccent
                    ),
                    contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp)
                ) {
                    Text(confirmText, style = AppType.button)
                }
            }
        },
        dismissButton = {
            if (dismissText.isNotBlank()) {
                TextButton(
                    onClick = { onDismiss?.invoke() ?: onDismissRequest() },
                    shape = RoundedCornerShape(AppRadius.pill),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    Text(
                        dismissText,
                        style = AppType.button,
                        color = SmartLedgerColors.fgSecondary
                    )
                }
            }
        }
    )
}

/**
 * 带输入框的弹窗。
 *
 * @param helper 输入框下方的辅助说明（如「来自 AI 建议，你可以修改后再确认」）
 * @param error  校验失败文案。必须有这个参数：
 *               否则用户输了非法值点「保存」会**静默没反应**，
 *               既不知道为什么失败，也不知道该怎么改。
 */
@Composable
fun SmartLedgerInputDialog(
    onDismissRequest: () -> Unit,
    title: String,
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    prefix: String? = null,
    confirmText: String = "保存",
    eyebrow: String? = null,
    helper: String? = null,
    error: String? = null,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        shape = RoundedCornerShape(AppRadius.cardLarge),
        containerColor = SmartLedgerColors.surface,
        tonalElevation = 0.dp,
        titleContentColor = SmartLedgerColors.fg,
        textContentColor = SmartLedgerColors.fgSecondary,
        title = {
            Column {
                if (!eyebrow.isNullOrBlank()) {
                    Eyebrow(text = eyebrow)
                    Spacer(Modifier.height(2.dp))
                }
                Text(title, style = AppType.sectionTitle, color = SmartLedgerColors.fg)
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    label = { Text(label, style = AppType.aux) },
                    prefix = prefix?.let { { Text(it, style = AppType.body) } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(AppRadius.field),
                    textStyle = AppType.cardNumberSmall,
                    isError = !error.isNullOrBlank(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = if (error.isNullOrBlank()) SmartLedgerColors.fg
                        else SmartLedgerColors.expense,
                        unfocusedBorderColor = if (error.isNullOrBlank()) SmartLedgerColors.borderStrong
                        else SmartLedgerColors.expense,
                        focusedLabelColor = SmartLedgerColors.fgSecondary,
                        unfocusedLabelColor = SmartLedgerColors.fgTertiary,
                        cursorColor = SmartLedgerColors.fg,
                        focusedContainerColor = SmartLedgerColors.surface,
                        unfocusedContainerColor = SmartLedgerColors.surface
                    )
                )
                // 错误优先于 helper：两者同时存在时只该看到错误
                if (!error.isNullOrBlank()) {
                    Spacer(Modifier.height(AppSpacing.sm))
                    AuxText(text = error, color = SmartLedgerColors.expense)
                } else if (!helper.isNullOrBlank()) {
                    Spacer(Modifier.height(AppSpacing.sm))
                    AuxText(text = helper, color = SmartLedgerColors.fgTertiary)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                shape = RoundedCornerShape(AppRadius.pill),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SmartLedgerColors.accent,
                    contentColor = SmartLedgerColors.onAccent
                ),
                contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp)
            ) {
                Text(confirmText, style = AppType.button)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismissRequest,
                shape = RoundedCornerShape(AppRadius.pill),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Text("取消", style = AppType.button, color = SmartLedgerColors.fgSecondary)
            }
        }
    )
}

/**
 * 选择型弹窗（单列选项，如 AI 服务商预设）。
 *
 * 不用 Material 的 ExposedDropdownMenu：那需要 Anchor 布局且样式难改，
 * 在暖纸底上显得突兀。用一个简洁的列表弹窗更贴合设计系统。
 */
@Composable
fun <T> SmartLedgerPickerDialog(
    onDismissRequest: () -> Unit,
    title: String,
    options: List<T>,
    labelOf: (T) -> String,
    sublabelOf: ((T) -> String)? = null,
    selected: T?,
    onSelect: (T) -> Unit,
    eyebrow: String? = null
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        shape = RoundedCornerShape(AppRadius.cardLarge),
        containerColor = SmartLedgerColors.surface,
        tonalElevation = 0.dp,
        title = {
            Column {
                if (!eyebrow.isNullOrBlank()) {
                    Eyebrow(text = eyebrow)
                    Spacer(Modifier.height(2.dp))
                }
                Text(title, style = AppType.sectionTitle, color = SmartLedgerColors.fg)
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
            ) {
                options.forEach { opt ->
                    val isSel = opt == selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = AppSize.minTouchTarget)
                            .clip(RoundedCornerShape(AppRadius.field))
                            .background(
                                if (isSel) SmartLedgerColors.accentDim else Color.Transparent
                            )
                            .clickable(onClick = { onSelect(opt) })
                            .padding(horizontal = AppSpacing.md),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                labelOf(opt),
                                style = AppType.listPrimary,
                                color = SmartLedgerColors.fg,
                                fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Medium
                            )
                            val sub = sublabelOf?.invoke(opt)
                            if (!sub.isNullOrBlank()) {
                                AuxText(text = sub, color = SmartLedgerColors.fgTertiary, maxLines = 1)
                            }
                        }
                        if (isSel) {
                            Spacer(Modifier.width(AppSpacing.sm))
                            Icon(
                                Icons.Outlined.Check,
                                contentDescription = null,
                                tint = SmartLedgerColors.fg,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(
                onClick = onDismissRequest,
                shape = RoundedCornerShape(AppRadius.pill)
            ) {
                Text("取消", style = AppType.button, color = SmartLedgerColors.fgSecondary)
            }
        }
    )
}
