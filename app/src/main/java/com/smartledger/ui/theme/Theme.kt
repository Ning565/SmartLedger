package com.smartledger.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * SmartLedger 主题 —— 「个人财务研究仪表盘」
 *
 * 固定品牌色，**不启用 Material 3 Dynamic Color**：
 * 动态取色会让每台设备的记账页颜色都不一样，破坏数据仪表盘的
 * 一致性与可信度，也与「学术、精确、安静」的基调冲突。
 */

// ═══════════════════════════════════════════════════════
// 扩展色：语义化令牌（亮 / 暗两套）
// ═══════════════════════════════════════════════════════

@Immutable
data class ExtendedColors(
    // 墨
    val ink: Color,
    val inkSoft: Color,
    val inkFaint: Color,
    // 纸
    val paper: Color,
    val paperStrong: Color,
    val paperDeep: Color,
    // 语义
    val seal: Color,
    val moss: Color,
    val amber: Color,
    val blue: Color,
    val violet: Color,
    val lavender: Color,
    // 语义浅底
    val sealTint: Color,
    val mossTint: Color,
    val amberTint: Color,
    val blueTint: Color,
    val violetTint: Color,
    val inkTint: Color,
    // 边框（Ink 12% / 16%）
    val borderSoft: Color,
    val borderStrong: Color,
    // 深墨之上的前景
    val onAccent: Color,
    // 图表
    val chartColors: List<Color>,
    // 底部导航
    val navSelected: Color,
    val navUnselected: Color,

    // ═══ 兼容别名 ═══
    // 保留旧字段名，使既有页面在迁移过程中不会因改名而编译失败；
    // 新代码请优先使用上面的语义名。
    val expense: Color,
    val expenseDim: Color,
    val income: Color,
    val incomeDim: Color,
    val accent: Color,
    val accentDim: Color,
    val background: Color,
    val surface: Color,
    val surfaceHover: Color,
    val foreground: Color,
    val foregroundSecondary: Color,
    val border: Color
)

private fun lightColors() = ExtendedColors(
    ink = Palette.Ink,
    inkSoft = Palette.InkSoft,
    inkFaint = Palette.InkFaint,
    paper = Palette.Paper,
    paperStrong = Palette.PaperStrong,
    paperDeep = Palette.PaperDeep,
    seal = Palette.Seal,
    moss = Palette.Moss,
    amber = Palette.Amber,
    blue = Palette.Blue,
    violet = Palette.Violet,
    lavender = Palette.Lavender,
    sealTint = Palette.SealTint,
    mossTint = Palette.MossTint,
    amberTint = Palette.AmberTint,
    blueTint = Palette.BlueTint,
    violetTint = Palette.VioletTint,
    inkTint = Palette.InkTint,
    borderSoft = Palette.BorderSoft,
    borderStrong = Palette.BorderStrong,
    onAccent = Palette.OnAccent,
    chartColors = Palette.ChartRampLight,
    navSelected = Palette.Ink,
    navUnselected = Palette.InkFaint,
    // 别名
    expense = Palette.Seal,
    expenseDim = Palette.SealTint,
    income = Palette.Moss,
    incomeDim = Palette.MossTint,
    accent = Palette.Ink,
    accentDim = Palette.InkTint,
    background = Palette.Paper,
    surface = Palette.PaperStrong,
    surfaceHover = Palette.PaperDeep,
    foreground = Palette.Ink,
    foregroundSecondary = Palette.InkSoft,
    border = Palette.BorderSoft
)

private fun darkColors() = ExtendedColors(
    ink = Palette.DarkInk,
    inkSoft = Palette.DarkInkSoft,
    inkFaint = Palette.DarkInkFaint,
    paper = Palette.DarkPaper,
    paperStrong = Palette.DarkPaperStrong,
    paperDeep = Palette.DarkPaperDeep,
    seal = Palette.DarkSeal,
    moss = Palette.DarkMoss,
    amber = Palette.DarkAmber,
    blue = Palette.DarkBlue,
    violet = Palette.DarkViolet,
    lavender = Palette.DarkLavender,
    sealTint = Palette.DarkSealTint,
    mossTint = Palette.DarkMossTint,
    amberTint = Palette.DarkAmberTint,
    blueTint = Palette.DarkBlueTint,
    violetTint = Palette.DarkVioletTint,
    inkTint = Palette.DarkInkTint,
    borderSoft = Palette.DarkBorderSoft,
    borderStrong = Palette.DarkBorderStrong,
    onAccent = Palette.DarkPaper,
    chartColors = Palette.ChartRampDark,
    navSelected = Palette.DarkInk,
    navUnselected = Palette.DarkInkFaint,
    // 别名
    expense = Palette.DarkSeal,
    expenseDim = Palette.DarkSealTint,
    income = Palette.DarkMoss,
    incomeDim = Palette.DarkMossTint,
    accent = Palette.DarkInk,
    accentDim = Palette.DarkInkTint,
    background = Palette.DarkPaper,
    surface = Palette.DarkPaperStrong,
    surfaceHover = Palette.DarkPaperDeep,
    foreground = Palette.DarkInk,
    foregroundSecondary = Palette.DarkInkSoft,
    border = Palette.DarkBorderSoft
)

