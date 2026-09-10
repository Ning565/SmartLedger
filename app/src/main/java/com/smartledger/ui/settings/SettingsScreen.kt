package com.smartledger.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.smartledger.service.accessibility.AccessibilityDiagnostics
import com.smartledger.service.accessibility.AccessibilityStatus
import com.smartledger.ui.theme.SmartLedgerColors
import com.smartledger.ui.theme.ThemeManager
import com.smartledger.ui.theme.ThemeMode

@Composable
fun SettingsScreen(
    onBack: () -> Unit = {},
    onNavigateToFeedback: () -> Unit = {},
    onNavigateToPrivacy: () -> Unit = {},
    onNavigateToAiSettings: () -> Unit = {}
) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("smart_ledger", Context.MODE_PRIVATE)

    // AI 配置状态，用于在列表项副标题里直接显示「未配置 / DeepSeek · deepseek-chat」
    val aiSettings = remember {
        (context.applicationContext as com.smartledger.SmartLedgerApp).aiSettingsRepository
    }
    val aiConfig by aiSettings.config.collectAsState()

    // 深色模式状态
    val themeMode by ThemeManager.themeMode
    var autoBackupEnabled by remember {
        mutableStateOf(prefs.getBoolean("auto_backup", true))
    }
    /** AI 数据披露弹窗（与隐私政策、AI 设置页共用同一份文案） */
    var showAiDisclosure by remember { mutableStateOf(false) }
    var debugToastsEnabled by remember {
        mutableStateOf(prefs.getBoolean("debug_toasts", false))
    }
    var confirmUncertain by remember {
        mutableStateOf(prefs.getBoolean("confirm_uncertain", true))
    }
    var confirmAllAuto by remember {
        mutableStateOf(prefs.getBoolean("confirm_all_auto", false))
    }

    // ═══ 页面辅助识别（无障碍采集）═══
    // 系统无障碍权限状态：从系统设置页返回后由 ON_RESUME 刷新
    var a11yEnabled by remember {
        mutableStateOf(AccessibilityStatus.isEnabledInSettings(context))
    }
    // App 内总开关：权限开着也能随时暂停解析（方案 2.4）
    var a11yAutoRecord by remember {
        mutableStateOf(prefs.getBoolean("accessibility_auto_record_enabled", true))
    }
    // P0-3：无障碍识别独立确认开关（默认关，谨慎用户可开）
    var confirmA11y by remember {
        mutableStateOf(prefs.getBoolean("confirm_accessibility", false))
    }
    var showA11yDiagnostics by remember { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                a11yEnabled = AccessibilityStatus.isEnabledInSettings(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 检查更新状态（版本号读安装包，勿写死）
    val appVersionLabel = remember {
        com.smartledger.util.UpdateChecker.currentVersionLabel(context)
    }
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var updateResult by remember { mutableStateOf<com.smartledger.util.UpdateChecker.UpdateInfo?>(null) }
    var showNoUpdate by remember { mutableStateOf(false) }
    var noUpdateMessage by remember { mutableStateOf("") }
    var updateError by remember { mutableStateOf<String?>(null) }
    var isDownloadingUpdate by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf(0) }
    var pendingInstallFile by remember { mutableStateOf<java.io.File?>(null) }
    val coroutineScope = rememberCoroutineScope()
    fun startApkDownload(info: com.smartledger.util.UpdateChecker.UpdateInfo) {
        updateError = null
        isDownloadingUpdate = true
        downloadProgress =
            com.smartledger.util.UpdateChecker.partialDownloadPercent(context, info)
        coroutineScope.launch {
            when (val result =
                com.smartledger.util.UpdateChecker.downloadApk(context, info) { p ->
                    downloadProgress = p
                }
            ) {
                is com.smartledger.util.UpdateChecker.DownloadResult.Success -> {
                    isDownloadingUpdate = false
                    updateResult = null
                    val ok = com.smartledger.util.UpdateChecker.installApk(
                        context, result.apkFile
                    )
                    if (!ok) {
                        pendingInstallFile = result.apkFile
                        Toast.makeText(
                            context,
                            "请允许安装未知应用后点「继续安装」",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        Toast.makeText(
                            context,
                            "请按提示完成安装",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                is com.smartledger.util.UpdateChecker.DownloadResult.NeedBrowser -> {
                    isDownloadingUpdate = false
                    com.smartledger.util.UpdateChecker.openDownloadPage(context, info)
                    updateResult = null
                }
                is com.smartledger.util.UpdateChecker.DownloadResult.Failed -> {
                    isDownloadingUpdate = false
                    updateError = result.message
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(SmartLedgerColors.bg)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            // ═══ 顶部栏 ═══
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "返回", tint = SmartLedgerColors.fg)
                    }
                    Text(
                        text = "设置",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = SmartLedgerColors.fg
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(8.dp)) }

            // ═══ 外观 ═══
            item {
                SectionTitle("外观")
            }
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SmartLedgerColors.surface)
                ) {
                    Column {
                        ThemeOption(
                            label = "跟随系统",
                            selected = themeMode == ThemeMode.SYSTEM,
                            onClick = {
                                ThemeManager.setTheme(ThemeMode.SYSTEM)
                                prefs.edit().putString("theme_mode", "SYSTEM").apply()
                            }
                        )
                        DividerLine()
                        ThemeOption(
                            label = "浅色模式",
                            selected = themeMode == ThemeMode.LIGHT,
                            onClick = {
                                ThemeManager.setTheme(ThemeMode.LIGHT)
                                prefs.edit().putString("theme_mode", "LIGHT").apply()
                            }
                        )
                        DividerLine()
                        ThemeOption(
                            label = "深色模式",
                            selected = themeMode == ThemeMode.DARK,
                            onClick = {
                                ThemeManager.setTheme(ThemeMode.DARK)
                                prefs.edit().putString("theme_mode", "DARK").apply()
                            }
                        )
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(20.dp)) }

            // ═══ 数据 ═══
            item {
                SectionTitle("数据")
            }
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SmartLedgerColors.surface)
                ) {
                    Column {
                        SwitchItem(
                            icon = Icons.Outlined.Download,
                            label = "自动备份",
                            description = "每周自动备份一次数据",
                            checked = autoBackupEnabled,
                            onCheckedChange = { enabled ->
                                autoBackupEnabled = enabled
                                prefs.edit().putBoolean("auto_backup", enabled).apply()
                                if (enabled) {
                                    com.smartledger.util.AutoBackupScheduler.schedule(context)
                                    Toast.makeText(context, "已开启每周自动备份", Toast.LENGTH_SHORT).show()
                                } else {
                                    com.smartledger.util.AutoBackupScheduler.cancel(context)
                                    Toast.makeText(context, "已关闭自动备份", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                        DividerLine()
                        SwitchItem(
                            icon = Icons.Outlined.Info,
                            label = "调试提示",
                            description = "自动记账时弹出 Toast 提示（排查问题用）",
                            checked = debugToastsEnabled,
                            onCheckedChange = { enabled ->
                                debugToastsEnabled = enabled
                                prefs.edit().putBoolean("debug_toasts", enabled).apply()
                            }
                        )
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(20.dp)) }

            // ═══ 自动记账 ═══
            item {
                SectionTitle("自动记账")
            }
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SmartLedgerColors.surface)
                ) {
                    Column {
                        SwitchItem(
                            icon = Icons.Outlined.Info,
                            label = "模糊账单需确认",
                            description = "金额截断、券类推送等不确定时，先弹窗确认并可改金额",
                            checked = confirmUncertain,
                            onCheckedChange = { enabled ->
                                confirmUncertain = enabled
                                prefs.edit().putBoolean("confirm_uncertain", enabled).apply()
                            }
                        )
                        DividerLine()
                        SwitchItem(
                            icon = Icons.Outlined.Info,
                            label = "全部自动记账需确认",
                            description = "微信/支付宝/银行卡等所有识别结果都先确认再入账",
                            checked = confirmAllAuto,
                            onCheckedChange = { enabled ->
                                confirmAllAuto = enabled
                                prefs.edit().putBoolean("confirm_all_auto", enabled).apply()
                            }
                        )
                        DividerLine()
                        MenuSettingItem(
                            icon = Icons.Outlined.Notifications,
                            label = "通知使用权",
                            onClick = { openNotificationListenerSettings(context) }
                        )
                        DividerLine()
                        MenuSettingItem(
                            icon = Icons.Outlined.Layers,
                            label = "悬浮窗权限",
                            onClick = {
                                try {
                                    context.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                                        data = android.net.Uri.fromParts("package", context.packageName, null)
                                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    })
                                } catch (_: Exception) {}
                            }
                        )
                        DividerLine()
                        MenuSettingItem(
                            icon = Icons.Outlined.BatteryStd,
                            label = "电池优化",
                            onClick = {
                                try {
                                    context.startActivity(android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    })
                                } catch (_: Exception) {}
                            }
                        )
                        DividerLine()
                        MenuSettingItem(
                            icon = Icons.Outlined.FactCheck,
                            label = "微信/支付宝页面辅助识别",
                            subtitle = if (a11yEnabled) "已开启" else "去开启 · 补齐微信支付/转账无通知的场景",
                            onClick = {
                                try {
                                    context.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    })
                                } catch (_: Exception) {}
                            }
                        )
                        DividerLine()
                        SwitchItem(
                            icon = Icons.Outlined.Smartphone,
                            label = "辅助识别开关",
                            description = "无障碍权限已开启时可临时暂停解析支付页面，通知识别不受影响",
                            checked = a11yAutoRecord,
                            onCheckedChange = { enabled ->
                                a11yAutoRecord = enabled
                                prefs.edit().putBoolean("accessibility_auto_record_enabled", enabled).apply()
                            }
                        )
                        DividerLine()
                        SwitchItem(
                            icon = Icons.Outlined.FactCheck,
                            label = "页面识别需确认",
                            description = "每次页面识别结果先弹窗确认再入账，适合谨慎使用（默认关闭）",
                            checked = confirmA11y,
                            onCheckedChange = { enabled ->
                                confirmA11y = enabled
                                prefs.edit().putBoolean("confirm_accessibility", enabled).apply()
                            }
                        )
                        if (debugToastsEnabled) {
                            DividerLine()
                            MenuSettingItem(
                                icon = Icons.Outlined.Analytics,
                                label = "采集诊断",
                                subtitle = if (a11yEnabled) "服务运行中" else "服务未开启",
                                onClick = { showA11yDiagnostics = true }
                            )
                        }
                    }
                }
            }

            // 自动记账故障提示
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                        .clickable { openNotificationListenerSettings(context) },
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SmartLedgerColors.accentDim)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            Icons.Outlined.Info,
                            contentDescription = null,
                            tint = SmartLedgerColors.accent,
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "没有自动记账？",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = SmartLedgerColors.fg
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "1. 安装或更新 App 后：系统会强制关闭通知使用权，需重新开启一次（Android 限制，无法跳过）。\n" +
                                    "2. 日常杀后台导致断开：应用会自动尝试重连；若仍无效，请关掉再打开「智能记账」的通知使用权。\n" +
                                    "3. 建议同时关闭电池优化、锁定后台，减少被系统杀掉。",
                                style = MaterialTheme.typography.bodySmall,
                                color = SmartLedgerColors.fgSecondary,
                                lineHeight = 20.sp
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "点此去开启 / 开关通知使用权 →",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Medium,
                                color = SmartLedgerColors.accent
                            )
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(12.dp)) }

            // ═══ AI 财务顾问 ═══
            item {
                SectionTitle("AI 财务顾问")
            }
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SmartLedgerColors.surface)
                ) {
                    Column {
                        MenuSettingItem(
                            icon = Icons.Outlined.AutoAwesome,
                            label = "AI 服务配置",
                            subtitle = if (aiConfig.isConfigured) aiConfig.displayLabel else "未配置",
                            onClick = onNavigateToAiSettings
                        )
                        DividerLine()
                        MenuSettingItem(
                            icon = Icons.Outlined.PrivacyTip,
                            label = "AI 会收到什么数据",
                            subtitle = "本地聚合 + 商户脱敏，不含通知原文",
                            onClick = { showAiDisclosure = true }
                        )
                    }
                }
            }

            // ═══ 关于 ═══
            item {
                SectionTitle("关于")
            }
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SmartLedgerColors.surface)
                ) {
                    Column {
                        MenuSettingItem(
                            icon = Icons.Outlined.Email,
                            label = "反馈建议",
                            onClick = onNavigateToFeedback
                        )
                        DividerLine()
                        MenuSettingItem(
                            icon = Icons.Outlined.Description,
                            label = "隐私政策",
                            onClick = onNavigateToPrivacy
                        )
                        DividerLine()
                        MenuSettingItem(
                            icon = Icons.Outlined.Refresh,
                            label = "检查更新",
                            subtitle = if (isCheckingUpdate) "检查中..." else null,
                            onClick = {
                                if (!isCheckingUpdate) {
                                    isCheckingUpdate = true
                                    coroutineScope.launch {
                                        when (val result =
                                            com.smartledger.util.UpdateChecker.checkUpdate(context)
                                        ) {
                                            is com.smartledger.util.UpdateChecker.CheckResult.HasUpdate -> {
                                                updateResult = result.info
                                            }
                                            is com.smartledger.util.UpdateChecker.CheckResult.UpToDate -> {
                                                noUpdateMessage =
                                                    "当前版本 ${result.currentVersion} 已是最新（线上 ${result.latestTag}），无需更新。"
                                                showNoUpdate = true
                                            }
                                            is com.smartledger.util.UpdateChecker.CheckResult.Failed -> {
                                                updateError = result.message
                                            }
                                        }
                                        isCheckingUpdate = false
                                    }
                                }
                            }
                        )
                        DividerLine()
                        MenuSettingItem(
                            icon = Icons.Outlined.Info,
                            label = "版本号",
                            subtitle = appVersionLabel,
                            onClick = { }
                        )
                    }
                }
            }
        }
    }

    // ═══ 检查更新结果弹窗 ═══
    updateResult?.let { info ->
        if (!isDownloadingUpdate && updateError == null) {
            com.smartledger.ui.components.SmartLedgerDialog(
                onDismissRequest = { updateResult = null },
                iconTint = SmartLedgerColors.accent,
                title = "发现新版本 ${info.versionName}",
                text = if (info.releaseNotes.isNotBlank()) {
                    val notes = info.releaseNotes.take(500)
                    if (info.releaseNotes.length > 500) "$notes\n..." else notes
                } else "有新版本可用，建议更新。",
                confirmText = if (info.apkUrl.isNullOrBlank()) "前往下载页" else "应用内下载",
                onConfirm = {
                    if (info.apkUrl.isNullOrBlank()) {
                        com.smartledger.util.UpdateChecker.openDownloadPage(context, info)
                        updateResult = null
                    } else {
                        startApkDownload(info)
                    }
                },
                dismissText = "稍后再说",
                onDismiss = { updateResult = null }
            )
        }
    }

    // ═══ 下载进度 ═══
    if (isDownloadingUpdate) {
        com.smartledger.ui.components.SmartLedgerDialog(
            onDismissRequest = { },
            iconTint = SmartLedgerColors.accent,
            title = "正在下载更新",
            text = "已下载 $downloadProgress%，请保持网络畅通…",
            confirmText = null,
            dismissText = "",
            content = {
                LinearProgressIndicator(
                    progress = downloadProgress / 100f,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp),
                    color = SmartLedgerColors.accent,
                    trackColor = SmartLedgerColors.border
                )
            }
        )
    }

    // 授权安装未知应用后可继续
    pendingInstallFile?.let { apk ->
        com.smartledger.ui.components.SmartLedgerDialog(
            onDismissRequest = { pendingInstallFile = null },
            iconTint = SmartLedgerColors.accent,
            title = "安装更新",
            text = "安装包已下载完成。若已允许安装未知应用，请点击继续安装。",
            confirmText = "继续安装",
            onConfirm = {
                val ok = com.smartledger.util.UpdateChecker.installApk(context, apk)
                if (ok) {
                    pendingInstallFile = null
                    Toast.makeText(context, "请按提示完成安装", Toast.LENGTH_SHORT).show()
                } else {
                    updateError = "仍未获得安装权限，请在系统设置中允许本应用安装未知应用。"
                }
            },
            dismissText = "取消",
            onDismiss = { pendingInstallFile = null }
        )
    }

    // ═══ 无障碍采集诊断（仅调试模式显示，方案 6.3） ═══
    if (showA11yDiagnostics) {
        com.smartledger.ui.components.SmartLedgerDialog(
            onDismissRequest = { showA11yDiagnostics = false },
            iconTint = SmartLedgerColors.accent,
            title = "页面辅助识别 · 采集诊断",
            // 诊断文本较长（今日计数 + 最近动态），用可滚动的 content 而非 text，
            // 避免小屏上把弹窗顶出屏幕
            content = {
                Text(
                    text = AccessibilityDiagnostics.buildSummary(context),
                    style = MaterialTheme.typography.bodySmall,
                    color = SmartLedgerColors.fgSecondary,
                    lineHeight = 20.sp,
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                )
            },
            confirmText = "好的",
            onConfirm = { showA11yDiagnostics = false }
        )
    }

    if (showNoUpdate) {
        com.smartledger.ui.components.SmartLedgerDialog(
            onDismissRequest = { showNoUpdate = false },
            iconTint = SmartLedgerColors.income,
            title = "已是最新版本",
            text = noUpdateMessage.ifBlank {
                "当前版本 $appVersionLabel 已是最新，无需更新。"
            },
            confirmText = "好的",
            onConfirm = { showNoUpdate = false }
        )
    }

    updateError?.let { msg ->
        val retryInfo = updateResult?.takeIf { !it.apkUrl.isNullOrBlank() }
        val cachedPct = retryInfo?.let {
            com.smartledger.util.UpdateChecker.partialDownloadPercent(context, it)
        } ?: 0
        com.smartledger.ui.components.SmartLedgerDialog(
            onDismissRequest = { updateError = null },
            iconTint = SmartLedgerColors.expense,
            title = if (retryInfo != null) "下载失败" else "检查更新失败",
            text = if (retryInfo != null && cachedPct > 0) {
                "$msg\n\n已下载 ${cachedPct}%，重新下载将从断点继续。"
            } else {
                msg
            },
            confirmText = if (retryInfo != null) "重新下载" else "好的",
            onConfirm = {
                if (retryInfo != null) {
                    startApkDownload(retryInfo)
                } else {
                    updateError = null
                }
            },
            dismissText = if (retryInfo != null) "取消" else "",
            onDismiss = { updateError = null }
        )
    }

    // ═══ AI 数据披露 ═══
    // 文案与 AiSettingsScreen 、隐私政策保持同一口径，
    // 三处不一致就等于隐私声明不实。
    if (showAiDisclosure) {
        com.smartledger.ui.components.SmartLedgerDialog(
            onDismissRequest = { showAiDisclosure = false },
            eyebrow = "PRIVACY",
            title = "AI 会收到什么数据",
            text = AI_DISCLOSURE_FULL,
            confirmText = "知道了",
            onConfirm = { showAiDisclosure = false },
            dismissText = "",
            onDismiss = { showAiDisclosure = false }
        )
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Medium,
        color = SmartLedgerColors.fgSecondary,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
    )
}

