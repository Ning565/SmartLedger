package com.smartledger.data.repository

import com.smartledger.data.db.dao.TransactionDao
import com.smartledger.data.db.dao.CategoryTotal
import com.smartledger.data.db.entity.Transaction
import kotlinx.coroutines.flow.Flow

class TransactionRepository(private val dao: TransactionDao) {

    val allTransactions: Flow<List<Transaction>> = dao.getAll()

    suspend fun insert(transaction: Transaction): Long = dao.insert(transaction)

    suspend fun update(transaction: Transaction) = dao.update(transaction)

    suspend fun delete(transaction: Transaction) = dao.delete(transaction)

    suspend fun getById(id: Long): Transaction? = dao.getById(id)

    suspend fun getByNotificationKey(key: String): Transaction? = dao.getByNotificationKey(key)

    fun getByTimeRange(startTime: Long, endTime: Long): Flow<List<Transaction>> =
        dao.getByTimeRange(startTime, endTime)

    fun getExpenseSum(startTime: Long, endTime: Long): Flow<Double> =
        dao.getExpenseSum(startTime, endTime)

    fun getIncomeSum(startTime: Long, endTime: Long): Flow<Double> =
        dao.getIncomeSum(startTime, endTime)

    fun getTotalExpenseSum(): Flow<Double> = dao.getTotalExpenseSum()

    fun getTotalIncomeSum(): Flow<Double> = dao.getTotalIncomeSum()

    fun getExpenseByCategory(categoryId: Long, startTime: Long, endTime: Long): Flow<Double> =
        dao.getExpenseByCategory(categoryId, startTime, endTime)

    fun getExpenseGroupByCategory(startTime: Long, endTime: Long): Flow<List<CategoryTotal>> =
        dao.getExpenseGroupByCategory(startTime, endTime)

    fun search(keyword: String): Flow<List<Transaction>> = dao.search(keyword)

    /** 聚合用轻量投影（AI 消费体检） */
    suspend fun getPointsInRange(startTime: Long, endTime: Long) =
        dao.getPointsInRange(startTime, endTime)

    suspend fun getExpenseSumOnce(startTime: Long, endTime: Long): Double =
        dao.getExpenseSumOnce(startTime, endTime)

    suspend fun getIncomeSumOnce(startTime: Long, endTime: Long): Double =
        dao.getIncomeSumOnce(startTime, endTime)

    /** 周期内支出笔数（AI 体检 Idle 态用，避免为数笔数拉全量投影） */
    suspend fun getExpenseCountInRange(startTime: Long, endTime: Long): Int =
        dao.getExpenseCountInRange(startTime, endTime)

    /** 最后一次写入时间，用于判断 AI 报告缓存是否已陈旧 */
    suspend fun getLatestCreatedAt(): Long? = dao.getLatestCreatedAt()
}