val LocalExtendedColors = staticCompositionLocalOf { lightColors() }

// ═══════════════════════════════════════════════════════
// Material 3 映射（按设计规范固定）
//   primary=#171714  secondary=#315D6F  tertiary=#6257C8
//   error=#C9482F    surface=#FFFDF7    surfaceContainer=#F3EFE5
// ═══════════════════════════════════════════════════════

private val LightColorScheme = lightColorScheme(
    primary = Palette.Ink,
    onPrimary = Palette.OnAccent,
    primaryContainer = Palette.PaperDeep,
    onPrimaryContainer = Palette.Ink,
    secondary = Palette.Blue,
    onSecondary = Palette.OnAccent,
    secondaryContainer = Palette.BlueTint,
    onSecondaryContainer = Palette.Blue,
    tertiary = Palette.Violet,
    onTertiary = Palette.OnAccent,
    tertiaryContainer = Palette.Lavender,
    onTertiaryContainer = Palette.Violet,
    error = Palette.Seal,
    onError = Palette.OnAccent,
    errorContainer = Palette.SealTint,
    onErrorContainer = Palette.Seal,
    background = Palette.Paper,
    onBackground = Palette.Ink,
    surface = Palette.PaperStrong,
    onSurface = Palette.Ink,
    surfaceVariant = Palette.PaperDeep,
    onSurfaceVariant = Palette.InkSoft,
    surfaceContainer = Palette.Paper,
    surfaceContainerLow = Palette.PaperStrong,
    surfaceContainerHigh = Palette.PaperDeep,
    outline = Palette.BorderStrong,
    outlineVariant = Palette.BorderSoft,
    scrim = Color(0x66171714)
)

private val DarkColorScheme = darkColorScheme(
    primary = Palette.DarkInk,
    onPrimary = Palette.DarkPaper,
    primaryContainer = Palette.DarkPaperDeep,
    onPrimaryContainer = Palette.DarkInk,
    secondary = Palette.DarkBlue,
    onSecondary = Palette.DarkPaper,
    secondaryContainer = Palette.DarkBlueTint,
    onSecondaryContainer = Palette.DarkBlue,
    tertiary = Palette.DarkViolet,
    onTertiary = Palette.DarkPaper,
    tertiaryContainer = Palette.DarkLavender,
    onTertiaryContainer = Palette.DarkViolet,
    error = Palette.DarkSeal,
    onError = Palette.DarkPaper,
    errorContainer = Palette.DarkSealTint,
    onErrorContainer = Palette.DarkSeal,
    background = Palette.DarkPaper,
    onBackground = Palette.DarkInk,
    surface = Palette.DarkPaperStrong,
    onSurface = Palette.DarkInk,
    surfaceVariant = Palette.DarkPaperDeep,
    onSurfaceVariant = Palette.DarkInkSoft,
    surfaceContainer = Palette.DarkPaper,
    surfaceContainerLow = Palette.DarkPaperStrong,
    surfaceContainerHigh = Palette.DarkPaperDeep,
    outline = Palette.DarkBorderStrong,
    outlineVariant = Palette.DarkBorderSoft,
    scrim = Color(0x99000000)
)

// ═══════════════════════════════════════════════════════
// 主题模式管理
// ═══════════════════════════════════════════════════════

enum class ThemeMode { SYSTEM, LIGHT, DARK }

object ThemeManager {
    private val _themeMode = mutableStateOf(ThemeMode.SYSTEM)
    val themeMode: State<ThemeMode> = _themeMode

    fun setTheme(mode: ThemeMode) {
        _themeMode.value = mode
    }

    fun init(mode: ThemeMode) {
        _themeMode.value = mode
    }
}

// ═══════════════════════════════════════════════════════
// 主题入口
// ═══════════════════════════════════════════════════════

