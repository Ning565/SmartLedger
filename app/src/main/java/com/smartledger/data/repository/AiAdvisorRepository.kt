package com.smartledger.data.repository

import com.smartledger.data.ai.AiClient
import com.smartledger.data.ai.AiStreamEvent
import com.smartledger.data.ai.SuggestedBudgetExtractor
import com.smartledger.data.ai.model.AiConfig
import com.smartledger.data.analytics.FinancialSummaryBuilder
import com.smartledger.data.analytics.SummaryFormatter
import com.smartledger.data.analytics.model.FinancialSummary
import com.smartledger.data.analytics.model.SummaryPeriod
import com.smartledger.data.db.dao.AiReportDao
import com.smartledger.data.db.entity.AiReportEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * AI 消费体检的编排层：聚合 → 指纹 → 缓存 → Prompt → 流式 → 落库。
 *
 * 状态机与 UI 交互留在 StatisticsViewModel，这里只提供**可复用的动作**，
 * 因此它不持有任何 UI 状态，也不会因为页面重建而丢失逻辑。
 */
class AiAdvisorRepository(
    private val summaryBuilder: FinancialSummaryBuilder,
    private val aiReportDao: AiReportDao,
    private val aiClient: AiClient,
    private val settings: AiSettingsRepository
) {

    companion object {
        /** 报告保留条数上限。正文是长文本，几十条就能占掉几百 KB */
        const val MAX_KEEP_REPORTS = 20
    }

    /** 本地聚合（不联网，未配置 AI 时也能用） */
    suspend fun buildSummary(
        period: SummaryPeriod,
        now: Long = System.currentTimeMillis()
    ): FinancialSummary = summaryBuilder.build(period, now)

    /**
     * 数据指纹：canonicalJson 的 SHA-256。
     *
     * 用 [SummaryFormatter.canonicalJson] 而不是 data class 的 toString()：
     * toString 里 Double 的格式（4280.0 vs 4280.00）和 List 顺序都不受控，
     * 会让同样的数据算出不同 hash，缓存直接失效。
     */
    suspend fun dataHash(summary: FinancialSummary): String = withContext(Dispatchers.Default) {
        sha256Hex(SummaryFormatter.canonicalJson(summary))
    }

    /**
     * 缓存命中判定。
     *
     * 周期 + 数据指纹 + 模型三者一致，且未过 TTL 才复用。
     * TTL 是必需的：当月 summary 每记一笔就变、hash 必然变，
     * 只按 hash 判重的话缓存等于永不命中，用户每次点开都要重烧一次 token。
     */
    suspend fun findReusable(
        summary: FinancialSummary,
        hash: String,
        model: String
    ): AiReportEntity? {
        val minCreatedAt = System.currentTimeMillis() - settings.reportTtlMillis()
        return aiReportDao.findReusable(summary.period.name, hash, model, minCreatedAt)
    }

    /** Idle 态展示「上次分析」，不校验 hash（数据变了也要能翻旧报告） */
    suspend fun findLatest(period: SummaryPeriod): AiReportEntity? =
        aiReportDao.findLatest(period.name)

    /** 发起流式请求。调用方负责 collect 与取消。 */
    fun stream(summary: FinancialSummary, config: AiConfig): Flow<AiStreamEvent> =
        aiClient.streamChat(
            config = config,
            messages = AiPromptBuilder.advisorMessages(summary),
            temperature = AiPromptBuilder.ADVISOR_TEMPERATURE,
            maxTokens = AiPromptBuilder.ADVISOR_MAX_TOKENS
        )

    /**
     * 落库并做保留策略清理。
     *
     * @param truncated 流被中断（用户停止 / 网络断），内容可能不完整。
     *        **中断的报告也要落库** —— 已经生成的半篇不该白扔，
     *        而且页面重建后能从库里恢复，不至于「切个 Tab 报告就没了」。
     */
    suspend fun persist(
        summary: FinancialSummary,
        hash: String,
        config: AiConfig,
        markdown: String,
        truncated: Boolean,
        fromReasoningFallback: Boolean
    ): AiReportEntity {
        val entity = AiReportEntity(
            periodType = summary.period.name,
            periodStart = summary.periodStart,
            periodEnd = summary.periodEnd,
            periodLabel = summary.periodLabel,
            dataHash = hash,
            model = config.model,
            markdown = markdown,
            suggestedBudget = SuggestedBudgetExtractor.extract(markdown),
            fromReasoningFallback = fromReasoningFallback,
            truncated = truncated,
            createdAt = System.currentTimeMillis()
        )
        val id = aiReportDao.insert(entity)
        aiReportDao.pruneOld(MAX_KEEP_REPORTS)
        return entity.copy(id = id)
    }

    suspend fun clearReports() = aiReportDao.clearAll()

    suspend fun reportCount(): Int = aiReportDao.count()

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        digest.forEach { b ->
            val hex = Integer.toHexString(b.toInt() and 0xFF)
            if (hex.length == 1) sb.append('0')
            sb.append(hex)
        }
        return sb.toString()
    }
}
