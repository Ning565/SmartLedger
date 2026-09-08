package com.smartledger.data.ai

import com.smartledger.data.ai.model.CategoryRef
import com.smartledger.data.ai.model.TransactionDraft
import com.smartledger.util.PaymentMethods
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * AI 记账字段解析器：模型原始输出 → 校验 → 映射 → [TransactionDraft]。
 *
 * ## 三条设计红线
 *
 * 1. **不入库**。本文件不 import TransactionRepository / TransactionDao，
 *    物理上不存在「AI 直接写库」的路径。草稿只回填表单，由用户确认后保存。
 *
 * 2. **不猜**。认不出来的字段留 null 并记进 missingFields，
 *    绝不用默认值填满 —— 那会让用户误以为 AI 认出来了，反而记出错账。
 *
 * 3. **分类必须映射到本地 id**。AI 返回的是分类名字符串，
 *    而库里存的是 categoryId，且分类名在 expense / income 下会重名
 *    （默认数据里「其他」两边都有）。必须按 (name, type) 联合定位，
 *    只按 name 查会把收入记到支出分类上。
 *
 * 纯函数（只依赖传入的分类表与时区），不接 Context，可完整单测。
 */
object AiTransactionParser {

    sealed interface Result {
        data class Ok(val draft: TransactionDraft) : Result

        /** 提不出 JSON：模型答非所问、输出被截断 */
        data object NotJson : Result

        /** 提出了 JSON 但没有可用金额：这种草稿没有意义，直接判失败 */
        data object NoAmount : Result
    }

    /** 金额上限：超过视为模型幻觉 */
    private const val MAX_AMOUNT = 1_000_000.0

    /** 日期下限：早于这个年份基本是解析错误 */
    private const val MIN_YEAR = 2000

    private const val MAX_MERCHANT_LEN = 30
    private const val MAX_NOTE_LEN = 50

    /** 无法确定时刻且日期不是今天时的兜底时间：正午 */
    private const val FALLBACK_NOON_HOUR = 12

    fun parse(
        rawModelOutput: String?,
        categories: List<CategoryRef>,
        now: Long,
        zone: TimeZone = TimeZone.getDefault(),
        allowedChannels: List<String> = PaymentMethods.PRESETS
    ): Result {
        val jsonText = JsonExtractor.extractObject(rawModelOutput) ?: return Result.NotJson

        val root = try {
            JSONObject(jsonText)
        } catch (_: Exception) {
            return Result.NotJson
        }

        val missing = mutableListOf<String>()

        // ── type：大小写不敏感归一到小写 ──
        // 模型经常无视 Prompt 返回 "EXPENSE"，不归一就会写出脏数据，
        // 导致这笔账在统计里被 `type = 'expense'` 的查询永久漏掉。
        val rawType = root.optStringOrNull("type")?.trim()?.lowercase(Locale.US)
        val type = when (rawType) {
            TransactionDraft.TYPE_EXPENSE -> TransactionDraft.TYPE_EXPENSE
            TransactionDraft.TYPE_INCOME -> TransactionDraft.TYPE_INCOME
            else -> {
                missing += TransactionDraft.FIELD_TYPE
                TransactionDraft.TYPE_EXPENSE      // 记账场景下支出是压倒性多数
            }
        }

        // ── amount ──
        val amount = parseAmount(root.opt("amount"))
        if (amount == null) {
            missing += TransactionDraft.FIELD_AMOUNT
            return Result.NoAmount
        }

        // ── category：按 (name, type) 精确匹配 ──
        val rawCategory = root.optStringOrNull("category")?.trim()
        val matched = rawCategory?.let { name ->
            categories.firstOrNull { it.type == type && it.name == name }
        }
        if (rawCategory != null && matched == null) {
            // AI 给了分类但本地没有（自造分类、或给了另一 type 的分类名）
            missing += TransactionDraft.FIELD_CATEGORY
        } else if (rawCategory == null) {
            missing += TransactionDraft.FIELD_CATEGORY
        }

        // ── channel：必须在允许列表内 ──
        val rawChannel = root.optStringOrNull("channel")?.trim()
        val channel = rawChannel?.takeIf { c -> allowedChannels.any { it == c } }
        if (channel == null) missing += TransactionDraft.FIELD_CHANNEL

        // ── merchant / note：可选，清洗但不算 missing ──
        val merchant = sanitize(root.optStringOrNull("merchant"), MAX_MERCHANT_LEN)
        val note = sanitize(root.optStringOrNull("note"), MAX_NOTE_LEN)

        // ── date + time → transactionTime ──
        val dateResult = resolveDate(root.optStringOrNull("date"), now, zone)
        if (dateResult.reportMissing) missing += TransactionDraft.FIELD_DATE
        val transactionTime = composeTime(
            dayStart = dateResult.dayStart,
            rawTime = root.optStringOrNull("time"),
            isToday = dateResult.isToday,
            now = now,
            zone = zone
        )

        return Result.Ok(
            TransactionDraft(
                amount = amount,
                type = type,
                categoryId = matched?.id,
                categoryName = matched?.name,
                channel = channel,
                merchant = merchant,
                note = note,
                transactionTime = transactionTime,
                missingFields = missing.distinct()
            )
        )
    }

