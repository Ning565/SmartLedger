package com.smartledger.ui.statistics

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.smartledger.SmartLedgerApp
import com.smartledger.data.ai.AiStreamEvent
import com.smartledger.data.ai.model.AiError
import com.smartledger.data.analytics.model.FinancialSummary
import com.smartledger.data.analytics.model.SummaryPeriod
import com.smartledger.data.db.dao.CategoryTotal
import com.smartledger.data.db.entity.AiReportEntity
import com.smartledger.data.db.entity.BudgetSource
import com.smartledger.data.db.entity.Category
import com.smartledger.data.db.entity.Transaction
import com.smartledger.util.DateUtil
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

/**
 * 统计页 ViewModel。
 *
 * 承担两块互不干扰的职责：
 *  1. 原有的周期统计（日/周/月/年 + 分类排行 + 环形图），响应式 Flow；
 *  2. AI 消费体检（本月 / 近 3 月），**命令式状态机**。
 *
 * AI 这块刻意不跟随统计页的日/周/月/年 Tab：
 * 「日」维度的消费体检没有分析价值（一天的数据得不出消费画像），
 * 而「年」又会把 Prompt 撑得很大。因此 AI 卡自带一个二选一周期切换，
 * 在任何 Tab 下都显示，避免用户在「日」Tab 找不到入口。
 */
class StatisticsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as SmartLedgerApp
    private val transactionRepo = app.transactionRepository
    private val categoryRepo = app.categoryRepository
    private val budgetRepo = app.budgetRepository
    private val advisor = app.aiAdvisorRepository
    private val aiSettings = app.aiSettingsRepository

    // ═══════════════════════════════════════════════════
    // 原有周期统计（行为保持不变）
    // ═══════════════════════════════════════════════════

    private val _selectedPeriod = MutableStateFlow("month")
    val selectedPeriod: Flow<String> = _selectedPeriod

    private val _timeRange = MutableStateFlow(getTimeRange("month"))

    val expenseByCategory: Flow<List<CategoryTotal>> = _timeRange.flatMapLatest { (start, end) ->
        transactionRepo.getExpenseGroupByCategory(start, end)
    }

    val transactions: Flow<List<Transaction>> = _timeRange.flatMapLatest { (start, end) ->
        transactionRepo.getByTimeRange(start, end)
    }

    val periodExpense: Flow<Double> = _timeRange.flatMapLatest { (start, end) ->
        transactionRepo.getExpenseSum(start, end)
    }

    val periodIncome: Flow<Double> = _timeRange.flatMapLatest { (start, end) ->
        transactionRepo.getIncomeSum(start, end)
    }

    val categories: Flow<List<Category>> = categoryRepo.getAll()

    fun setPeriod(period: String) {
        _selectedPeriod.value = period
        _timeRange.value = getTimeRange(period)
    }

    /** 回到前台时刷新「日/周」等相对今天的区间 */
    fun refreshTimeRange() {
        _timeRange.value = getTimeRange(_selectedPeriod.value)
    }

    private fun getTimeRange(period: String): Pair<Long, Long> {
        return when (period) {
            "day" -> Pair(DateUtil.getTodayStartTime(), DateUtil.getTodayEndTime())
            "week" -> Pair(DateUtil.getWeekStartTime(), DateUtil.getWeekEndTime())
            "year" -> Pair(DateUtil.getYearStartTime(), DateUtil.getYearEndTime())
            else -> Pair(
                DateUtil.getMonthStartTime(DateUtil.getCurrentYearMonth()),
                DateUtil.getMonthEndTime(DateUtil.getCurrentYearMonth())
            )
        }
    }

    // ═══════════════════════════════════════════════════
    // AI 消费体检
    // ═══════════════════════════════════════════════════

    private val _aiPeriod = MutableStateFlow(SummaryPeriod.THIS_MONTH)
    val aiPeriod: StateFlow<SummaryPeriod> = _aiPeriod.asStateFlow()

    private val _aiState = MutableStateFlow<AiReportUiState>(AiReportUiState.NotConfigured)
    val aiState: StateFlow<AiReportUiState> = _aiState.asStateFlow()

    /** 报告全文是否展开（默认折叠前几行，避免把统计页整体挤下去） */
    private val _reportExpanded = MutableStateFlow(false)
    val reportExpanded: StateFlow<Boolean> = _reportExpanded.asStateFlow()

    /** 采纳 AI 建议额度的弹窗状态 */
    private val _adoptPrompt = MutableStateFlow<Double?>(null)
    val adoptPrompt: StateFlow<Double?> = _adoptPrompt.asStateFlow()

    private var generateJob: Job? = null

    /** 流式累积用的 StringBuilder，避免每个 delta 都 new 一个大字符串 */
    private val streamBuffer = StringBuilder()

    /**
     * 本次生成所用的 Summary 与指纹。
     *
     * 必须缓存下来给 [stopGeneration] 用：若停止时重新聚合一遍，
     * 期间新记的账会让 hash 变化，落库的报告就会被标记成
     * 「匹配新数据」，下次打开时当作新鲜缓存直接复用 ——
     * 用户看到的却是停止前生成的旧内容，这是一个很难发现的隐形 bug。
     */
    private var activeSummary: FinancialSummary? = null
    private var activeHash: String? = null

    /** 上一次刷新 UI 的时间，用于 80ms 节流 */
    private var lastEmitAt = 0L

    init {
        // AI 配置变化时重新评估入口状态（在设置页保存/清除后统计页要立即跟着变）
        viewModelScope.launch {
            aiSettings.config.collect { cfg ->
                if (!cfg.isConfigured) {
                    generateJob?.cancel()
                    _aiState.value = AiReportUiState.NotConfigured
                } else if (_aiState.value is AiReportUiState.NotConfigured) {
                    loadIdleState()
                }
            }
        }
        viewModelScope.launch {
            _aiPeriod.collect { loadIdleState() }
        }
    }

    fun setAiPeriod(period: SummaryPeriod) {
        if (_aiPeriod.value == period) return
        // 切周期时不打断正在进行的生成，但要把结果丢弃，
        // 否则会把「本月」的报告挂到「近 3 月」的卡片上
        generateJob?.cancel()
        _aiPeriod.value = period
        _reportExpanded.value = false
    }

    /**
     * 载入 Idle 态：查缓存 + 判断数据是否已更新。
     *
     * 「数据是否已更新」用 `MAX(createdAt) > report.createdAt` 判断，
     * 一次极轻的查询就够；不需要重新聚合整份 Summary 再算 hash。
     */
    private fun loadIdleState() {
        viewModelScope.launch {
            if (!aiSettings.config.value.isConfigured) {
                _aiState.value = AiReportUiState.NotConfigured
                return@launch
            }
            val period = _aiPeriod.value
            val cached = advisor.findLatest(period)
            val latestTx = transactionRepo.getLatestCreatedAt() ?: 0L
            val dataChanged = cached != null && latestTx > cached.createdAt

            // 支出笔数：用一句 COUNT 查，不再为了数笔数把整个周期的投影全拉进内存
            val range = com.smartledger.data.analytics.SummaryPeriods.current(
                period, System.currentTimeMillis(), java.util.TimeZone.getDefault()
            )
            val expenseCount = try {
                transactionRepo.getExpenseCountInRange(range.start, range.end)
            } catch (_: Exception) {
                0
            }

            _aiState.value = if (expenseCount == 0 && cached == null) {
                AiReportUiState.NoData
            } else {
                AiReportUiState.Idle(cached, dataChanged, expenseCount)
            }
        }
    }

    /**
     * 生成报告。
     *
     * @param force true = 绕过缓存（「重新生成」按钮）；
     *              false = 先查缓存，命中就不发网络请求（省额度）
     */
    fun generateReport(force: Boolean = false) {
        val config = aiSettings.config.value
        if (!config.isConfigured) {
            _aiState.value = AiReportUiState.NotConfigured
            return
        }
        if (_aiState.value is AiReportUiState.Preparing ||
            _aiState.value is AiReportUiState.Streaming
        ) {
            return   // 防重复点击，避免并发两条流烧双份额度
        }

        generateJob?.cancel()
        activeSummary = null
        activeHash = null
        _reportExpanded.value = true
        _aiState.value = AiReportUiState.Preparing

        generateJob = viewModelScope.launch {
            val period = _aiPeriod.value

            // ── 1. 本地聚合 ──
            val summary: FinancialSummary = try {
                advisor.buildSummary(period)
            } catch (e: Exception) {
                _aiState.value = AiReportUiState.Failed(
                    AiError.Unknown(e.javaClass.simpleName)
                )
                return@launch
            }

            if (summary.transactionCount == 0 || summary.categoryStats.isEmpty()) {
                _aiState.value = AiReportUiState.NoData
                return@launch
            }

            val hash = advisor.dataHash(summary)
            activeSummary = summary
            activeHash = hash

            // ── 2. 缓存命中判定 ──
            if (!force) {
                val reusable = advisor.findReusable(summary, hash, config.model)
                if (reusable != null) {
                    _aiState.value = AiReportUiState.Success(
                        report = reusable,
                        truncated = reusable.truncated,
                        fromReasoning = reusable.fromReasoningFallback
                    )
                    return@launch
                }
            }

            // ── 3. 流式生成 ──
            streamBuffer.setLength(0)
            lastEmitAt = 0L
            var fromReasoning = false

            advisor.stream(summary, config).collect { event ->
                when (event) {
                    is AiStreamEvent.Delta -> {
                        streamBuffer.append(event.text)
                        emitThrottled()
                    }

                    is AiStreamEvent.Completed -> {
                        fromReasoning = event.fromReasoningFallback
                        finishStream(summary, hash, config, truncated = false, fromReasoning)
                    }

                    is AiStreamEvent.Failed -> {
                        // 已经产出过内容：落库保留并标为不完整，比整篇丢弃好。
                        // finishStream 内部会处理「文本为空」的情况（转为 Failed），
                        // 所以这里不要再自己判一次、更不要对 _aiState 做强制转型。
                        if (event.partialText.isNotBlank()) {
                            finishStream(
                                summary, hash, config,
                                truncated = true,
                                fromReasoning = fromReasoning
                            )
                        } else {
                            _aiState.value = AiReportUiState.Failed(event.error, null)
                        }
                    }
                }
            }
        }
    }

    /**
     * 80ms 节流刷新。
     *
     * 不做节流的话，每个 token 都会触发一次「整篇 Markdown 重新解析 + 重组」，
     * 长报告在低端机上会明显掉帧。节流后重算频率约 12 次/秒，观感仍是流式。
     */
    private fun emitThrottled() {
        val now = System.currentTimeMillis()
        if (now - lastEmitAt < STREAM_THROTTLE_MS) return
        lastEmitAt = now
        _aiState.value = AiReportUiState.Streaming(streamBuffer.toString())
    }

    private suspend fun finishStream(
        summary: FinancialSummary,
        hash: String,
        config: com.smartledger.data.ai.model.AiConfig,
        truncated: Boolean,
        fromReasoning: Boolean
    ) {
        // 收尾时强制刷一次，保证最后一个 token 一定上屏
        _aiState.value = AiReportUiState.Streaming(streamBuffer.toString())

        val text = streamBuffer.toString()
        if (text.isBlank()) {
            _aiState.value = AiReportUiState.Failed(AiError.EmptyReasoningOutput, null)
            return
        }
        val entity = try {
            advisor.persist(
                summary = summary,
                hash = hash,
                config = config,
                markdown = text,
                truncated = truncated,
                fromReasoningFallback = fromReasoning
            )
        } catch (e: Exception) {
            // 落库失败不该让用户白等一场：正文照样展示，只是不缓存
            _aiState.value = AiReportUiState.Success(
                report = AiReportEntity(
                    periodType = summary.period.name,
                    periodStart = summary.periodStart,
                    periodEnd = summary.periodEnd,
                    periodLabel = summary.periodLabel,
                    dataHash = hash,
                    model = config.model,
                    markdown = text,
                    suggestedBudget = com.smartledger.data.ai.SuggestedBudgetExtractor.extract(text),
                    fromReasoningFallback = fromReasoning,
                    truncated = truncated,
                    createdAt = System.currentTimeMillis()
                ),
                truncated = truncated,
                fromReasoning = fromReasoning
            )
            return
        }
        _aiState.value = AiReportUiState.Success(
            report = entity,
            truncated = truncated,
            fromReasoning = fromReasoning
        )
    }

    /** 用户点「停止生成」：保留已生成部分并落库 */
    fun stopGeneration() {
        val job = generateJob
        val text = streamBuffer.toString()
        val summary = activeSummary
        val hash = activeHash

        // 先取消协程（会断开底层连接），再用已累积的文本落库
        job?.cancel()
        generateJob = null

        if (text.isBlank() || summary == null || hash == null) {
            activeSummary = null
            activeHash = null
            loadIdleState()
            return
        }
        viewModelScope.launch {
            // 用的是生成时那份 summary，而不是重新聚合 —— 理由见 activeSummary 注释
            finishStream(
                summary = summary,
                hash = hash,
                config = aiSettings.config.value,
                truncated = true,
                fromReasoning = false
            )
            activeSummary = null
            activeHash = null
        }
    }

    /** 从报告视图退回 Idle（保留缓存，可再点开） */
    fun dismissReport() {
        _reportExpanded.value = false
        loadIdleState()
    }

    /**
     * 直接展示已缓存的报告。
     *
     * **不能走 generateReport(force = false)**：那条路径先重新聚合再算 hash，
     * 一旦 TTL 过期或数据变过就会真的重发请求。用户点的是「查看报告」，
     * 语义上就是看旧报告，不该在背后静默烧一次额度。
     */
    fun viewCachedReport(report: AiReportEntity) {
        _reportExpanded.value = false   // 旧报告默认折叠，避免一下撑满屏
        _aiState.value = AiReportUiState.Success(
            report = report,
            truncated = report.truncated,
            fromReasoning = report.fromReasoningFallback
        )
    }

    fun toggleReportExpanded() {
        _reportExpanded.value = !_reportExpanded.value
    }

    /** 重试：绕过缓存重新生成 */
    fun retry() = generateReport(force = true)

    // ═══ 采纳 AI 建议额度 ═══

    fun openAdoptPrompt() {
        val suggested = (_aiState.value as? AiReportUiState.Success)
            ?.report?.suggestedBudget
        if (suggested != null && suggested > 0) _adoptPrompt.value = suggested
    }

    fun dismissAdoptPrompt() {
        _adoptPrompt.value = null
    }

    /**
     * 采纳为**下月**可用总额度。
     *
     * 必须经用户二次确认，且确认前可改金额；
     * 用户改过金额就记 MANUAL —— 那已经是用户自己的决定了，
     * `AI_SUGGESTED` 只标记「原样采纳」，这样 source 字段将来才有分析价值。
     *
     * **AI 永远不能自动改预算**：这个方法只能由弹窗的确认按钮触发。
     */
    fun adoptSuggestedBudget(amount: Double, suggested: Double, onDone: (String) -> Unit) {
        val safe = amount.takeIf { !it.isNaN() && !it.isInfinite() && it > 0 && it <= 1_000_000 }
        if (safe == null) {
            onDone("金额无效，请输入 0 ～ 1,000,000 之间的数字")
            return
        }
        viewModelScope.launch {
            val nextMonth = DateUtil.shiftYearMonth(DateUtil.getCurrentYearMonth(), 1)
            val source = if (kotlin.math.abs(safe - suggested) < 0.005) {
                BudgetSource.AI_SUGGESTED
            } else {
                BudgetSource.MANUAL
            }
            budgetRepo.setExpenseLimit(nextMonth, safe, source)
            _adoptPrompt.value = null
            onDone("已设置 $nextMonth 可用额度 ¥${"%,.2f".format(safe)}")
        }
    }

    companion object {
        /** 流式 UI 刷新节流间隔 */
        private const val STREAM_THROTTLE_MS = 80L
    }
}
