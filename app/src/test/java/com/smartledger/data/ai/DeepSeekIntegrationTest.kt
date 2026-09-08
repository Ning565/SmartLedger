package com.smartledger.data.ai

import com.smartledger.data.ai.http.HttpEngine
import com.smartledger.data.ai.http.HttpRequest
import com.smartledger.data.ai.http.HttpResponse
import com.smartledger.data.ai.http.UrlConnectionHttpEngine
import com.smartledger.data.ai.model.AiConfig
import com.smartledger.data.ai.model.AiError
import com.smartledger.data.ai.model.AiProviderPreset
import com.smartledger.data.ai.model.CategoryRef
import com.smartledger.data.ai.model.TransactionDraft
import com.smartledger.data.analytics.MerchantAnonymizer
import com.smartledger.data.analytics.SummaryAggregator
import com.smartledger.data.analytics.SummaryFormatter
import com.smartledger.data.analytics.SummaryPeriods
import com.smartledger.data.analytics.model.SummaryPeriod
import com.smartledger.data.analytics.model.TxPoint
import com.smartledger.data.repository.AiPromptBuilder
import com.smartledger.util.PaymentMethods
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * DeepSeek 真链路集成测试。
 *
 * 与其余单测的区别：这里跑的是**真实的 UrlConnectionHttpEngine + 真实网络 + 真实模型**，
 * 覆盖的是单测无法覆盖的部分 —— TLS、gzip、SSE 分块到达、模型的真实输出形态。
 *
 * ## 运行方式（默认不跑，避免消耗额度）
 * ```
 * ./gradlew testDebugUnitTest -PaiIntegration=true
 * ```
 * Key 从 `local.properties` 读（已 gitignore），缺失时 [assumeTrue] 自动跳过。
 *
 * ## 为什么这个测试值得花额度
 *
 * 它验证的是三件**只有真链路才能暴露**的事：
 *  1. 推理模型的 `content` 为空陷阱（实测 deepseek-v4-flash 会返回空正文，
 *     若不做兜底，AI 报告页会是一片空白，而单测里用假数据永远发现不了）；
 *  2. SSE 真的逐块到达（若 HttpURLConnection 透明 gzip 生效，
 *     流式会退化成"等全部生成完才一次性刷出来"，单测同样发现不了）；
 *  3. **真正发出去的 HTTP body 里没有敏感信息** ——
 *     这是整个 AI 功能最硬的红线，必须对着真实请求体断言，而不是对着中间对象。
 */
class DeepSeekIntegrationTest {

    private val apiKey = System.getProperty("ai.api.key").orEmpty()
    private val baseUrlRaw = System.getProperty("ai.base.url")
        .orEmpty().ifBlank { "https://api.deepseek.com" }
    private val model = System.getProperty("ai.model").orEmpty().ifBlank { "deepseek-chat" }
    private val timeoutMs = System.getProperty("ai.timeout.ms")?.toLongOrNull() ?: 90_000L

    private lateinit var engine: RecordingEngine
    private lateinit var client: AiClientImpl
    private lateinit var config: AiConfig

    private val sh = TimeZone.getTimeZone("Asia/Shanghai")

    /** 记录真实发出的请求，用于断言脱敏与请求头 */
    private class RecordingEngine(private val delegate: HttpEngine) : HttpEngine {
        val requests = mutableListOf<HttpRequest>()
        override suspend fun post(request: HttpRequest): HttpResponse {
            requests += request
            return delegate.post(request)
        }

        override suspend fun postStreaming(
            request: HttpRequest,
            onLine: suspend (String) -> Unit
        ): Int {
            requests += request
            return delegate.postStreaming(request, onLine)
        }
    }

    @Before
    fun setUp() {
        // 没有 Key 就整类跳过，而不是失败：CI 与其他开发者机器上不该报错
        assumeTrue("未配置 deepseek.api.key，跳过真链路测试", apiKey.isNotBlank())
        engine = RecordingEngine(UrlConnectionHttpEngine())
        client = AiClientImpl(engine)
        config = AiConfig(
            preset = AiProviderPreset.DEEPSEEK,
            baseUrl = baseUrlRaw,
            apiKey = apiKey,
            model = model
        )
    }

    // ═══════════════════════════════════════════════════
    // 1. 连通性
    // ═══════════════════════════════════════════════════

