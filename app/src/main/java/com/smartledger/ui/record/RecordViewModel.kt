package com.smartledger.ui.record

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.smartledger.SmartLedgerApp
import com.smartledger.data.ai.AiResult
import com.smartledger.data.ai.AiTransactionParser
import com.smartledger.data.ai.model.CategoryRef
import com.smartledger.data.ai.model.TransactionDraft
import com.smartledger.data.db.entity.Category
import com.smartledger.data.db.entity.Transaction
import com.smartledger.data.repository.AiPromptBuilder
import com.smartledger.service.SmartCategorizer
import com.smartledger.util.DateUtil
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * 记账页 ViewModel。
 *
 * 承担三件事：
 *  1. 表单状态（提升自 Composable，见 [RecordUiState]）
 *  2. 保存（走原有 TransactionRepository，行为不变）
 *  3. AI 自然语言解析 → 草稿 → 回填
 *
 * **红线**：第 3 步只写 `_uiState`，不存在任何调用 `insert` 的路径。
 * AI 绝不能直接保存 Transaction —— 必须经用户检查后点「记一笔」。
 */
class RecordViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as SmartLedgerApp
    private val transactionRepo = app.transactionRepository
    private val categoryRepo = app.categoryRepository
    private val aiSettings = app.aiSettingsRepository
    private val aiClient = app.aiClient

    private val _uiState = MutableStateFlow(RecordUiState())
    val uiState: StateFlow<RecordUiState> = _uiState.asStateFlow()

    /** 自然语言输入框的文本。刻意不放进 RecordUiState：它不是账单的一部分 */
    private val _nlInput = MutableStateFlow("")
    val nlInput: StateFlow<String> = _nlInput.asStateFlow()

    private val _nlState = MutableStateFlow<NlParseState>(NlParseState.Hidden)
    val nlState: StateFlow<NlParseState> = _nlState.asStateFlow()

    /** 全量分类缓存，AI 解析时要用它做 (name, type) → id 映射 */
    private val _allCategories = MutableStateFlow<List<Category>>(emptyList())
    val allCategories: StateFlow<List<Category>> = _allCategories.asStateFlow()

    private var parseJob: Job? = null

    init {
        viewModelScope.launch {
            // 一次性拉全量分类：既用于 AI 映射，也用于把 categoryId 反查成名字
            categoryRepo.getAll().collect { _allCategories.value = it }
        }
        // AI 配置可能在别的页面被改（设置页保存 / 清除），这里持续跟随
        viewModelScope.launch {
            aiSettings.config.collect { cfg ->
                _nlState.value = if (cfg.isConfigured) {
                    // 已配置：若当前是 Hidden 或 Idle 则放开入口，
                    // 但不要把正在进行的解析状态冲掉
                    when (_nlState.value) {
                        NlParseState.Hidden, NlParseState.Idle -> NlParseState.Idle
                        else -> _nlState.value
                    }
                } else {
                    NlParseState.Hidden
                }
            }
        }
    }

    fun getCategories(type: String): Flow<List<Category>> = categoryRepo.getByType(type)

    // ═══════════════════════════════════════════════════
    // 表单事件
    // ═══════════════════════════════════════════════════

    /** 切换收支类型时清空已选分类：分类是按 type 分组的，跨类型会选到错分类 */
    fun setType(type: String) {
        if (_uiState.value.transactionType == type) return
        _uiState.value = _uiState.value.copy(
            transactionType = type,
            selectedCategoryId = null
        )
    }

    /** 数字键盘按键，逻辑见 [AmountKeypad]（与原 Composable 内实现一致） */
    fun pressKey(key: String) {
        val cur = _uiState.value.amountText
        val next = AmountKeypad.press(cur, key)
        if (next != cur) _uiState.value = _uiState.value.copy(amountText = next)
    }

    fun setCategoryId(id: Long?) {
        _uiState.value = _uiState.value.copy(selectedCategoryId = id)
    }

    fun setMerchant(v: String) {
        _uiState.value = _uiState.value.copy(merchant = v)
    }

    fun setNote(v: String) {
        _uiState.value = _uiState.value.copy(note = v)
    }

    fun setPaymentMethod(v: String) {
        _uiState.value = _uiState.value.copy(paymentMethod = v)
    }

    fun setTransactionTime(ms: Long) {
        _uiState.value = _uiState.value.copy(transactionTime = ms)
    }

    fun setNlInput(v: String) {
        _nlInput.value = v
        // 用户改了输入就清掉上一次的解析反馈，避免旧提示误导
        if (_nlState.value is NlParseState.Filled || _nlState.value is NlParseState.Failed) {
            _nlState.value = NlParseState.Idle
        }
    }

    // ═══════════════════════════════════════════════════
    // 日期选择
    // ═══════════════════════════════════════════════════

    /**
     * 按「年-月-日」重设交易日期，**保留当前的时分**。
     *
     * 为什么保留时分：选「昨天」时若把时间抹成 00:00，
     * 这笔账会落进「夜间 22-06」时段桶，
     * 在 AI 消费体检里被当成凌晨消费，直接扭曲行为画像。
     *
     * 保留的是**当前表单里已有的时分**，而不是 `Calendar.getInstance()` 的当前墙钟时间：
     * 用户先用 AI 解析出「昨晚 20:00」，再把日期从前天改成昨天，
     * 应该继续是 20:00，而不是跳成此刻的 14:30。
     *
     * @return 实际写入的时间戳；若目标日期在未来则返回 null（不允许记未来的账）
     */
    fun setDate(year: Int, month1to12: Int, day: Int): Long? {
        val current = _uiState.value.transactionTime
        val cal = Calendar.getInstance().apply { timeInMillis = current }
        val keepHour = cal.get(Calendar.HOUR_OF_DAY)
        val keepMinute = cal.get(Calendar.MINUTE)

        cal.set(year, month1to12 - 1, day, keepHour, keepMinute, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val target = cal.timeInMillis

        // 不允许未来日期，与首页「不能翻到未来月」的既有约定一致。
        // 比较用「当天 00:00」而不是毫秒值，否则今天晚些时候也会被当成未来。
        if (startOfDay(target) > DateUtil.getTodayStartTime()) return null

        _uiState.value = _uiState.value.copy(transactionTime = target)
        return target
    }

    /** 快捷项：0=今天，-1=昨天，-2=前天 */
    fun setRelativeDay(offsetDays: Int) {
        val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, offsetDays) }
        setDate(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
    }

    /** 把交易时间重置为「今天 + 当前时刻」 */
    fun resetDateToNow() {
        _uiState.value = _uiState.value.copy(transactionTime = System.currentTimeMillis())
    }

    private fun startOfDay(ms: Long): Long = Calendar.getInstance().apply {
        timeInMillis = ms
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    // ═══════════════════════════════════════════════════
    // 保存
    // ═══════════════════════════════════════════════════

    /**
     * 保存账单。
     *
     * 相对原实现的两个变化：
     *  1. 新增 `transactionTime`（原来硬编码 `System.currentTimeMillis()`）；
     *  2. 金额非法时回调 [onInvalid] 给出提示，
     *     原实现是 `if (amount != null && amount > 0)` 不满足就**静默什么都不做**，
     *     用户点了「记一笔」没反应也不知道为什么。
     *
     * `source` 仍是 `"manual"`：AI 只是帮填表单，最终由用户确认并点击保存，
     * 语义上就是手工记账。而且 `CsvExporter` 里写死了
     * `if (source == "auto") "自动" else "手动"`，新增枚举值会污染导出/导入/统计。
     */
    fun save(onSuccess: () -> Unit, onInvalid: (String) -> Unit) {
        val s = _uiState.value
        val amount = s.amountText.toDoubleOrNull()
        when {
            amount == null || amount.isNaN() || amount <= 0.0 ->
                onInvalid("请输入有效金额")

            amount > RecordUiState.MAX_AMOUNT ->
                onInvalid("金额过大，请检查")

            else -> viewModelScope.launch {
                val merchant = s.merchant.trim().ifBlank { null }
                val categoryId = s.selectedCategoryId
                transactionRepo.insert(
                    Transaction(
                        amount = amount,
                        type = s.transactionType,
                        categoryId = categoryId,
                        merchant = merchant,
                        paymentMethod = s.paymentMethod.trim().ifBlank { "其他" },
                        note = s.note.trim().ifBlank { null },
                        source = "manual",
                        transactionTime = s.transactionTime
                    )
                )

                // 用户确认保存后才把「商户 → 分类」写进 SmartCategorizer：
                // AI 可能猜错分类，解析出结果时就写会污染自动记账的规则。
                if (merchant != null && categoryId != null) {
                    SmartCategorizer.saveMerchantCategory(
                        getApplication(), merchant, categoryId
                    )
                }

                // AI 解析质量自查计数（不入库、不展示）：
                // 只在本笔账确实来自 AI 回填（Filled 态）时记一次「被接受」。
                if (_nlState.value is NlParseState.Filled) {
                    aiSettings.recordParseAccepted()
                }

                resetAfterSaved()
                onSuccess()
            }
        }
    }

    /**
     * 保存后重置：金额归零、分类清空、商户/备注清空，
     * **保留支付渠道**（原实现的连续记账体验）。
     *
     * 日期也回到「今天 + 当前时刻」—— 补记完昨天的账之后，
     * 下一笔大概率是今天的，不该让用户再改一次。
     */
    fun resetAfterSaved() {
        val keepChannel = _uiState.value.paymentMethod
        _uiState.value = RecordUiState(paymentMethod = keepChannel)
        _nlInput.value = ""
        if (_nlState.value !is NlParseState.Hidden) _nlState.value = NlParseState.Idle
    }

    // ═══════════════════════════════════════════════════
    // AI 自然语言解析
    // ═══════════════════════════════════════════════════

    /** 语音与文字走完全相同的链路：语音只是往 nlInput 里塞文本 */
    fun parseNaturalLanguage() {
        val text = _nlInput.value.trim()
        if (text.isEmpty()) return
        if (_nlState.value is NlParseState.Running) return

        val config = aiSettings.config.value
        if (!config.isConfigured) {
            _nlState.value = NlParseState.Failed("请先在设置 → AI 财务顾问里配置 AI 服务")
            return
        }

        parseJob?.cancel()
        _nlState.value = NlParseState.Running

        parseJob = viewModelScope.launch {
            val categories = _allCategories.value.map {
                CategoryRef(it.id, it.name, it.type)
            }
            val messages = AiPromptBuilder.parserMessages(
                userText = text,
                categories = categories,
                now = System.currentTimeMillis()
            )

            when (
                val result = aiClient.chat(
                    config = config,
                    messages = messages,
                    temperature = AiPromptBuilder.PARSER_TEMPERATURE,
                    maxTokens = AiPromptBuilder.PARSER_MAX_TOKENS
                )
            ) {
                is AiResult.Failure -> {
                    _nlState.value = NlParseState.Failed(result.error.userMessage)
                    aiSettings.recordParseAttempt()
                }

                is AiResult.Success -> {
                    val parsed = AiTransactionParser.parse(
                        rawModelOutput = result.text,
                        categories = categories,
                        now = System.currentTimeMillis()
                    )
                    when (parsed) {
                        AiTransactionParser.Result.NotJson -> {
                            _nlState.value = NlParseState.Failed("AI 返回内容无法解析，请换一种说法或手工填写")
                            aiSettings.recordParseAttempt()
                        }

                        AiTransactionParser.Result.NoAmount -> {
                            _nlState.value = NlParseState.Failed("没识别出金额，请检查后重试")
                            aiSettings.recordParseAttempt()
                        }

                        is AiTransactionParser.Result.Ok -> {
                            applyDraft(parsed.draft)
                            aiSettings.recordParseAttempt()
                        }
                    }
                }
            }
        }
    }

    fun cancelParse() {
        parseJob?.cancel()
        parseJob = null
        _nlState.value = NlParseState.Idle
    }

    /**
     * 把草稿回填进表单。
     *
     * **只覆盖解析出非 null 的字段**，null 字段保持表单现值 ——
     * 解析失败时用户已经手工填的内容绝不能被清掉。
     *
     * type 影响分类列表，因此必须先 setType 再 setCategoryId；
     * 这里直接一次性 copy，避免中间态触发 Compose 重组时分类列表还没切换。
     */
    private fun applyDraft(draft: TransactionDraft) {
        val cur = _uiState.value

        // 类型变化时，若草稿没给出可用分类，旧分类必须清空（它属于另一个 type）
        val typeChanged = draft.type != null && draft.type != cur.transactionType
        val nextCategoryId = when {
            draft.categoryId != null -> draft.categoryId
            typeChanged -> null                 // 跨类型且没识别出分类 → 清空
            else -> cur.selectedCategoryId      // 保持用户已选
        }

        _uiState.value = cur.copy(
            transactionType = draft.type ?: cur.transactionType,
            amountText = draft.amount?.let { AmountKeypad.fromAmount(it) } ?: cur.amountText,
            selectedCategoryId = nextCategoryId,
            merchant = draft.merchant ?: cur.merchant,
            note = draft.note ?: cur.note,
            // 渠道没识别出来时保留用户上次用的渠道（连续记账体验）
            paymentMethod = draft.channel ?: cur.paymentMethod,
            transactionTime = draft.transactionTime ?: cur.transactionTime
        )

        _nlState.value = NlParseState.Filled(
            summary = describeDraft(draft),
            missing = draft.missingFields
        )
    }

    /** 「昨天 · 餐饮 · 老乡鸡 · ¥32 · 微信」这样的回填摘要 */
    private fun describeDraft(d: TransactionDraft): String {
        val parts = mutableListOf<String>()
        d.transactionTime?.let {
            parts += when {
                DateUtil.isToday(it) -> "今天"
                startOfDay(it) == startOfDay(System.currentTimeMillis() - 86_400_000L) -> "昨天"
                else -> DateUtil.formatDate(it)
            }
            // 带上时刻，用户才能确认「昨晚」有没有被理解对
            parts += DateUtil.formatTime(it)
        }
        d.categoryName?.let { parts += it }
        d.merchant?.let { parts += it }
        d.amount?.let { parts += com.smartledger.ui.components.formatMoney(it) }
        d.channel?.let { parts += it }
        return parts.joinToString(" · ")
    }
}
