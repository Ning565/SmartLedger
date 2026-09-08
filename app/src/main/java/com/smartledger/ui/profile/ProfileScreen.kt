package com.smartledger.ui.profile

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.smartledger.SmartLedgerApp
import com.smartledger.data.db.AppDatabase
import com.smartledger.ui.components.AuxText
import com.smartledger.ui.components.Eyebrow
import com.smartledger.ui.components.Hairline
import com.smartledger.ui.components.SectionCard
import com.smartledger.ui.components.SmartLedgerInputDialog
import com.smartledger.ui.components.UiTokens
import com.smartledger.ui.theme.AppRadius
import com.smartledger.ui.theme.AppSize
import com.smartledger.ui.theme.AppSpacing
import com.smartledger.ui.theme.AppType
import com.smartledger.ui.theme.SmartLedgerColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 设置 Tab（原「我的」页）。
 *
 * 「我的」与「设置」原本内容高度重叠（昵称/主题 + 各功能入口），
 * 分成两层只会让用户多点一次，因此合并成一个 Tab。
 *
 * 新增「AI 财务顾问」分组。它是可选功能：未配置时副标题写「未配置」，
 * 配置后写当前服务商与模型，让用户一眼看出 AI 功能是否可用。
 */
@Composable
fun ProfileScreen(
    onNavigateToBudget: () -> Unit = {},
    onNavigateToCategory: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onNavigateToBackup: () -> Unit = {},
    onNavigateToAiSettings: () -> Unit = {},
    onExport: () -> Unit = {}
) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("smart_ledger", Context.MODE_PRIVATE)

    var nickname by remember { mutableStateOf(prefs.getString("nickname", "记账用户") ?: "记账用户") }
    var showEditName by remember { mutableStateOf(false) }

    // AI 配置状态：跟随 AiSettingsRepository 的 StateFlow，
    // 从 AI 设置页返回后副标题会立刻更新，不需要手动刷新
    val aiSettings = remember { (context.applicationContext as SmartLedgerApp).aiSettingsRepository }
    val aiConfig by aiSettings.config.collectAsState()

    var daysSinceFirst by remember { mutableStateOf(1) }
    LaunchedEffect(Unit) {
        daysSinceFirst = withContext(Dispatchers.IO) {
            try {
                val db = AppDatabase.getInstance(context)
                val firstTime = db.transactionDao().getFirstTransactionTime()
                if (firstTime != null && firstTime > 0) {
                    val diff = System.currentTimeMillis() - firstTime
                    maxOf(1, (diff / (1000 * 60 * 60 * 24)).toInt())
                } else {
                    1
                }
            } catch (_: Exception) {
                1
            }
        }
    }

    val appVersionLabel = remember {
        com.smartledger.util.UpdateChecker.currentVersionLabel(context)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SmartLedgerColors.bg)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = UiTokens.pagePadding,
                end = UiTokens.pagePadding,
                top = AppSpacing.xl,
                bottom = 96.dp
            ),
            verticalArrangement = Arrangement.spacedBy(UiTokens.cardGap)
        ) {
            // ═══ 用户卡 ═══
            item {
                SectionCard(large = true) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .background(SmartLedgerColors.surfaceHover)
                                .clickable { showEditName = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = nickname.firstOrNull()?.toString() ?: "记",
                                style = AppType.cardNumberSmall,
                                color = SmartLedgerColors.fg
                            )
                        }
                        Spacer(Modifier.width(AppSpacing.lg))
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { showEditName = true }
                        ) {
                            Eyebrow(text = "PROFILE")
                            Spacer(Modifier.height(2.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = nickname,
                                    style = AppType.sectionTitle,
                                    color = SmartLedgerColors.fg,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                Spacer(Modifier.width(6.dp))
                                Icon(
                                    Icons.Outlined.Edit,
                                    contentDescription = "修改昵称",
                                    tint = SmartLedgerColors.fgTertiary,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                            Spacer(Modifier.height(2.dp))
                            AuxText(text = "智能记账已陪伴你 $daysSinceFirst 天")
                        }
                    }
                }
            }

            // ═══ AI 财务顾问（可选功能，独立分组突出但不喧宾夺主）═══
            item {
                SectionCard(
                    border = if (aiConfig.isConfigured) {
                        SmartLedgerColors.ai.copy(alpha = 0.35f)
                    } else null
                ) {
                    Eyebrow(text = "AI ADVISOR", color = SmartLedgerColors.ai)
                    Spacer(Modifier.height(AppSpacing.sm))
                    MenuItem(
                        icon = Icons.Outlined.AutoAwesome,
                        label = "AI 财务顾问",
                        sublabel = if (aiConfig.isConfigured) {
                            aiConfig.displayLabel
                        } else {
                            "未配置 · 消费体检与自然语言记账"
                        },
                        iconTint = SmartLedgerColors.ai,
                        showDivider = false,
                        onClick = onNavigateToAiSettings
                    )
                    Spacer(Modifier.height(AppSpacing.sm))
                    Hairline()
                    Spacer(Modifier.height(AppSpacing.sm))
                    AuxText(
                        text = "AI 为可选功能。仅在你主动点击时联网，发送的是本地统计结果，" +
                                "不含通知原文、真实商户名与备注。",
                        color = SmartLedgerColors.fgTertiary
                    )
                }
            }

            // ═══ 数据 ═══
            item {
                SectionCard(contentPadding = PaddingValues(vertical = AppSpacing.xs)) {
                    Eyebrow(text = "DATA", modifier = Modifier.padding(
                        start = AppSpacing.lg, top = AppSpacing.md, bottom = AppSpacing.xs
                    ))
                    MenuItem(
                        icon = Icons.Outlined.Schedule,
                        label = "预算管理",
                        onClick = onNavigateToBudget
                    )
                    MenuItem(
                        icon = Icons.Outlined.GridView,
                        label = "分类管理",
                        onClick = onNavigateToCategory
                    )
                    MenuItem(
                        icon = Icons.Outlined.Download,
                        label = "数据导出",
                        onClick = onExport
                    )
                    MenuItem(
                        icon = Icons.Outlined.SaveAlt,
                        label = "数据备份",
                        onClick = onNavigateToBackup
                    )
                }
            }

            // ═══ 通用 ═══
            item {
                SectionCard(contentPadding = PaddingValues(vertical = AppSpacing.xs)) {
                    Eyebrow(text = "GENERAL", modifier = Modifier.padding(
                        start = AppSpacing.lg, top = AppSpacing.md, bottom = AppSpacing.xs
                    ))
                    MenuItem(
                        icon = Icons.Outlined.Tune,
                        label = "通用设置",
                        sublabel = "外观、自动记账、权限、关于",
                        showDivider = false,
                        onClick = onNavigateToSettings
                    )
                }
            }

            // ═══ 版本号（读安装包，勿写死）═══
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = AppSpacing.lg),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Eyebrow(text = "VERSION")
                    Spacer(Modifier.height(2.dp))
                    AuxText(text = appVersionLabel, color = SmartLedgerColors.fgSecondary)
                }
            }
        }
    }

    if (showEditName) {
        var editName by remember { mutableStateOf(nickname) }
        SmartLedgerInputDialog(
            onDismissRequest = { showEditName = false },
            eyebrow = "PROFILE",
            title = "修改昵称",
            label = "昵称",
            value = editName,
            onValueChange = { editName = it },
            confirmText = "保存",
            onConfirm = {
                val newName = editName.trim()
                if (newName.isNotBlank()) {
                    nickname = newName
                    prefs.edit().putString("nickname", newName).apply()
                }
                showEditName = false
            }
        )
    }
}

// ═══════════════════════════════════════════════════════
// 菜单项
// ═══════════════════════════════════════════════════════

@Composable
private fun MenuItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    sublabel: String? = null,
    iconTint: Color = SmartLedgerColors.fgSecondary,
    showDivider: Boolean = true
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = AppSize.minTouchTarget)
                .clip(RoundedCornerShape(AppRadius.field))
                .clickable(onClick = onClick)
                .padding(horizontal = AppSpacing.lg, vertical = AppSpacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(AppSpacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = AppType.listPrimary,
                    color = SmartLedgerColors.fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!sublabel.isNullOrBlank()) {
                    AuxText(
                        text = sublabel,
                        color = SmartLedgerColors.fgTertiary,
                        maxLines = 1
                    )
                }
            }
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = SmartLedgerColors.fgTertiary,
                modifier = Modifier.size(18.dp)
            )
        }
        if (showDivider) {
            Hairline(modifier = Modifier.padding(horizontal = AppSpacing.lg))
        }
    }
}
