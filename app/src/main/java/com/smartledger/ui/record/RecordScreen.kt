package com.smartledger.ui.record

import android.app.DatePickerDialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backspace
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material.icons.outlined.OndemandVideo
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.outlined.AttachMoney
import androidx.compose.material.icons.outlined.TrendingUp
import androidx.compose.material.icons.outlined.CardGiftcard
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.smartledger.data.db.entity.Category
import com.smartledger.ui.components.AuxText
import com.smartledger.ui.components.Eyebrow
import com.smartledger.ui.components.Hairline
import com.smartledger.ui.components.IconButtonQuiet
import com.smartledger.ui.components.PrimaryButton
import com.smartledger.ui.components.QuietButton
import com.smartledger.ui.components.SecondaryButton
import com.smartledger.ui.components.SegmentedTabs
import com.smartledger.ui.components.SectionCard
import com.smartledger.ui.components.SmartLedgerDialog
import com.smartledger.ui.components.StatusPill
import com.smartledger.ui.components.PillTone
import com.smartledger.ui.components.SubPanel
import com.smartledger.ui.components.UiTokens
import com.smartledger.ui.components.PaymentChannelPicker
import com.smartledger.ui.theme.AppRadius
import com.smartledger.ui.theme.AppSize
import com.smartledger.ui.theme.AppSpacing
import com.smartledger.ui.theme.AppType
import com.smartledger.ui.theme.SmartLedgerColors
import com.smartledger.util.DateUtil
import java.util.Calendar

/**
 * 记账页。
 *
 * 保留原有表单结构（类型 / 金额 / 商户 / 备注 / 渠道 / 分类 / 数字键盘），
 * 在顶部增加一条自然语言输入栏与麦克风按钮。
 *
 * 自然语言栏在视觉上刻意做成**辅助工具而不是主入口**：
 * 用 SubPanel（次级分区底）而非强调色卡片，高度紧凑，
 * AI 未配置时整块不显示 —— 不给用户一个点了就报错的按钮。
 *
 * AI 解析结果只回填普通表单，用户确认后才入库。
 */
