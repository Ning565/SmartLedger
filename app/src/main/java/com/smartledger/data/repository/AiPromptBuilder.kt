package com.smartledger.data.repository

import com.smartledger.data.ai.model.AiMessage
import com.smartledger.data.analytics.SummaryFormatter
import com.smartledger.data.analytics.model.FinancialSummary
import com.smartledger.data.analytics.model.SummaryPeriod
import com.smartledger.data.ai.model.CategoryRef
import com.smartledger.util.PaymentMethods
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Prompt 构造（纯函数，可单测）。
 *
 * 全部 AI 文案集中在这里，不散落在 ViewModel 里，理由：
 *  1. Prompt 是需要反复调优的资产，集中才能迭代；
 *  2. 可以被单测断言「Prompt 里绝不含真实商户名 / note / 通知原文」——
 *     这是整个 AI 功能最硬的一条红线，必须自动化守住，不能靠 Review 自觉。
 */
object AiPromptBuilder {

    // ═══════════════════════════════════════════════════
    // 消费体检
    // ═══════════════════════════════════════════════════

    /**
     * system 消息。
     *
     * 排版约束写得很死（只允许 ##、-、**），因为渲染用的是自研的
     * 极简 MarkdownText：不支持表格、代码块、图片、链接。
     * 让模型输出这些语法只会得到一坨原文，不如一开始就不让它写。
     */
    private const val ADVISOR_SYSTEM = """你是一位精通个人财务规划与行为心理学的专属财务顾问，服务于一款名为 SmartLedger 的个人记账应用。

工作原则：
1. 你收到的所有数字都已由客户端本地计算完成，必须直接引用，不得自行重算或修改。
2. 不得虚构输入中不存在的数据；输入里没有的信息，直接说明「数据不足」。
3. 商户名称已匿名化为编号，编号只在本次报告内有意义，不要试图猜测或还原真实商户。
4. 不提供股票、基金、保险、借贷、虚拟货币等投资或金融产品建议。
5. 语言风格：克制、专业、客观、有建设性；不使用夸张情绪化表达，不说教。
6. 排版只使用二级标题（##）、无序列表（-）和加粗（**）。不要使用表格、代码块、引用块、图片和三级以上标题。
7. 全文控制在 700 字以内。"""

    /**
     * user 消息。
     *
     * `{summary}` 就是 [SummaryFormatter.full] 的输出，
     * 也是**唯一**会进入 Prompt 的用户数据。
     */
    fun advisorMessages(summary: FinancialSummary): List<AiMessage> {
        val notes = buildString {
            if (summary.isLowSample) {
                appendLine()
                append("注意：本周期支出样本量较小（仅 ")
                append(summary.categoryStats.sumOf { it.count })
                append(" 笔），请谨慎下结论，不要据此给出「消费画像」这类强断言。")
            }
            if (summary.budget == null || summary.budget <= 0) {
                appendLine()
                append("注意：用户尚未设置本周期预算上限。第 3 节请说明「因未设置预算，无法判断超支风险」，并给出一个建议的预算区间。")
            }
            if (summary.previousPeriodExpense == null) {
                appendLine()
                append("注意：没有可对比的历史周期数据，不要编造环比结论。")
            }
            if (summary.daysElapsed in 1 until summary.daysTotal) {
                appendLine()
                append("注意：本周期尚未结束（已过 ")
                append(summary.daysElapsed).append(" / ")
                .append(summary.daysTotal)
                append(" 天），预测时要考虑这一点。")
            }
        }

        val user = buildString {
            appendLine("请根据以下用户近期消费数据进行诊断，并按指定结构输出。")
            appendLine()
            appendLine("【用户财务数据】")
            appendLine(SummaryFormatter.full(summary))
            if (notes.isNotBlank()) {
                appendLine()
                append(notes.trim())
            }
            appendLine()
            appendLine("【输出要求】")
            appendLine()
            appendLine("## 1. 消费结构诊断")
            appendLine("分析刚需支出与弹性/享乐支出的比例是否合理，指出当前最核心的 1~2 个「漏财点」。必须引用上面的具体分类与金额。")
            appendLine()
            appendLine("## 2. 行为习惯画像")
            appendLine("根据时段分布、工作日/周末差异、消费频次，提炼 2~3 个最明显的消费行为特征。")
            appendLine()
            appendLine("## 3. 动态预警与预测")
            appendLine("根据预算、已发生支出和当前消费速度，判断本周期是否存在超支风险。")
            appendLine()
            appendLine("## 4. 下周行动建议")
            appendLine("只给出 3 条未来 7 天可直接执行的具体行动，每条必须包含可量化的目标（金额或次数）。禁止「少花钱」「理性消费」「记好每一笔」这类空泛表述。")
            appendLine()
            appendLine("## 5. 建议可用总额度")
            appendLine("结合近期消费水平、当前收入与预算执行情况，给出下一个预算周期的建议可用总额度，并用一句话说明理由。")
            appendLine()
            appendLine("全文最后必须单独成行输出（不要放进列表，也不要加粗）：")
            appendLine()
            appendLine("建议可用总额度：¥数字")
            appendLine()
            appendLine("例如：")
            appendLine("建议可用总额度：¥4800")
        }

        return listOf(
            AiMessage.system(ADVISOR_SYSTEM),
            AiMessage.user(user)
        )
    }