@Composable
fun SmartLedgerTheme(
    /** 半透明确认页等场景勿改状态栏，避免与系统遮罩违和 */
    syncSystemBars: Boolean = true,
    content: @Composable () -> Unit
) {
    val themeMode by ThemeManager.themeMode
    val isDark = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    // 固定品牌色：刻意不调用 dynamicLightColorScheme / dynamicDarkColorScheme
    val colorScheme = if (isDark) DarkColorScheme else LightColorScheme
    val extendedColors = if (isDark) darkColors() else lightColors()

    // 状态栏颜色（仅 Activity 场景；悬浮窗等非 Activity Context 不可强转）
    val view = LocalView.current
    if (syncSystemBars && !view.isInEditMode) {
        SideEffect {
            val activity = view.context as? Activity ?: return@SideEffect
            @Suppress("DEPRECATION")
            activity.window.statusBarColor = extendedColors.paper.toArgb()
            WindowCompat.getInsetsController(activity.window, view)
                .isAppearanceLightStatusBars = !isDark
        }
    }

    CompositionLocalProvider(
        LocalExtendedColors provides extendedColors,
        LocalIsDarkTheme provides isDark
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}

// ═══════════════════════════════════════════════════════
// 便捷访问
// ═══════════════════════════════════════════════════════

/**
 * 全局颜色访问入口。
 *
 * 所有页面统一从这里取色，**不要在页面里写死 Color(0xFF...)**，
 * 否则深色模式会失效、视觉也无法保持统一。
 */
object SmartLedgerColors {
    // 墨
    val fg: Color @Composable get() = LocalExtendedColors.current.ink
    val fgSecondary: Color @Composable get() = LocalExtendedColors.current.inkSoft
    val fgTertiary: Color @Composable get() = LocalExtendedColors.current.inkFaint

    // 纸
    val bg: Color @Composable get() = LocalExtendedColors.current.paper
    val surface: Color @Composable get() = LocalExtendedColors.current.paperStrong
    val surfaceHover: Color @Composable get() = LocalExtendedColors.current.paperDeep

    // 语义
    val expense: Color @Composable get() = LocalExtendedColors.current.seal
    val expenseDim: Color @Composable get() = LocalExtendedColors.current.sealTint
    val income: Color @Composable get() = LocalExtendedColors.current.moss
    val incomeDim: Color @Composable get() = LocalExtendedColors.current.mossTint
    val warning: Color @Composable get() = LocalExtendedColors.current.amber
    val warningDim: Color @Composable get() = LocalExtendedColors.current.amberTint
    val info: Color @Composable get() = LocalExtendedColors.current.blue
    val infoDim: Color @Composable get() = LocalExtendedColors.current.blueTint

    // AI 专用点色
    val ai: Color @Composable get() = LocalExtendedColors.current.violet
    val aiDim: Color @Composable get() = LocalExtendedColors.current.violetTint
    val aiSurface: Color @Composable get() = LocalExtendedColors.current.lavender

    // 交互强调（= Ink）
    val accent: Color @Composable get() = LocalExtendedColors.current.ink
    val accentDim: Color @Composable get() = LocalExtendedColors.current.inkTint
    val onAccent: Color @Composable get() = LocalExtendedColors.current.onAccent

    // 边框
    val border: Color @Composable get() = LocalExtendedColors.current.borderSoft
    val borderStrong: Color @Composable get() = LocalExtendedColors.current.borderStrong

    // 图表 / 导航
    val chartColors: List<Color> @Composable get() = LocalExtendedColors.current.chartColors
    val navSelected: Color @Composable get() = LocalExtendedColors.current.navSelected
    val navUnselected: Color @Composable get() = LocalExtendedColors.current.navUnselected

    // 兼容旧别名（新代码请用语义名）
    val foreground: Color @Composable get() = LocalExtendedColors.current.ink
    val foregroundSecondary: Color @Composable get() = LocalExtendedColors.current.inkSoft
    val background: Color @Composable get() = LocalExtendedColors.current.paper
    val seal: Color @Composable get() = LocalExtendedColors.current.seal
    val moss: Color @Composable get() = LocalExtendedColors.current.moss
    val amber: Color @Composable get() = LocalExtendedColors.current.amber
    val blue: Color @Composable get() = LocalExtendedColors.current.blue
    val violet: Color @Composable get() = LocalExtendedColors.current.violet
}

/**
 * 当前是否深色模式。
 *
 * 图表需要在深色下改用更浅的网格线 / 轴线，单靠语义色无法表达，
 * 因此显式暴露一个 CompositionLocal，避免页面各自再调 isSystemInDarkTheme()
 * （那样会漏掉用户在设置里手动指定 LIGHT / DARK 的情况）。
 */
val LocalIsDarkTheme = staticCompositionLocalOf { false }