@Composable
fun RecordScreen(
    onSaved: () -> Unit = {},
    viewModel: RecordViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val nlInput by viewModel.nlInput.collectAsState()
    val nlState by viewModel.nlState.collectAsState()

    // 必须 remember 住 Flow 实例：`getCategories()` 每次调用都返回一个**新** Flow，
    // 而状态提升后每按一个数字键都会重组 uiState ——
    // 不 remember 就会每次重组都重启一次 Room 收集（白跑一次数据库查询）。
    // 原实现里表单状态是 Composable 内部的 remember，重组频率低，问题不明显；
    // 提到 ViewModel 后必须显式处理。
    val categoriesFlow = remember(viewModel, state.transactionType) {
        viewModel.getCategories(state.transactionType)
    }
    val categories by categoriesFlow.collectAsState(initial = emptyList())

    val context = LocalContext.current
    var showDateDialog by remember { mutableStateOf(false) }
    val speechAvailable = remember { SpeechInput.isAvailable(context) }

    // 语音识别：系统面板，不需要 RECORD_AUDIO 权限
    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // 用户取消 / 识别失败都返回 null，一律静默处理
        val text = SpeechInput.extractResult(result.data)
        if (!text.isNullOrBlank()) {
            viewModel.setNlInput(text)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SmartLedgerColors.bg)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(AppSpacing.lg))

            // ═══ 自然语言 / 语音快捷填写（AI 未配置时整块隐藏）═══
            if (nlState !is NlParseState.Hidden) {
                NaturalLanguageBar(
                    input = nlInput,
                    onInputChange = viewModel::setNlInput,
                    parseState = nlState,
                    micAvailable = speechAvailable,
                    onMicClick = {
                        runCatching { speechLauncher.launch(SpeechInput.buildIntent()) }
                        // ActivityNotFoundException 等一律静默：降级为纯文字输入
                    },
                    onParse = viewModel::parseNaturalLanguage,
                    onCancel = viewModel::cancelParse,
                    modifier = Modifier.padding(horizontal = UiTokens.pagePadding)
                )
                Spacer(Modifier.height(AppSpacing.cardGap))
            }

            // ═══ 收支类型 ═══
            SegmentedTabs(
                items = listOf("支出" to RecordUiState.TYPE_EXPENSE, "收入" to RecordUiState.TYPE_INCOME),
                selected = state.transactionType,
                onSelect = viewModel::setType,
                modifier = Modifier
                    .padding(horizontal = 72.dp)
                    .fillMaxWidth()
            )

            // ═══ Hero 金额 ═══
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = UiTokens.pagePadding, vertical = AppSpacing.lg),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Eyebrow(text = if (state.isExpense) "EXPENSE" else "INCOME")
                Spacer(Modifier.height(AppSpacing.sm))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = "¥",
                        style = AppType.cardNumberSmall,
                        color = SmartLedgerColors.fgSecondary,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = state.amountText,
                        style = AppType.heroAmount,
                        color = if (state.isExpense) SmartLedgerColors.expense
                        else SmartLedgerColors.income,
                        maxLines = 1
                    )
                }
            }

            // ═══ 表单卡片：商户 / 备注 / 日期 / 渠道 ═══
            SectionCard(
                modifier = Modifier.padding(horizontal = UiTokens.pagePadding),
                contentPadding = PaddingValues(
                    horizontal = AppSpacing.lg,
                    vertical = AppSpacing.sm
                )
            ) {
                InlineFieldRow(
                    icon = Icons.Outlined.Person,
                    value = state.merchant,
                    onValueChange = viewModel::setMerchant,
                    placeholder = "商户名称（如蒙牛、美团）"
                )
                Hairline()
                InlineFieldRow(
                    icon = Icons.Outlined.Edit,
                    value = state.note,
                    onValueChange = viewModel::setNote,
                    placeholder = "添加备注"
                )
                Hairline()
                DateRow(
                    transactionTime = state.transactionTime,
                    onClick = { showDateDialog = true }
                )
                Hairline()
                Column {
                    Eyebrow(text = "PAYMENT CHANNEL")
                    Spacer(Modifier.height(6.dp))
                    PaymentChannelPicker(
                        selected = state.paymentMethod,
                        onSelected = viewModel::setPaymentMethod
                    )
                }
            }

            Spacer(Modifier.height(AppSpacing.cardGap))

            // ═══ 分类 ═══
            SectionCard(
                modifier = Modifier.padding(horizontal = UiTokens.pagePadding),
                contentPadding = PaddingValues(AppSpacing.lg)
            ) {
                Eyebrow(text = "CATEGORY")
                Spacer(Modifier.height(AppSpacing.md))
                if (categories.isEmpty()) {
                    AuxText(text = "暂无可用分类，请到「分类管理」中添加")
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp),
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
                        verticalArrangement = Arrangement.spacedBy(AppSpacing.md),
                        userScrollEnabled = false
                    ) {
                        items(categories, key = { it.id }) { category ->
                            CategoryItem(
                                category = category,
                                selected = state.selectedCategoryId == category.id,
                                onClick = { viewModel.setCategoryId(category.id) }
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(AppSpacing.cardGap))

            // ═══ 数字键盘 ═══
            SectionCard(
                modifier = Modifier.padding(horizontal = UiTokens.pagePadding),
                tint = SmartLedgerColors.surfaceHover,
                contentPadding = PaddingValues(AppSpacing.md)
            ) {
                AmountKeypad.KEYS.chunked(3).forEach { rowKeys ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
                    ) {
                        rowKeys.forEach { key ->
                            KeypadKey(
                                key = key,
                                onClick = { viewModel.pressKey(key) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    Spacer(Modifier.height(AppSpacing.sm))
                }
                Spacer(Modifier.height(AppSpacing.xs))

                PrimaryButton(
                    text = "记一笔",
                    onClick = {
                        viewModel.save(
                            onSuccess = onSaved,
                            onInvalid = { msg ->
                                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                )
            }

            Spacer(Modifier.height(AppSpacing.xxl))
        }
    }

    // ═══ 日期选择 ═══
    if (showDateDialog) {
        DatePickDialog(
            current = state.transactionTime,
            onDismiss = { showDateDialog = false },
            onRelative = { offset ->
                viewModel.setRelativeDay(offset)
                showDateDialog = false
            },
            onCustom = {
                showDateDialog = false
                val cal = Calendar.getInstance().apply { timeInMillis = state.transactionTime }
                DatePickerDialog(
                    context,
                    { _, y, m, d ->
                        // 返回 null 说明选到了未来日期，被 ViewModel 拒绝
                        val applied = viewModel.setDate(y, m + 1, d)
                        if (applied == null) {
                            android.widget.Toast.makeText(
                                context, "不能记未来日期的账", android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    cal.get(Calendar.YEAR),
                    cal.get(Calendar.MONTH),
                    cal.get(Calendar.DAY_OF_MONTH)
                ).apply {
                    // 不允许未来日期，与首页「不能翻到未来月」的约定一致
                    datePicker.maxDate = System.currentTimeMillis()
                }.show()
            }
        )
    }
}

// ═══════════════════════════════════════════════════════
// 自然语言输入栏
// ═══════════════════════════════════════════════════════

/**
 * 自然语言 / 语音输入栏。
 *
 * 视觉上用 SubPanel（次级分区底）而不是 Violet 强调卡：
 * 在记账页它是辅助工具，抢了主表单的注意力反而拖慢高频操作。
 * Violet 只用在左侧的 ✨ 图标上做一点标识。
 */
@Composable
private fun NaturalLanguageBar(
    input: String,
    onInputChange: (String) -> Unit,
    parseState: NlParseState,
    micAvailable: Boolean,
    onMicClick: () -> Unit,
    onParse: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val running = parseState is NlParseState.Running

    SubPanel(modifier = modifier, contentPadding = PaddingValues(AppSpacing.md)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.AutoAwesome,
                contentDescription = null,
                tint = SmartLedgerColors.ai,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(AppSpacing.sm))
            Box(modifier = Modifier.weight(1f)) {
                androidx.compose.foundation.text.BasicTextField(
                    value = input,
                    onValueChange = onInputChange,
                    enabled = !running,
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 40.dp),
                    textStyle = AppType.body.copy(color = SmartLedgerColors.fg),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(SmartLedgerColors.fg),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                        autoCorrectEnabled = false
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onDone = { if (!running && input.isNotBlank()) onParse() }
                    ),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (input.isEmpty()) {
                                Text(
                                    text = "说一句或输入一句话，例如：昨天老乡鸡32元微信",
                                    style = AppType.aux,
                                    color = SmartLedgerColors.fgTertiary,
                                    maxLines = 1
                                )
                            }
                            inner()
                        }
                    }
                )
            }
            if (micAvailable) {
                IconButtonQuiet(
                    icon = Icons.Outlined.Mic,
                    contentDescription = "语音输入",
                    onClick = onMicClick,
                    enabled = !running,
                    tint = SmartLedgerColors.fgSecondary
                )
            }
        }

        Spacer(Modifier.height(AppSpacing.sm))

        Row(verticalAlignment = Alignment.CenterVertically) {
            // 状态反馈放在按钮左侧，避免整块高度跳变
            Box(modifier = Modifier.weight(1f)) {
                when (val s = parseState) {
                    is NlParseState.Filled -> Column {
                        if (s.missing.isEmpty()) {
                            StatusPill(text = "已回填", tone = PillTone.POSITIVE)
                        } else {
                            StatusPill(
                                text = "部分未识别：${s.missing.joinToString("、")}",
                                tone = PillTone.WARNING
                            )
                        }
                        if (s.summary.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            AuxText(text = s.summary, color = SmartLedgerColors.fgSecondary)
                        }
                    }

                    is NlParseState.Failed -> StatusPill(text = s.message, tone = PillTone.DANGER)

                    NlParseState.Running -> StatusPill(text = "AI 解析中…", tone = PillTone.AI)

                    else -> AuxText(
                        text = "解析结果只回填表单，确认无误后再保存",
                        color = SmartLedgerColors.fgTertiary
                    )
                }
            }
            Spacer(Modifier.width(AppSpacing.sm))
            if (running) {
                QuietButton(text = "取消", onClick = onCancel)
            } else {
                SecondaryButton(
                    text = "AI 解析",
                    onClick = onParse,
                    enabled = input.isNotBlank(),
                    tone = PillTone.AI
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════
// 表单行
// ═══════════════════════════════════════════════════════

/** 图标 + 无边框输入的行内字段，比独立输入框更紧凑 */
@Composable
private fun InlineFieldRow(
    icon: ImageVector,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = AppSize.minTouchTarget),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = SmartLedgerColors.fgTertiary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(AppSpacing.md))
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 44.dp),
            textStyle = AppType.body.copy(color = SmartLedgerColors.fg),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(SmartLedgerColors.fg),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                autoCorrectEnabled = false
            ),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
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
}

/** 日期行：点击弹出选择器 */
@Composable
private fun DateRow(transactionTime: Long, onClick: () -> Unit) {
    val isToday = DateUtil.isToday(transactionTime)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = AppSize.minTouchTarget)
            .clip(RoundedCornerShape(AppRadius.small))
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Outlined.CalendarMonth,
            contentDescription = null,
            tint = if (isToday) SmartLedgerColors.fgTertiary else SmartLedgerColors.info,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(AppSpacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = DateUtil.formatDate(transactionTime),
                style = AppType.listPrimary,
                color = SmartLedgerColors.fg
            )
            AuxText(
                text = "${DateUtil.getDayOfWeek(transactionTime)} ${DateUtil.formatTime(transactionTime)}" +
                        if (isToday) "" else " · 已改期",
                color = SmartLedgerColors.fgTertiary,
                maxLines = 1
            )
        }
        Text(
            text = if (isToday) "今天" else "已修改",
            style = AppType.pill,
            color = if (isToday) SmartLedgerColors.fgTertiary else SmartLedgerColors.info
        )
    }
}

