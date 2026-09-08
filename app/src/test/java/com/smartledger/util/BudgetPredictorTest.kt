package com.smartledger.util

import com.smartledger.util.BudgetPredictor.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 动态预算与超支预测测试。
 *
 * 覆盖方案文档 6.3 节列出的全部边界用例。这块逻辑的错法很隐蔽：
 * 不会崩，只会给出「看起来合理但其实错了」的数字，
 * 例如把月初交房租算成「预计月末支出 9 万」。
 */
class BudgetPredictorTest {

    // ═══ 未设置预算 ═══

    @Test
    fun `未设置预算 - NO_BUDGET 且不给今日建议额度`() {
        val r = BudgetPredictor.predict(null, 1000.0, 7, 30)
        assertEquals(Status.NO_BUDGET, r.status)
        assertNull("未设预算时不该显示今日建议可用", r.dailyAvailable)
        assertNull(r.usedPercent)
        assertNull(r.projectedBudgetDay)
        assertFalse(r.showProjection)
        // 但已支出与日均仍应可用，首页要显示
        assertEquals(1000.0, r.currentExpense, 1e-9)
        assertEquals(142.86, r.averageDailyExpense, 1e-9)
        assertEquals(24, r.remainingDays)
    }

    @Test
    fun `预算为 0 视为未设置`() {
        val r = BudgetPredictor.predict(0.0, 100.0, 7, 30)
        assertEquals(Status.NO_BUDGET, r.status)
        assertNull(r.dailyAvailable)
    }

    @Test
    fun `预算为负视为未设置`() {
        val r = BudgetPredictor.predict(-500.0, 100.0, 7, 30)
        assertEquals(Status.NO_BUDGET, r.status)
    }

    // ═══ 当月无支出 ═══

    @Test
    fun `当月无支出 - SAFE 且无法预测超支日`() {
        val r = BudgetPredictor.predict(5000.0, 0.0, 7, 30)
        assertEquals(Status.SAFE, r.status)
        assertEquals(0.0, r.averageDailyExpense, 1e-9)
        assertNull("没花钱就不该编一个超支日期", r.projectedBudgetDay)
        // 剩余 5000 / 含今天的 24 天
        assertEquals(5000.0, r.remainingBudget, 1e-9)
        assertEquals(24, r.remainingDays)
        assertEquals(208.33, r.dailyAvailable!!, 1e-9)
        assertEquals(0.0, r.usedPercent!!, 1e-9)
    }

    // ═══ 月初护栏 ═══

    @Test
    fun `月初 1 号大额固定支出 - 不显示荒谬预测`() {
        val r = BudgetPredictor.predict(5000.0, 3000.0, 1, 30)
        assertEquals(Status.WARNING, r.status)
        // 内部确实算出了 90000，但 showProjection=false，UI 不会展示
        assertEquals(90000.0, r.predictedMonthExpense, 1e-9)
        assertFalse("月初 1~4 号必须挡住预测展示", r.showProjection)
        assertEquals(2, r.projectedBudgetDay)
        assertEquals(30, r.remainingDays)
        assertEquals(66.67, r.dailyAvailable!!, 1e-9)   // (5000-3000)/30
    }

    @Test
    fun `月初护栏边界 - 第 4 天挡 第 5 天放`() {
        assertFalse(BudgetPredictor.predict(5000.0, 1000.0, 4, 30).showProjection)
        assertTrue(BudgetPredictor.predict(5000.0, 1000.0, 5, 30).showProjection)
        assertEquals(5, BudgetPredictor.MIN_DAYS_FOR_PROJECTION)
    }

    // ═══ 月中 ═══

    @Test
    fun `月中节奏正常 - SAFE`() {
        val r = BudgetPredictor.predict(5000.0, 2400.0, 15, 30)
        assertEquals(Status.SAFE, r.status)
        assertEquals(160.0, r.averageDailyExpense, 1e-9)
        assertEquals(4800.0, r.predictedMonthExpense, 1e-9)
        assertNull("预测不超支时不该显示超支日期", r.projectedBudgetDay)
        assertEquals(2600.0, r.remainingBudget, 1e-9)
        assertEquals(16, r.remainingDays)          // 30 - 15 + 1，今天计入
        assertEquals(162.5, r.dailyAvailable!!, 1e-9)
        assertEquals(48.0, r.usedPercent!!, 1e-9)
        assertTrue(r.showProjection)
    }

