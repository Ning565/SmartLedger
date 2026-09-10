package com.smartledger.data.db.dao

import androidx.room.*
import com.smartledger.data.analytics.model.TxPoint
import com.smartledger.data.db.entity.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {

    @Insert
    suspend fun insert(transaction: Transaction): Long

    @Update
    suspend fun update(transaction: Transaction)

    @Delete
    suspend fun delete(transaction: Transaction)

    @Query("SELECT * FROM transactions ORDER BY transactionTime DESC")
    fun getAll(): Flow<List<Transaction>>

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getById(id: Long): Transaction?

    @Query("SELECT * FROM transactions WHERE notificationKey = :key LIMIT 1")
    suspend fun getByNotificationKey(key: String): Transaction?

    @Query("SELECT * FROM transactions WHERE transactionTime BETWEEN :startTime AND :endTime ORDER BY transactionTime DESC")
    fun getByTimeRange(startTime: Long, endTime: Long): Flow<List<Transaction>>

    @Query("SELECT * FROM transactions WHERE transactionTime BETWEEN :startTime AND :endTime ORDER BY transactionTime DESC")
    suspend fun getByTimeRangeOnce(startTime: Long, endTime: Long): List<Transaction>

    /**
     * 按 notificationKey 精确查重（C9）。无障碍链路的 key 形如
     * `a11y:wechat:<hash>`，指纹不含时间 —— 同一历史支付页永远同 key，
     * 隔天重开页面时 DedupHelper 的时间窗救不了，靠这里终身去重。
     */
    @Query("SELECT * FROM transactions WHERE notificationKey = :key LIMIT 1")
    suspend fun getByNotificationKeyOnce(key: String): Transaction?

    @Query("SELECT COALESCE(SUM(amount), 0) FROM transactions WHERE type = 'expense' AND transactionTime BETWEEN :startTime AND :endTime")
    fun getExpenseSum(startTime: Long, endTime: Long): Flow<Double>

    @Query("SELECT COALESCE(SUM(amount), 0) FROM transactions WHERE type = 'income' AND transactionTime BETWEEN :startTime AND :endTime")
    fun getIncomeSum(startTime: Long, endTime: Long): Flow<Double>

    /** 全部支出合计（不限月份） */
    @Query("SELECT COALESCE(SUM(amount), 0) FROM transactions WHERE type = 'expense'")
    fun getTotalExpenseSum(): Flow<Double>

    /** 全部收入合计（不限月份） */
    @Query("SELECT COALESCE(SUM(amount), 0) FROM transactions WHERE type = 'income'")
    fun getTotalIncomeSum(): Flow<Double>

    @Query("SELECT COALESCE(SUM(amount), 0) FROM transactions WHERE type = 'expense' AND categoryId = :categoryId AND transactionTime BETWEEN :startTime AND :endTime")
    fun getExpenseByCategory(categoryId: Long, startTime: Long, endTime: Long): Flow<Double>

    @Query("SELECT categoryId, SUM(amount) as total FROM transactions WHERE type = 'expense' AND transactionTime BETWEEN :startTime AND :endTime GROUP BY categoryId ORDER BY total DESC")
    fun getExpenseGroupByCategory(startTime: Long, endTime: Long): Flow<List<CategoryTotal>>

    @Query("SELECT * FROM transactions WHERE merchant LIKE '%' || :keyword || '%' OR note LIKE '%' || :keyword || '%' ORDER BY transactionTime DESC")
    fun search(keyword: String): Flow<List<Transaction>>

    @Query("SELECT COUNT(*) FROM transactions")
    suspend fun getCount(): Int

    @Query("SELECT MIN(transactionTime) FROM transactions")
    suspend fun getFirstTransactionTime(): Long?

    /**
     * 最后一次写入时间。
     *
     * 用来便宜地判断「AI 报告缓存生成之后有没有新增/修改过账单」。
     * 比起每次进页面都重新聚合一遍 FinancialSummary 再算 dataHash，
     * 这一句 `SELECT MAX(createdAt)` 几乎零成本，而效果一样：
     * 有变化就提示用户「数据已更新」，由用户自己决定要不要重新生成。
     */
    @Query("SELECT MAX(createdAt) FROM transactions")
    suspend fun getLatestCreatedAt(): Long?

    // ═══════════════════════════════════════════════════
    // AI 消费体检用的聚合查询
    //
    // 字段口径务必注意（这是原方案文档写错的地方）：
    //   时间列是 `transactionTime`（不是 timestamp）
    //   分类列是 `categoryId`（不是 category，且可空）
    //   type 存的是**小写** `'expense'` / `'income'`（不是 'EXPENSE'）
    // ═══════════════════════════════════════════════════

    /**
     * 指定周期总支出（一次性）。
     *
     * 与已有的 [getExpenseSum] 区别：那个返回 Flow 供 UI 订阅，
     * 这个是一次性取值，供聚合链路里「上一周期同期支出」这种
     * 不需要响应式的场景使用。
     */
    @Query(
        """
        SELECT COALESCE(SUM(amount), 0) FROM transactions
        WHERE type = 'expense' AND transactionTime BETWEEN :startTime AND :endTime
        """
    )
    suspend fun getExpenseSumOnce(startTime: Long, endTime: Long): Double

    /** 指定周期总收入（一次性） */
    @Query(
        """
        SELECT COALESCE(SUM(amount), 0) FROM transactions
        WHERE type = 'income' AND transactionTime BETWEEN :startTime AND :endTime
        """
    )
    suspend fun getIncomeSumOnce(startTime: Long, endTime: Long): Double

    /**
     * 周期内支出笔数（一次性）。
     *
     * 专供 AI 体检 Idle 态判断「本周期有多少笔支出」：
     * 旧实现为了数一个笔数把整个周期的投影（近 3 月可达千行）
     * 全拉进内存再 `count {}`，浪费。直接用 SQL COUNT 更轻。
     * 条件与 SummaryAggregator 的支出笔数口径一致：type='expense' 且 amount>0。
     */
    @Query(
        """
        SELECT COUNT(*) FROM transactions
        WHERE type = 'expense' AND amount > 0 AND transactionTime BETWEEN :startTime AND :endTime
        """
    )
    suspend fun getExpenseCountInRange(startTime: Long, endTime: Long): Int

    /**
     * 聚合用的轻量投影。
     *
     * 只取 5 列：金额、类型、分类 id、商户、时间。
     * **刻意不取 note / notificationKey / paymentMethod** ——
     * 它们对统计无用，且 note 是禁止上传 AI 的用户备注原文，
     * 从取数阶段就不让它进入内存，比事后过滤可靠得多。
     *
     * 时段 / 星期分桶放在 Kotlin 侧做（见 SummaryAggregator）：
     * SQLite 的 strftime 默认按 UTC 计算小时与星期，
     * 北京时间 23:30 的消费会被算成「下午」，夜间消费统计直接失真。
     *
     * 一个月的典型数据量是几十到几百行，近 3 个月千行量级，
     * 单趟遍历的成本可以忽略。
     */
    @Query(
        """
        SELECT amount, type, categoryId, merchant, transactionTime
        FROM transactions
        WHERE transactionTime BETWEEN :startTime AND :endTime
        """
    )
    suspend fun getPointsInRange(startTime: Long, endTime: Long): List<TxPoint>
}

data class CategoryTotal(
    val categoryId: Long?,
    val total: Double
)
