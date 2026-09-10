package com.smartledger.service.accessibility

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 包名 → 中文别名（诊断模块与抓取格式化共用，避免两份映射漂移） */
internal fun packageAlias(pkg: String): String = when (pkg) {
    AccessibilityFingerprintBuilder.WECHAT_PACKAGE -> "微信"
    AccessibilityFingerprintBuilder.ALIPAY_PACKAGE -> "支付宝"
    else -> pkg.substringAfterLast('.')
}

/**
 * 空树是否该重扫（纯函数，可 JVM 单测）。
 *
 * 触发条件刻意收得很紧 —— 只有「**扫到了窗口**但整页一个文本节点都没有」
 * 才重试。其余情况都不重试：
 *
 * - `failed`（遍历抛异常）：再走一遍多半还是抛，且异常本身要留在诊断里
 * - `windowCount == 0`：走的是 [ScanCapture.rootAvailable] = false 那条路，
 *   问题在「拿不到窗口」而不是「窗口是空的」，重试无意义
 * - 已经用完重试次数：**页面真的为空**时反复重扫只是白耗电
 *
 * 这三条是 debug.5 那次「5/5 读出 0 个文本节点」之后加的 ——
 * 当时的 dump 分不清"节点失效"和"页面本来就没文本"，重试 + 节点数序列
 * （`尝试=0/0/12` 还是 `0/0/0`）刚好把这两者分开。
 */
internal fun shouldRetryEmptyTree(
    textNodeTotal: Int,
    windowCount: Int,
    failed: Boolean,
    attempt: Int,
    maxRetries: Int
): Boolean = !failed && windowCount > 0 && textNodeTotal == 0 && attempt < maxRetries

/**
 * 事件类型 → 短标签（纯函数，刻意不引 android 常量以便 JVM 单测）。
 *
 * `STATE` = TYPE_WINDOW_STATE_CHANGED，页面切换，**无条件**触发完整扫描；
 * `CONTENT` = TYPE_WINDOW_CONTENT_CHANGED，高频刷新，要先过 quickProbe 才算数。
 * 两者混在一起数就分不清「扫描少」是事件少还是探测全被拦。
 */
internal fun eventTypeLabel(eventType: Int): String = when (eventType) {
    0x20 -> "STATE"
    0x800 -> "CONTENT"
    else -> "其他(0x${Integer.toHexString(eventType)})"
}

/**
 * 无障碍「完整扫描」的观测快照（纯数据，不依赖 Android API，可 JVM 单测）。
 *
 * ## 为什么需要它
 * debug.4 真机实测：微信转出 / 转入 / 商家付款三条路径全部不记账，
 * 诊断显示 `完整扫描=134 / 成功识别=1`，而扫描样本里出现的是**聊天页**的节点
 * （`转账成功，马上通知TA` / `图片` / `小视频` / `红包` / `转账` / `¥0.02`）。
 *
 * 指向的根因是「扫到的窗口不是用户正在看的那个」——`rootInActiveWindow`
 * 指向微信聊天窗口，支付页在另一个窗口里。旧诊断只存了**一次**扫描的
 * 前 30 条文本、每条截断 24 字、不含窗口信息，既看不出扫的是哪个窗口，
 * 也看不出那个窗口里到底有什么。
 *
 * [ScanCapture] 把一次扫描的**全部候选窗口**连同各自的 root 结构、
 * 信号判定与**完整节点列表**记下来：事后既能确认选窗是否修好，
 * 也能在词形不匹配时直接照着真实页面校准词表。
 */
