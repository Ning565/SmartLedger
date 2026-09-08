package com.smartledger.ui.statistics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smartledger.data.ai.model.AiError
import com.smartledger.data.analytics.model.SummaryPeriod
import com.smartledger.data.db.entity.AiReportEntity
import com.smartledger.ui.components.AuxText
import com.smartledger.ui.components.Eyebrow
import com.smartledger.ui.components.Hairline
import com.smartledger.ui.components.IconButtonQuiet
import com.smartledger.ui.components.MarkdownText
import com.smartledger.ui.components.PillTone
import com.smartledger.ui.components.PrimaryButton
import com.smartledger.ui.components.QuietButton
import com.smartledger.ui.components.SecondaryButton
import com.smartledger.ui.components.SegmentedTabs
import com.smartledger.ui.components.SectionCard
import com.smartledger.ui.components.SmartLedgerInputDialog
import com.smartledger.ui.components.StatusPill
import com.smartledger.ui.components.SubPanel
import com.smartledger.ui.components.formatMoney
import com.smartledger.ui.theme.AppRadius
import com.smartledger.ui.theme.AppSpacing
import com.smartledger.ui.theme.AppType
import com.smartledger.ui.theme.SmartLedgerColors
import com.smartledger.util.DateUtil

/**
 * ✨ AI 消费体检卡片。
 *
 * ## 视觉
 * Paper Strong 底 + Violet 细边框，标题左侧一个 Violet 的 ✨ 图标。
 * 不用大面积 Lavender 填充 —— 规范里 Lavender 是「淡紫背景」的可选方案，
 * 但整块淡紫会和统计页的暖纸底打架，细边框更克制、也更符合
 * 「AI 元素有明显但不过度」的要求。
 *
 * ## 交互
 * 生成中用**克制的流式文本**直接铺开，不做聊天气泡界面 ——
 * 这是一份报告，不是一段对话。
 *
 * ## 触发
 * 只有用户点击才发请求。打开页面、切换 Tab、新增账单都不会触发。
 */