@Composable
private fun ThemeOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(
                selectedColor = SmartLedgerColors.accent,
                unselectedColor = SmartLedgerColors.fgSecondary
            )
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = SmartLedgerColors.fg
        )
    }
}

@Composable
private fun SwitchItem(
    icon: ImageVector,
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = SmartLedgerColors.fg,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = SmartLedgerColors.fg)
            Text(description, style = MaterialTheme.typography.bodySmall, color = SmartLedgerColors.fgSecondary)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = SmartLedgerColors.accent,
                checkedTrackColor = SmartLedgerColors.accentDim
            )
        )
    }
}

@Composable
private fun MenuSettingItem(
    icon: ImageVector,
    label: String,
    subtitle: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = label, tint = SmartLedgerColors.fg, modifier = Modifier.size(22.dp))
        Spacer(modifier = Modifier.width(14.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = SmartLedgerColors.fg, modifier = Modifier.weight(1f))
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = SmartLedgerColors.fgSecondary)
        }
        Spacer(modifier = Modifier.width(4.dp))
        Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = SmartLedgerColors.fgSecondary, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun DividerLine() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = SmartLedgerColors.border,
        thickness = 0.5.dp
    )
}

private fun openNotificationListenerSettings(context: Context) {
    try {
        context.startActivity(
            Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    } catch (_: Exception) {
        try {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (_: Exception) {
        }
    }
}
