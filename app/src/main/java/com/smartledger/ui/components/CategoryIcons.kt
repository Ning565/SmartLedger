package com.smartledger.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachMoney
import androidx.compose.material.icons.outlined.CardGiftcard
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.OndemandVideo
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.TrendingUp
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 分类名 → 图标映射。
 *
 * 原本在 `RecordScreen` 与 `StatisticsScreen` 里各抄了一份完全相同的 when，
 * 现在流水页、总览页也要用。抽到一处，避免新增分类时漏改某个页面
 * 导致同一个分类在不同页面显示不同图标。
 *
 * 匹配按**分类名**而不是 icon 字段：历史数据里 `Category.icon` 存的是
 * "Restaurant"/"DirectionsCar" 这类 Material 旧名，与当前使用的
 * Outlined 系列不完全对应，用名字匹配更稳。
 */
fun categoryIcon(name: String): ImageVector = when (name) {
    "餐饮" -> Icons.Outlined.Restaurant
    "交通" -> Icons.Outlined.DirectionsBus
    "购物" -> Icons.Outlined.ShoppingBag
    "娱乐" -> Icons.Outlined.OndemandVideo
    "居住" -> Icons.Outlined.Home
    "医疗" -> Icons.Outlined.FavoriteBorder
    "教育" -> Icons.Outlined.MenuBook
    "通讯" -> Icons.Outlined.Phone
    "日用" -> Icons.Outlined.ShoppingCart
    "工资" -> Icons.Outlined.AttachMoney
    "理财" -> Icons.Outlined.TrendingUp
    "红包" -> Icons.Outlined.CardGiftcard
    "转账" -> Icons.Outlined.SwapHoriz
    else -> Icons.Outlined.MoreHoriz
}