@Composable
fun AiReportCard(
    state: AiReportUiState,
    period: SummaryPeriod,
    expanded: Boolean,
    adoptPrompt: Double?,
    onPeriodChange: (SummaryPeriod) -> Unit,
    onGenerate: () -> Unit,
    onRegenerate: () -> Unit,
    /** 直接展示缓存报告，不重发请求（与 onGenerate 必须分开，否则会白烧额度） */
    onViewCached: (AiReportEntity) -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    onDismissReport: () -> Unit,
    onToggleExpanded: () -> Unit,
    onOpenAdopt: () -> Unit,
    onDismissAdopt: () -> Unit,
    onAdoptConfirm: (Double, Double, (String) -> Unit) -> Unit,
    onNavigateToAiSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    SectionCard(
        modifier = modifier,
        large = true,
        border = SmartLedgerColors.ai.copy(alpha = 0.35f),
        contentPadding = PaddingValues(AppSpacing.lg)
    ) {
        // ═══ 卡头：✨ + 标题 + 周期切换 ═══
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(AppRadius.small))
                    .background(SmartLedgerColors.aiDim),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    tint = SmartLedgerColors.ai,
                    modifier = Modifier.size(16.dp)
                )
            }
            Spacer(Modifier.width(AppSpacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Eyebrow(text = "AI ADVISOR", color = SmartLedgerColors.ai)
                Text(
                    text = "${period.label}消费体检",
                    style = AppType.listPrimary,
                    color = SmartLedgerColors.fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // 报告展开时收起周期切换，避免生成中途换周期
            if (state !is AiReportUiState.Success &&
                state !is AiReportUiState.Streaming &&
                state !is AiReportUiState.Preparing
            ) {
                SegmentedTabs(
                    items = listOf("本月" to SummaryPeriod.THIS_MONTH.name, "近3月" to SummaryPeriod.LAST_3_MONTHS.name),
                    selected = period.name,
                    onSelect = { name ->
                        onPeriodChange(SummaryPeriod.fromName(name))
                    },
                    modifier = Modifier.width(150.dp)
                )
            } else {
                // 生成中用 Stop（中止），已完成用 Close（收起）——
                // 两个动作语义不同，图标不能混用，否则用户会以为点一下就把报告删了
                val generating = state is AiReportUiState.Streaming ||
                        state is AiReportUiState.Preparing
                IconButtonQuiet(
                    icon = if (generating) Icons.Outlined.Stop else Icons.Outlined.Close,
                    contentDescription = if (generating) "停止生成" else "收起报告",
                    onClick = if (generating) onStop else onDismissReport,
                    tint = if (generating) SmartLedgerColors.expense else SmartLedgerColors.fgTertiary
                )
            }
        }

        Spacer(Modifier.height(AppSpacing.md))

        when (state) {
            AiReportUiState.NotConfigured -> NotConfiguredBody(onNavigateToAiSettings)

            AiReportUiState.NoData -> {
                AuxText(text = "${period.label}还没有支出记录，记几笔账后再来看看。")
            }

            is AiReportUiState.Idle -> IdleBody(
                cached = state.cached,
                dataChanged = state.dataChanged,
                expenseCount = state.expenseCount,
                period = period,
                onGenerate = onGenerate,
                // IdleBody 只在 cached != null 时才渲染「查看报告」按钮，
                // 这里再兜一层空安全，避免将来改动按钮条件时静默传 null
                onView = { state.cached?.let(onViewCached) },
                onRegenerate = onRegenerate
            )

            AiReportUiState.Preparing -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        color = SmartLedgerColors.ai,
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(AppSpacing.sm))
                    AuxText(text = "正在本地聚合你的消费数据…", color = SmartLedgerColors.fgSecondary)
                }
            }

            is AiReportUiState.Streaming -> StreamingBody(
                text = state.text,
                onStop = onStop
            )

            is AiReportUiState.Success -> SuccessBody(
                report = state.report,
                truncated = state.truncated,
                fromReasoning = state.fromReasoning,
                expanded = expanded,
                onToggleExpanded = onToggleExpanded,
                onRegenerate = onRegenerate,
                onOpenAdopt = onOpenAdopt
            )

            is AiReportUiState.Failed -> FailedBody(
                error = state.error,
                partialText = state.partialText,
                onRetry = onRetry,
                onNavigateToAiSettings = onNavigateToAiSettings
            )
        }
    }

    // ═══ 采纳建议额度：必须二次确认，且确认前可改金额 ═══
    if (adoptPrompt != null) {
        var input by remember(adoptPrompt) {
            mutableStateOf(trimAmount(adoptPrompt))
        }
        var error by remember { mutableStateOf<String?>(null) }
        val nextMonth = remember {
            DateUtil.shiftYearMonth(DateUtil.getCurrentYearMonth(), 1)
        }

        SmartLedgerInputDialog(
            onDismissRequest = onDismissAdopt,
            eyebrow = "BUDGET",
            title = "设置 $nextMonth 可用额度",
            label = "月度支出上限",
            value = input,
            prefix = "¥",
            helper = "来自 AI 建议 ${formatMoney(adoptPrompt)}，你可以修改后再确认。" +
                    "AI 不会自动改动你的预算。",
            confirmText = "确认设置",
            error = error,
            onValueChange = { raw ->
                // 只接受金额形态，避免把非法字符串带进数据库
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
                onAdoptConfirm(v, adoptPrompt) { msg ->
                    error = if (msg.startsWith("已设置")) null else msg
                    if (error == null) onDismissAdopt()
                }
            }
        )
    }
}

// ═══════════════════════════════════════════════════════
// 各状态的卡片内容
// ═══════════════════════════════════════════════════════