    @Test
    fun `月中节奏偏快 - WARNING 并给出超支日期`() {
        val r = BudgetPredictor.predict(5000.0, 2800.0, 15, 30)
        assertEquals(Status.WARNING, r.status)
        assertEquals(186.67, r.averageDailyExpense, 1e-9)
        assertEquals(5600.0, r.predictedMonthExpense, 1e-9)
        assertEquals(27, r.projectedBudgetDay)      // ceil(5000/186.67)
        assertEquals(2200.0, r.remainingBudget, 1e-9)
        assertEquals(137.5, r.dailyAvailable!!, 1e-9)
        assertEquals(56.0, r.usedPercent!!, 1e-9)
    }

    // ═══ 临界与超支 ═══

    @Test
    fun `刚好用满预算 - SAFE 且剩余为 0`() {
        val r = BudgetPredictor.predict(5000.0, 5000.0, 30, 30)
        assertEquals("花光不等于超支", Status.SAFE, r.status)
        assertEquals(0.0, r.remainingBudget, 1e-9)
        assertEquals(0.0, r.dailyAvailable!!, 1e-9)
        assertEquals(1, r.remainingDays)            // 月末最后一天仍算 1 天，避免除零
        assertEquals(100.0, r.usedPercent!!, 1e-9)
        assertNull(r.projectedBudgetDay)
    }

    @Test
    fun `超出 1 分钱即判 DANGER`() {
        val r = BudgetPredictor.predict(5000.0, 5000.01, 30, 30)
        assertEquals(Status.DANGER, r.status)
    }

    @Test
    fun `已超支 - DANGER 且超支日期落在今天之前`() {
        val r = BudgetPredictor.predict(5000.0, 5600.0, 20, 30)
        assertEquals(Status.DANGER, r.status)
        assertEquals(0.0, r.remainingBudget, 1e-9)  // 负数钳到 0，不给用户看负额度
        assertEquals(0.0, r.dailyAvailable!!, 1e-9)
        assertEquals(280.0, r.averageDailyExpense, 1e-9)
        assertEquals(18, r.projectedBudgetDay)      // ceil(5000/280)
        assertTrue("超支日期应 <= 已过天数", r.projectedBudgetDay!! <= r.daysElapsed)
        assertEquals(112.0, r.usedPercent!!, 1e-9)
    }

    @Test
    fun `月末最后一天 - remainingDays 为 1 不会除零`() {
        val r = BudgetPredictor.predict(5000.0, 4900.0, 30, 30)
        assertEquals(Status.SAFE, r.status)
        assertEquals(1, r.remainingDays)
        assertEquals(100.0, r.dailyAvailable!!, 1e-9)
        assertEquals(4900.0, r.predictedMonthExpense, 1e-9)
    }

    // ═══ 月份长度 ═══

    @Test
    fun `2 月 28 天`() {
        val r = BudgetPredictor.predict(3000.0, 1500.0, 14, 28)
        assertEquals(Status.SAFE, r.status)
        assertEquals(107.14, r.averageDailyExpense, 1e-9)
        assertEquals(3000.0, r.predictedMonthExpense, 1e-9)   // 正好用完，不算超支
        assertEquals(15, r.remainingDays)
        assertEquals(100.0, r.dailyAvailable!!, 1e-9)
    }

    @Test
    fun `闰年 2 月 29 天`() {
        val r = BudgetPredictor.predict(2900.0, 1450.0, 14, 29)
        assertEquals(29, r.daysTotal)
        assertEquals(16, r.remainingDays)
        // 1450/14*29 = 2998.28... > 2900 → WARNING
        assertEquals(Status.WARNING, r.status)
    }

    @Test
    fun `31 天大月`() {
        val r = BudgetPredictor.predict(3100.0, 1000.0, 10, 31)
        assertEquals(31, r.daysTotal)
        assertEquals(22, r.remainingDays)
        assertEquals(3100.0, r.predictedMonthExpense, 1e-9)   // 1000/10*31
        assertEquals(Status.SAFE, r.status)
    }

    // ═══ 历史月份：daysElapsed = daysTotal，预测退化为实际 ═══

