package com.smartledger.ui.theme

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 布局与动效令牌 —— 全 App 统一，禁止逐页临时取值。
 *
 * 规范：
 *  - 页面统一 16dp 左右边距
 *  - 卡片间距 12dp
 *  - 大卡片 24dp 圆角，普通卡片 18dp 圆角
 *  - Chip 使用 pill 形态
 *  - 按钮高度 44～48dp，最小触控区域 48dp
 *  - 阴影极轻，层次主要靠背景层级 / 1dp 边框 / 留白
 *  - 动效仅保留必要反馈，150～300ms，并尊重系统「减弱动态效果」
 */
@Stable
object AppSpacing {
    /** 页面左右边距 */
    val page: Dp = 16.dp

    /** 卡片之间的间距 */
    val cardGap: Dp = 12.dp

    /** 区块（section）之间的间距 */
    val sectionGap: Dp = 24.dp

    /** 卡片内边距（大卡片） */
    val cardPadding: Dp = 20.dp

    /** 卡片内边距（紧凑卡片 / 列表项） */
    val cardPaddingCompact: Dp = 14.dp

    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 24.dp
    val xxl: Dp = 32.dp
}

@Stable
object AppRadius {
    /** 大卡片（Hero、预算卡、AI 体检卡） */
    val cardLarge: Dp = 24.dp

    /** 普通卡片 */
    val card: Dp = 18.dp

    /** 输入框 / 次级容器 */
    val field: Dp = 14.dp

    /** 小元素（标签底、色块） */
    val small: Dp = 8.dp

    /** Chip / Pill：全圆角 */
    val pill: Dp = 999.dp
}

@Stable
object AppSize {
    /** 主按钮高度 */
    val buttonHeight: Dp = 48.dp

    /** 次级 / 紧凑按钮高度 */
    val buttonHeightCompact: Dp = 44.dp

    /** 最小触控区域 */
    val minTouchTarget: Dp = 48.dp

    /** 1dp 细边框 —— 层次的主要手段 */
    val hairline: Dp = 1.dp

    /** 进度条 / 分隔条厚度 */
    val barThickness: Dp = 6.dp

    /** 底部导航高度 */
    val bottomBarHeight: Dp = 64.dp

    /** 列表项最小高度（紧凑流水列表） */
    val listItemMinHeight: Dp = 56.dp
}

@Stable
object AppMotion {
    /** 页面淡入 / 卡片轻微上移 */
    const val EnterMs: Int = 240

    /** 按钮按压反馈 */
    const val PressMs: Int = 150

    /** 数字变化 */
    const val NumberMs: Int = 300

    /** 通用状态切换 */
    const val StandardMs: Int = 200

    /** 卡片上移距离 */
    val enterOffsetY: Dp = 8.dp
}

/**
 * 系统「减弱动态效果」检测。
 *
 * Android 侧的可访问性开关体现为 Animator duration scale = 0
 * （设置 → 无障碍 → 移除动画 / 开发者选项 → 动画时长缩放）。
 * 命中时所有非必要动效时长归零，只保留状态切换本身。
 */
object MotionPreference {

    fun isReduced(context: Context): Boolean = try {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) == 0f
    } catch (_: Exception) {
        false
    }
}

/** 当前是否应关闭动效（Composition 内使用） */
@Composable
fun rememberReducedMotion(): Boolean = MotionPreference.isReduced(LocalContext.current)
