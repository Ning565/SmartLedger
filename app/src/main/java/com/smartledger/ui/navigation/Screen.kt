package com.smartledger.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 底部导航四项：**总览 / 流水 / 统计 / 设置**。
 *
 * 相对旧版的两点变化：
 *  1. 新增「流水」页（完整的按月账单列表 + 收支筛选）。
 *     原来交易列表只作为「最近交易」嵌在首页里，翻历史很别扭。
 *  2. **移除「记账」Tab**，记账改由总览页与流水页的 FAB 进入。
 *     四个 Tab 已经占满，而记账是一个「动作」不是一个「视图」，
 *     用 FAB 承载更符合它的语义，也让底部导航保持四项。
 *
 * **不新增 AI 独立 Tab**：AI 是现有功能的增强层 ——
 * 消费体检挂在统计页顶部，自然语言记账挂在记账页顶部，
 * 单独开一个 AI Tab 会把"增强"变成"另一个 App"。
 *
 * 「我的」并入「设置」：两者内容高度重叠（昵称/主题 + 各功能入口），
 * 分成两层只会让用户多点一次。
 */
sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    data object Home : Screen("home", "总览", Icons.Outlined.Home)
    data object Transactions : Screen("transactions", "流水", Icons.Outlined.ReceiptLong)
    data object Statistics : Screen("statistics", "统计", Icons.Outlined.BarChart)

    /**
     * 设置 Tab。route 沿用旧的 "profile"：
     * MainActivity 里多处 `popUpTo(Screen.Home.route)` 与深链逻辑依赖既有路由名，
     * 改路由名的收益远小于回归风险。
     */
    data object Settings : Screen("profile", "设置", Icons.Outlined.Settings)
}

val bottomNavItems = listOf(
    Screen.Home,
    Screen.Transactions,
    Screen.Statistics,
    Screen.Settings
)