@Composable
private fun DatePickDialog(
    current: Long,
    onDismiss: () -> Unit,
    onRelative: (Int) -> Unit,
    onCustom: () -> Unit
) {
    fun label(offset: Int): String {
        val c = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, offset) }
        return "${c.get(Calendar.MONTH) + 1}月${c.get(Calendar.DAY_OF_MONTH)}日"
    }

    fun yesterdayMs(): Long =
        Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -1) }.timeInMillis
    val currentOffset = when {
        DateUtil.isToday(current) -> 0
        DateUtil.formatDate(current) == DateUtil.formatDate(yesterdayMs()) -> -1
        else -> null
    }

    SmartLedgerDialog(
        onDismissRequest = onDismiss,
        eyebrow = "DATE",
        title = "选择日期",
        text = "补记历史账单时，时刻会保留当前时分，以免被算进夜间消费。",
        dismissText = "取消",
        onDismiss = onDismiss,
        content = {
            listOf(0 to "今天", -1 to "昨天", -2 to "前天").forEach { (offset, name) ->
                val selected = currentOffset == offset
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = AppSize.minTouchTarget)
                        .clip(RoundedCornerShape(AppRadius.field))
                        .background(if (selected) SmartLedgerColors.accentDim else Color.Transparent)
                        .clickable { onRelative(offset) }
                        .padding(horizontal = AppSpacing.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = name,
                        style = AppType.listPrimary,
                        color = SmartLedgerColors.fg,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    AuxText(text = label(offset), color = SmartLedgerColors.fgTertiary)
                }
            }
            Hairline(modifier = Modifier.padding(vertical = AppSpacing.sm))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = AppSize.minTouchTarget)
                    .clip(RoundedCornerShape(AppRadius.field))
                    .clickable(onClick = onCustom)
                    .padding(horizontal = AppSpacing.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "选择其他日期…",
                    style = AppType.listPrimary,
                    color = SmartLedgerColors.fg,
                    modifier = Modifier.weight(1f)
                )
                AuxText(
                    text = DateUtil.formatDate(current),
                    color = SmartLedgerColors.fgTertiary
                )
            }
        }
    )
}

