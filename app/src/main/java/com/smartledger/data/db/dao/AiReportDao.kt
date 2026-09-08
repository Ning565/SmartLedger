package com.smartledger.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.smartledger.data.db.entity.AiReportEntity

@Dao
interface AiReportDao {

    @Insert
    suspend fun insert(entity: AiReportEntity): Long

    /**
     * 缓存命中判定：周期类型 + 数据指纹 + 模型 三者一致，且未过 TTL。
     *
     * 为什么要 TTL：当月的 FinancialSummary 每记一笔账就变，`dataHash` 必然变化，
     * 若只按 hash 判重则缓存等于永不命中，用户每次点开都要重烧一次 token。
     * 加上 TTL 后，「短时间内数据没实质变化」也能复用。
     *
     * @param minCreatedAt = now - ttlMillis
     */
    @Query(
        """
        SELECT * FROM ai_reports
        WHERE periodType = :periodType
          AND dataHash = :dataHash
          AND model = :model
          AND createdAt >= :minCreatedAt
        ORDER BY createdAt DESC
        LIMIT 1
        """
    )
    suspend fun findReusable(
        periodType: String,
        dataHash: String,
        model: String,
        minCreatedAt: Long
    ): AiReportEntity?

    /** Idle 态展示「上次分析」，不校验 hash（数据变了也要能看旧报告） */
    @Query(
        """
        SELECT * FROM ai_reports
        WHERE periodType = :periodType
        ORDER BY createdAt DESC
        LIMIT 1
        """
    )
    suspend fun findLatest(periodType: String): AiReportEntity?

    @Query("SELECT * FROM ai_reports WHERE id = :id")
    suspend fun findById(id: Long): AiReportEntity?

    /**
     * 保留策略：只留最近 keep 条，防止长期使用后无限膨胀。
     * 报告正文是长文本，几十条就可能占掉几百 KB。
     */
    @Query(
        """
        DELETE FROM ai_reports
        WHERE id NOT IN (
            SELECT id FROM ai_reports ORDER BY createdAt DESC LIMIT :keep
        )
        """
    )
    suspend fun pruneOld(keep: Int)

    @Query("SELECT COUNT(*) FROM ai_reports")
    suspend fun count(): Int

    /** 「清除配置与本地 AI 报告」入口 */
    @Query("DELETE FROM ai_reports")
    suspend fun clearAll()
}
