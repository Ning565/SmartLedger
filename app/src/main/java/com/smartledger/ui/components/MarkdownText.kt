package com.smartledger.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.smartledger.ui.theme.AppType
import com.smartledger.ui.theme.SmartLedgerColors

/**
 * 极简 Markdown 渲染（自研，不引依赖）。
 *
 * ## 为什么不引 Markdown 库
 *  - Markwon / compose-markdown 会带入 commonmark 及一串传递依赖，APK 增重；
 *  - release 开了 `isMinifyEnabled`，多一个反射/注解驱动的库就多一份 R8 风险；
 *  - 我们的输出格式是**自己 Prompt 约束出来的可控子集**，
 *    [AiPromptBuilder] 里明确只允许 `##`、`-`、`**`，
 *    用不上表格、代码块、图片、链接这些能力。
 *
 * ## 支持的语法
 * | 语法 | 渲染 |
 * |---|---|
 * | `## 标题` | sectionTitle 字重 + 上下留白 |
 * | `### 标题` | 略小一档 |
 * | `**加粗**` | Bold |
 * | `- 项` / `* 项` | 圆点 + 缩进 |
 * | `1. 项` | 保留原编号 + 缩进 |
 * | 空行 | 段落间距 |
 * | 其他（表格/代码块/图片/链接） | 按纯文本原样输出，不崩 |
 *
 * ## 流式渲染的关键要求
 * 生成过程中最后一行很可能是 `**未闭合`。解析器必须容忍未闭合标记
 * （当成普通文本），否则流式输出会在半句处抛异常或吞字。
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    baseColor: Color = SmartLedgerColors.fg,
    secondaryColor: Color = SmartLedgerColors.fgSecondary,
    accentColor: Color = SmartLedgerColors.fg
) {
    val blocks = remember(markdown) { parse(markdown) }

    Column(modifier = modifier.fillMaxWidth()) {
        blocks.forEachIndexed { index, block ->
            if (index > 0) Spacer(Modifier.height(block.spaceBefore()))
            when (block) {
                is MdBlock.Heading -> Text(
                    text = renderInline(block.text, accentColor, bold = true),
                    style = if (block.level <= 2) AppType.sectionTitle else AppType.listPrimary,
                    color = accentColor,
                    modifier = Modifier.padding(top = if (index == 0) 0.dp else 4.dp)
                )

                is MdBlock.Bullet -> Row(Modifier.fillMaxWidth()) {
                    Text(
                        text = block.marker,
                        style = AppType.body,
                        color = secondaryColor,
                        modifier = Modifier.width(18.dp)
                    )
                    Text(
                        text = renderInline(block.text, baseColor, bold = false),
                        style = AppType.body,
                        color = baseColor,
                        modifier = Modifier.weight(1f)
                    )
                }

                is MdBlock.Paragraph -> Text(
                    text = renderInline(block.text, baseColor, bold = false),
                    style = AppType.body,
                    color = baseColor,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════
// 解析
// ═══════════════════════════════════════════════════════

/**
 * 解析后的块级元素。
 *
 * 声明为 internal 而不是 private：`parse()` 需要被单测直接调用
 * （流式未闭合标记、围栏截断这些边界只能靠单测守住），
 * 而 internal 函数的返回类型不能比它更严。
 */
internal sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Bullet(val marker: String, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock

    fun spaceBefore(): androidx.compose.ui.unit.Dp = when (this) {
        is Heading -> 14.dp
        is Bullet -> 4.dp
        is Paragraph -> 8.dp
    }
}

private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
private val UNORDERED = Regex("^\\s*[-*+]\\s+(.*)$")
private val ORDERED = Regex("^\\s*(\\d+)[.)]\\s+(.*)$")
private val FENCE = Regex("^\\s*(```|~~~)")
private val HR = Regex("^\\s*(-{3,}|\\*{3,}|_{3,})\\s*$")