    // ═══════════════════════════════════════════════════
    // 字段级解析
    // ═══════════════════════════════════════════════════

    /**
     * 金额解析。
     *
     * 模型可能给 Double(32.0)、Int(32)、String("32")、String("32.00")，
     * 甚至 String("¥32")。全部接住，非法值返回 null。
     */
    private fun parseAmount(raw: Any?): Double? {
        val value: Double = when (raw) {
            is Number -> {
                val d = raw.toDouble()
                if (d.isNaN() || d.isInfinite()) return null
                d
            }

            is String -> {
                // 去掉货币符、千分位、空格、中文「元」
                val cleaned = raw.replace(Regex("[¥￥,，\\s元]"), "")
                cleaned.toDoubleOrNull() ?: return null
            }

            else -> return null
        }
        if (value.isNaN() || value.isInfinite()) return null
        if (value > MAX_AMOUNT) return null
        // 先归一到两位小数，**再**判正负：
        // 0.001 这种输入原始值 > 0 但四舍五入后是 0，
        // 若先判后舍会把 amount = 0.0 当成合法结果放过去。
        val rounded = Math.round(value * 100.0) / 100.0
        if (rounded <= 0.0) return null
        return rounded
    }

    /**
     * 文本清洗：去首尾空白、压内部换行、截断长度、剔除纯标点。
     *
     * 压换行是因为商户名/备注会显示在单行列表项里，
     * 带换行会把行高撑乱。
     */
    private fun sanitize(raw: String?, maxLen: Int): String? {
        if (raw == null) return null
        val s = raw.replace(Regex("[\\r\\n\\t]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (s.isEmpty()) return null
        // 全是标点/符号的「商户名」没有信息量，例如模型返回 "-" 或 "无"
        if (s.none { it.isLetterOrDigit() }) return null
        return if (s.length > maxLen) s.take(maxLen) else s
    }

    private class DateResolution(
        val dayStart: Long,
        val isToday: Boolean,
        /**
         * 是否应向用户报「日期未识别」。
         *
         * 关键区分：**没给日期不算未识别**。
         * 用户说「麦当劳28元」本来就不会提日期，回落今天是记账 App
         * 唯一合理的默认行为；这时候提示「未识别：日期」只会变成噪声，
         * 让用户以为需要手工补一个本来就对的值。
         *
         * 只有「模型给了一个我们看不懂的日期」才值得报 ——
         * 那说明用户提了时间但我们没理解，需要他确认。
         */
        val reportMissing: Boolean
    )

    /**
     * 日期解析。
     *
     * - 严格 `yyyy-MM-dd`，`isLenient = false`，
     *   否则 "2026-02-31" 会被 SimpleDateFormat 悄悄滚成 3 月 3 日；
     * - **未来日期钳到今天**：用户说「明天预付房租」时，
     *   记账 App 不该记一笔未来的账（会污染「今日支出」和预算进度）；
     * - 早于 [MIN_YEAR] 的视为解析错误，回落今天。
     */
    private fun resolveDate(raw: String?, now: Long, zone: TimeZone): DateResolution {
        val todayStart = startOfDay(now, zone)

        if (raw.isNullOrBlank()) {
            return DateResolution(todayStart, isToday = true, reportMissing = false)
        }

        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            isLenient = false
            timeZone = zone
        }
        val parsed = try {
            fmt.parse(raw.trim())?.time
        } catch (_: Exception) {
            null
        }
        if (parsed == null) {
            // 给了日期但格式不对 → 真的没看懂，要告诉用户
            return DateResolution(todayStart, isToday = true, reportMissing = true)
        }

        val dayStart = startOfDay(parsed, zone)
        val minStart = startOfDay(
            Calendar.getInstance(zone).apply {
                clear()
                set(MIN_YEAR, Calendar.JANUARY, 1)
            }.timeInMillis, zone
        )

        return when {
            // 未来 → 钳到今天。我们看懂了日期，只是不接受未来值，
            // 表单会直接显示今天的日期，用户看得见也能改，不必报未识别。
            dayStart > todayStart ->
                DateResolution(todayStart, isToday = true, reportMissing = false)

            // 过于久远 → 视为解析错误
            dayStart < minStart ->
                DateResolution(todayStart, isToday = true, reportMissing = true)

            else -> DateResolution(dayStart, isToday = dayStart == todayStart, reportMissing = false)
        }
    }

    /**
     * 把 date 与 time 合成毫秒时间戳。
     *
     * time 缺失时的兜底很关键：
     *  - 日期是**今天** → 用当前时刻（用户刚发生的消费，时刻就是现在）；
     *  - 日期是**过去** → 用正午 12:00，**不能用 00:00**。
     *    00:00 会落进「夜间 22-06」桶，让一笔白天补记的账
     *    在 AI 报告里变成「凌晨消费」，直接扭曲行为画像。
     */
    private fun composeTime(
        dayStart: Long,
        rawTime: String?,
        isToday: Boolean,
        now: Long,
        zone: TimeZone
    ): Long {
        val cal = Calendar.getInstance(zone).apply { timeInMillis = dayStart }

        val hm = parseHourMinute(rawTime)
        if (hm != null) {
            cal.set(Calendar.HOUR_OF_DAY, hm.first)
            cal.set(Calendar.MINUTE, hm.second)
        } else {
            val src = Calendar.getInstance(zone).apply { timeInMillis = now }
            if (isToday) {
                cal.set(Calendar.HOUR_OF_DAY, src.get(Calendar.HOUR_OF_DAY))
                cal.set(Calendar.MINUTE, src.get(Calendar.MINUTE))
            } else {
                cal.set(Calendar.HOUR_OF_DAY, FALLBACK_NOON_HOUR)
                cal.set(Calendar.MINUTE, 0)
            }
        }
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /**
     * 严格解析 HH:mm，非法返回 null。
     *
     * 不依赖时区（只是拆小时与分钟），因此不传 zone。
     * 兼容全角冒号，因为中文输入法下模型很容易返回「12：30」。
     */
    private fun parseHourMinute(raw: String?): Pair<Int, Int>? {
        if (raw.isNullOrBlank()) return null
        val m = Regex("""^\s*(\d{1,2})\s*[:：]\s*(\d{1,2})\s*$""").find(raw) ?: return null
        val h = m.groupValues[1].toIntOrNull() ?: return null
        val min = m.groupValues[2].toIntOrNull() ?: return null
        if (h !in 0..23 || min !in 0..59) return null
        return h to min
    }

    private fun startOfDay(ms: Long, zone: TimeZone): Long =
        Calendar.getInstance(zone).apply {
            timeInMillis = ms
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    /**
     * org.json 的坑：`optString(key)` 在键不存在时返回 **空串**而不是 null，
     * 在值为 JSON null 时返回字符串 **"null"**。
     * 直接用会把「AI 没给这个字段」和「AI 给了空值」混为一谈，
     * 进而错判 missingFields。这里统一成真正的 null。
     */
    private fun JSONObject.optStringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        val v = opt(key) ?: return null
        val s = v.toString().trim()
        if (s.isEmpty()) return null
        if (s == "null" || s == "NULL") return null
        return s
    }
}
