package com.smartledger.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.smartledger.ui.theme.AppSpacing
import com.smartledger.util.PaymentMethods

/**
 * 支付渠道选择：预设 pill chip + 自定义输入。
 *
 * 修掉了原实现的一个问题：点击「自定义」时会立刻
 * `onSelected(customText.trim())`，而此时 customText 还是空串，
 * 于是把渠道写成了 ""。虽然保存时有 `ifBlank { "其他" }` 兜底，
 * 但中间态会让 chip 全部失去选中高亮，看起来像"什么都没选"。
 * 现在改为：**只有真的输入了内容才回写**，点「自定义」仅切换输入模式。
 *
 * @param selected 当前渠道名（预设或自定义文案）
 * @param label    传 null 则不显示标题（外层已有 eyebrow 时用）
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PaymentChannelPicker(
    selected: String,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = "渠道"
) {
    var customMode by remember {
        mutableStateOf(selected.isNotBlank() && !PaymentMethods.isPreset(selected))
    }
    var customText by remember {
        mutableStateOf(
            if (selected.isNotBlank() && !PaymentMethods.isPreset(selected)) selected else ""
        )
    }

    // 外部切换账单时同步（编辑弹窗里换了另一条账单）
    LaunchedEffect(selected) {
        if (PaymentMethods.isPreset(selected)) {
            customMode = false
        } else if (selected.isNotBlank()) {
            customMode = true
            customText = selected
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        if (!label.isNullOrBlank()) {
            Eyebrow(text = label)
            Spacer(Modifier.height(6.dp))
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
        ) {
            PaymentMethods.PRESETS.forEach { method ->
                FilterChipItem(
                    text = method,
                    selected = !customMode && selected == method,
                    onClick = {
                        customMode = false
                        onSelected(method)
                    }
                )
            }
            FilterChipItem(
                text = PaymentMethods.CUSTOM_LABEL,
                selected = customMode,
                onClick = {
                    customMode = true
                    // 已有自定义文案时才回写；空文案不动 selected，
                    // 避免把渠道清成空串
                    val text = customText.trim()
                    if (text.isNotEmpty()) onSelected(text)
                }
            )
        }
        if (customMode) {
            Spacer(Modifier.height(AppSpacing.sm))
            AppTextField(
                value = customText,
                onValueChange = {
                    customText = it
                    val trimmed = it.trim()
                    // 输入过程中允许为空（用户正在删改），但空值不回写，
                    // 由保存时的 ifBlank { "其他" } 兜底
                    if (trimmed.isNotEmpty()) onSelected(trimmed)
                },
                placeholder = "输入渠道名称，如招商银行"
            )
        }
    }
}