// ═══════════════════════════════════════════════════════
// 分类项
// ═══════════════════════════════════════════════════════

@Composable
private fun CategoryItem(category: Category, selected: Boolean, onClick: () -> Unit) {
    val icon = getCategoryIcon(category.name)
    val fg = if (selected) SmartLedgerColors.fg else SmartLedgerColors.fgSecondary
    val bg = if (selected) SmartLedgerColors.accentDim else Color.Transparent

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(AppRadius.field))
            .clickable(onClick = onClick)
            .background(bg)
            .padding(vertical = 10.dp)
    ) {
        Icon(icon, contentDescription = category.name, tint = fg, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(6.dp))
        Text(
            text = category.name,
            style = AppType.aux,
            color = fg,
            maxLines = 1,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = TextAlign.Center
        )
    }
}

// ═══════════════════════════════════════════════════════
// 键盘按键
// ═══════════════════════════════════════════════════════

@Composable
private fun KeypadKey(key: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .heightIn(min = AppSize.minTouchTarget)
            .clip(RoundedCornerShape(AppRadius.field))
            .background(SmartLedgerColors.surface)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (key == AmountKeypad.KEY_BACKSPACE) {
            Icon(
                Icons.Outlined.Backspace,
                contentDescription = "删除",
                tint = SmartLedgerColors.fgSecondary,
                modifier = Modifier.size(20.dp)
            )
        } else {
            Text(
                text = key,
                style = AppType.cardNumberSmall,
                color = SmartLedgerColors.fg
            )
        }
    }
}

// ═══════════════════════════════════════════════════════
// 分类图标映射（与原实现一致）
// ═══════════════════════════════════════════════════════

private fun getCategoryIcon(name: String): ImageVector = when (name) {
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

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, widthDp = 393, heightDp = 852)
@Composable
private fun RecordScreenPreview() {
    com.smartledger.ui.theme.SmartLedgerTheme {
        RecordScreen()
    }
}
