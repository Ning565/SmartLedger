package com.smartledger.ui.settings

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.smartledger.data.ai.BaseUrlNormalizer
import com.smartledger.data.ai.model.AiProviderPreset
import com.smartledger.ui.components.AppTextField
import com.smartledger.ui.components.AppTopBar
import com.smartledger.ui.components.AuxText
import com.smartledger.ui.components.Eyebrow
import com.smartledger.ui.components.FilterChipItem
import com.smartledger.ui.components.Hairline
import com.smartledger.ui.components.IconButtonQuiet
import com.smartledger.ui.components.PrimaryButton
import com.smartledger.ui.components.QuietButton
import com.smartledger.ui.components.SecondaryButton
import com.smartledger.ui.components.SectionCard
import com.smartledger.ui.components.SectionHeader
import com.smartledger.ui.components.SmartLedgerDialog
import com.smartledger.ui.components.SmartLedgerPickerDialog
import com.smartledger.ui.components.StatusPill
import com.smartledger.ui.components.PillTone
import com.smartledger.ui.components.SubPanel
import com.smartledger.ui.components.UiTokens
import com.smartledger.ui.theme.AppSpacing
import com.smartledger.ui.theme.AppType
import com.smartledger.ui.theme.SmartLedgerColors

/**
 * AI 财务顾问配置页。
 *
 * 这一页是 AI 功能的唯一入口配置，也是隐私边界最敏感的地方：
 * API Key 必须加密存储、绝不进日志；用户必须清楚知道什么数据会出网。
 *
 * 设计上刻意做成「标准表单卡片」而不是花哨的向导 ——
 * 用这一页的人是想让 AI 跑起来的技术型用户，直给字段最有效。
 */
