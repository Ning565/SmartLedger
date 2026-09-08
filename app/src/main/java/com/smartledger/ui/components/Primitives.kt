package com.smartledger.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartledger.ui.theme.AppMotion
import com.smartledger.ui.theme.AppRadius
import com.smartledger.ui.theme.AppSize
import com.smartledger.ui.theme.AppSpacing
import com.smartledger.ui.theme.AppType
import com.smartledger.ui.theme.SmartLedgerColors
import com.smartledger.ui.theme.asEyebrow
import com.smartledger.ui.theme.rememberReducedMotion
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * SmartLedger 设计系统基础组件。
 *
 * 统一组件语言：**英文 kicker/eyebrow + 标题 + 主数字 + 辅助说明**，
 * 重要状态用简洁 Status Pill。所有页面一律复用这里的组件，
 * 不逐页临时发挥，以保证「个人财务研究仪表盘」的一致观感。
 *
 * 视觉手段的优先级（严格遵循，禁止大面积玻璃拟态/霓虹/渐变/3D/强阴影）：
 *   背景层级 > 1dp 细边框 > 留白  >>  阴影
 */

// ═══════════════════════════════════════════════════════
// 卡片
// ═══════════════════════════════════════════════════════

/**
 * 标准卡片：Paper Strong 底 + 1dp Ink 12% 边框 + 极轻阴影。
 *
 * @param large    true → 24dp 圆角（Hero / 预算卡 / AI 卡）；false → 18dp（普通卡片）
 * @param tint     覆盖背景色，例如 AI 卡用 Lavender 淡紫底
 * @param border   覆盖边框色，例如 AI 卡用 Violet 细边框
 */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    large: Boolean = false,
    tint: Color? = null,
    border: Color? = null,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(AppSpacing.cardPadding),
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(if (large) AppRadius.cardLarge else AppRadius.card)
    val bg = tint ?: SmartLedgerColors.surface
    val stroke = BorderStroke(AppSize.hairline, border ?: SmartLedgerColors.border)

    // 阴影极轻：1dp，仅用来把卡片从纸面上「抬起」一点点
    Box(
        modifier = modifier
            .fillMaxWidth()
            .shadow(elevation = 1.dp, shape = shape, clip = false)
            .clip(shape)
            .background(bg)
            .border(stroke, shape)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClick
                    )
                } else Modifier
            )
            .padding(contentPadding)
    ) {
        Column(modifier = Modifier.fillMaxWidth(), content = content)
    }
}

/** 次级分区容器：Paper Deep 底，用于卡片内部的嵌套分组 */
@Composable
fun SubPanel(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(AppSpacing.md),
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(AppRadius.field)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(SmartLedgerColors.surfaceHover)
            .padding(contentPadding)
    ) {
        Column(modifier = Modifier.fillMaxWidth(), content = content)
    }
}

// ═══════════════════════════════════════════════════════
// 文字层级
// ═══════════════════════════════════════════════════════

/**
 * 英文 kicker / eyebrow：全大写 + 大字距。
 * 顶会海报图表标注的典型样式，为区块提供一层元信息。
 */
