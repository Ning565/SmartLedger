package com.smartledger.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * AI 消费体检报告缓存。
 *
 * 存在这张表的三个理由：
 *  1. **省额度**：同一周期、同一份统计数据在 TTL 内不重复请求；
 *  2. **抗页面重建**：统计页 ViewModel 走默认作用域，底部 Tab 切换带
 *     `saveState = true`，切走再切回 ViewModel 可能已被回收。报告若只存内存，
 *     用户切个 Tab 回来就会看到「刚才生成的报告没了」；
 *  3. **可回溯**：用户能翻看历史月份的报告。
 */
@Entity(
    tableName = "ai_reports",
    indices = [Index(value = ["periodStart", "periodEnd"])]
)
data class AiReportEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** 分析周期类型，取 SummaryPeriod.name（THIS_MONTH / LAST_3_MONTHS） */
    val periodType: String,

    val periodStart: Long,
    val periodEnd: Long,

    /** 展示用标签，如「2026 年 9 月」 */
    val periodLabel: String,

    /**
     * FinancialSummary 规范化 JSON 的 SHA-256。
     * 数据没变则 hash 不变，用来判断能否复用缓存。
     */
    val dataHash: String,

    /** 生成该报告所用的模型。换模型后旧报告不应复用。 */
    val model: String,

    /** 报告正文（Markdown） */
    val markdown: String,

    /**
     * 从正文里提取的「建议可用总额度」。
     * 只作展示与「采纳」入口的预填值，**永不自动写入 budgets 表**。
     */
    val suggestedBudget: Double?,

    /**
     * 正文是否由推理模型的 reasoning_content 兜底而来。
     * 为 true 时 UI 需提示「该模型是推理模型，建议改用 deepseek-chat」。
     */
    val fromReasoningFallback: Boolean = false,

    /** 流被中断（用户停止 / 网络断）导致内容可能不完整 */
    val truncated: Boolean = false,

    val createdAt: Long
)
