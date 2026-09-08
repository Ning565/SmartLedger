package com.smartledger.ui.record

/**
 * 金额数字键盘的按键逻辑。
 *
 * 原本内联在 `RecordScreen.kt` 的 private 顶层函数里，无法测试。
 * 抽出来后 **退格 / 小数点 / 两位小数上限 / 前导 0 替换 这四个核心行为一字未改**，
 * 并用单测逐条锁死 —— 这是用户最高频的输入路径，重构时任何「顺手优化」都可能引入回归。
 *
 * 相对原实现只补了一处：整数位上限 [MAX_INT_DIGITS]。
 * 原实现可以无限按数字键，能输到 20 位并真的存进数据库
 * （超出 Double 精确表示范围，且金额毫无意义），属于隐形 bug。
 *
 * 键盘按键：1-9、0、`.`、`⌫`
 */
object AmountKeypad {

    const val KEY_BACKSPACE = "⌫"
    const val KEY_DOT = "."

    /** 小数位上限 */
    const val MAX_DECIMALS = 2

    /** 金额字符串的最大整数位长度，防止溢出 Double 精度 */
    const val MAX_INT_DIGITS = 9

    val KEYS: List<String> = listOf(
        "1", "2", "3",
        "4", "5", "6",
        "7", "8", "9",
        KEY_DOT, "0", KEY_BACKSPACE
    )

    /**
     * @param current 当前金额文本，约定初始为 "0"
     * @param key     按下的键
     * @return 新的金额文本；非法输入原样返回 current
     */
    fun press(current: String, key: String): String {
        return when (key) {
            KEY_BACKSPACE -> if (current.length <= 1) "0" else current.dropLast(1)

            KEY_DOT -> if (current.contains(KEY_DOT)) current else "${current}."

            else -> {
                // 只接受单个数字键，其它输入一律忽略
                if (key.length != 1 || !key[0].isDigit()) return current

                if (current == "0") {
                    key
                } else {
                    val dotIndex = current.indexOf(KEY_DOT)
                    when {
                        // 已有小数点且小数位已达上限 → 忽略
                        dotIndex != -1 && current.length - dotIndex > MAX_DECIMALS -> current
                        // 整数位已达上限 → 忽略，避免超出 Double 精确表示范围
                        intDigits(current) >= MAX_INT_DIGITS && dotIndex == -1 -> current
                        else -> current + key
                    }
                }
            }
        }
    }

    /** 当前整数部分的位数 */
    private fun intDigits(current: String): Int {
        val dot = current.indexOf(KEY_DOT)
        val intPart = if (dot >= 0) current.substring(0, dot) else current
        // "0" 不算已占用位数，否则无法从 0 开始输入
        return if (intPart == "0") 0 else intPart.length
    }

    /** 逐键重放一串输入，便于测试与「清空后重填」 */
    fun replay(initial: String, keys: Sequence<String>): String =
        keys.fold(initial) { acc, k -> press(acc, k) }

    /**
     * 把 AI 解析出的金额转成键盘文本形态。
     *
     * AI 给的是 32.0 / 18.5 / 219.99，直接塞进 amountText 会显示成 "32.0"，
     * 与用户手动输入 "32" 的观感不一致，也会让「继续按数字键」的行为变怪
     * （"32.0" 再按 5 会变成 "32.05"，而用户想的是 32.0 后面接 5）。
     * 因此统一去掉无意义的小数尾巴。
     */
    fun fromAmount(amount: Double): String {
        if (amount.isNaN() || amount.isInfinite() || amount <= 0.0) return "0"
        val rounded = Math.round(amount * 100.0) / 100.0
        return if (rounded == rounded.toLong().toDouble()) {
            rounded.toLong().toString()
        } else {
            // 去掉末尾多余的 0：18.50 → 18.5
            String.format(java.util.Locale.US, "%.2f", rounded).trimEnd('0').trimEnd('.')
        }
    }
}