@Composable
fun Eyebrow(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = SmartLedgerColors.fgTertiary
) {
    Text(
        text = text.asEyebrow(),
        style = AppType.eyebrow,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

/**
 * 区块标题组：eyebrow（可选）+ 标题 + 右侧 trailing。
 * 页面里所有「区块标题」都走这里，保证间距与层级完全一致。
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    eyebrowColor: Color = SmartLedgerColors.fgTertiary,
    titleColor: Color = SmartLedgerColors.fg,
    trailing: (@Composable RowScope.() -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            if (!eyebrow.isNullOrBlank()) {
                Eyebrow(text = eyebrow, color = eyebrowColor)
                Spacer(Modifier.height(2.dp))
            }
            Text(
                text = title,
                style = AppType.sectionTitle,
                color = titleColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(AppSpacing.sm))
            trailing()
        }
    }
}

/** 辅助说明文字 */
@Composable
fun AuxText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = SmartLedgerColors.fgSecondary,
    maxLines: Int = Int.MAX_VALUE
) {
    Text(
        text = text,
        style = AppType.aux,
        color = color,
        maxLines = maxLines,
        overflow = if (maxLines == 1) TextOverflow.Ellipsis else TextOverflow.Clip,
        modifier = modifier
    )
}

// ═══════════════════════════════════════════════════════
// Status Pill
// ═══════════════════════════════════════════════════════

/** Pill 语义色。同一屏不要同时大面积使用多个 tone。 */
enum class PillTone { NEUTRAL, POSITIVE, WARNING, DANGER, INFO, AI }

/**
 * 简洁状态标签：pill 形态 + 同色系 10% 浅底 + 语义色文字。
 * 不使用实心大色块，保持安静。
 */
@Composable
fun StatusPill(
    text: String,
    tone: PillTone = PillTone.NEUTRAL,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null
) {
    val fg = when (tone) {
        PillTone.NEUTRAL -> SmartLedgerColors.fgSecondary
        PillTone.POSITIVE -> SmartLedgerColors.income
        PillTone.WARNING -> SmartLedgerColors.warning
        PillTone.DANGER -> SmartLedgerColors.expense
        PillTone.INFO -> SmartLedgerColors.info
        PillTone.AI -> SmartLedgerColors.ai
    }
    val bg = when (tone) {
        PillTone.NEUTRAL -> SmartLedgerColors.accentDim
        PillTone.POSITIVE -> SmartLedgerColors.incomeDim
        PillTone.WARNING -> SmartLedgerColors.warningDim
        PillTone.DANGER -> SmartLedgerColors.expenseDim
        PillTone.INFO -> SmartLedgerColors.infoDim
        PillTone.AI -> SmartLedgerColors.aiDim
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(AppRadius.pill))
            .background(bg)
            .heightIn(min = 24.dp)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(13.dp)
            )
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text = text,
            style = AppType.pill,
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ═══════════════════════════════════════════════════════
// 按钮
// ═══════════════════════════════════════════════════════

/**
 * 主按钮：Ink 实心 + 纸白文字，高度 48dp，pill 圆角。
 * 深色模式下自动反转为纸白底 + 墨色文字。
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null
) {
    val bg = if (enabled) SmartLedgerColors.accent else SmartLedgerColors.accentDim
    val fg = if (enabled) SmartLedgerColors.onAccent else SmartLedgerColors.fgTertiary

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = AppSize.buttonHeight)
            .clip(RoundedCornerShape(AppRadius.pill))
            .background(bg)
            .clickable(enabled = enabled && !loading, onClick = onClick)
            .padding(horizontal = AppSpacing.lg),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = fg,
                strokeWidth = 2.dp
            )
            Spacer(Modifier.width(AppSpacing.sm))
        } else if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(AppSpacing.sm))
        }
        Text(
            text = text,
            style = AppType.button,
            color = fg,
            maxLines = 1
        )
    }
}

/** 次级按钮：纸面底 + 1dp 边框 + 墨色文字 */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    tone: PillTone = PillTone.NEUTRAL
) {
    val fg = when (tone) {
        PillTone.AI -> SmartLedgerColors.ai
        PillTone.DANGER -> SmartLedgerColors.expense
        PillTone.INFO -> SmartLedgerColors.info
        PillTone.POSITIVE -> SmartLedgerColors.income
        PillTone.WARNING -> SmartLedgerColors.warning
        PillTone.NEUTRAL -> if (enabled) SmartLedgerColors.fg else SmartLedgerColors.fgTertiary
    }
    val strokeColor = if (enabled) SmartLedgerColors.borderStrong else SmartLedgerColors.border

    Row(
        modifier = modifier
            .heightIn(min = AppSize.buttonHeightCompact)
            .clip(RoundedCornerShape(AppRadius.pill))
            .background(SmartLedgerColors.surface)
            .border(AppSize.hairline, strokeColor, RoundedCornerShape(AppRadius.pill))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = AppSpacing.lg),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text = text, style = AppType.button, color = fg, maxLines = 1)
    }
}

/** 文字按钮：无边框无底色，用于「重新生成」「取消」等轻量动作 */
@Composable
fun QuietButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = SmartLedgerColors.fgSecondary
) {
    Box(
        modifier = modifier
            .heightIn(min = AppSize.minTouchTarget)
            .clip(RoundedCornerShape(AppRadius.small))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = AppSpacing.sm),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = AppType.button,
            color = if (enabled) color else SmartLedgerColors.fgTertiary,
            maxLines = 1
        )
    }
}

