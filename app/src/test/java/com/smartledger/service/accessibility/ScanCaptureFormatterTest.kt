package com.smartledger.service.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 抓取输出的文本化（debug.4）。
 *
 * 这份输出是排查的唯一凭据：它必须能让人（和我）直接看出
 * 「扫的是哪个窗口」「那个窗口里有什么」「卡在哪一步」。
 */
class ScanCaptureFormatterTest {

    private fun node(
        text: String,
        depth: Int = 2,
        viewId: String? = "com.tencent.mm:id/amount_tv",
        className: String = "android.widget.TextView"
    ) = UiTextNode(text = text, viewId = viewId, className = className, depth = depth, index = 0)

    private fun window(
        index: Int,
        nodes: List<UiTextNode>,
        strongWords: List<String> = emptyList(),
        amountProbeHit: Boolean = false,
        activeRoot: Boolean = false,
        rootClass: String? = "FrameLayout"
    ) = WindowCapture(
        windowIndex = index,
        windowType = 1,
        isActive = activeRoot,
        isFocused = activeRoot,
        packageName = "com.tencent.mm",
        rootClass = rootClass,
        rootChildCount = 3,
        isActiveRoot = activeRoot,
        nodes = nodes,
        strongWords = strongWords,
        amountProbeHit = amountProbeHit
    )

    /** 支付结果页的形状：状态词 + 金额都有 */
    private fun paidWindow(index: Int, activeRoot: Boolean = false) = window(
        index = index,
        nodes = listOf(node("支付成功"), node("¥0.01")),
        strongWords = listOf("支付成功"),
        amountProbeHit = true,
        activeRoot = activeRoot
    )

    private fun capture(
        windows: List<WindowCapture>,
        chosenIndex: Int?,
        outcome: String = "未命中支付信号",
        windowsNote: String? = null
    ) = ScanCapture(
        at = 1_700_000_000_000L,
        packageName = "com.tencent.mm",
        rootAvailable = true,
        windowsNote = windowsNote,
        chosenIndex = chosenIndex,
        windows = windows,
        outcome = outcome
    )

    @Test
    fun `摘要行带包名别名 窗口数 选中下标与outcome`() {
        val lines = ScanCaptureFormatter.format(
            capture(listOf(paidWindow(0, activeRoot = true)), chosenIndex = 0, outcome = "识别 支出 ¥0.01")
        )
        val head = lines.first()
        assertTrue(head, head.contains("微信"))
        assertTrue(head, head.contains("窗口数=1"))
        assertTrue(head, head.contains("选中=0"))
        assertTrue(head, head.contains("识别 支出 ¥0.01"))
    }

    @Test
    fun `节点行带深度 短类名 viewId 与完整原文`() {
        val longText = "待邱天宇817确认收款（这条超过旧的 24 字截断，必须完整保留）"
        val lines = ScanCaptureFormatter.format(
            capture(
                listOf(window(0, listOf(node(longText, depth = 4)), activeRoot = true)),
                chosenIndex = 0
            )
        )
        val nodeLine = lines.first { it.contains("·") }
        assertTrue(nodeLine, nodeLine.contains("L4"))
        assertTrue(nodeLine, nodeLine.contains("TextView"))
        assertTrue(nodeLine, nodeLine.contains("id=amount_tv"))
        assertTrue(nodeLine, nodeLine.contains(longText))
    }

    @Test
    fun `有金额无状态词与有状态词无金额必须能区分`() {
        // 这是判断「词表漏词」还是「页面结构不对」的关键分叉
        val amountOnly = ScanCaptureFormatter
            .format(capture(listOf(window(0, listOf(node("¥0.01")), amountProbeHit = true)), 0))
            .first { it.startsWith("  [w0") }
        assertTrue(amountOnly, amountOnly.contains("信号=未命中"))
        assertTrue(amountOnly, amountOnly.contains("命中词=—"))
        assertTrue(amountOnly, amountOnly.contains("金额形态=有"))

        val wordOnly = ScanCaptureFormatter
            .format(capture(listOf(window(0, listOf(node("支付成功")), strongWords = listOf("支付成功"))), 0))
            .first { it.startsWith("  [w0") }
        assertTrue(wordOnly, wordOnly.contains("信号=未命中"))
        assertTrue(wordOnly, wordOnly.contains("命中词=支付成功"))
        assertTrue(wordOnly, wordOnly.contains("金额形态=无"))

        val hit = ScanCaptureFormatter
            .format(capture(listOf(paidWindow(0)), 0))
            .first { it.startsWith("  [w0") }
        assertTrue(hit, hit.contains("信号=命中"))
    }

    @Test
    fun `空树窗口显式标注而不是留白`() {
        val lines = ScanCaptureFormatter.format(capture(listOf(window(0, emptyList())), 0))
        assertTrue(lines.any { it.contains("无文本节点") })
    }

    @Test
    fun `未选中窗口截断到20条且标注总数`() {
        val many = (1..25).map { node("节点$it") }
        val lines = ScanCaptureFormatter.format(
            capture(
                listOf(paidWindow(0), window(1, many, activeRoot = true)),
                chosenIndex = 0
            )
        )
        assertTrue(lines.any { it.contains("节点20") })
        assertTrue(lines.none { it.contains("节点21") })
        assertTrue(lines.any { it.contains("截断，共 25 条") })
    }

    @Test
    fun `选中窗口保留到150条`() {
        val many = (1..160).map { node("节点$it") }
        val lines = ScanCaptureFormatter.format(capture(listOf(window(0, many, activeRoot = true)), 0))
        assertTrue(lines.any { it.contains("节点150") })
        assertTrue(lines.none { it.contains("节点151") })
        assertTrue(lines.any { it.contains("截断，共 160 条") })
    }

    @Test
    fun `windows失败原因会带进输出`() {
        val lines = ScanCaptureFormatter.format(
            capture(
                listOf(paidWindow(0, activeRoot = true)),
                chosenIndex = 0,
                windowsNote = "windows 返回空（flagRetrieveInteractiveWindows 未生效或被系统限制）"
            )
        )
        assertTrue(lines.any { it.contains("flagRetrieveInteractiveWindows") })
    }

    @Test
    fun `每次抓取都以摘要行开头`() {
        val captures = listOf(
            capture(listOf(paidWindow(0, activeRoot = true)), 0),
            capture(listOf(paidWindow(0, activeRoot = true)), 0)
        )
        val lines = captures.flatMap { ScanCaptureFormatter.format(it) }
        assertEquals(2, lines.count { it.startsWith("[") })
    }
}
