package com.smartledger.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * SmartLedger Typography —— 学术海报式排版
 *
 * 字体优先系统 Sans（Android 上即 MiSans / Noto Sans CJK / Roboto，随设备默认），
 * 不打包自定义字体文件：既符合「系统 Sans」的规范要求，也让 CJK 自动落到
 * 设备本地最优中文字形上，同时不增加 APK 体积。
 *
 * 层次不靠花哨字形，而靠**字重 + 字距 + 字号**三者组合营造顶会海报感：
 * 大数字收紧字距（负 letterSpacing）显得精确，
 * 英文 eyebrow 放大字距并大写，形成研究图表的标注感。
 *
 * 注意：这里**不给 TextStyle 设默认 color**。
 * 旧实现写死了 `color = Foreground`（亮色墨色），在深色模式下会让未显式传色的
 * 文字变成深底深字而不可读。改为继承 MaterialTheme 的 contentColor，随主题正确切换。
 */

/** 数字用：开启等宽数字（tabular number），保证金额竖向对齐、跳变时不抖动 */
private const val TABULAR_NUMBERS = "tnum"

/** 系统 Sans，CJK 自动落到 MiSans / Noto Sans CJK */
private val Sans = FontFamily.SansSerif

val Typography = Typography(
    // Hero 金额 40–48sp
    displayLarge = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 40.sp,
        lineHeight = 48.sp,
        letterSpacing = (-1.0).sp,
        fontFeatureSettings = TABULAR_NUMBERS
    ),
    displayMedium = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.6).sp,
        fontFeatureSettings = TABULAR_NUMBERS
    ),
    // 页面标题 28–32sp
    headlineLarge = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.3).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.2).sp
    ),
    headlineSmall = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp
    ),
    // 卡片主数字 24–28sp
    titleLarge = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.1).sp
    ),
    titleMedium = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 22.sp
    ),
    titleSmall = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp
    ),
    // 正文 14–16sp
    bodyLarge = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.1.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.1.sp
    ),
    // 辅助信息 12–13sp
    bodySmall = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.1.sp
    ),
    labelLarge = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp
    ),
    // 英文 Eyebrow 10–11sp + letterSpacing
    labelSmall = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.8.sp
    )
)

/**
 * 语义化排版 —— 组件统一从这里取样式，**不要在页面里临场拼字号**。
 *
 * 这是「所有页面保持统一设计系统、不逐页临时发挥」的落地点：
 * 组件语言固定为「英文 eyebrow + 标题 + 主数字 + 辅助说明」，
 * 四个层级在这里一次性定死。
 */
object AppType {

    /** Hero 金额：总览页顶部大数字 */
    val heroAmount = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 44.sp,
        lineHeight = 50.sp,
        letterSpacing = (-1.2).sp,
        fontFeatureSettings = TABULAR_NUMBERS
    )

    /** 页面标题 */
    val pageTitle = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 30.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.4).sp
    )

    /** 卡片主数字：剩余额度 / 今日建议 / 月末预测 */
    val cardNumber = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.5).sp,
        fontFeatureSettings = TABULAR_NUMBERS
    )

    /** 卡片次级数字 */
    val cardNumberSmall = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.2).sp,
        fontFeatureSettings = TABULAR_NUMBERS
    )

    /** 区块标题 */
    val sectionTitle = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.1).sp
    )

    /** 列表主信息：商户名 */
    val listPrimary = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 21.sp
    )

    /** 列表次信息：分类 · 支付来源 */
    val listSecondary = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.1.sp
    )

    /** 列表金额（右对齐，等宽数字） */
    val listAmount = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = (-0.2).sp,
        fontFeatureSettings = TABULAR_NUMBERS
    )

    /** 正文 */
    val body = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 23.sp,
        letterSpacing = 0.1.sp
    )

    /** 辅助说明 */
    val aux = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.1.sp
    )

    /**
     * 英文 kicker / eyebrow：全大写 + 大字距。
     * 顶会海报里图表标注的典型样式，用来给区块加一层「研究感」的元信息。
     */
    val eyebrow = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 1.1.sp
    )

    /** Status Pill 内文字 */
    val pill = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp
    )

    /** 按钮文字 */
    val button = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    )
}

/**
 * eyebrow 文案统一大写。
 * 中文不受影响（`uppercase()` 对 CJK 是 no-op），因此可以直接对中英混排使用。
 */
fun String.asEyebrow(): String = this.uppercase()
