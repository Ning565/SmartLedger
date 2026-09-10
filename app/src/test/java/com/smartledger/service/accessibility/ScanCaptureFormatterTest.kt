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
        rootClass: String? = "FrameLayout",
        packageName: String? = "com.tencent.mm",
        mainThreadTexts: List<String> = emptyList(),
        structure: List<UiStructureNode> = emptyList()
    ) = WindowCapture(
        windowIndex = index,
        windowType = 1,
        isActive = activeRoot,
        isFocused = activeRoot,
        packageName = packageName,
        rootClass = rootClass,
        rootChildCount = 3,
        isActiveRoot = activeRoot,
        nodes = nodes,
        strongWords = strongWords,
        amountProbeHit = amountProbeHit,
        mainThreadTexts = mainThreadTexts,
        structure = structure
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
        windowsNote: String? = null,
        attempts: List<Int> = listOf(windows.sumOf { it.nodes.size }),
        rawWindows: List<String> = emptyList()
    ) = ScanCapture(
        at = 1_700_000_000_000L,
        packageName = "com.tencent.mm",
        rootAvailable = true,
        windowsNote = windowsNote,
        rawWindows = rawWindows,
        chosenIndex = chosenIndex,
        windows = windows,
        outcome = outcome,
        attempts = attempts
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

    // ═══ debug.6：让下一份 dump 能一锤定音的三条 ═══

    @Test
    fun `窗口行必须带自己的包名 —— 顶层包名是事件的 不是窗口的`() {
        // debug.5 的坑：dump 顶层写「微信」，w0 里却是桌面文件夹的节点
        // （activeRoot 降级那条不做包名过滤）。没有 pkg= 就只能靠节点内容猜
        val lines = ScanCaptureFormatter.format(
            capture(
                listOf(
                    paidWindow(0, activeRoot = true),
                    window(1, listOf(node("金融理财")), packageName = "com.miui.home")
                ),
                chosenIndex = 0
            )
        )
        val home = lines.first { it.startsWith("  [w1") }
        assertTrue(home, home.contains("pkg=com.miui.home"))
        val wechat = lines.first { it.startsWith("  [w0") }
        assertTrue(wechat, wechat.contains("pkg=com.tencent.mm"))
    }

    @Test
    fun `主线文本数与文本节点数必须分开显示`() {
        // 这两个数相等才是「真的没有内容」；主线 > 0 而 IO = 0 是「节点跨线程失效」
        val lines = ScanCaptureFormatter.format(
            capture(
                listOf(window(0, emptyList(), activeRoot = true, mainThreadTexts = listOf("支付成功", "¥0.01"))),
                chosenIndex = 0
            )
        )
        val w0 = lines.first { it.startsWith("  [w0") }
        assertTrue(w0, w0.contains("文本节点=0"))
        assertTrue(w0, w0.contains("主线文本=2"))
    }

    @Test
    fun `没有重试时不显示尝试数列`() {
        val lines = ScanCaptureFormatter.format(capture(listOf(paidWindow(0, activeRoot = true)), 0))
        assertTrue(lines.first(), !lines.first().contains("尝试="))
    }

    @Test
    fun `重试过就把各次节点数摊开 —— 0杠0杠12 与 0杠0杠0 是两种结论`() {
        val recovered = ScanCaptureFormatter
            .format(capture(listOf(paidWindow(0, activeRoot = true)), 0, attempts = listOf(0, 0, 12)))
            .first()
        assertTrue(recovered, recovered.contains("尝试=0/0/12"))

        val neverRecovered = ScanCaptureFormatter
            .format(capture(listOf(window(0, emptyList(), activeRoot = true)), 0, attempts = listOf(0, 0, 0)))
            .first()
        assertTrue(neverRecovered, neverRecovered.contains("尝试=0/0/0"))
    }

    // ═══ debug.8：把「这页为什么没字」问到底 ═══

    private fun struct(
        depth: Int,
        className: String,
        childCount: Int = 0,
        liveChildCount: Int = childCount,
        visible: Boolean = true,
        important: Boolean = true,
        width: Int = 1080,
        height: Int = 200
    ) = UiStructureNode(
        depth = depth,
        className = className,
        viewId = null,
        childCount = childCount,
        liveChildCount = liveChildCount,
        visibleToUser = visible,
        importantForAccessibility = important,
        width = width,
        height = height
    )

    @Test
    fun `原始窗口清单要打出被丢掉的窗口与原因`() {
        // debug.7 的盲区：`窗口数=1` 是**过滤后**的数 —— 支付页若在过滤阶段
        // 就被丢掉，诊断里连它存在过都看不出来
        val lines = ScanCaptureFormatter.format(
            capture(
                listOf(paidWindow(0, activeRoot = true)),
                0,
                rawWindows = listOf(
                    "w0 type=1 active focused root=有 pkg=com.tencent.mm → 采纳",
                    "w1 type=3 — root=无 → 丢弃：读不到 root",
                    "w2 type=2 — root=有 pkg=com.miui.home → 丢弃：包名不符"
                )
            )
        )
        assertTrue(lines.any { it.contains("原始 windows=3") })
        assertTrue(lines.any { it.contains("丢弃：读不到 root") })
        assertTrue(lines.any { it.contains("pkg=com.miui.home") })
    }

    @Test
    fun `空树时改列结构 而不是只留一句无文本节点`() {
        val lines = ScanCaptureFormatter.format(
            capture(
                listOf(
                    window(
                        0, emptyList(), activeRoot = true,
                        structure = listOf(
                            struct(0, "android.widget.FrameLayout", childCount = 1),
                            struct(1, "android.view.SurfaceView")
                        )
                    )
                ),
                0
            )
        )
        assertTrue(lines.any { it.contains("FrameLayout") && it.contains("子=1") })
        assertTrue(lines.any { it.contains("SurfaceView") })
    }

    @Test
    fun `结构行要标出被剪枝 不可见 零尺寸 不重要 —— 四种不同的病因`() {
        val lines = ScanCaptureFormatter.format(
            capture(
                listOf(
                    window(
                        0, emptyList(), activeRoot = true,
                        structure = listOf(
                            // childCount 报 3 却一个都取不到 → 无障碍在这一层剪了枝
                            struct(1, "android.widget.LinearLayout", childCount = 3, liveChildCount = 0),
                            struct(2, "android.widget.TextView", visible = false),
                            struct(3, "android.widget.TextView", width = 0, height = 0),
                            struct(4, "android.widget.TextView", important = false)
                        )
                    )
                ),
                0
            )
        )
        assertTrue(lines.any { it.contains("子=3→0") })
        assertTrue(lines.any { it.contains("不可见") })
        assertTrue(lines.any { it.contains("尺寸=0") })
        assertTrue(lines.any { it.contains("不重要") })
    }

    @Test
    fun `有文本节点时不打结构 —— 结构只在问不出字的时候才有意义`() {
        val lines = ScanCaptureFormatter.format(
            capture(
                listOf(
                    paidWindow(0, activeRoot = true).copy(
                        structure = listOf(struct(1, "android.view.SurfaceView"))
                    )
                ),
                0
            )
        )
        assertTrue(lines.none { it.contains("▸") })
    }

    @Test
    fun `空树又没采到结构时显式说明 而不是留白`() {
        val lines = ScanCaptureFormatter.format(capture(listOf(window(0, emptyList())), 0))
        assertTrue(lines.any { it.contains("结构未采集") })
    }
}