@Composable
private fun NotConfiguredBody(onNavigateToAiSettings: () -> Unit) {
    AuxText(
        text = "配置 AI 服务后，可基于本地统计数据生成消费结构诊断与节流建议。" +
                "不上传通知原文、真实商户名与备注。"
    )
    Spacer(Modifier.height(AppSpacing.md))
    SecondaryButton(
        text = "去设置",
        onClick = onNavigateToAiSettings,
        tone = PillTone.AI
    )
}

@Composable
private fun IdleBody(
    cached: AiReportEntity?,
    dataChanged: Boolean,
    expenseCount: Int,
    period: SummaryPeriod,
    onGenerate: () -> Unit,
    onView: () -> Unit,
    onRegenerate: () -> Unit
) {
    if (cached == null) {
        AuxText(
            text = if (expenseCount > 0) {
                "将基于你${period.label} $expenseCount 笔支出的本地统计生成分析。"
            } else {
                "将基于你${period.label}的本地消费统计生成分析。"
            }
        )
        Spacer(Modifier.height(AppSpacing.md))
        PrimaryButton(
            text = "生成${period.label}报告",
            onClick = onGenerate,
            enabled = expenseCount > 0
        )
        if (expenseCount == 0) {
            Spacer(Modifier.height(AppSpacing.sm))
            AuxText(text = "本周期还没有支出记录", color = SmartLedgerColors.fgTertiary)
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                AuxText(
                    text = "上次分析：${DateUtil.formatDateTime(cached.createdAt)} · ${cached.model}",
                    color = SmartLedgerColors.fgSecondary
                )
                if (dataChanged) {
                    Spacer(Modifier.height(2.dp))
                    AuxText(
                        text = "此后又有新账单，报告数据已不是最新",
                        color = SmartLedgerColors.warning
                    )
                }
                if (cached.truncated) {
                    Spacer(Modifier.height(2.dp))
                    AuxText(text = "上次生成被中断，内容可能不完整", color = SmartLedgerColors.fgTertiary)
                }
            }
        }
        Spacer(Modifier.height(AppSpacing.md))
        Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
            SecondaryButton(text = "查看报告", onClick = onView, modifier = Modifier.weight(1f))
            SecondaryButton(
                text = "重新生成",
                onClick = onRegenerate,
                modifier = Modifier.weight(1f),
                tone = PillTone.AI
            )
        }
    }
}

@Composable
private fun StreamingBody(text: String, onStop: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            color = SmartLedgerColors.ai,
            strokeWidth = 2.dp
        )
        Spacer(Modifier.width(AppSpacing.sm))
        AuxText(
            text = if (text.isEmpty()) "正在分析你的消费结构…" else "正在生成报告…",
            color = SmartLedgerColors.fgSecondary,
            modifier = Modifier.weight(1f)
        )
        QuietButton(text = "停止生成", onClick = onStop, color = SmartLedgerColors.expense)
    }
    if (text.isNotEmpty()) {
        Spacer(Modifier.height(AppSpacing.md))
        Hairline()
        Spacer(Modifier.height(AppSpacing.md))
        // 流式文本直接铺开，不做聊天气泡：这是一份报告，不是一段对话
        MarkdownText(markdown = text)
    }
}