data class WindowCapture(
    /** 候选窗口下标（0 起），与 [AccessibilityWindowSelector] 的 index 同源 */
    val windowIndex: Int,
    /** AccessibilityWindowInfo.type；-1 = 该窗口不在 windows 列表里（仅 activeRoot 降级） */
    val windowType: Int,
    val isActive: Boolean,
    val isFocused: Boolean,
    val packageName: String?,
    /** root 的短类名（如 FrameLayout）——区分「指错窗口」与「自绘页面」 */
    val rootClass: String?,
    val rootChildCount: Int,
    /** 是否就是 rootInActiveWindow 对应的窗口 */
    val isActiveRoot: Boolean,
    /** 完整文本节点列表（深度 / viewId / 原文都不截断） */
    val nodes: List<UiTextNode>,
    /** 整页文本命中的强状态词（空 = 没有状态词） */
    val strongWords: List<String>,
    /** 整页是否存在金额形态（与探测层同一张宽松正则） */
    val amountProbeHit: Boolean,
    /**
     * **主线程**浅层读到的文本（≤20 节点/深 3，debug.6）。
     *
     * 与 [nodes] 是同一个窗口的两次读数：一个在拿到窗口的**那一刻**于主线程读，
     * 一个在 IO 协程遍历。两者对比才能区分 debug.5 真机暴露的两种「文本节点=0」：
     *
     * - [mainThreadTexts] 有、[nodes] 空 → 节点在跨线程期间失效（时机问题，软件层可修）
     * - 两边都空 → 该窗口的无障碍树本来就没有文本（自绘/受限窗口，得评估 OCR）
     *
     * 非调试模式恒为空列表（读它是有成本的 binder 遍历）。
     */
    val mainThreadTexts: List<String> = emptyList(),
    /**
     * 节点来源对照（debug.9）：同一个窗口用两个来源各浅读一次的文本数。
     *
     * 假设是「debug.5 换用 `AccessibilityWindowInfo.getRoot()` 取根节点，于是
     * 微信**任何**页面都读成空树」。真机读数**推翻了它** —— 两个来源都是 0：
     *
     * ```
     * 浅读对照：activeRoot=0 / windowRoot=0
     * ```
     *
     * 这行因此保留为常规诊断：它同时排除了节点来源，也顺带排除了
     * 「两个来源指向不同窗口」。两个数都是 0 时，剩下的解释只能落在
     * 服务配置或系统限制上，而不是我们读哪个对象。
     */
    val activeRootShallowTexts: Int? = null,
    val windowRootShallowTexts: Int? = null,
    /**
     * 该窗口的**视图结构**（debug.8）。**只在 [nodes] 为空时才有值**。
     *
     * 空树时「有什么字」已经问不出东西了，结构是唯一还能推进的问题：
     * 类名能区分自绘（SurfaceView）与 H5（WebView），
     * `childCount` 与实际取到的子节点数之差能看出无障碍在哪一层剪了枝。
     * 非空树时不采集（正常页面白跑一趟 binder 遍历没有意义）。
     */
    val structure: List<UiStructureNode> = emptyList()
) {
    /** 双条件同时满足才是「支付强信号」（与 [PaymentSignalDetector.hasStrongSignal] 同口径） */
    val signalHit: Boolean get() = strongWords.isNotEmpty() && amountProbeHit
}

/**
 * 一次完整扫描的完整观测。
 *
 * [outcome] 在解析 / 去重 / 入账各分支回填，因此「扫到了但没记账」的每一步
 * 都能在诊断里对上号，而不是只看到一个「成功识别：0」。
 */
data class ScanCapture(
    val at: Long,
    val packageName: String,
    /** rootInActiveWindow 是否可用 */
    val rootAvailable: Boolean,
    /** `windows` 为空 / 抛异常时的原因（flag 未生效会在这里显形） */
    val windowsNote: String?,
    /**
     * `getWindows()` 返回的**原始**窗口清单，一条一行（debug.8）。
     *
     * 与 [windows] 的区别：这里是**过滤前**的全部窗口，每条都带
     * 「采纳 / 丢弃 + 原因」。旧转储的 `窗口数=N` 是过滤后的数 ——
     * 支付页如果在过滤阶段就被丢掉，诊断里连它存在过都看不出来，
     * 而这恰恰是 debug.7 之后最可疑的一条线索。
     *
     * 放在 [windowsNote] 之后、且有默认值：既有的构造点不必逐个改。
     */
    val rawWindows: List<String> = emptyList(),
    /** 用的哪一个窗口的文本判进位，null = 没有可用快照 */
    val chosenIndex: Int?,
    val windows: List<WindowCapture>,
    val outcome: String,
    /**
     * 各次尝试抓到的**文本节点总数**（debug.6 空树重试）。
     *
     * 一次逻辑扫描因为空树会重扫若干次，这里记 `[0, 0, 12]` 就能一眼看出
     * 「第 3 次才拿到」——直接区分「渲染慢」（重试能救）与「一直为空」
     * （重试也救不了，得换手段）。长度为 1 表示没有触发重试。
     *
     * 重试不各写一条抓取：环形缓冲只有 5 格，一次转账的重试就能把它塞满，
     * 反而把真正有用的前几次扫描挤出去。
     */
    val attempts: List<Int> = listOf(windows.sumOf { it.nodes.size })
)

/** 一个**原始**窗口的处置结果（debug.8） */
enum class WindowDecision(val accepted: Boolean) {
    /** 包名与目标一致 → 采纳 */
    ACCEPT_MATCH(true),

    /**
     * 包名读不出（null）→ **仍然采纳**。
     *
     * 这是 debug.8 改掉的那个坑：节点失效或窗口受限时 `packageName`
     * 就是 null，而旧写法 `if (pkg != packageName) continue` 在 null 时
     * 也成立 → 窗口被**静默丢掉**，诊断里连它存在过都看不出来。
     * 支付页若在这样一个窗口里，它从来没进过候选。
     */
    ACCEPT_UNKNOWN_PKG(true),

