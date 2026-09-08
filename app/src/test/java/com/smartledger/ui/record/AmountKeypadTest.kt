package com.smartledger.ui.record

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 金额键盘回归测试。
 *
 * 这些用例的作用是**把重构前的行为钉死**：
 * RecordScreen 的键盘逻辑原本内联在 Composable 里无法测试，
 * 状态提升时如果不小心改了某个分支，这里会立刻红。
 */
class AmountKeypadTest {

    // ═══ 前导 0 替换 ═══

    @Test
    fun `初始 0 按数字键直接替换`() {
        assertEquals("5", AmountKeypad.press("0", "5"))
        assertEquals("9", AmountKeypad.press("0", "9"))
        assertEquals("1", AmountKeypad.press("0", "1"))
    }

    @Test
    fun `初始 0 按 0 仍是 0`() {
        assertEquals("0", AmountKeypad.press("0", "0"))
    }

    @Test
    fun `非 0 状态下按 0 正常追加`() {
        assertEquals("10", AmountKeypad.press("1", "0"))
        assertEquals("120", AmountKeypad.press("12", "0"))
    }

    // ═══ 小数点 ═══

    @Test
    fun `初始 0 按小数点得到 0 点`() {
        assertEquals("0.", AmountKeypad.press("0", "."))
    }

    @Test
    fun `小数点只能有一个`() {
        assertEquals("12.", AmountKeypad.press("12", "."))
        assertEquals("12.", AmountKeypad.press("12.", "."))
        assertEquals("12.3", AmountKeypad.press("12.3", "."))
    }

    @Test
    fun `0 点后可以直接按数字`() {
        assertEquals("0.5", AmountKeypad.press("0.", "5"))
        assertEquals("0.05", AmountKeypad.press("0.0", "5"))
    }

    // ═══ 两位小数上限 ═══

    @Test
    fun `最多两位小数`() {
        assertEquals("12.3", AmountKeypad.press("12.", "3"))
        assertEquals("12.34", AmountKeypad.press("12.3", "4"))
        assertEquals("第三位小数必须被拒绝", "12.34", AmountKeypad.press("12.34", "5"))
        assertEquals("12.34", AmountKeypad.press("12.34", "."))
    }

    @Test
    fun `整数部分不受两位小数限制`() {
        var s = "1"
        repeat(8) { s = AmountKeypad.press(s, "2") }
        assertEquals("122222222", s)
    }

    // ═══ 整数位上限（相对原实现新增的保护）═══

    @Test
    fun `整数位超过上限后拒绝继续输入`() {
        // 原实现可以无限按数字键，能输到 20 位并真的存进数据库
        var s = "0"
        repeat(20) { s = AmountKeypad.press(s, "9") }
        assertEquals(AmountKeypad.MAX_INT_DIGITS, s.length)
        assertEquals("999999999", s)
        // 且不会超出 Double 精确表示范围
        assertEquals(s.toDouble().toLong().toString(), s)
    }

    @Test
    fun `整数位达上限后仍可输入小数`() {
        val s = AmountKeypad.press("999999999", ".")
        assertEquals("999999999.", s)
        assertEquals("999999999.5", AmountKeypad.press(s, "5"))
    }

    // ═══ 退格 ═══

    @Test
    fun `退格删掉最后一位`() {
        assertEquals("12", AmountKeypad.press("123", "⌫"))
        assertEquals("12.", AmountKeypad.press("12.3", "⌫"))
        assertEquals("12", AmountKeypad.press("12.", "⌫"))
    }

    @Test
    fun `退格到只剩一位时归零而不是变空串`() {
        assertEquals("0", AmountKeypad.press("5", "⌫"))
        assertEquals("0", AmountKeypad.press("0", "⌫"))
    }

    @Test
    fun `连续退格最终稳定在 0`() {
        var s = "123.45"
        repeat(10) { s = AmountKeypad.press(s, "⌫") }
        assertEquals("0", s)
    }

    // ═══ 非法输入 ═══

    @Test
    fun `非数字键被忽略`() {
        assertEquals("12", AmountKeypad.press("12", "a"))
        assertEquals("12", AmountKeypad.press("12", ""))
        assertEquals("12", AmountKeypad.press("12", "12"))
        assertEquals("12", AmountKeypad.press("12", "-"))
        assertEquals("12", AmountKeypad.press("12", " "))
    }

    // ═══ 序列重放 ═══

    @Test
    fun `完整输入序列`() {
        // 用户想输入 32.50
        val s = AmountKeypad.replay("0", sequenceOf("3", "2", ".", "5", "0"))
        assertEquals("32.50", s)
        assertEquals(32.5, s.toDouble(), 1e-9)
    }

    @Test
    fun `输入后改主意的序列`() {
        // 输错 128 → 退两格 → 改成 126
        val s = AmountKeypad.replay(
            "0",
            sequenceOf("1", "2", "8", "⌫", "⌫", "2", "6")
        )
        assertEquals("126", s)
    }

    @Test
    fun `键盘按键布局共 12 个且顺序固定`() {
        assertEquals(
            listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", ".", "0", "⌫"),
            AmountKeypad.KEYS
        )
    }

    // ═══ AI 金额回填格式化 ═══

    @Test
    fun `整数金额去掉小数尾巴`() {
        // AI 返回 32.0，直接塞进输入框会显示 "32.0"，
        // 与用户手动输入 "32" 的观感不一致，
        // 而且再按数字键会变成 "32.05" 这种用户没想要的值
        assertEquals("32", AmountKeypad.fromAmount(32.0))
        assertEquals("28", AmountKeypad.fromAmount(28.0))
        assertEquals("12000", AmountKeypad.fromAmount(12000.0))
        assertEquals("219", AmountKeypad.fromAmount(219.0))
    }

    @Test
    fun `小数金额保留有效位`() {
        assertEquals("18.5", AmountKeypad.fromAmount(18.5))
        assertEquals("219.99", AmountKeypad.fromAmount(219.99))
        assertEquals("0.05", AmountKeypad.fromAmount(0.05))
        assertEquals("100.5", AmountKeypad.fromAmount(100.50))
        assertEquals("100.1", AmountKeypad.fromAmount(100.10))
    }

    @Test
    fun `浮点尾巴被归一`() {
        assertEquals("32.3", AmountKeypad.fromAmount(32.299999999))
        assertEquals("0.07", AmountKeypad.fromAmount(0.070000001))
    }

    @Test
    fun `非法金额回落为 0`() {
        assertEquals("0", AmountKeypad.fromAmount(0.0))
        assertEquals("0", AmountKeypad.fromAmount(-5.0))
        assertEquals("0", AmountKeypad.fromAmount(Double.NaN))
        assertEquals("0", AmountKeypad.fromAmount(Double.POSITIVE_INFINITY))
    }

    @Test
    fun `回填后的文本可以继续用键盘编辑`() {
        // 回填 → 用户再按一位小数，行为必须与手动输入一致
        val filled = AmountKeypad.fromAmount(32.0)
        assertEquals("32", filled)
        assertEquals("32.", AmountKeypad.press(filled, "."))
        assertEquals("32.5", AmountKeypad.press(AmountKeypad.press(filled, "."), "5"))
    }
}