@Composable
private fun SuccessBody(
    report: AiReportEntity,
    truncated: Boolean,
    fromReasoning: Boolean,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onRegenerate: () -> Unit,
    onOpenAdopt: () -> Unit
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Eyebrow(text = "REPORT · ${report.periodLabel}", modifier = Modifier.weight(1f))
            AuxText(
                text = DateUtil.formatDateTime(report.createdAt),
                color = SmartLedgerColors.fgTertiary
            )
        }

        Spacer(Modifier.height(AppSpacing.md))
        Hairline()
        Spacer(Modifier.height(AppSpacing.md))

        if (truncated) {
            StatusPill(text = "生成被中断，内容可能不完整", tone = PillTone.WARNING)
            Spacer(Modifier.height(AppSpacing.sm))
        }
        if (fromReasoning) {
            StatusPill(
                text = "该模型为推理模型，正文由思考过程兜底，建议改用 deepseek-chat",
                tone = PillTone.WARNING
            )
            Spacer(Modifier.height(AppSpacing.sm))
        }

        val shown = if (expanded) report.markdown else collapse(report.markdown)
        MarkdownText(markdown = shown)

        if (!expanded && report.markdown != shown) {
            Spacer(Modifier.height(AppSpacing.sm))
            QuietButton(text = "展开全文", onClick = onToggleExpanded, color = SmartLedgerColors.ai)
        } else if (expanded && report.markdown.length > COLLAPSE_LINE_LIMIT * 40) {
            QuietButton(text = "收起", onClick = onToggleExpanded, color = SmartLedgerColors.fgTertiary)
        }

        // ═══ 建议可用总额度 ═══
        if (report.suggestedBudget != null && report.suggestedBudget > 0) {
            Spacer(Modifier.height(AppSpacing.md))
            SubPanel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Eyebrow(text = "SUGGESTED BUDGET")
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = formatMoney(report.suggestedBudget),
                            style = AppType.cardNumberSmall,
                            color = SmartLedgerColors.fg
                        )
                    }
                    SecondaryButton(
                        text = "采纳为下月预算",
                        onClick = onOpenAdopt,
                        tone = PillTone.AI
                    )
                }
            }
        }

        Spacer(Modifier.height(AppSpacing.md))
        Hairline()
        Spacer(Modifier.height(AppSpacing.sm))
        AuxText(
            text = "报告中商户已匿名编号，编号仅在本次报告内有效。" +
                    "AI 生成内容仅供参考，不构成投资建议。",
            color = SmartLedgerColors.fgTertiary
        )
        Spacer(Modifier.height(AppSpacing.sm))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            SecondaryButton(text = "重新生成", onClick = onRegenerate, tone = PillTone.AI)
        }
    }
}

@Composable
private fun FailedBody(
    error: AiError,
    partialText: String?,
    onRetry: () -> Unit,
    onNavigateToAiSettings: () -> Unit
) {
    StatusPill(text = "生成失败", tone = PillTone.DANGER)
    Spacer(Modifier.height(AppSpacing.sm))
    AuxText(text = error.userMessage, color = SmartLedgerColors.expense)

    if (!partialText.isNullOrBlank()) {
        Spacer(Modifier.height(AppSpacing.md))
        AuxText(text = "已生成的部分：", color = SmartLedgerColors.fgSecondary)
        Spacer(Modifier.height(AppSpacing.sm))
        MarkdownText(markdown = partialText)
    }

    Spacer(Modifier.height(AppSpacing.md))
    Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
        SecondaryButton(text = "重试", onClick = onRetry, modifier = Modifier.weight(1f))
        // 只有配置类错误才给「去设置」，网络错误给了也没用
        if (error is AiError.InvalidApiKey || error is AiError.ModelNotFound ||
            error is AiError.NotConfigured || error is AiError.Incompatible ||
            error is AiError.Cleartext || error is AiError.EmptyReasoningOutput
        ) {
            SecondaryButton(
                text = "检查配置",
                onClick = onNavigateToAiSettings,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

// ═══════════════════════════════════════════════════════
// 工具
// ═══════════════════════════════════════════════════════

/** 折叠时只保留前若干行 */
private const val COLLAPSE_LINE_LIMIT = 8

private fun collapse(markdown: String): String {
    val lines = markdown.split('\n')
    if (lines.size <= COLLAPSE_LINE_LIMIT) return markdown
    return lines.take(COLLAPSE_LINE_LIMIT).joinToString("\n") + "\n…"
}

/** 4800.0 → "4800"，4800.5 → "4800.5"，避免输入框里出现多余的 .0 */
private fun trimAmount(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString()
    else String.format(java.util.Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')

/** 供统计页在 LazyColumn 里给卡片留白用 */
internal val AiCardBottomGap = AppSpacing.sectionGap