/** 图标按钮：保证 48dp 最小触控区域 */
@Composable
fun IconButtonQuiet(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = SmartLedgerColors.fg,
    enabled: Boolean = true
) {
    Box(
        modifier = modifier
            .sizeIn(minWidth = AppSize.minTouchTarget, minHeight = AppSize.minTouchTarget)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) tint else SmartLedgerColors.fgTertiary,
            modifier = Modifier.size(20.dp)
        )
    }
}

// ═══════════════════════════════════════════════════════
// Chip（pill 形态筛选条件）
// ═══════════════════════════════════════════════════════

@Composable
fun FilterChipItem(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null
) {
    val bg = if (selected) SmartLedgerColors.accent else Color.Transparent
    val fg = if (selected) SmartLedgerColors.onAccent else SmartLedgerColors.fgSecondary
    val stroke = if (selected) SmartLedgerColors.accent else SmartLedgerColors.borderStrong

    Row(
        modifier = modifier
            .heightIn(min = 34.dp)
            .clip(RoundedCornerShape(AppRadius.pill))
            .background(bg)
            .border(AppSize.hairline, stroke, RoundedCornerShape(AppRadius.pill))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(5.dp))
        }
        Text(text = text, style = AppType.pill, color = fg, maxLines = 1)
    }
}

/** 分段切换（日/周/月/年 这类互斥周期）：pill 容器 + 选中项墨色填充 */
@Composable
fun SegmentedTabs(
    items: List<Pair<String, String>>,   // label to value
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(AppRadius.pill)
    Row(
        modifier = modifier
            .clip(shape)
            .background(SmartLedgerColors.surfaceHover)
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items.forEach { (label, value) ->
            val isSel = value == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 34.dp)
                    .clip(shape)
                    .background(if (isSel) SmartLedgerColors.surface else Color.Transparent)
                    .clickable { onSelect(value) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    style = AppType.pill,
                    color = if (isSel) SmartLedgerColors.fg else SmartLedgerColors.fgSecondary,
                    fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════
// 分隔线 / 进度条
// ═══════════════════════════════════════════════════════

/** 1dp 细线，层次的主要手段之一 */
@Composable
fun Hairline(
    modifier: Modifier = Modifier,
    color: Color = SmartLedgerColors.border,
    thickness: Dp = AppSize.hairline
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(thickness)
            .background(color)
    )
}

/**
 * 细线性进度（预算 / 分类占比）。
 * 平面、低饱和，无渐变、无 3D。
 */
@Composable
fun ThinProgressBar(
    progress: Float,                       // 0f..1f，超出会被钳制
    color: Color,
    modifier: Modifier = Modifier,
    trackColor: Color = SmartLedgerColors.surfaceHover,
    thickness: Dp = AppSize.barThickness
) {
    val reduced = rememberReducedMotion()
    val animated = animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(if (reduced) 0 else AppMotion.NumberMs),
        label = "thinProgress"
    )
    val shape = RoundedCornerShape(AppRadius.pill)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(thickness)
            .clip(shape)
            .background(trackColor)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(animated.value)
                .fillMaxHeight()
                .clip(shape)
                .background(color)
        )
    }
}

/**
 * 细环形进度（统计页总支出环）。
 * 用 Canvas 画两段圆弧，线宽细、端点圆角，无装饰。
 */
@Composable
fun ThinDonut(
    segments: List<Pair<Float, Color>>,    // 权重 to 颜色，权重之和会被归一化
    modifier: Modifier = Modifier,
    trackColor: Color = SmartLedgerColors.surfaceHover,
    strokeWidth: Dp = 10.dp,
    center: (@Composable () -> Unit)? = null
) {
    val total = segments.sumOf { it.first.toDouble() }.toFloat()
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(modifier = Modifier.matchParentSize()) {
            val stroke = strokeWidth.toPx()
            val inset = stroke / 2f
            val arcSize = androidx.compose.ui.geometry.Size(
                size.width - stroke, size.height - stroke
            )
            val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)

            // 轨道
            drawArc(
                color = trackColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = stroke,
                    cap = androidx.compose.ui.graphics.StrokeCap.Butt
                )
            )

            if (total > 0f) {
                var start = -90f
                segments.forEach { (weight, color) ->
                    if (weight <= 0f) return@forEach
                    val sweep = (weight / total) * 360f
                    drawArc(
                        color = color,
                        startAngle = start,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(
                            width = stroke,
                            cap = androidx.compose.ui.graphics.StrokeCap.Butt
                        )
                    )
                    start += sweep
                }
            }
        }
        if (center != null) center()
    }
}