    @Test
    fun `查看历史月份时预测退化为实际支出`() {
        val r = BudgetPredictor.predict(5000.0, 4850.0, 30, 30)
        assertEquals(Status.SAFE, r.status)
        assertEquals(4850.0, r.predictedMonthExpense, 1e-9)
        assertNull("历史月份不该给出未来超支日期", r.projectedBudgetDay)
        assertEquals(97.0, r.usedPercent!!, 1e-9)
    }

    @Test
    fun `历史月份已超支仍判 DANGER`() {
        val r = BudgetPredictor.predict(5000.0, 5300.0, 31, 31)
        assertEquals(Status.DANGER, r.status)
    }

    // ═══ 脏输入防御：任何一路都不能算出 NaN / Infinity ═══

    @Test
    fun `daysTotal 非法时兜底为 30`() {
        assertEquals(30, BudgetPredictor.predict(5000.0, 100.0, 1, 0).daysTotal)
        assertEquals(30, BudgetPredictor.predict(5000.0, 100.0, 1, -5).daysTotal)
        assertEquals(30, BudgetPredictor.predict(5000.0, 100.0, 1, 9999).daysTotal)
    }

    @Test
    fun `daysElapsed 越界被钳制且不除零`() {
        // 0 会被钳到 1，避免 0/0
        val zero = BudgetPredictor.predict(5000.0, 0.0, 0, 30)
        assertEquals(1, zero.daysElapsed)
        assertFalse(zero.averageDailyExpense.isNaN())

        // 超过总天数会被钳到总天数
        val over = BudgetPredictor.predict(5000.0, 100.0, 99, 30)
        assertEquals(30, over.daysElapsed)
        assertEquals(1, over.remainingDays)
    }

    @Test
    fun `NaN 与 Infinity 支出被当作 0`() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { bad ->
            val r = BudgetPredictor.predict(5000.0, bad, 7, 30)
            assertFalse("NaN 支出产生了 NaN 结果: $bad", r.dailyAvailable!!.isNaN())
            assertFalse(r.predictedMonthExpense.isNaN())
            assertFalse(r.averageDailyExpense.isInfinite())
        }
    }

    @Test
    fun `NaN 预算按未设置处理`() {
        assertEquals(Status.NO_BUDGET, BudgetPredictor.predict(Double.NaN, 100.0, 7, 30).status)
    }

    @Test
    fun `负支出被钳到 0 不产生负额度`() {
        val r = BudgetPredictor.predict(5000.0, -800.0, 7, 30)
        assertEquals(0.0, r.currentExpense, 1e-9)
        assertEquals(5000.0, r.remainingBudget, 1e-9)
        assertEquals(Status.SAFE, r.status)
    }

    @Test
    fun `超大预算不溢出`() {
        val r = BudgetPredictor.predict(1e12, 1000.0, 7, 30)
        assertFalse(r.dailyAvailable!!.isInfinite())
        assertEquals(Status.SAFE, r.status)
    }

    // ═══ 公式口径回归 ═══

    @Test
    fun `今日建议可用 = 剩余额度 除以 含今天的剩余天数`() {
        // 手算核对：预算 5000，已花 1234.56，今天 9 号，9 月共 30 天
        val r = BudgetPredictor.predict(5000.0, 1234.56, 9, 30)
        val expectedRemaining = 5000.0 - 1234.56
        val expectedDays = 30 - 9 + 1        // 22，今天计入
        assertEquals(expectedDays, r.remainingDays)
        assertEquals(
            Math.round(expectedRemaining / expectedDays * 100.0) / 100.0,
            r.dailyAvailable!!, 1e-9
        )
    }

    @Test
    fun `月末预测 = 日均 乘以 当月总天数`() {
        val r = BudgetPredictor.predict(9000.0, 2000.0, 10, 30)
        assertEquals(200.0, r.averageDailyExpense, 1e-9)
        assertEquals(6000.0, r.predictedMonthExpense, 1e-9)
    }

    @Test
    fun `超支日期 = 预算 除以 日均 向上取整 并钳在当月内`() {
        // 预算 1000，日均 300 → ceil(3.33) = 4
        val r = BudgetPredictor.predict(1000.0, 300.0, 1, 30)
        assertEquals(4, r.projectedBudgetDay)
        // 日均极大时钳到 1，不会算出第 0 天
        val extreme = BudgetPredictor.predict(1000.0, 100000.0, 20, 30)
        assertEquals(Status.DANGER, extreme.status)
        assertTrue(extreme.projectedBudgetDay!! >= 1)
    }
}