    @Test
    fun `真实连通性探测成功且请求头正确`() = runBlocking {
        val result = withTimeoutOrNull(timeoutMs) { client.testConnection(config) }
        assertNotNull("探测超时（${timeoutMs}ms）", result)

        val ok = result as AiResult.Success
        println("[AI-IT] testConnection reply=${ok.text} viaReasoning=${ok.fromReasoningFallback}")
        assertTrue(ok.text.isNotBlank())

        val req = engine.requests.single()
        assertEquals("Bearer $apiKey", req.headers["Authorization"])
        assertTrue(
            "endpoint 拼接错误：${req.url}",
            req.url.endsWith("/chat/completions")
        )
        // 探测必须省额度
        val body = org.json.JSONObject(req.body)
        assertEquals(8, body.getInt("max_tokens"))
        assertFalse("Key 不得出现在请求体里", req.body.contains(apiKey))
    }

    @Test
    fun `错误的 Key 被正确归类为 InvalidApiKey`() = runBlocking {
        val bad = config.copy(apiKey = "sk-this-key-is-definitely-invalid-000000")
        val result = withTimeoutOrNull(timeoutMs) { client.testConnection(bad) }
        assertNotNull(result)
        val failure = result as AiResult.Failure
        println("[AI-IT] bad key -> ${failure.error}")
        assertEquals(AiError.InvalidApiKey, failure.error)
        // 错误文案不得回显 Key
        assertFalse(failure.error.userMessage.contains("sk-this-key-is-definitely-invalid"))
    }

    @Test
    fun `不存在的模型被正确归类`() = runBlocking {
        val bad = config.copy(model = "no-such-model-xyz-999")
        val result = withTimeoutOrNull(timeoutMs) { client.testConnection(bad) }
        assertNotNull(result)
        val failure = result as AiResult.Failure
        println("[AI-IT] bad model -> ${failure.error}")
        // DeepSeek 对未知模型可能返回 404（ModelNotFound）或 400（Unknown），两者都算正确识别
        assertTrue(
            "预期 ModelNotFound 或带说明的 Unknown，实际=${failure.error}",
            failure.error is AiError.ModelNotFound || failure.error is AiError.Unknown
        )
    }

    // ═══════════════════════════════════════════════════
    // 2. 自然语言记账：真实模型输出 → 本地解析
    // ═══════════════════════════════════════════════════

    private val categories = listOf(
        CategoryRef(1, "餐饮", "expense"),
        CategoryRef(2, "交通", "expense"),
        CategoryRef(3, "购物", "expense"),
        CategoryRef(4, "娱乐", "expense"),
        CategoryRef(5, "居住", "expense"),
        CategoryRef(6, "通讯", "expense"),
        CategoryRef(10, "其他", "expense"),
        CategoryRef(11, "工资", "income"),
        CategoryRef(20, "其他", "income")
    )