// ═══════════════════════════════════════════════════════
// 指标格（卡片内的 label + value 单元）
// ═══════════════════════════════════════════════════════

/**
 * 指标格：eyebrow 标签 + 主数字 + 可选辅助说明。
 * 这是「标题 + eyebrow + 主数字 + 辅助说明」组件语言的最小单元，
 * Hero 卡与预算卡内部的每个数字都用它，保证对齐与间距一致。
 */
@Composable
fun MetricCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = SmartLedgerColors.fg,
    valueStyle: TextStyle = AppType.cardNumberSmall,
    sub: String? = null,
    subColor: Color = SmartLedgerColors.fgSecondary,
    align: TextAlign = TextAlign.Start
) {
    Column(modifier = modifier, horizontalAlignment = when (align) {
        TextAlign.End -> Alignment.End
        TextAlign.Center -> Alignment.CenterHorizontally
        else -> Alignment.Start
    }) {
        Eyebrow(text = label, color = SmartLedgerColors.fgTertiary)
        Spacer(Modifier.height(3.dp))
        Text(
            text = value,
            style = valueStyle,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = align
        )
        if (!sub.isNullOrBlank()) {
            Spacer(Modifier.height(2.dp))
            AuxText(text = sub, color = subColor, maxLines = 1)
        }
    }
}

// ═══════════════════════════════════════════════════════
// 顶栏（详情页统一）
// ═══════════════════════════════════════════════════════

/**
 * 详情页顶栏：返回 + 页面标题 + eyebrow + 右侧动作。
 * 不使用 Material TopAppBar（自带阴影与高度不符合本设计系统），
 * 而是用纸面 + 底部 1dp 细线的极简结构。
 */
@Composable
fun AppTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    actions: (@Composable RowScope.() -> Unit)? = null
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = AppSpacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButtonQuiet(
                icon = Icons.Outlined.ArrowBack,
                contentDescription = "返回",
                onClick = onBack
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = AppSpacing.xs)
            ) {
                if (!eyebrow.isNullOrBlank()) {
                    Eyebrow(text = eyebrow)
                    Spacer(Modifier.height(1.dp))
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = SmartLedgerColors.fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (actions != null) {
                Row(verticalAlignment = Alignment.CenterVertically, content = actions)
            } else {
                Spacer(Modifier.width(AppSize.minTouchTarget))
            }
        }
        Hairline()
    }
}

// ═══════════════════════════════════════════════════════
// 数字格式化（全 App 统一口径）
// ═══════════════════════════════════════════════════════

/**
 * 金额格式化：千分位 + 最多两位小数，整数不显示小数位。
 *
 * 统一用 [Locale.US] 做分组，避免设备 Locale 为德语/法语时
 * 把 1234.5 渲染成 "1.234,5" 而与其他页面口径不一致。
 */
fun formatMoney(value: Double, withSymbol: Boolean = true): String {
    if (value.isNaN() || value.isInfinite()) return if (withSymbol) "¥0" else "0"
    val v = value
    val isWhole = kotlin.math.abs(v - kotlin.math.round(v)) < 0.005
    val body = if (isWhole) {
        String.format(Locale.US, "%,.0f", kotlin.math.round(v))
    } else {
        String.format(Locale.US, "%,.2f", v)
    }
    return if (withSymbol) "¥$body" else body
}

/** 百分比格式化：保留 1 位小数 */
fun formatPercent(value: Double?): String =
    if (value == null || value.isNaN() || value.isInfinite()) "—"
    else String.format(Locale.US, "%.1f%%", value)

/**
 * 数字变化动画：金额跳变时做 300ms 插值，避免生硬闪烁。
 * 系统开启「减弱动态效果」时直接显示目标值。
 */