@Composable
fun AiSettingsScreen(
    onBack: () -> Unit,
    viewModel: AiSettingsViewModel = viewModel()
) {
    val form by viewModel.form.collectAsState()
    val saved by viewModel.saved.collectAsState()
    val testState by viewModel.testState.collectAsState()
    val savedNotice by viewModel.savedNotice.collectAsState()
    val keyDirty by viewModel.keyDirty.collectAsState()
    val fieldsTouched by viewModel.fieldsTouched.collectAsState()
    val needsKeyReentry by viewModel.needsKeyReentry.collectAsState()
    val showPrivacyDialog by viewModel.showPrivacyDialog.collectAsState()

    var showProviderPicker by remember { mutableStateOf(false) }
    var showPresetSwitchConfirm by remember { mutableStateOf<AiProviderPreset?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showDataDisclosure by remember { mutableStateOf(false) }
    var keyVisible by remember { mutableStateOf(false) }

    // 掩码状态下用等宽字体，避免 • 字符宽度不一造成抖动
    val isMasked = !keyDirty && form.apiKey.isNotEmpty()

    /**
     * 输入框实际展示的文本。
     *
     * 这里有个容易做错的地方：未修改时 `form.apiKey` 存的是**掩码串**
     * （sk-••••••1a2b），真明文只在 `saved.apiKey` 里。
     * 若直接把 form.apiKey 交给输入框，再叠上 PasswordVisualTransformation，
     * 就会变成「掩码之上再掩码」——点眼睛切换也只会看到同一堆圆点，
     * 用户永远看不到自己存的是什么 Key。
     *
     * 因此：掩码态 + 用户点了眼睛 → 显示内存里的真明文。
     * 这只是用户在自己设备上看自己的 Key，不会写入任何持久化位置。
     */
    val displayedKey = if (isMasked && keyVisible) saved.apiKey else form.apiKey

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SmartLedgerColors.bg)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            AppTopBar(
                title = "AI 财务顾问",
                eyebrow = "AI ADVISOR",
                onBack = onBack
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = UiTokens.pagePadding,
                    end = UiTokens.pagePadding,
                    top = AppSpacing.lg,
                    bottom = 48.dp
                ),
                verticalArrangement = Arrangement.spacedBy(UiTokens.cardGap)
            ) {
                // ═══ 换机 / 恢复备份导致 Key 解不开 ═══
                if (needsKeyReentry) {
                    item {
                        SectionCard(tint = SmartLedgerColors.warningDim) {
                            Eyebrow(text = "ACTION REQUIRED", color = SmartLedgerColors.warning)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "需要重新填写 API Key",
                                style = com.smartledger.ui.theme.AppType.listPrimary,
                                color = SmartLedgerColors.fg
                            )
                            Spacer(Modifier.height(4.dp))
                            AuxText(
                                "换机或从备份恢复后，加密密钥不会跟随迁移，" +
                                        "因此原 Key 无法解密。Base URL 与 Model 已为你保留，只需重填 Key。"
                            )
                        }
                    }
                }

                // ═══ 服务商配置 ═══
                item {
                    SectionCard(large = true) {
                        SectionHeader(
                            eyebrow = "PROVIDER",
                            title = "服务商配置"
                        )
                        Spacer(Modifier.height(AppSpacing.lg))

                        // 服务商预设：伪输入框，整块可点。
                        // enabled = false 让内层 BasicTextField 不消费点击，
                        // 因此 clickable 直接加在 AppTextField 的根 Modifier 上就能生效。
                        AppTextField(
                            value = form.preset.label,
                            onValueChange = {},
                            label = "服务商",
                            enabled = false,
                            modifier = Modifier.clickable { showProviderPicker = true },
                            trailing = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Outlined.ExpandMore,
                                        contentDescription = null,
                                        tint = SmartLedgerColors.fgSecondary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    // 扩大点击区域到 48dp
                                    Box(
                                        modifier = Modifier
                                            .size(UiTokens.touchTarget)
                                            .align(Alignment.CenterVertically),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "选择",
                                            style = com.smartledger.ui.theme.AppType.aux,
                                            color = SmartLedgerColors.fgSecondary
                                        )
                                    }
                                }
                            },
                            helper = null,
                            error = null
                        )

                        Spacer(Modifier.height(AppSpacing.lg))

                        // Base URL
                        AppTextField(
                            value = form.baseUrl,
                            onValueChange = viewModel::onBaseUrlChange,
                            label = "BASE URL",
                            placeholder = "https://api.deepseek.com/v1",
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                autoCorrectEnabled = false
                            ),
                            helper = when {
                                form.baseUrl.isBlank() -> "选择服务商后会自动填入，也可手工修改"
                                BaseUrlNormalizer.isCleartext(form.baseUrl) ->
                                    "⚠ 系统禁止明文 HTTP，请改用 https"
                                !BaseUrlNormalizer.looksValid(form.baseUrl) ->
                                    "⚠ 地址格式不正确"
                                else -> "将请求 ${BaseUrlNormalizer.chatCompletionsUrl(form.baseUrl)}"
                            },
                            error = if (form.baseUrl.isNotBlank() &&
                                (BaseUrlNormalizer.isCleartext(form.baseUrl) ||
                                        !BaseUrlNormalizer.looksValid(form.baseUrl))
                            ) " " else null
                        )

                        Spacer(Modifier.height(AppSpacing.lg))

                        // API Key
                        AppTextField(
                            value = displayedKey,
                            onValueChange = viewModel::onApiKeyChange,
                            label = "API KEY",
                            placeholder = form.preset.apiKeyHint,
                            obscure = !keyVisible,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                autoCorrectEnabled = false
                            ),
                            helper = when {
                                isMasked && !keyVisible ->
                                    "已保存（点右侧眼睛可查看明文）。不修改则保留原 Key。"

                                isMasked ->
                                    "当前显示的是已保存的明文。一旦编辑就会当作新 Key 保存。"

                                else ->
                                    "仅保存在本机并用系统密钥库加密，不会上传到 SmartLedger，也不会写进日志"
                            },
                            trailing = {
                                IconButtonQuiet(
                                    icon = if (keyVisible) Icons.Outlined.VisibilityOff
                                    else Icons.Outlined.Visibility,
                                    contentDescription = if (keyVisible) "隐藏" else "显示",
                                    onClick = { keyVisible = !keyVisible },
                                    tint = SmartLedgerColors.fgSecondary
                                )
                            }
                        )

                        Spacer(Modifier.height(AppSpacing.lg))

                        // Model
                        AppTextField(
                            value = form.model,
                            onValueChange = viewModel::onModelChange,
                            label = "MODEL",
                            placeholder = form.preset.defaultModel.ifBlank { "模型名称" },
                            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false)
                        )

                        // 预设模型快捷选择
                        if (form.preset.modelOptions.size > 1) {
                            Spacer(Modifier.height(AppSpacing.md))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
                            ) {
                                form.preset.modelOptions.forEach { m ->
                                    FilterChipItem(
                                        text = m,
                                        selected = form.model == m,
                                        onClick = { viewModel.onModelChange(m) }
                                    )
                                }
                            }
                        }

                        // 推理模型额度提示
                        if (viewModel.isReasoningModel()) {
                            Spacer(Modifier.height(AppSpacing.md))
                            SubPanel {
                                Eyebrow(text = "TOKEN USAGE", color = SmartLedgerColors.warning)
                                Spacer(Modifier.height(2.dp))
                                AuxText(
                                    "${form.model} 是推理模型：它会先输出一段思考过程，" +
                                            "额度消耗明显更高，且在输出长度受限时正文可能为空。" +
                                            "日常使用建议选 ${form.preset.modelOptions.firstOrNull() ?: "deepseek-chat"}。"
                                )
                            }
                        }

                        if (form.preset.unverified) {
                            Spacer(Modifier.height(AppSpacing.md))
                            AuxText(
                                "该服务商尚未逐一实测，若测试连接失败请核对官方文档的 Base URL 与模型名。",
                                color = SmartLedgerColors.fgTertiary
                            )
                        }
                    }
                }

                // ═══ 测试与保存 ═══
                item {
                    SectionCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
                        ) {
                            SecondaryButton(
                                text = if (testState is AiSettingsViewModel.TestState.Running)
                                    "测试中…" else "测试连接",
                                onClick = viewModel::testConnection,
                                enabled = testState !is AiSettingsViewModel.TestState.Running &&
                                        form.baseUrl.isNotBlank() && form.model.isNotBlank(),
                                modifier = Modifier.weight(1f)
                            )
                            PrimaryButton(
                                text = "保存",
                                onClick = viewModel::save,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        // 测试结果用小型状态标签呈现，不弹 Toast（信息量太大）
                        when (val t = testState) {
                            is AiSettingsViewModel.TestState.Running -> {
                                Spacer(Modifier.height(AppSpacing.md))
                                StatusPill(text = "正在测试…", tone = PillTone.INFO)
                            }

                            is AiSettingsViewModel.TestState.Ok -> {
                                Spacer(Modifier.height(AppSpacing.md))
                                StatusPill(text = "连接成功", tone = PillTone.POSITIVE)
                                Spacer(Modifier.height(6.dp))
                                AuxText(
                                    "模型回复：${t.reply}　耗时 ${"%.1f".format(t.elapsedMs / 1000.0)}s"
                                )
                                if (t.viaReasoning) {
                                    Spacer(Modifier.height(4.dp))
                                    AuxText(
                                        "该模型返回正文为空，已用推理内容兜底。建议改用非推理模型。",
                                        color = SmartLedgerColors.warning
                                    )
                                }
                            }

                            is AiSettingsViewModel.TestState.Failed -> {
                                Spacer(Modifier.height(AppSpacing.md))
                                StatusPill(text = "测试失败", tone = PillTone.DANGER)
                                Spacer(Modifier.height(6.dp))
                                AuxText(text = t.message, color = SmartLedgerColors.expense)
                            }

                            else -> Unit
                        }

                        if (!savedNotice.isNullOrBlank()) {
                            Spacer(Modifier.height(AppSpacing.md))
                            Hairline()
                            Spacer(Modifier.height(AppSpacing.md))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                AuxText(
                                    text = savedNotice!!,
                                    color = SmartLedgerColors.fgSecondary,
                                    modifier = Modifier.weight(1f)
                                )
                                QuietButton(text = "知道了", onClick = viewModel::dismissNotice)
                            }
                        }

                        if (saved.isConfigured) {
                            Spacer(Modifier.height(AppSpacing.md))
                            Hairline()
                            Spacer(Modifier.height(AppSpacing.md))
                            Eyebrow(text = "ACTIVE CONFIG")
                            Spacer(Modifier.height(4.dp))
                            AuxText(text = saved.displayLabel, color = SmartLedgerColors.fg)
                        }
                    }
                }

                // ═══ 数据披露 ═══
                item {
                    SectionCard(onClick = { showDataDisclosure = true }) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Outlined.Info,
                                contentDescription = null,
                                tint = SmartLedgerColors.ai,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(AppSpacing.md))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "AI 会收到什么数据？",
                                    style = com.smartledger.ui.theme.AppType.listPrimary,
                                    color = SmartLedgerColors.fg
                                )
                                AuxText("本地聚合 + 商户脱敏，不含通知原文与备注")
                            }
                        }
                    }
                }

                // ═══ 清除 ═══
                item {
                    SectionCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = null,
                                tint = SmartLedgerColors.expense,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(AppSpacing.md))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "清除配置与本地 AI 报告",
                                    style = com.smartledger.ui.theme.AppType.listPrimary,
                                    color = SmartLedgerColors.expense
                                )
                                AuxText("删除 API Key、服务商配置与已缓存的报告")
                            }
                            QuietButton(
                                text = "清除",
                                onClick = { showClearConfirm = true },
                                color = SmartLedgerColors.expense
                            )
                        }
                    }
                }
            }
        }
    }

    // ═══ 服务商选择 ═══
    if (showProviderPicker) {
        SmartLedgerPickerDialog(
            onDismissRequest = { showProviderPicker = false },
            title = "选择服务商",
            eyebrow = "PROVIDER",
            options = AiProviderPreset.entries.toList(),
            labelOf = { it.label },
            sublabelOf = { p ->
                when {
                    p.unverified -> "未实测 · ${p.defaultBaseUrl.ifBlank { "需手工填写" }}"
                    p.defaultBaseUrl.isBlank() -> "需手工填写 Base URL 与 Model"
                    else -> p.defaultBaseUrl
                }
            },
            selected = form.preset,
            onSelect = { picked ->
                showProviderPicker = false
                if (picked != form.preset && fieldsTouched &&
                    (form.baseUrl.isNotBlank() || form.model.isNotBlank())
                ) {
                    // 用户手改过地址/模型，切预设会覆盖，先确认
                    showPresetSwitchConfirm = picked
                } else {
                    viewModel.selectPreset(picked)
                }
            }
        )
    }

    if (showPresetSwitchConfirm != null) {
        SmartLedgerDialog(
            onDismissRequest = { showPresetSwitchConfirm = null },
            eyebrow = "PROVIDER",
            title = "切换服务商",
            text = "切换为「${showPresetSwitchConfirm!!.label}」会用推荐值覆盖当前的 Base URL 与 Model。\n\nAPI Key 不会被改动。",
            confirmText = "覆盖并切换",
            onConfirm = {
                viewModel.selectPreset(showPresetSwitchConfirm!!)
                showPresetSwitchConfirm = null
            },
            dismissText = "取消",
            onDismiss = { showPresetSwitchConfirm = null }
        )
    }

    // ═══ 首次保存的隐私告知（合规必做）═══
    if (showPrivacyDialog) {
        SmartLedgerDialog(
            onDismissRequest = { viewModel.dismissPrivacyDialog(acknowledged = false) },
            eyebrow = "PRIVACY",
            title = "启用 AI 前请了解",
            // 长文本必须走 content + verticalScroll：SmartLedgerDialog 的 text 参数
            // 外层没有滚动容器，AlertDialog 的 text 区高度受限，
            // 文案超出后既看不全也滑不动（与诊断弹窗同一修法，见方案 B.3）。
            // 样式沿用 SmartLedgerDialog 内部渲染 text 的 AppType.body + fgSecondary，
            // 保证改成 content 后视觉不变。
            content = {
                Text(
                    text = AI_DISCLOSURE_SHORT,
                    style = AppType.body,
                    color = SmartLedgerColors.fgSecondary,
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                )
            },
            confirmText = "我已了解",
            onConfirm = { viewModel.dismissPrivacyDialog(acknowledged = true) },
            dismissText = "再想想",
            onDismiss = { viewModel.dismissPrivacyDialog(acknowledged = false) }
        )
    }

    if (showDataDisclosure) {
        SmartLedgerDialog(
            onDismissRequest = { showDataDisclosure = false },
            eyebrow = "PRIVACY",
            title = "AI 会收到什么数据",
            // 同上：这份文案约 20 行，不滚动则大部分内容永远看不到
            content = {
                Text(
                    text = AI_DISCLOSURE_FULL,
                    style = AppType.body,
                    color = SmartLedgerColors.fgSecondary,
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                )
            },
            confirmText = "知道了",
            onConfirm = { showDataDisclosure = false },
            dismissText = "",
            onDismiss = { showDataDisclosure = false }
        )
    }

    if (showClearConfirm) {
        SmartLedgerDialog(
            onDismissRequest = { showClearConfirm = false },
            icon = Icons.Outlined.Delete,
            iconTint = SmartLedgerColors.expense,
            title = "清除 AI 配置",
            text = "将删除 API Key、服务商配置，以及本地缓存的全部 AI 报告。\n\n记账数据不受影响。",
            confirmText = "清除",
            confirmColor = SmartLedgerColors.expense,
            onConfirm = {
                showClearConfirm = false
                viewModel.clearAll { }
            },
            dismissText = "取消",
            onDismiss = { showClearConfirm = false }
        )
    }

    // 保存后若测试状态还挂着失败信息，切一下配置就清掉，避免误导
    LaunchedEffect(form.baseUrl, form.model, form.preset) {
        if (testState is AiSettingsViewModel.TestState.Failed) viewModel.cancelTest()
    }
}

