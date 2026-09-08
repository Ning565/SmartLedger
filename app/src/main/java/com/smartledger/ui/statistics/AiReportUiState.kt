package com.smartledger.ui.statistics

import com.smartledger.data.ai.model.AiError
import com.smartledger.data.db.entity.AiReportEntity

/**
 * AI 消费体检卡的状态机。
 *
 * 状态迁移：
 * ```
 * NotConfigured ──(去设置配置)──> Idle
 * Idle ──[生成报告]──> Preparing ──> Streaming ──> Success
 *                          │            │
 *                          │            ├─[停止生成]──> Success(truncated = true)
 *                          │            └─[流中断]────> Success(truncated = true) 或 Failed(带 partialText)
 *                          └─[聚合/网络失败]──> Failed
 * Success ──[重新生成]──> Preparing
 * Failed  ──[重试]──────> Preparing
 * ```
 *
 * ## 为什么中断的报告也要落库并显示为 Success
 *
 * 已经生成的半篇内容不该白扔，而且统计页 ViewModel 走默认作用域、
 * 底部 Tab 切换带 `saveState = true`，切走再切回 ViewModel 可能已被回收。
 * 只有落库才能保证「切个 Tab 回来报告还在」。
 */
sealed interface AiReportUiState {

    /** AI 未配置：显示「去设置」，不显示任何生成按钮 */
    data object NotConfigured : AiReportUiState

    /** 周期内没有任何支出，生成报告没有意义 */
    data object NoData : AiReportUiState

    /**
     * 待生成。
     *
     * @param cached       最近一次报告（可能已过期）
     * @param dataChanged  缓存生成之后又记过账，提示用户「数据已更新」
     * @param expenseCount 本周期支出笔数，用于「基于你本月 62 笔消费」这类文案
     */
    data class Idle(
        val cached: AiReportEntity?,
        val dataChanged: Boolean,
        val expenseCount: Int
    ) : AiReportUiState

    /** 本地聚合中（还没发网络请求） */
    data object Preparing : AiReportUiState

    /** 流式生成中。text 是已累积的正文 */
    data class Streaming(val text: String) : AiReportUiState

    /**
     * 生成完成（含「中断但已保留部分内容」）。
     *
     * @param truncated     流被中断，内容可能不完整
     * @param fromReasoning 正文来自推理模型的 reasoning_content 兜底
     */
    data class Success(
        val report: AiReportEntity,
        val truncated: Boolean = false,
        val fromReasoning: Boolean = false
    ) : AiReportUiState

    /**
     * 失败。
     * @param partialText 失败前已生成的内容，非空时 UI 提供「保留已生成部分」
     */
    data class Failed(
        val error: AiError,
        val partialText: String? = null
    ) : AiReportUiState
}