@Composable
fun AnimatedMoney(
    value: Double,
    modifier: Modifier = Modifier,
    style: TextStyle = AppType.cardNumber,
    color: Color = SmartLedgerColors.fg,
    withSymbol: Boolean = true,
    align: TextAlign = TextAlign.Start
) {
    val reduced = rememberReducedMotion()
    val animated by animateFloatAsState(
        targetValue = value.toFloat(),
        animationSpec = tween(if (reduced) 0 else AppMotion.NumberMs),
        label = "money"
    )
    // 动画结束前用插值，结束后精确显示目标值，避免 float 精度导致尾数漂移
    val shown = if (reduced || kotlin.math.abs(animated - value) < 0.005f) {
        value
    } else {
        animated.toDouble()
    }
    Text(
        text = formatMoney(shown, withSymbol),
        style = style,
        color = color,
        textAlign = align,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

// ═══════════════════════════════════════════════════════
// 进场动效（页面淡入 + 卡片轻微上移）
// ═══════════════════════════════════════════════════════

/**
 * 卡片进场：淡入 + 8dp 上移，240ms。
 *
 * 只用于**首次进入页面**的一批卡片，列表滚动中新增的项不要用，
 * 否则会造成持续的视觉噪声，违背「安静」的基调。
 *
 * 命名刻意避开 Compose 的 `EnterTransition`，防止与页面里的导入歧义。
 */
class CardEnterSpec(val reduced: Boolean)

@Composable
fun rememberCardEnterSpec(): CardEnterSpec {
    val reduced = rememberReducedMotion()
    return remember(reduced) { CardEnterSpec(reduced) }
}

/**
 * 给卡片套上进场动效。
 * @param index 同批卡片的序号，用于错峰（每个 30ms，最多错开到 150ms）
 */
@Composable
fun Modifier.enterAnim(
    spec: CardEnterSpec,
    index: Int = 0
): Modifier {
    if (spec.reduced) return this

    val started = remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val delayMs = (index * 30).coerceAtMost(150)
        if (delayMs > 0) delay(delayMs.toLong())
        started.value = true
    }
    val alphaValue by animateFloatAsState(
        targetValue = if (started.value) 1f else 0f,
        animationSpec = tween(AppMotion.EnterMs),
        label = "enterAlpha"
    )
    val remain by animateFloatAsState(
        targetValue = if (started.value) 0f else 1f,
        animationSpec = tween(AppMotion.EnterMs),
        label = "enterOffset"
    )
    return this
        .alpha(alphaValue)
        .offset(y = (remain * AppMotion.enterOffsetY.value).dp)
}

// ═══════════════════════════════════════════════════════
// 表单输入
// ═══════════════════════════════════════════════════════

/**
 * 统一文本输入框。
 *
 * 不用 Material 默认的 OutlinedTextField 配色：那套紫色/蓝色聚焦态
 * 与暖纸底冲突。这里统一为：纸面底 + 1dp Ink 16% 边框，
 * 聚焦时边框转为主墨色，不引入额外强调色。
 *
 * @param trailing 右侧插槽（密码可见性切换、清除按钮等）
 */
@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    helper: String? = null,
    error: String? = null,
    singleLine: Boolean = true,
    enabled: Boolean = true,
    obscure: Boolean = false,
    keyboardOptions: androidx.compose.foundation.text.KeyboardOptions =
        androidx.compose.foundation.text.KeyboardOptions.Default,
    imeAction: androidx.compose.ui.text.input.ImeAction =
        androidx.compose.ui.text.input.ImeAction.Done,
    onImeAction: (() -> Unit)? = null,
    leadingIcon: ImageVector? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null
) {
    val shape = RoundedCornerShape(AppRadius.field)
    val borderColor = when {
        error != null -> SmartLedgerColors.expense
        else -> SmartLedgerColors.borderStrong
    }

    Column(modifier = modifier.fillMaxWidth()) {
        if (!label.isNullOrBlank()) {
            Eyebrow(text = label)
            Spacer(Modifier.height(6.dp))
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = AppSize.buttonHeightCompact)
                .clip(shape)
                .background(SmartLedgerColors.surface)
                .border(AppSize.hairline, borderColor, shape)
                .padding(horizontal = AppSpacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leadingIcon != null) {
                Icon(
                    leadingIcon,
                    contentDescription = null,
                    tint = SmartLedgerColors.fgTertiary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(AppSpacing.sm))
            }
            BasicTextFallback(
                value = value,
                onValueChange = onValueChange,
                placeholder = placeholder,
                obscure = obscure,
                enabled = enabled,
                keyboardOptions = keyboardOptions,
                imeAction = imeAction,
                onImeAction = onImeAction,
                modifier = Modifier.weight(1f)
            )
            if (trailing != null) {
                Spacer(Modifier.width(AppSpacing.sm))
                trailing()
            }
        }
        when {
            !error.isNullOrBlank() -> {
                Spacer(Modifier.height(6.dp))
                AuxText(text = error, color = SmartLedgerColors.expense)
            }

            !helper.isNullOrBlank() -> {
                Spacer(Modifier.height(6.dp))
                AuxText(text = helper, color = SmartLedgerColors.fgTertiary)
            }
        }
    }
}

