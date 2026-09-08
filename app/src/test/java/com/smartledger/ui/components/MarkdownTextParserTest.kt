package com.smartledger.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Markdown 解析测试。
 *
 * 只测 `parse()`（纯函数），不渲染 Composable —— 不需要 Robolectric。
 *
 * 最关键的一组是**流式边界**：生成过程中文本随时可能在任意位置被截断，
 * 未闭合的 `**`、断在中间的代码围栏都不能让解析器崩或吞掉整段内容。
 */
class MarkdownTextParserTest {

    private fun headings(md: String) = parse(md).filterIsInstance<MdBlock.Heading>()
    private fun bullets(md: String) = parse(md).filterIsInstance<MdBlock.Bullet>()
    private fun paragraphs(md: String) = parse(md).filterIsInstance<MdBlock.Paragraph>()

    /** 取块的文字内容（两个重载：已解析的块列表 / 原始 Markdown） */
    private fun texts(blocks: List<MdBlock>): List<String> = blocks.map {
        when (it) {
            is MdBlock.Heading -> it.text
            is MdBlock.Bullet -> it.text
            is MdBlock.Paragraph -> it.text
        }
    }

    private fun texts(md: String): List<String> = texts(parse(md))

    // ═══ 标题 ═══

    @Test
    fun `二级标题被识别并保留层级`() {
        val hs = headings("## 1. 消费结构诊断")
        assertEquals(1, hs.size)
        assertEquals(2, hs[0].level)
        assertEquals("1. 消费结构诊断", hs[0].text)
    }

    @Test
    fun `一级到六级标题都能识别`() {
        val hs = headings("# a\n## b\n### c\n#### d\n##### e\n###### f")
        assertEquals(listOf(1, 2, 3, 4, 5, 6), hs.map { it.level })
    }

    @Test
    fun `标题里的加粗标记保留给 renderInline 处理`() {
        // 不在 parse 阶段剔掉 **：标题已经整体加粗，但内部仍可能有
        // 「特别重」的片段，剔掉就丢失了这个语义
        assertEquals("**重点**", headings("## **重点**")[0].text)
    }

    // ═══ 列表 ═══

    @Test
    fun `无序列表用圆点标记`() {
        val bs = bullets("- 第一项\n- 第二项\n* 第三项\n+ 第四项")
        assertEquals(4, bs.size)
        assertTrue(bs.all { it.marker == "•" })
        assertEquals(listOf("第一项", "第二项", "第三项", "第四项"), bs.map { it.text })
    }

    @Test
    fun `有序列表保留原编号`() {
        val bs = bullets("1. 控制午餐 25 元\n2. 周末外出不超过 2 次\n3. 22 点后不购物")
        assertEquals(listOf("1.", "2.", "3."), bs.map { it.marker })
        assertEquals("控制午餐 25 元", bs[0].text)
    }

    @Test
    fun `带缩进的列表项也能识别`() {
        assertEquals(1, bullets("   - 缩进项").size)
    }

    // ═══ 段落 ═══

    @Test
    fun `普通文本作为段落`() {
        val ps = paragraphs("本月餐饮支出 1797.00 元，占比 42.0%。")
        assertEquals(1, ps.size)
        assertEquals("本月餐饮支出 1797.00 元，占比 42.0%。", ps[0].text)
    }

    @Test
    fun `空行不产生块`() {
        assertEquals(2, paragraphs("第一段\n\n\n\n第二段").size)
    }

    @Test
    fun `空输入返回空列表`() {
        assertTrue(parse("").isEmpty())
        assertTrue(parse("   ").isEmpty())
        assertTrue(parse("\n\n").isEmpty())
    }

    // ═══ 分隔线与不支持的语法 ═══

    @Test
    fun `水平分隔线被忽略`() {
        assertTrue(parse("---").isEmpty())
        assertTrue(parse("***").isEmpty())
        assertTrue(parse("___").isEmpty())
    }