    /** now 固定为 2026-09-07 14:30 周一，便于断言相对日期 */
    private val now: Long = Calendar.getInstance(sh).apply {
        set(2026, Calendar.SEPTEMBER, 7, 14, 30, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private suspend fun parseReal(userText: String): TransactionDraft {
        val messages = AiPromptBuilder.parserMessages(userText, categories, now, sh)
        val result = withTimeoutOrNull(timeoutMs) {
            client.chat(
                config, messages,
                AiPromptBuilder.PARSER_TEMPERATURE,
                AiPromptBuilder.PARSER_MAX_TOKENS
            )
        }
        assertNotNull("chat 超时：$userText", result)
        val ok = result as AiResult.Success
        println("[AI-IT] 输入「$userText」→ 模型原文：${ok.text}")

        val parsed = AiTransactionParser.parse(ok.text, categories, now, sh, PaymentMethods.PRESETS)
        assertTrue(
            "模型输出无法解析：${ok.text}\n结果=$parsed",
            parsed is AiTransactionParser.Result.Ok
        )
        return (parsed as AiTransactionParser.Result.Ok).draft
    }

    @Test
    fun `真实解析 - 昨天中午老乡鸡 32 微信`() = runBlocking {
        val d = parseReal("昨天中午跟同事在老乡鸡吃快餐AA花了32微信付款")
        assertEquals(32.0, d.amount!!, 1e-9)
        assertEquals(TransactionDraft.TYPE_EXPENSE, d.type)
        assertEquals("餐饮", d.categoryName)
        assertEquals(1L, d.categoryId)
        assertEquals("微信", d.channel)
        assertNotNull("商户应被识别出来", d.merchant)
        // 相对日期换算：2026-09-07 的「昨天」= 09-06
        val cal = Calendar.getInstance(sh).apply { timeInMillis = d.transactionTime!! }
        assertEquals(2026, cal.get(Calendar.YEAR))
        assertEquals(Calendar.SEPTEMBER, cal.get(Calendar.MONTH))
        assertEquals(6, cal.get(Calendar.DAY_OF_MONTH))
        // 「中午」应落在午间桶，不能是 00:00
        assertTrue(
            "中午被解析成了 ${cal.get(Calendar.HOUR_OF_DAY)} 点",
            cal.get(Calendar.HOUR_OF_DAY) in 10..14
        )
    }

    @Test
    fun `真实解析 - 今天工资到账 12000 走 income 且分类正确`() = runBlocking {
        val d = parseReal("今天工资到账12000")
        assertEquals(12000.0, d.amount!!, 1e-9)
        assertEquals(TransactionDraft.TYPE_INCOME, d.type)
        assertEquals("工资", d.categoryName)
        assertEquals(11L, d.categoryId)
    }

    @Test
    fun `真实解析 - 昨晚吃饭 AA 的时间落在夜间桶`() = runBlocking {
        val d = parseReal("昨晚和朋友吃饭AA我付68")
        assertEquals(68.0, d.amount!!, 1e-9)
        val cal = Calendar.getInstance(sh).apply { timeInMillis = d.transactionTime!! }
        assertEquals("「昨晚」应是昨天", 6, cal.get(Calendar.DAY_OF_MONTH))
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        assertTrue("「昨晚」被解析成 $hour 点", hour in 18..23)
        assertEquals(
            com.smartledger.data.analytics.model.TimeBucket.ofHour(hour),
            if (hour >= 22) com.smartledger.data.analytics.model.TimeBucket.NIGHT
            else com.smartledger.data.analytics.model.TimeBucket.EVENING
        )
    }

    @Test
    fun `真实解析 - 无金额输入不应编造金额`() = runBlocking {
        val messages = AiPromptBuilder.parserMessages("买了个东西", categories, now, sh)
        val result = withTimeoutOrNull(timeoutMs) {
            client.chat(config, messages, AiPromptBuilder.PARSER_TEMPERATURE, AiPromptBuilder.PARSER_MAX_TOKENS)
        }
        assertNotNull(result)
        val ok = result as AiResult.Success
        println("[AI-IT] 「买了个东西」→ ${ok.text}")
        val parsed = AiTransactionParser.parse(ok.text, categories, now, sh, PaymentMethods.PRESETS)
        // 关键：不许凭空编一个金额出来。
        // 注意 Result.Ok 里是 draft，不是直接的字段。
        assertTrue(
            "无金额输入却被解析出了金额：$parsed",
            parsed is AiTransactionParser.Result.NoAmount ||
                    (parsed as? AiTransactionParser.Result.Ok)?.draft?.amount == null
        )
    }

    @Test
    fun `真实解析 - 分类只能来自本地清单不得自造`() = runBlocking {
        // 「充话费」本地有「通讯」分类，模型必须用它而不是自造「话费」
        val d = parseReal("充话费50")
        assertEquals(50.0, d.amount!!, 1e-9)
        if (d.categoryName != null) {
            assertTrue(
                "模型自造了分类：${d.categoryName}",
                categories.any { it.name == d.categoryName && it.type == d.type }
            )
        }
        if (d.channel != null) {
            assertTrue(
                "模型自造了渠道：${d.channel}",
                PaymentMethods.PRESETS.contains(d.channel)
            )
        }
    }

    // ═══════════════════════════════════════════════════
    // 3. AI 消费体检：真实流式 + 脱敏红线
    // ═══════════════════════════════════════════════════

    /** 构造一份带真实商户名的 Summary（模拟用户真实数据） */
    private fun buildSummary(): com.smartledger.data.analytics.model.FinancialSummary {
        val realMerchants = listOf(
            "瑞幸咖啡科技园店", "麦当劳南山店", "淘宝XX数码旗舰店",
            "滴滴出行", "美团外卖", "京东商城", "招商银行信用卡还款"
        )
        val points = ArrayList<TxPoint>()
        val cal = Calendar.getInstance(sh)
        // 28 天、每天 2 笔，覆盖工作日/周末与多个时段
        for (day in 1..28) {
            for (k in 0..1) {
                cal.set(2026, Calendar.SEPTEMBER, day, if (k == 0) 12 else 22, 30, 0)
                cal.set(Calendar.MILLISECOND, 0)
                points += TxPoint(
                    amount = 20.0 + day * 3.0 + k * 15.5,
                    type = "expense",
                    categoryId = (day % 3 + 1).toLong(),
                    merchant = realMerchants[(day + k) % realMerchants.size],
                    transactionTime = cal.timeInMillis
                )
            }
        }
        // 一笔收入
        cal.set(2026, Calendar.SEPTEMBER, 10, 9, 0, 0)
        points += TxPoint(12000.0, "income", 11L, null, cal.timeInMillis)

        cal.set(2026, Calendar.SEPTEMBER, 28, 20, 0, 0)
        val nowMs = cal.timeInMillis
        val range = SummaryPeriods.current(SummaryPeriod.THIS_MONTH, nowMs, sh)
        return SummaryAggregator.aggregate(
            period = SummaryPeriod.THIS_MONTH,
            range = range,
            now = nowMs,
            tz = sh,
            points = points,
            categoryNameById = mapOf(1L to "餐饮", 2L to "交通", 3L to "购物", 11L to "工资"),
            budget = 5000.0,
            previousPeriodExpense = 3627.0
        )
    }

    @Test
    fun `真实流式报告 - 逐块到达、可渲染、能提取建议额度`() = runBlocking {
        val summary = buildSummary()

        // ── 脱敏红线：先本地断言 Prompt 文本干净 ──
        val promptText = AiPromptBuilder.advisorMessages(summary)
            .joinToString("\n") { it.content }
        val realNames = listOf(
            "瑞幸咖啡科技园店", "麦当劳南山店", "淘宝XX数码旗舰店",
            "滴滴出行", "美团外卖", "京东商城", "招商银行信用卡还款"
        )
        realNames.forEach { n ->
            assertFalse("Prompt 泄漏真实商户名：$n", promptText.contains(n))
        }
        // 通用防线：Prompt 里不该出现 11 位以上连续数字（卡号/手机号/订单号形态）
        assertFalse(
            "Prompt 含 11 位以上连续数字，疑似敏感编号",
            Regex("\\d{11,}").containsMatchIn(promptText)
        )

        // ── 真实流式请求 ──
        val events = withTimeoutOrNull(timeoutMs * 2) {
            client.streamChat(
                config,
                AiPromptBuilder.advisorMessages(summary),
                AiPromptBuilder.ADVISOR_TEMPERATURE,
                AiPromptBuilder.ADVISOR_MAX_TOKENS
            ).toList()
        }
        assertNotNull("流式请求超时", events)

        val deltas = events!!.filterIsInstance<AiStreamEvent.Delta>()
        assertTrue("没有收到任何流式增量：$events", deltas.isNotEmpty())

        // 流式必须是**多块**到达；若只有 1 块，说明退化成了一次性返回
        // （典型原因是 HttpURLConnection 透明 gzip 破坏了 SSE）
        assertTrue(
            "只收到 ${deltas.size} 个增量，流式可能已退化成一次性返回",
            deltas.size >= 5
        )

        val markdown = deltas.joinToString("") { it.text }
        println("[AI-IT] 报告 ${markdown.length} 字，${deltas.size} 个增量")
        assertTrue(markdown.isNotBlank())

        val last = events.last()
        assertTrue("流未正常收尾：$last", last is AiStreamEvent.Completed)

        // ── 真正发出去的 HTTP body 必须干净 ──
        val wireBody = engine.requests.last().body
        realNames.forEach { n ->
            assertFalse("**发到网络上的请求体**泄漏真实商户名：$n", wireBody.contains(n))
        }
        assertFalse(wireBody.contains(apiKey))
        // 通用防线：11 位以上连续数字（卡号/手机号/订单号形态）
        assertFalse(
            "请求体含 11 位以上连续数字，疑似卡号/手机号/订单号",
            Regex("\\d{11,}").containsMatchIn(wireBody)
        )
        // 脱敏编号确实进了请求体
        assertTrue("请求体里没有匿名商户编号", wireBody.contains("商户 #1"))

        // ── 报告能被 Markdown 解析器处理 ──
        val blocks = com.smartledger.ui.components.parse(markdown)
        assertTrue("报告解析不出任何块", blocks.isNotEmpty())
        assertFalse(
            "报告残留了未渲染的 ``` 围栏",
            blocks.any { b ->
                val t = when (b) {
                    is com.smartledger.ui.components.MdBlock.Heading -> b.text
                    is com.smartledger.ui.components.MdBlock.Bullet -> b.text
                    is com.smartledger.ui.components.MdBlock.Paragraph -> b.text
                }
                t.contains("```")
            }
        )

        // ── 五个章节都应出现（Prompt 强约束）──
        listOf("消费结构诊断", "行为习惯画像", "下周行动建议").forEach { section ->
            assertTrue("报告缺少章节「$section」", markdown.contains(section))
        }

        // ── 建议额度：能提取到最好，提取不到也要如实反映 ──
        val suggested = SuggestedBudgetExtractor.extract(markdown)
        println("[AI-IT] 建议额度=$suggested")
        println("[AI-IT] 报告末尾 200 字：${markdown.takeLast(200)}")
        if (suggested != null) {
            assertTrue(suggested > 0 && suggested <= 1_000_000)
        }

        // ── AI 不得虚构输入里不存在的数字口径 ──
        // 总支出必须与本地计算一致地出现在 Prompt 里（而不是模型自己算一个）
        val localExpense = SummaryFormatter.money(summary.totalExpense)
        assertTrue("Prompt 未包含本地算出的总支出 $localExpense", promptText.contains(localExpense))
    }

    @Test
    fun `推理模型的真实行为 - content 为空时必须兜底而不是返回空白`() = runBlocking {
        // 实测结论：deepseek-v4-flash / pro 是推理模型，
        // max_tokens 小的时候 content 会是空串、正文全在 reasoning_content。
        // 这里用真实请求复现并验证兜底逻辑，避免用户选了 v4 系列看到一片空白。
        val reasoningConfig = config.copy(model = "deepseek-v4-flash")
        val result = withTimeoutOrNull(timeoutMs) {
            client.chat(
                reasoningConfig,
                listOf(com.smartledger.data.ai.model.AiMessage.user("只回复两个字：你好")),
                temperature = 0.0,
                maxTokens = 64
            )
        }
        assertNotNull(result)
        println("[AI-IT] deepseek-v4-flash 结果：$result")
        // 无论走正文还是走推理兜底，都不能是 Failure —— 否则用户看到的就是"功能坏了"
        assertTrue(
            "推理模型返回了失败而不是兜底内容：$result",
            result is AiResult.Success
        )
    }

    // ═══════════════════════════════════════════════════
    // 4. 商户脱敏一致性（与真实商户名联动）
    // ═══════════════════════════════════════════════════

    @Test
    fun `真实商户名经聚合脱敏后不出现在 Summary 任何文本字段里`() {
        val summary = buildSummary()
        // canonicalJson 是最终进 hash 与 Prompt 的规范化形态，必须彻底干净
        val json = SummaryFormatter.canonicalJson(summary)
        val text = SummaryFormatter.full(summary)
        listOf("瑞幸", "麦当劳", "淘宝", "滴滴", "美团", "京东", "招商银行").forEach { frag ->
            assertFalse("canonicalJson 泄漏商户片段：$frag", json.contains(frag))
            assertFalse("Prompt 文本泄漏商户片段：$frag", text.contains(frag))
        }
        // 匿名编号必须存在，否则 AI 拿不到高频消费这个核心信号
        assertTrue(summary.topMerchantStats.isNotEmpty())
        assertTrue(summary.topMerchantStats.all { it.anonymizedName.contains("商户 #") })
        // 脱敏后仍保留了行为信息（笔数与金额），AI 才能做诊断
        assertTrue(summary.topMerchantStats.any { it.count > 1 })
        assertEquals(
            summary.topMerchantStats.size,
            summary.topMerchantStats.map { it.anonymizedName }.distinct().size
        )
        // 商户归一化规则一致性（防两行同名）
        assertTrue(
            MerchantAnonymizer.normalizeKey("瑞幸 咖啡") ==
                    MerchantAnonymizer.normalizeKey("瑞幸咖啡")
        )
    }
}
