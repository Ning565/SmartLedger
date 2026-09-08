package com.smartledger.data.ai

import com.smartledger.data.ai.model.CategoryRef
import com.smartledger.data.ai.model.TransactionDraft
import com.smartledger.util.PaymentMethods
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * AI 记账字段解析测试。
 *
 * 这里喂的是「模型应该返回的 JSON」，确定性地验证本地校验与映射规则；
 * 模型真实输出的稳定性由 DeepSeek 集成测试单独覆盖。
 *
 * 基准时刻固定为 **2026-09-07 14:30（周一，Asia/Shanghai）**，
 * 所有相对日期断言都基于它。
 */
class AiTransactionParserTest {

    private val sh = TimeZone.getTimeZone("Asia/Shanghai")

    /** 2026-09-07 14:30 Asia/Shanghai */
    private val now: Long = Calendar.getInstance(sh).apply {
        set(2026, Calendar.SEPTEMBER, 7, 14, 30, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** 与 AppDatabase 默认分类一致：注意「其他」在两种 type 下各有一条 */
    private val categories = listOf(
        CategoryRef(1, "餐饮", "expense"),
        CategoryRef(2, "交通", "expense"),
        CategoryRef(3, "购物", "expense"),
        CategoryRef(4, "娱乐", "expense"),
        CategoryRef(5, "居住", "expense"),
        CategoryRef(6, "通讯", "expense"),
        CategoryRef(10, "其他", "expense"),
        CategoryRef(11, "工资", "income"),
        CategoryRef(12, "理财", "income"),
        CategoryRef(20, "其他", "income")
    )

    private fun parse(raw: String?, nowMs: Long = now) =
        AiTransactionParser.parse(raw, categories, nowMs, sh, PaymentMethods.PRESETS)

    private fun ok(raw: String?, nowMs: Long = now): TransactionDraft {
        val r = parse(raw, nowMs)
        assertTrue("期望解析成功，实际=$r", r is AiTransactionParser.Result.Ok)
        return (r as AiTransactionParser.Result.Ok).draft
    }

    private fun hourMinute(ms: Long): Pair<Int, Int> {
        val c = Calendar.getInstance(sh).apply { timeInMillis = ms }
        return c.get(Calendar.HOUR_OF_DAY) to c.get(Calendar.MINUTE)
    }

    private fun ymd(ms: Long): String {
        val c = Calendar.getInstance(sh).apply { timeInMillis = ms }
        return "%04d-%02d-%02d".format(
            c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH)
        )
    }

    // ═══════════════════════════════════════════════════
    // 方案文档要求的必测输入
    // ═══════════════════════════════════════════════════

    @Test
    fun `字段完整 - 昨天中午老乡鸡 32 微信`() {
        // 这是 DeepSeek 实测返回的真实 JSON
        val d = ok(
            """{"amount":32.0,"type":"expense","category":"餐饮","channel":"微信",
              |"merchant":"老乡鸡","note":"同事AA","date":"2026-09-06","time":"12:00"}""".trimMargin()
        )
        assertEquals(32.0, d.amount!!, 1e-9)
        assertEquals("expense", d.type)
        assertEquals(1L, d.categoryId)
        assertEquals("餐饮", d.categoryName)
        assertEquals("微信", d.channel)
        assertEquals("老乡鸡", d.merchant)
        assertEquals("同事AA", d.note)
        assertEquals("2026-09-06", ymd(d.transactionTime!!))
        assertEquals(12 to 0, hourMinute(d.transactionTime!!))
        assertTrue("字段齐全时 missingFields 应为空，实际=${d.missingFields}", d.isComplete)
        assertTrue(d.isUsable)
    }

    @Test
    fun `今天麦当劳 28 元 - 缺渠道`() {
        val d = ok(
            """{"amount":28,"type":"expense","category":"餐饮","channel":null,
              |"merchant":"麦当劳","note":null,"date":"2026-09-07","time":null}""".trimMargin()
        )
        assertEquals(28.0, d.amount!!, 1e-9)
        assertNull("渠道未提及就该是 null，不能瞎猜", d.channel)
        assertNull(d.note)
        assertEquals(listOf(TransactionDraft.FIELD_CHANNEL), d.missingFields)
        assertFalse(d.isComplete)
        assertTrue("缺渠道仍然可用，UI 保留用户上次选的渠道", d.isUsable)
        // 日期是今天且没给时刻 → 用当前时刻
        assertEquals("2026-09-07", ymd(d.transactionTime!!))
        assertEquals(14 to 30, hourMinute(d.transactionTime!!))
    }

    @Test
    fun `昨天滴滴 35 块微信`() {
        val d = ok(
            """{"amount":35,"type":"expense","category":"交通","channel":"微信",
              |"merchant":"滴滴","date":"2026-09-06","time":null}""".trimMargin()
        )
        assertEquals(2L, d.categoryId)
        assertEquals(35.0, d.amount!!, 1e-9)
        // 过去日期且无时刻 → 正午，**不能是 00:00**
        assertEquals(12 to 0, hourMinute(d.transactionTime!!))
    }

    @Test
    fun `今天工资到账 12000 - 收入分类必须走 income 分支`() {
        val d = ok(
            """{"amount":12000,"type":"income","category":"工资","channel":null,
              |"merchant":null,"date":"2026-09-07","time":null}""".trimMargin()
        )
        assertEquals("income", d.type)
        assertEquals(11L, d.categoryId)
        assertEquals("工资", d.categoryName)
        assertEquals(12000.0, d.amount!!, 1e-9)
    }

    @Test
    fun `上午瑞幸 18_5 - 金额带小数`() {
        val d = ok("""{"amount":18.5,"type":"expense","category":"餐饮","time":"09:00","date":"2026-09-07"}""")
        assertEquals(18.5, d.amount!!, 1e-9)
        assertEquals(9 to 0, hourMinute(d.transactionTime!!))
    }

    // ═══════════════════════════════════════════════════
    // 分类映射（最容易错的地方）
    // ═══════════════════════════════════════════════════

    @Test
    fun `分类名在两种 type 下重名时必须按 type 定位`() {
        // 默认数据里「其他」在 expense(id=10) 和 income(id=20) 下各有一条。
        // 只按 name 查会命中错误的一条，把收入记到支出分类上。
        val expenseOther = ok("""{"amount":10,"type":"expense","category":"其他"}""")
        assertEquals(10L, expenseOther.categoryId)

        val incomeOther = ok("""{"amount":10,"type":"income","category":"其他"}""")
        assertEquals(20L, incomeOther.categoryId)
    }

    @Test
    fun `AI 返回支出分类名但 type 是 income 时不得跨类命中`() {
        // 「餐饮」只存在于 expense。type=income 时不该回落到 expense 的餐饮
        val d = ok("""{"amount":10,"type":"income","category":"餐饮"}""")
        assertNull("跨 type 命中会记出错账", d.categoryId)
        assertTrue(d.missingFields.contains(TransactionDraft.FIELD_CATEGORY))
    }

    @Test
    fun `AI 自造分类时留空并记为未识别`() {
        val d = ok("""{"amount":10,"type":"expense","category":"宠物"}""")
        assertNull(d.categoryId)
        assertNull(d.categoryName)
        assertTrue(d.missingFields.contains(TransactionDraft.FIELD_CATEGORY))
        assertTrue("缺分类仍然可用，用户手选即可", d.isUsable)
    }

    @Test
    fun `分类为 null 时记为未识别`() {
        val d = ok("""{"amount":10,"type":"expense","category":null}""")
        assertNull(d.categoryId)
        assertTrue(d.missingFields.contains(TransactionDraft.FIELD_CATEGORY))
    }

    @Test
    fun `本地分类表为空时不崩`() {
        val r = AiTransactionParser.parse(
            """{"amount":10,"type":"expense","category":"餐饮"}""",
            emptyList(), now, sh, PaymentMethods.PRESETS
        )
        val d = (r as AiTransactionParser.Result.Ok).draft
        assertNull(d.categoryId)
        assertTrue(d.isUsable)
    }

    // ═══════════════════════════════════════════════════
    // type 归一
    // ═══════════════════════════════════════════════════

    @Test
    fun `type 大写时归一到小写`() {
        // 实测模型经常无视 Prompt 返回大写；不归一就会写出脏数据，
        // 这笔账之后会被所有 `type = 'expense'` 的查询永久漏掉
        assertEquals("expense", ok("""{"amount":10,"type":"EXPENSE"}""").type)
        assertEquals("income", ok("""{"amount":10,"type":"INCOME"}""").type)
        assertEquals("expense", ok("""{"amount":10,"type":"Expense"}""").type)
    }

    @Test
    fun `type 非法时回落 expense 并记为未识别`() {
        val d = ok("""{"amount":10,"type":"TRANSFER"}""")
        assertEquals("expense", d.type)
        assertTrue(d.missingFields.contains(TransactionDraft.FIELD_TYPE))
    }

    @Test
    fun `type 缺失时回落 expense 并记为未识别`() {
        val d = ok("""{"amount":10,"category":"餐饮"}""")
        assertEquals("expense", d.type)
        assertEquals(1L, d.categoryId)   // 回落的 type 仍要参与分类匹配
        assertTrue(d.missingFields.contains(TransactionDraft.FIELD_TYPE))
    }

    // ═══════════════════════════════════════════════════
    // 渠道
    // ═══════════════════════════════════════════════════

    @Test
    fun `渠道必须在允许列表内`() {
        PaymentMethods.PRESETS.forEach { ch ->
            assertEquals(ch, ok("""{"amount":10,"channel":"$ch"}""").channel)
        }
    }

    @Test
    fun `AI 返回不在列表里的渠道时留空`() {
        // Prompt 里给的是 7 个预设，模型可能返回「其他」「银行卡片」之类
        val d = ok("""{"amount":10,"channel":"其他"}""")
        assertNull(d.channel)
        assertTrue(d.missingFields.contains(TransactionDraft.FIELD_CHANNEL))
    }

    @Test
    fun `渠道带空白时被清理后仍能命中`() {
        assertEquals("微信", ok("""{"amount":10,"channel":"  微信  "}""").channel)
    }

    // ═══════════════════════════════════════════════════
    // 金额
    // ═══════════════════════════════════════════════════

    @Test
    fun `金额可为整数小数或字符串`() {
        assertEquals(32.0, ok("""{"amount":32}""").amount!!, 1e-9)
        assertEquals(32.0, ok("""{"amount":32.0}""").amount!!, 1e-9)
        assertEquals(32.5, ok("""{"amount":32.5}""").amount!!, 1e-9)
        assertEquals(32.0, ok("""{"amount":"32"}""").amount!!, 1e-9)
        assertEquals(32.55, ok("""{"amount":"32.55"}""").amount!!, 1e-9)
    }

    @Test
    fun `金额字符串带货币符与千分位也能解析`() {
        assertEquals(32.0, ok("""{"amount":"¥32"}""").amount!!, 1e-9)
        assertEquals(32.0, ok("""{"amount":"￥32"}""").amount!!, 1e-9)
        assertEquals(1234.56, ok("""{"amount":"1,234.56"}""").amount!!, 1e-9)
        assertEquals(32.0, ok("""{"amount":"32元"}""").amount!!, 1e-9)
        assertEquals(32.0, ok("""{"amount":" 32 "}""").amount!!, 1e-9)
    }

    @Test
    fun `金额超过两位小数被归一`() {
        assertEquals(32.0, ok("""{"amount":32.004}""").amount!!, 1e-9)
        assertEquals(32.01, ok("""{"amount":32.005}""").amount!!, 1e-9)
        assertEquals(32.3, ok("""{"amount":32.299}""").amount!!, 1e-9)
    }

    @Test
    fun `金额为 0 负数 或超上限时判为 NoAmount`() {
        listOf(
            """{"amount":0}""",
            """{"amount":-32}""",
            """{"amount":0.001}""",          // 四舍五入后是 0
            """{"amount":1000001}""",         // 超过 100 万上限
            """{"amount":"abc"}""",
            """{"amount":null}""",
            """{"type":"expense","category":"餐饮"}"""   // 干脆没有 amount
        ).forEach { raw ->
            val r = parse(raw)
            assertEquals("应判为 NoAmount：$raw，实际=$r", AiTransactionParser.Result.NoAmount, r)
        }
    }

    @Test
    fun `金额刚好在上限内可接受`() {
        assertEquals(1_000_000.0, ok("""{"amount":1000000}""").amount!!, 1e-9)
    }

    // ═══════════════════════════════════════════════════
    // 日期与时刻
    // ═══════════════════════════════════════════════════

    @Test
    fun `相对日期换算 - 昨天前天`() {
        assertEquals("2026-09-06", ymd(ok("""{"amount":1,"date":"2026-09-06"}""").transactionTime!!))
        assertEquals("2026-09-05", ymd(ok("""{"amount":1,"date":"2026-09-05"}""").transactionTime!!))
    }

    @Test
    fun `未来日期被钳到今天`() {
        // 用户说「明天预付房租」，记账 App 不该记一笔未来的账：
        // 那会污染「今日支出」与预算进度
        val d = ok("""{"amount":3000,"date":"2026-09-20"}""")
        assertEquals("2026-09-07", ymd(d.transactionTime!!))
    }

    @Test
    fun `跨年未来日期同样被钳制`() {
        assertEquals("2026-09-07", ymd(ok("""{"amount":1,"date":"2027-01-01"}""").transactionTime!!))
    }

    @Test
    fun `过于久远的日期视为解析错误回落今天`() {
        assertEquals("2026-09-07", ymd(ok("""{"amount":1,"date":"1990-05-05"}""").transactionTime!!))
    }

    @Test
    fun `非法日期格式回落今天并记为未识别`() {
        val d = ok("""{"amount":1,"date":"昨天"}""")
        assertEquals("2026-09-07", ymd(d.transactionTime!!))
        assertTrue(d.missingFields.contains(TransactionDraft.FIELD_DATE))
    }

    @Test
    fun `不存在的日期不得被 SimpleDateFormat 悄悄滚动`() {
        // isLenient=false 的意义：2026-02-31 不能滚成 3 月 3 日
        val d = ok("""{"amount":1,"date":"2026-02-31"}""")
        assertEquals("2026-09-07", ymd(d.transactionTime!!))
        assertTrue(d.missingFields.contains(TransactionDraft.FIELD_DATE))
    }

    @Test
    fun `日期缺失时回落今天且**不算**未识别`() {
        val d = ok("""{"amount":1}""")
        assertEquals("2026-09-07", ymd(d.transactionTime!!))
        // 用户说「麦当劳28元」本来就不会提日期，回落今天是唯一合理默认。
        // 此时提示「未识别：日期」只会变成噪声，让用户以为要手工补一个本来就对的值。
        assertFalse(
            "默认今天不该报未识别，实际=${d.missingFields}",
            d.missingFields.contains(TransactionDraft.FIELD_DATE)
        )
    }

    @Test
    fun `未来日期钳制后也不报未识别（我们看懂了，只是不接受未来值）`() {
        val d = ok("""{"amount":3000,"date":"2026-09-20"}""")
        assertEquals("2026-09-07", ymd(d.transactionTime!!))
        assertFalse(d.missingFields.contains(TransactionDraft.FIELD_DATE))
    }

    @Test
    fun `过去日期无时刻时落到正午而不是 00-00`() {
        // 关键：00:00 会落进「夜间 22-06」桶，
        // 让一笔白天补记的账在 AI 报告里变成「凌晨消费」，直接扭曲行为画像
        val d = ok("""{"amount":1,"date":"2026-09-01"}""")
        assertEquals(12 to 0, hourMinute(d.transactionTime!!))
        assertFalse(hourMinute(d.transactionTime!!).first >= 22)
    }

    @Test
    fun `今天无时刻时沿用当前时刻`() {
        val d = ok("""{"amount":1,"date":"2026-09-07"}""")
        assertEquals(14 to 30, hourMinute(d.transactionTime!!))
    }

    @Test
    fun `时刻解析 - 常规与全角冒号`() {
        assertEquals(9 to 0, hourMinute(ok("""{"amount":1,"date":"2026-09-07","time":"09:00"}""").transactionTime!!))
        assertEquals(23 to 30, hourMinute(ok("""{"amount":1,"date":"2026-09-07","time":"23:30"}""").transactionTime!!))
        assertEquals(0 to 5, hourMinute(ok("""{"amount":1,"date":"2026-09-07","time":"00:05"}""").transactionTime!!))
        // 中文输入法下模型很容易返回全角冒号
        assertEquals(20 to 0, hourMinute(ok("""{"amount":1,"date":"2026-09-06","time":"20：00"}""").transactionTime!!))
    }

    @Test
    fun `非法时刻被忽略并走兜底`() {
        // 25 点不存在
        val d1 = ok("""{"amount":1,"date":"2026-09-06","time":"25:00"}""")
        assertEquals(12 to 0, hourMinute(d1.transactionTime!!))
        // 70 分不存在
        val d2 = ok("""{"amount":1,"date":"2026-09-06","time":"10:70"}""")
        assertEquals(12 to 0, hourMinute(d2.transactionTime!!))
        // 完全不是时间
        val d3 = ok("""{"amount":1,"date":"2026-09-06","time":"昨晚"}""")
        assertEquals(12 to 0, hourMinute(d3.transactionTime!!))
    }

    @Test
    fun `夜间消费时刻能正确落进 22-06 桶`() {
        val d = ok("""{"amount":68,"date":"2026-09-06","time":"23:15"}""")
        val (h, _) = hourMinute(d.transactionTime!!)
        assertEquals(23, h)
        assertEquals(
            com.smartledger.data.analytics.model.TimeBucket.NIGHT,
            com.smartledger.data.analytics.model.TimeBucket.ofHour(h)
        )
    }

    // ═══════════════════════════════════════════════════
    // 文本清洗
    // ═══════════════════════════════════════════════════

    @Test
    fun `商户名超长被截断`() {
        val long = "这".repeat(80)
        val d = ok("""{"amount":1,"merchant":"$long"}""")
        assertEquals(30, d.merchant!!.length)
    }

    @Test
    fun `备注超长被截断`() {
        val long = "备注".repeat(60)
        val d = ok("""{"amount":1,"note":"$long"}""")
        assertEquals(50, d.note!!.length)
    }

    @Test
    fun `商户名里的换行被压成空格`() {
        // 商户名会显示在单行列表项里，带换行会把行高撑乱
        val d = ok("""{"amount":1,"merchant":"瑞幸\n咖啡"}""")
        assertNotNull(d.merchant)
        assertFalse(d.merchant!!.contains("\n"))
        assertEquals("瑞幸 咖啡", d.merchant)
    }

    @Test
    fun `纯标点商户名被丢弃`() {
        assertNull(ok("""{"amount":1,"merchant":"-"}""").merchant)
        assertNull(ok("""{"amount":1,"merchant":"..."}""").merchant)
        assertNull(ok("""{"amount":1,"merchant":"★★"}""").merchant)
    }

    @Test
    fun `中文词汇商户名不会被误删`() {
        // 「无」是合法字符，不能当成空值丢弃；
        // 只有全部由标点/符号组成时才丢
        assertEquals("无", ok("""{"amount":1,"merchant":"无"}""").merchant)
        assertEquals("无印良品", ok("""{"amount":1,"merchant":"无印良品"}""").merchant)
    }

    @Test
    fun `空串与空白商户名归为 null`() {
        assertNull(ok("""{"amount":1,"merchant":""}""").merchant)
        assertNull(ok("""{"amount":1,"merchant":"   "}""").merchant)
    }

    // ═══════════════════════════════════════════════════
    // JSON 容错
    // ═══════════════════════════════════════════════════

    @Test
    fun `带代码围栏的输出能解析`() {
        val d = ok("```json\n{\"amount\":32,\"category\":\"餐饮\"}\n```")
        assertEquals(32.0, d.amount!!, 1e-9)
    }

    @Test
    fun `前后带解释文字的输出能解析`() {
        val d = ok("好的，解析结果如下：\n{\"amount\":32,\"category\":\"餐饮\"}\n希望有帮助！")
        assertEquals(32.0, d.amount!!, 1e-9)
    }

    @Test
    fun `完全不是 JSON 时返回 NotJson`() {
        listOf(
            null,
            "",
            "   ",
            "抱歉，我无法解析这句话。",
            "{\"amount\":32",              // 被 max_tokens 截断
            "[1,2,3]",                     // 是 JSON 但不是对象
            "<html>500</html>"
        ).forEach { raw ->
            assertEquals("应判为 NotJson：$raw", AiTransactionParser.Result.NotJson, parse(raw))
        }
    }

    @Test
    fun `org-json 的空值语义被正确区分`() {
        // optString 在键不存在时返回空串、在 JSON null 时返回 "null" 字符串，
        // 直接用会把「没给字段」和「给了 null」混为一谈
        val missing = ok("""{"amount":10}""")
        val explicitNull = ok("""{"amount":10,"merchant":null}""")
        val nullString = ok("""{"amount":10,"merchant":"null"}""")
        assertNull(missing.merchant)
        assertNull(explicitNull.merchant)
        assertNull("字符串 \"null\" 也应视为无值", nullString.merchant)
    }

    @Test
    fun `多余字段不影响解析`() {
        val d = ok("""{"amount":10,"category":"餐饮","confidence":0.9,"extra":{"a":1}}""")
        assertEquals(10.0, d.amount!!, 1e-9)
        assertEquals(1L, d.categoryId)
    }

    // ═══════════════════════════════════════════════════
    // missingFields 语义
    // ═══════════════════════════════════════════════════

    @Test
    fun `缺多个字段时全部列出且不重复`() {
        val d = ok("""{"amount":10}""")
        assertTrue(d.missingFields.contains(TransactionDraft.FIELD_CATEGORY))
        assertTrue(d.missingFields.contains(TransactionDraft.FIELD_CHANNEL))
        assertEquals(d.missingFields.size, d.missingFields.distinct().size)
    }

    @Test
    fun `字段齐全时 missingFields 为空`() {
        val d = ok(
            """{"amount":32,"type":"expense","category":"餐饮","channel":"微信",
              |"merchant":"老乡鸡","date":"2026-09-07","time":"12:00"}""".trimMargin()
        )
        assertTrue("实际=${d.missingFields}", d.isComplete)
    }

    @Test
    fun `解析器绝不直接产出 Transaction 实体`() {
        // 设计红线：AI 只回填表单，不入库。
        // 用反射确认 TransactionDraft 里没有任何 id / source / notificationKey 字段，
        // 也就是它根本无法被当成一条已存在的账单写库。
        val fields = TransactionDraft::class.java.declaredFields.map { it.name }
        assertFalse("草稿不应带数据库主键", fields.contains("id"))
        assertFalse("草稿不应带来源标记", fields.contains("source"))
        assertFalse("草稿不应带通知去重键", fields.contains("notificationKey"))
    }
}
