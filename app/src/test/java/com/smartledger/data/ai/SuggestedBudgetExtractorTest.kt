package com.smartledger.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 建议额度提取的单测（方案 3.7 节约定的必测形态）。
 *
 * 历史教训：正则交替分支的千分位组原为 `*`（零个逗号组也成功），
 * Java 正则的「左优先」语义让不带千分位的 4 位以上金额只截取前 3 位
 * （¥4800 → 480、¥12000 → 120）。这个值会经「采纳为下月预算」弹窗
 * 一路写进 budgets 表，错 10 倍的预算会被用户一键确认 —— 因此这里的
 * 无千分位回归用例一个都不能省。
 */
class SuggestedBudgetExtractorTest {

    // ═══ 方案 3.7 节约定的必测形态 ═══

    @Test
    fun `标准形态 - 半角货币符`() {
        assertEquals(4800.0, SuggestedBudgetExtractor.extract("建议可用总额度：¥4800")!!, 1e-9)
    }

    @Test
    fun `全角货币符加千分位`() {
        assertEquals(4800.0, SuggestedBudgetExtractor.extract("建议可用总额度：￥4,800")!!, 1e-9)
    }

    @Test
    fun `半角冒号加单位元`() {
        assertEquals(4800.0, SuggestedBudgetExtractor.extract("建议可用总额度: 4800 元")!!, 1e-9)
    }

    @Test
    fun `加粗包裹带小数`() {
        assertEquals(4800.5, SuggestedBudgetExtractor.extract("**建议可用总额度：¥4800.50**")!!, 1e-9)
    }

    @Test
    fun `带约字和单位`() {
        assertEquals(5000.0, SuggestedBudgetExtractor.extract("建议可用总额度：约 5000元")!!, 1e-9)
    }

    @Test
    fun `正文多次出现时取最后一次`() {
        val md = "……上月方案讨论里曾出现建议可用总额度：¥5000 的说法……\n\n" +
                "## 5. 建议可用总额度\n建议可用总额度：¥4800"
        assertEquals(4800.0, SuggestedBudgetExtractor.extract(md)!!, 1e-9)
    }

    @Test
    fun `没有该行返回null`() {
        assertNull(SuggestedBudgetExtractor.extract("## 1. 消费结构诊断\n本周支出较为集中，餐饮占比最高。"))
    }

    @Test
    fun `零值返回null`() {
        assertNull(SuggestedBudgetExtractor.extract("建议可用总额度：¥0"))
    }

    @Test
    fun `超上限视为幻觉返回null`() {
        assertNull(SuggestedBudgetExtractor.extract("建议可用总额度：¥99999999"))
    }

    // ═══ 回归：无千分位的 4 位以上金额不得截断 ═══

    @Test
    fun `五位数无千分位完整提取`() {
        // 旧正则（千分位组为 *）会截成 120
        assertEquals(12000.0, SuggestedBudgetExtractor.extract("建议可用总额度：¥12000")!!, 1e-9)
    }

    @Test
    fun `六位数无千分位完整提取`() {
        // 旧正则会截成 100
        assertEquals(100000.0, SuggestedBudgetExtractor.extract("建议可用总额度：¥100000")!!, 1e-9)
    }

    @Test
    fun `三位以内整数带小数不受影响`() {
        assertEquals(980.5, SuggestedBudgetExtractor.extract("建议可用总额度：¥980.50")!!, 1e-9)
    }

    @Test
    fun `多重组千分位带小数`() {
        assertEquals(123456.78, SuggestedBudgetExtractor.extract("建议可用总额度：￥123,456.78")!!, 1e-9)
    }

    @Test
    fun `中文句号结尾`() {
        assertEquals(4800.0, SuggestedBudgetExtractor.extract("建议可用总额度：¥4800。")!!, 1e-9)
    }

    @Test
    fun `模拟真实报告尾部`() {
        val tail = "综上所述，建议在控制餐饮频次的同时保留必要通勤支出。\n\n" +
                "建议可用总额度：¥5,200"
        assertEquals(5200.0, SuggestedBudgetExtractor.extract(tail)!!, 1e-9)
    }

    // ═══ 输入防御 ═══

    @Test
    fun `null与空白输入返回null`() {
        assertNull(SuggestedBudgetExtractor.extract(null))
        assertNull(SuggestedBudgetExtractor.extract(""))
        assertNull(SuggestedBudgetExtractor.extract("   \n "))
    }

    @Test
    fun `只有标签没有数字返回null`() {
        assertNull(SuggestedBudgetExtractor.extract("建议可用总额度：¥"))
    }
}
