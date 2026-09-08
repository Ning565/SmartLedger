package com.smartledger.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * SmartLedger 品牌色板 —— 「学术纸张」视觉语言
 *
 * 设计基调：学术感、精确、安静、高级、可信。
 * 参考 NeurIPS / ICLR / ACL 顶会海报与研究系统的视觉语言：
 * 暖纸张底色、细边框、大留白、轻阴影、克制的语义色。
 *
 * 全局采用固定品牌色，不启用 Material 3 Dynamic Color。
 *
 * 语义分工（务必遵守，避免同一屏出现多个大面积强调色）：
 *  - Ink      主文字 / 品牌主色 / 交互强调（primary）
 *  - Seal     支出、超支、危险、主要强调色
 *  - Moss     收入、成功、正向
 *  - Amber    待确认、预警、注意
 *  - Blue     图表、信息、次级强调（secondary）
 *  - Violet   AI 功能专用点色（tertiary）
 *  - Lavender AI 功能浅底
 */
object Palette {

    // ═══ 中性：纸张与墨 ═══

    /** 主文字 Ink */
    val Ink = Color(0xFF171714)

    /** 次文字 Ink Soft */
    val InkSoft = Color(0xFF44443D)

    /** 三级文字 / 占位符（由 InkSoft 派生，用于 disabled、hint） */
    val InkFaint = Color(0xFF6E6E63)

    /** 页面背景 Paper */
    val Paper = Color(0xFFF3EFE5)

    /** 卡片背景 Paper Strong */
    val PaperStrong = Color(0xFFFFFDF7)

    /** 次级分区背景 Paper Deep */
    val PaperDeep = Color(0xFFE7DFCE)

    // ═══ 语义色 ═══

    /** 主要强调色 Seal —— 支出 / 超支 / 危险 */
    val Seal = Color(0xFFC9482F)

    /** 收入 / 成功 Moss */
    val Moss = Color(0xFF375A45)

    /** 待确认 / 预警 Amber */
    val Amber = Color(0xFFC68A2F)

    /** 图表 / 信息 Blue */
    val Blue = Color(0xFF315D6F)

    /** AI 功能专用点色 Violet */
    val Violet = Color(0xFF6257C8)

    /** AI 功能浅底 Lavender */
    val Lavender = Color(0xFFEFECFF)

    // ═══ 边框：统一使用 Ink 12%～16% 透明度 ═══

    /** 细边框（1dp 分隔、卡片描边）：Ink 12% */
    val BorderSoft = Color(0x1F171714)

    /** 强调边框（输入框聚焦、选中态）：Ink 16% */
    val BorderStrong = Color(0x29171714)

    /** 选中态浅底：Ink 8% */
    val InkTint = Color(0x14171714)

    // ═══ 语义色浅底（同色系 10% 透明，用于 Pill / 高亮块） ═══

    val SealTint = Color(0x1AC9482F)
    val MossTint = Color(0x1A375A45)
    val AmberTint = Color(0x1AC68A2F)
    val BlueTint = Color(0x1A315D6F)
    val VioletTint = Color(0x1A6257C8)

    // ═══ 深色模式：暖墨纸张（由亮色语义等比提亮，保持同一色相） ═══

    val DarkPaper = Color(0xFF171612)
    val DarkPaperStrong = Color(0xFF201E19)
    val DarkPaperDeep = Color(0xFF2A2822)

    val DarkInk = Color(0xFFF1EDE2)
    val DarkInkSoft = Color(0xFFB4AFA2)
    val DarkInkFaint = Color(0xFF87837A)

    val DarkSeal = Color(0xFFE2705A)
    val DarkMoss = Color(0xFF7FA98C)
    val DarkAmber = Color(0xFFDBA95C)
    val DarkBlue = Color(0xFF7FA6B8)
    val DarkViolet = Color(0xFF9C93E8)
    val DarkLavender = Color(0xFF2A2742)

    /** 深色下边框改用暖白低透明度，避免纯黑边在暖底上发脏 */
    val DarkBorderSoft = Color(0x1AF1EDE2)   // 10%
    val DarkBorderStrong = Color(0x24F1EDE2) // 14%
    val DarkInkTint = Color(0x14F1EDE2)      // 8%

    val DarkSealTint = Color(0x24E2705A)
    val DarkMossTint = Color(0x247FA98C)
    val DarkAmberTint = Color(0x24DBA95C)
    val DarkBlueTint = Color(0x247FA6B8)
    val DarkVioletTint = Color(0x2E9C93E8)

    // ═══ 图表色板：二维、平面、低饱和 ═══
    //
    // 用于分类环形图 / 横向条形图 / 折线趋势。
    // 以 Blue、Moss、Amber 为主轴做同明度递变，整体低饱和，
    // 不引入霓虹或高饱和色，保证在暖纸底上安静可读。
    // 数量 ≥ 8，覆盖 StatisticsScreen 的 take(8)。

    val ChartRampLight = listOf(
        Blue,               // #315D6F
        Color(0xFF5B7C8A),
        Moss,               // #375A45
        Color(0xFF7C8B6E),
        Amber,              // #C68A2F
        Color(0xFFA89272),
        Color(0xFF8A8578),
        Color(0xFF6E6A5E),
    )

    val ChartRampDark = listOf(
        DarkBlue,           // #7FA6B8
        Color(0xFF6C909E),
        DarkMoss,           // #7FA98C
        Color(0xFF8FA391),
        DarkAmber,          // #DBA95C
        Color(0xFFB9A382),
        Color(0xFF9C978A),
        Color(0xFF837F73),
    )

    val OnAccent = Color(0xFFFFFDF7)   // 深墨 / Seal / Blue 之上的前景（近纸白）
}