    @Test
    fun `表格行被丢弃且不产生空白段落`() {
        // Prompt 已禁止表格，但模型偶尔不听话；
        // 至少要保证不崩、不显示竖线垃圾，也不因清洗后变空串而撑出一堆空隙
        val blocks = parse("| 分类 | 金额 |\n|---|---|\n| 餐饮 | 100 |")
        assertTrue("表格行应被完全丢弃，实际=${texts(blocks)}", blocks.isEmpty())
    }

    @Test
    fun `混合内容中表格行不影响其他段落`() {
        val blocks = parse("前言\n|---|\n后记")
        assertEquals(listOf("前言", "后记"), texts(blocks))
    }

    @Test
    fun `图片保留 alt、链接保留锚文本`() {
        assertEquals("alt说明", paragraphs("![alt](http://x/a.png)说明")[0].text)
        assertEquals("点我", paragraphs("[点我](https://example.com)")[0].text)
        // URL 本身不得泄漏给用户
        assertTrue(!paragraphs("[点我](https://example.com)")[0].text.contains("https"))
    }

    @Test
    fun `行内代码标记被去掉但文字保留`() {
        assertEquals("用 deepseek-chat", paragraphs("用 `deepseek-chat`")[0].text)
    }

    // ═══ 流式截断边界（最关键）═══

    @Test
    fun `代码围栏内的内容按纯文本输出且不显示围栏`() {
        val blocks = parse("前言\n```\n代码内容\n```\n后记")
        val all = texts(blocks).joinToString("|")
        assertTrue("围栏标记泄漏给用户：$all", !all.contains("```"))
        assertTrue("围栏内容被整段丢弃：$all", all.contains("代码内容"))
        assertTrue(all.contains("前言"))
        assertTrue(all.contains("后记"))
    }

    @Test
    fun `带语言标注的围栏也能处理`() {
        val all = texts(parse("```json\n{\"a\":1}\n```")).joinToString("|")
        assertTrue(!all.contains("```"))
        assertTrue(all.contains("{\"a\":1}"))
    }

    @Test
    fun `流式截断在围栏中间时残留内容仍被吐出`() {
        // 生成到一半被停止：只有开头的 ```，没有结尾
        val all = texts(parse("正文\n```\n还没写完的内容")).joinToString("|")
        assertTrue("截断在围栏内的内容被吞掉了：$all", all.contains("还没写完的内容"))
        assertTrue(!all.contains("```"))
    }

    @Test
    fun `CRLF 换行不会残留回车符`() {
        val blocks = parse("## 标题\r\n- 项目\r\n正文\r\n")
        assertEquals(3, blocks.size)
        blocks.forEach { b ->
            val t = when (b) {
                is MdBlock.Heading -> b.text
                is MdBlock.Bullet -> b.text
                is MdBlock.Paragraph -> b.text
            }
            assertTrue("残留 \\r：${t.toCharArray().toList()}", !t.contains("\r"))
        }
    }

    // ═══ 完整样例 ═══

    @Test
    fun `完整报告样例的结构符合预期`() {
        val blocks = parse(MARKDOWN_SAMPLE)
        val hs = blocks.filterIsInstance<MdBlock.Heading>()
        // 样例里有 3 个 ## 标题
        assertEquals(3, hs.size)
        assertEquals(
            listOf("1. 消费结构诊断", "2. 行为习惯画像", "4. 下周行动建议"),
            hs.map { it.text }
        )
        // 3 个无序项 + 3 个有序项
        val bs = blocks.filterIsInstance<MdBlock.Bullet>()
        assertEquals(6, bs.size)
        assertEquals(listOf("•", "•", "•", "1.", "2.", "3."), bs.map { it.marker })
        // 最后的建议额度是一行普通段落，必须完整保留（正则要从原文里提取它）
        val lastText = texts(MARKDOWN_SAMPLE).last()
        assertTrue("建议额度行丢失：$lastText", lastText.contains("建议可用总额度：¥4800"))
    }

    @Test
    fun `块间距按类型区分（标题最大、列表最紧凑）`() {
        val h = MdBlock.Heading(2, "x")
        val b = MdBlock.Bullet("•", "x")
        val p = MdBlock.Paragraph("x")
        assertTrue(h.spaceBefore() > p.spaceBefore())
        assertTrue(p.spaceBefore() > b.spaceBefore())
    }
}