internal fun parse(markdown: String): List<MdBlock> {
    if (markdown.isBlank()) return emptyList()

    val out = ArrayList<MdBlock>()
    // 代码围栏内的内容按纯文本段落输出（Prompt 已禁止代码块，
    // 但模型偶尔不听话，至少要保证不崩、不把 ``` 显示给用户）
    var inFence = false
    val fenceBuffer = StringBuilder()

    markdown.split('\n').forEach { rawLine ->
        val line = rawLine.trimEnd('\r')

        if (FENCE.containsMatchIn(line)) {
            if (inFence) {
                if (fenceBuffer.isNotBlank()) out += MdBlock.Paragraph(fenceBuffer.toString().trim())
                fenceBuffer.setLength(0)
                inFence = false
            } else {
                inFence = true
            }
            return@forEach
        }
        if (inFence) {
            fenceBuffer.appendLine(line)
            return@forEach
        }

        val trimmed = line.trim()
        if (trimmed.isEmpty()) return@forEach
        if (HR.matches(trimmed)) return@forEach

        HEADING.find(trimmed)?.let { m ->
            // 标题里的 ** 刻意保留：renderInline 会把它渲染成加粗，
            // 在 parse 阶段就剔掉反而丢失了「这一段要特别重」的语义。
            out += MdBlock.Heading(m.groupValues[1].length, stripInlineNoise(m.groupValues[2]))
            return@forEach
        }
        ORDERED.find(line)?.let { m ->
            out += MdBlock.Bullet("${m.groupValues[1]}.", stripInlineNoise(m.groupValues[2]))
            return@forEach
        }
        UNORDERED.find(line)?.let { m ->
            out += MdBlock.Bullet("•", stripInlineNoise(m.groupValues[1]))
            return@forEach
        }
        val para = stripInlineNoise(trimmed)
        // 表格行等被清洗后会变成空串，不能让它变成一个空白段落
        // （否则会在报告里撑出一堆 8dp 的空隙）
        if (para.isNotEmpty()) out += MdBlock.Paragraph(para)
    }

    // 流式输出可能在围栏中间被截断，收尾时把残留内容也吐出来
    if (inFence && fenceBuffer.isNotBlank()) {
        out += MdBlock.Paragraph(fenceBuffer.toString().trim())
    }
    return out
}

/**
 * 去掉不支持的行内语法标记，保留文字本身。
 *
 * 图片保留 alt、链接保留锚文本、表格行整行丢弃 ——
 * 直接丢掉整行会让报告缺一段，比显示纯文本更糟。
 *
 * **不动 `**`**：加粗是我们明确支持的语法，由 renderInline 处理。
 */
private fun stripInlineNoise(text: String): String = text
    .replace(Regex("!\\[([^]]*)]\\([^)]*\\)"), "$1")      // 图片 → alt
    .replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1")        // 链接 → 文字
    .replace(Regex("`{1,3}"), "")                          // 行内代码标记
    .replace(Regex("^\\|.*\\|$"), "")                      // 表格行
    .trim()

/**
 * 行内渲染：只处理 `**加粗**`。
 *
 * **必须容忍未闭合标记**：流式生成时最后一行经常是 `**本月餐饮`，
 * 若严格按配对解析会把这半句整段吞掉，用户会看到文字凭空消失再出现。
 * 做法：只有找到配对的结束标记才当成加粗，否则原样输出星号。
 */
private fun renderInline(text: String, color: Color, bold: Boolean): AnnotatedString =
    buildAnnotatedString {
        val base = SpanStyle(color = color, fontWeight = if (bold) FontWeight.SemiBold else null)
        val strong = SpanStyle(color = color, fontWeight = FontWeight.Bold)

        var i = 0
        while (i < text.length) {
            if (i + 1 < text.length && text[i] == '*' && text[i + 1] == '*') {
                val close = text.indexOf("**", i + 2)
                if (close > i + 2) {
                    withStyle(strong) { append(text.substring(i + 2, close)) }
                    i = close + 2
                    continue
                }
                // 未闭合：当作普通字符输出
            }
            withStyle(base) { append(text[i]) }
            i++
        }
    }

/** 供 Preview 与测试使用的样例文本 */
internal const val MARKDOWN_SAMPLE = """## 1. 消费结构诊断

本月餐饮支出 **1797.00 元**，占总支出 42.0%，是最核心的漏财点。

## 2. 行为习惯画像

- 周末支出占比 55.0%，明显高于工作日
- 夜间消费 6 笔共 720.00 元
- 高频小额餐饮 28 笔

## 4. 下周行动建议

1. 将工作日午餐控制在 25 元以内
2. 周末外出就餐不超过 2 次
3. 22 点后不打开购物 App

建议可用总额度：¥4800"""