/** 首次告知用的精简版文案 */
internal const val AI_DISCLOSURE_SHORT =
    "启用后，「AI 消费体检」与「自然语言记账」会由你的手机直连你配置的 AI 服务商。\n\n" +
            "发送内容仅限本地统计结果：金额合计、分类名与占比、脱敏后的商户编号、时段与星期分布、笔数、预算数值。\n\n" +
            "不会发送：原始支付通知、银行卡号、手机号、订单号、真实商户名、备注原文。\n\n" +
            "SmartLedger 不设中转服务器，不收集任何数据。未配置或未启用时，App 完全离线运行。"

/** 「AI 会收到什么数据」的完整文案，与隐私政策保持同一口径 */
internal const val AI_DISCLOSURE_FULL =
    "【会发送】\n" +
            "· 周期标签（如 2026 年 9 月）\n" +
            "· 收入 / 支出 / 预算的金额合计\n" +
            "· 分类名称与对应金额、笔数、占比\n" +
            "· 脱敏后的商户编号（如「餐饮商户 #1」）与金额、笔数\n" +
            "· 时段、工作日/周末、夜间的金额与笔数分布\n" +
            "· 交易总笔数、日均、环比百分比\n\n" +
            "【不会发送】\n" +
            "· 原始支付通知全文、标题、包名\n" +
            "· 银行卡号、手机号、订单号、支付流水号\n" +
            "· 真实商户名称（含任何片段）\n" +
            "· 备注原文、联系人姓名\n" +
            "· 逐笔时间戳序列（只发聚合后的分布）\n\n" +
            "【传输方式】\n" +
            "由你的设备直连你配置的服务商，SmartLedger 不设中转服务器、不收集任何数据。\n" +
            "API Key 用 Android 系统密钥库加密后仅存本机，不参与自动备份，也不会写入日志。\n\n" +
            "【语音】\n" +
            "语音记账使用系统语音识别，SmartLedger 不录音、不上传音频，只接收系统返回的文字。"