    /** 体检报告用较高 temperature，长输出 */
    const val ADVISOR_TEMPERATURE = 0.7
    const val ADVISOR_MAX_TOKENS = 2048

    // ═══════════════════════════════════════════════════
    // 自然语言记账
    // ═══════════════════════════════════════════════════

    private const val PARSER_SYSTEM = """你是 SmartLedger 的记账字段解析器。你只输出一个 JSON 对象，不输出任何其他内容。
不要输出 Markdown 代码块标记，不要输出解释、前言或后记。"""

    /** 解析用低 temperature，要稳定不要创造力 */
    const val PARSER_TEMPERATURE = 0.2
    const val PARSER_MAX_TOKENS = 400

    /**
     * @param categories 本地全量分类（含两种 type）。
     *        **必须从数据库动态读取**，不能硬编码 ——
     *        用户可以在「分类管理」里增删分类，硬编码会让 AI 返回本地不存在的分类名。
     * @param now        当前时刻，用于「昨天/前天/上周三」的换算
     */
    fun parserMessages(
        userText: String,
        categories: List<CategoryRef>,
        now: Long,
        zone: TimeZone = TimeZone.getDefault()
    ): List<AiMessage> {
        val cal = Calendar.getInstance(zone).apply { timeInMillis = now }
        val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = zone }
        val weekday = when (cal.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> "周一"; Calendar.TUESDAY -> "周二"
            Calendar.WEDNESDAY -> "周三"; Calendar.THURSDAY -> "周四"
            Calendar.FRIDAY -> "周五"; Calendar.SATURDAY -> "周六"
            else -> "周日"
        }

        // 按 type 分开给两份清单：分类名在 expense / income 下会重名
        // （默认数据里「其他」两边都有），混成一份会让 AI 无法判断该用哪一个
        val expenseCats = categories.filter { it.type == "expense" }.joinToString("、") { it.name }
        val incomeCats = categories.filter { it.type == "income" }.joinToString("、") { it.name }
        val channels = PaymentMethods.PRESETS.joinToString("、")

        val user = """从用户输入中提取一笔交易信息。

当前日期时间：${dateFmt.format(now)}（$weekday）
时区：${zone.id}

可用分类（必须原样从下面选一个，不得自造）：
- 支出分类：${expenseCats.ifBlank { "（无）" }}
- 收入分类：${incomeCats.ifBlank { "（无）" }}

可用支付渠道（必须原样从下面选一个）：
$channels

用户输入：
${userText.trim()}

严格按以下 JSON 格式输出：

{
  "amount": 32.0,
  "type": "expense",
  "category": "餐饮",
  "channel": "微信",
  "merchant": "老乡鸡",
  "note": "同事AA",
  "date": "2026-09-06",
  "time": "12:00"
}

规则：
1. 无法确定的字段一律输出 null，禁止猜测。
2. type 只能是 "expense" 或 "income"（小写）。
3. category 必须来自上面对应 type 的分类列表；若都不匹配，输出 null（不要输出「其他」来凑）。
4. channel 必须来自上面的渠道列表；未提及则输出 null。
5. amount 为正数，单位元；「32块」「32元」「三十二」都解析为 32.0。
6. 「今天/昨天/前天/上周三/9月5日」等按当前日期换算为 date（yyyy-MM-dd）；未提及日期则输出今天。
7. 「上午/中午/下午/晚上/昨晚/凌晨」等换算为 time（HH:mm，取该时段中点：上午09:00、中午12:00、下午15:00、晚上20:00、凌晨02:00）；未提及则输出 null。
8. merchant 只填商家/店名，不要把「和同事」「AA」等写进 merchant。
9. note 填补充信息（如同行人、事由），没有则 null。
10. 只输出 JSON。"""

        return listOf(
            AiMessage.system(PARSER_SYSTEM),
            AiMessage.user(user)
        )
    }

    /** 连通性探测用的极小请求，尽量少烧额度 */
    fun probeMessages(): List<AiMessage> = listOf(
        AiMessage.system("You are a connectivity probe. Reply with exactly: OK"),
        AiMessage.user("ping")
    )

    /** 周期标签，供 UI 与报告标题使用 */
    fun periodTitle(period: SummaryPeriod, label: String): String = when (period) {
        SummaryPeriod.THIS_MONTH -> "$label AI 消费体检"
        SummaryPeriod.LAST_3_MONTHS -> "$label AI 消费体检"
    }
}