    /** 包名不符（读得出来，且不是目标包）→ 丢弃 */
    REJECT_OTHER_PKG(false),

    /** 同包名但已超出单次扫描的窗口数上限 → 丢弃 */
    REJECT_OVER_LIMIT(false)
}

/**
 * 决定一个原始窗口是否进候选（纯函数，可 JVM 单测）。
 *
 * 判定顺序是有意的：**先比包名、再判上限**。包名不符的窗口不该占
 * [maxWindows] 的名额，否则一个无关窗口排在同包名窗口前面时，
 * 真正想看的那个会因「超出上限」被丢掉。
 */
internal fun decideWindow(
    pkg: String?,
    targetPackage: String,
    acceptedSoFar: Int,
    maxWindows: Int
): WindowDecision = when {
    pkg != null && pkg != targetPackage -> WindowDecision.REJECT_OTHER_PKG
    acceptedSoFar >= maxWindows -> WindowDecision.REJECT_OVER_LIMIT
    pkg == null -> WindowDecision.ACCEPT_UNKNOWN_PKG
    else -> WindowDecision.ACCEPT_MATCH
}

/** 选窗的输入（纯数据，供 [AccessibilityWindowSelector] 单测） */
data class WindowCandidate(
    val index: Int,
    val signalHit: Boolean,
    val textNodeCount: Int,
    val isActiveRoot: Boolean
)

/**
 * 多窗口候选的选取规则（纯函数，方案 6.4 的「可单测」约束）。
 *
 * 微信支付流程里的窗口不止一个：聊天页（活跃窗口）与支付结果页常常并存，
 * `rootInActiveWindow` 未必指向后者。这里在全部同包名窗口里挑一个交给 Parser。
 *
 * 规则（按优先级）：
 *  1. **命中支付强信号的窗口优先**
 *  2. 多个命中时取**文本节点最少**的
 *  3. 都没命中时取**文本节点最多**的（只影响诊断样本，不会入库）
 *  4. 平票时 activeRoot 优先，再按窗口下标取靠前的
 *
 * 规则 2 刻意取「最少」而非最多：微信聊天页本身也含
 * `你发起了一笔转账` + `¥0.01`（两者都在强词表 / expenseWords 里），
 * 按节点数取最多会优先选中又大又吵的聊天页，把一次转出记成两笔。
 * 支付结果页小而聚焦（状态词 + 金额 + 按钮，通常十几个节点），
 * 节点数是这两者最稳的区分器。
 */
object AccessibilityWindowSelector {

    fun selectBest(candidates: List<WindowCandidate>): Int? {
        if (candidates.isEmpty()) return null

        val hits = candidates.filter { it.signalHit }
        return if (hits.isNotEmpty()) {
            hits.minWith(
                compareBy({ it.textNodeCount }, { !it.isActiveRoot }, { it.index })
            ).index
        } else {
            candidates.maxWith(
                compareBy({ it.textNodeCount }, { it.isActiveRoot }, { -it.index })
            ).index
        }
    }
}

/**
 * 抓取结果的文本化（纯函数；诊断弹窗与「复制全部」共用同一份输出）。
 *
 * 输出刻意做成「人能直接读、也能直接发给我」的形状：窗口头一行带
 * 类型 / 活跃 / root 类名 / 节点数 / 命中词，节点逐行带深度与 viewId。
 */
object ScanCaptureFormatter {

    /**
     * 选中窗口的节点上限：支付结果页通常十几个节点，150 条足以完整覆盖，
     * 同时给「万一被选中了」的聊天页封顶。
     */
    private const val MAX_NODES_CHOSEN = 150

    /** 未选中窗口的节点上限：只需要够认出「这是聊天页」即可，避免整段输出爆炸 */
    private const val MAX_NODES_OTHER = 20

    /** 空树时每窗口的结构行上限：够看出「这是什么页面」即可 */
    private const val MAX_STRUCTURE_ROWS = 40

    fun format(capture: ScanCapture): List<String> = buildList {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(capture.at))
        // 尝试次数 >1 说明空树触发了重扫，把各次的节点数摊开 ——
        // 「0/0/12」= 渲染慢，重试救回来了；「0/0/0」= 一直读不到，重试没用
        val attemptNote =
            if (capture.attempts.size > 1) " 尝试=${capture.attempts.joinToString("/")}" else ""
        add(
            "[$time] ${packageAlias(capture.packageName)} root可用=${capture.rootAvailable} " +
                "窗口数=${capture.windows.size} 选中=${capture.chosenIndex ?: "-"}" +
                attemptNote + " → ${capture.outcome}"
        )
        capture.windowsNote?.let { add("  ⚠ $it") }