/**
 * BasicTextField 包装。
 *
 * 用 BasicTextField 而不是 OutlinedTextField：后者自带一套无法完全关掉的
 * 容器/边框/label 动画逻辑，与本设计系统的「纸面 + 1dp 细边框」冲突，
 * 而且高度不好控。BasicTextField 只给文字能力，外观完全由外层决定。
 */
@Composable
private fun RowScope.BasicTextFallback(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String?,
    obscure: Boolean,
    enabled: Boolean,
    keyboardOptions: androidx.compose.foundation.text.KeyboardOptions,
    imeAction: androidx.compose.ui.text.input.ImeAction,
    onImeAction: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val textColor = if (enabled) SmartLedgerColors.fg else SmartLedgerColors.fgTertiary
    androidx.compose.foundation.text.BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .wrapContentHeight(Alignment.CenterVertically),
        enabled = enabled,
        singleLine = true,
        textStyle = AppType.body.copy(color = textColor),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(SmartLedgerColors.fg),
        // 关掉自动纠错/联想：Base URL 与 API Key 被输入法「纠正」一下就直接不可用了
        keyboardOptions = keyboardOptions.copy(
            imeAction = imeAction,
            autoCorrectEnabled = false
        ),
        visualTransformation = if (obscure) {
            androidx.compose.ui.text.input.PasswordVisualTransformation()
        } else {
            androidx.compose.ui.text.input.VisualTransformation.None
        },
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
            onDone = { onImeAction?.invoke() }
        ),
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty() && !placeholder.isNullOrBlank()) {
                    Text(
                        text = placeholder,
                        style = AppType.body,
                        color = SmartLedgerColors.fgTertiary,
                        maxLines = 1
                    )
                }
                inner()
            }
        }
    )
}

/** 空状态：图标 + 一句话 + 可选动作，纸面底、细边框 */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    icon: ImageVector? = null,
    action: (@Composable () -> Unit)? = null
) {
    SectionCard(modifier = modifier, contentPadding = PaddingValues(AppSpacing.xl)) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (icon != null) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(SmartLedgerColors.surfaceHover),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = SmartLedgerColors.fgTertiary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.height(AppSpacing.md))
            }
            Text(
                text = title,
                style = AppType.listPrimary,
                color = SmartLedgerColors.fgSecondary,
                textAlign = TextAlign.Center
            )
            if (!description.isNullOrBlank()) {
                Spacer(Modifier.height(AppSpacing.xs))
                AuxText(
                    text = description,
                    color = SmartLedgerColors.fgTertiary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (action != null) {
                Spacer(Modifier.height(AppSpacing.lg))
                action()
            }
        }
    }
}

/** 供外部按规范取用的圆角/间距常量再导出，避免页面直接依赖 theme 包细节 */
object UiTokens {
    val pagePadding: Dp = AppSpacing.page
    val cardGap: Dp = AppSpacing.cardGap
    val sectionGap: Dp = AppSpacing.sectionGap
    val cardRadius: Dp = AppRadius.card
    val largeCardRadius: Dp = AppRadius.cardLarge
    val pillRadius: Dp = AppRadius.pill
    val hairline: Dp = AppSize.hairline
    val touchTarget: Dp = AppSize.minTouchTarget
    val eyebrowSize = 11.sp
}