        // 过滤**前**的窗口清单（debug.8）：被丢掉的窗口只在这里可见。
        // 单起一节而不是混进下面的候选列表 —— 这一节回答「系统给了我们什么」，
        // 下面那一节回答「我们拿到的窗口里有什么」，两个问题不能混
        if (capture.rawWindows.isNotEmpty()) add("  原始 windows=${capture.rawWindows.size}：")
        capture.rawWindows.forEach { add("    raw $it") }

        capture.windows.forEach { w ->
            val marks = buildList {
                if (w.isActiveRoot) add("activeRoot")
                if (w.isActive) add("active")
                if (w.isFocused) add("focused")
            }.joinToString(" ").ifBlank { "—" }
            // pkg 必须打：dump 顶层只显示**事件**的包名，而候选窗口里混进
            // 别的包（activeRoot 降级那条不做包名过滤）时，只看节点内容
            // 会把桌面文件夹的节点误读成微信支付页 —— debug.5 的 dump 就是这样
            add(
                "  [w${w.windowIndex} $marks type=${w.windowType} " +
                    // 用**完整包名**而不是别名/短名：这一列的作用就是识别
                    // 「这条窗口到底属于谁」，com.miui.home 与 com.tencent.mm
                    // 缩成 home / mm 反而不好认
                    "pkg=${w.packageName ?: "null"}] " +
                    "root=${w.rootClass ?: "null"} 子节点=${w.rootChildCount} " +
                    "文本节点=${w.nodes.size} 主线文本=${w.mainThreadTexts.size} " +
                    "信号=${if (w.signalHit) "命中" else "未命中"} " +
                    "命中词=${w.strongWords.joinToString("/").ifBlank { "—" }} " +
                    "金额形态=${if (w.amountProbeHit) "有" else "无"}"
            )
            // 节点来源对照（debug.9）：只有两个来源确实不同时才打，
            // 正常情况（降级路径 / 非调试）不占行
            if (w.activeRootShallowTexts != null && w.windowRootShallowTexts != null) {
                add(
                    "      浅读对照：activeRoot=${w.activeRootShallowTexts} " +
                        "/ windowRoot=${w.windowRootShallowTexts}"
                )
            }
            val limit = if (w.windowIndex == capture.chosenIndex) MAX_NODES_CHOSEN else MAX_NODES_OTHER
            if (w.nodes.isEmpty()) {
                // 空树才有结构（debug.8）：这是「为什么这页没字」唯一的答案来源
                if (w.structure.isEmpty()) {
                    add("      （无文本节点，且结构未采集）")
                } else {
                    add("      （无文本节点，改列结构）")
                    w.structure.take(MAX_STRUCTURE_ROWS).forEach { add(structureLine(it)) }
                    if (w.structure.size > MAX_STRUCTURE_ROWS) {
                        add("      …（结构截断，共 ${w.structure.size} 个节点）")
                    }
                }
            } else {
                w.nodes.take(limit).forEach { n ->
                    add("      · L${n.depth} ${shortClass(n.className)} ${shortId(n.viewId)} \"${n.text}\"")
                }
                if (w.nodes.size > limit) add("      …（截断，共 ${w.nodes.size} 条）")
            }
        }
    }

    private fun shortClass(name: String?): String =
        name?.substringAfterLast('.')?.ifBlank { null } ?: "?"

    private fun shortId(id: String?): String =
        id?.let { "id=" + it.substringAfterLast('/') }.orEmpty()

    /**
     * 结构转储的一行（debug.8）。
     *
     * 三个「异常才打」的标注是刻意省的：
     * - `子=3→0`：childCount 报 3 但一个都取不到 → 无障碍在这一层剪了枝
     * - `不可见`：isVisibleToUser=false → 窗口在过渡态，内容被系统裁掉
     * - `不重要`：微信主动把这一支对无障碍隐藏了
     *
     * 尺寸为 0 也单独标注：节点在屏幕外，其子树本就不可读。
     */
    private fun structureLine(n: UiStructureNode): String = buildString {
        append("      ▸ L").append(n.depth).append(' ').append(shortClass(n.className))
        if (n.childCount < 0) {
            append(" （节点已失效，读不出）")
            return@buildString
        }
        append(" 子=").append(n.childCount)
        if (n.liveChildCount != n.childCount) append("→").append(n.liveChildCount)
        shortId(n.viewId).takeIf { it.isNotEmpty() }?.let { append(' ').append(it) }
        if (!n.visibleToUser) append(" 不可见")
        if (!n.importantForAccessibility) append(" 不重要")
        if (n.width == 0 || n.height == 0) append(" 尺寸=0")
        else append(" ${n.width}x${n.height}")
    }
}
