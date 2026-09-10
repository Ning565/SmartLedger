package com.smartledger.service.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * UI Tree 的纯文本快照。
 *
 * [UiTextNode] / [UiSnapshot] 是**纯数据**（不依赖 Android API），
 * 因此 Parser / 金额提取 / 商户提取全部可以纯 JVM 单测；
 * 对 AccessibilityNodeInfo 的遍历只发生在 [UiTreeSnapshotExtractor]
 * 这一层薄胶水里（方案 6.4 的设计约束）。
 *
 * [index] 是「文本节点序号」而非视图序号：一个视图同时带 text 与
 * contentDescription 且内容不同时产出两个节点，打分制里的
 * 「相邻节点距离」都基于这个序号。
 */
data class UiTextNode(
    val text: String,
    val viewId: String?,
    val className: String?,
    val depth: Int,
    val index: Int
)

data class UiSnapshot(
    val packageName: String,
    val nodes: List<UiTextNode>,
    val capturedAt: Long
)

/**
 * 视图结构的一行（纯数据，debug.8）。
 *
 * 与 [UiTextNode] 互补：[UiTextNode] 回答「这页有什么字」，本类回答
 * **「这页为什么没字」**。
 *
 * ## 为什么需要它
 * debug.7 真机：微信窗口 4/4 读出 `文本节点=0`，且主线程与 IO 两次读数
 * **都是 0**（排除了节点失效）、重试 3 次**还是 0**（排除了渲染慢）、
 * `pkg=com.tencent.mm activeRoot active focused`（排除了扫错窗口）。
 * 三条都排除后只剩「这棵树本来就没有文本」，但旧转储在
 * `（无文本节点）` 一行就断了 —— 看不出那棵树长什么样，也就分不清
 * 到底是自绘（要 OCR）、WebView（还有救）、还是被系统剪枝（改选窗）。
 *
 * 本类记的每个字段都对应一个待排除的可能：
 * - [className] 是 `SurfaceView` → 自绘；是 `WebView` → H5 虚拟树没建起来
 * - [liveChildCount] 与 [childCount] 不等 → 无障碍在这一层把子树剪掉了
 * - [visibleToUser] = false → 窗口在过渡态/被遮挡，内容被系统裁掉
 * - [width]/[height] 为 0 → 节点在屏幕外，其子树本就不可读
 * - [importantForAccessibility] = false → 微信主动对无障碍隐藏了这一支
 */
data class UiStructureNode(
    val depth: Int,
    val className: String?,
    val viewId: String?,
    /** `childCount` 报告的子节点数 */
    val childCount: Int,
    /** `getChild(i)` 实际返回非 null 的个数 —— 与 [childCount] 不等即为被剪枝 */
    val liveChildCount: Int,
    val visibleToUser: Boolean,
    /** `isImportantForAccessibility()`：微信可主动把一支标记为不重要 */
    val importantForAccessibility: Boolean,
    val width: Int,
    val height: Int
)

/** 文本归一化（纯函数，可单测） */
object UiTreeTextNormalizer {

    /**
     * 规则（方案 4.1）：
     * - 全角货币符 ￥ → ¥（金额提取只需认半角）
     * - 全角冒号 ： → :（时间形态识别统一）
     * - 换行合并为空格、连续空白合一、去首尾空白
     */
    fun normalize(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return raw
            .replace('￥', '¥')
            .replace("：", ":")
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replace('\t', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}

/**
 * 把当前窗口转成 [UiSnapshot]。
 *
 * 遍历是 BFS（方案 4.1 原型），节点数上限 [MAX_NODES]（300）：
 * 超大页面直接截断 —— 支付结果页的信息都在前几百个节点里，
 * 截断不影响识别，反而保证了耗电上限。
 *
 * 线程说明：AccessibilityNodeInfo 可跨线程使用（Parcelable），
 * 本方法在 IO 线程被调用（C6：避免 300 次 binder 调用阻塞主线程）。
 */
object UiTreeSnapshotExtractor {

    const val MAX_NODES = 300

    /**
     * 遍历的**视图节点**总数上限（P2-7）：[MAX_NODES] 限制的是文本节点数，
     * 纯容器节点（无文本）不计数却仍会 getChild()（binder 调用）。
     * 聊天列表这类页面可能有上千个无文本容器，必须双重封顶。
     */
    private const val MAX_VISITED_VIEWS = 800

    private const val SHALLOW_MAX_NODES = 40
    private const val SHALLOW_MAX_DEPTH = 4

    /** 结构转储的节点/深度上限 —— 只在空树时跑，取够看出「这是什么页面」即可 */
    private const val STRUCTURE_MAX_NODES = 60
    private const val STRUCTURE_MAX_DEPTH = 12

    fun extract(root: AccessibilityNodeInfo, packageName: String): UiSnapshot {
        val result = mutableListOf<UiTextNode>()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var index = 0
        var visitedViews = 0

        while (queue.isNotEmpty() && result.size < MAX_NODES && visitedViews < MAX_VISITED_VIEWS) {
            val (node, depth) = queue.removeFirst()
            visitedViews++

            // 同一视图的 text 与 contentDescription 内容不同时各记一条；
            // 相同时只记一条（支付页大量重复 contentDescription，去重省节点额度）
            val values = listOfNotNull(
                node.text?.toString(),
                node.contentDescription?.toString()
            )
                .map { UiTreeTextNormalizer.normalize(it) }
                .filter { it.isNotBlank() }
                .distinct()

            for (text in values) {
                if (result.size >= MAX_NODES) break
                result += UiTextNode(
                    text = text,
                    viewId = node.viewIdResourceName,
                    className = node.className?.toString(),
                    depth = depth,
                    index = index++
                )
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it to depth + 1) }
            }
        }

        return UiSnapshot(
            packageName = packageName,
            nodes = result,
            capturedAt = System.currentTimeMillis()
        )
    }

    /**
     * 浅层文本探测（C1 补齐）：TYPE_WINDOW_CONTENT_CHANGED 的 quickProbe 用。
     *
     * 只走 DFS 深度 ≤ 4、最多 [SHALLOW_MAX_NODES] 个**视图**节点，
     * 收集其中的文本 —— 聊天列表刷新这类高频事件在几百微秒内即可判明
     * 「没有支付强特征」并立即返回，不产生完整快照。
     */
    fun extractShallow(
        source: AccessibilityNodeInfo,
        maxNodes: Int = SHALLOW_MAX_NODES,
        maxDepth: Int = SHALLOW_MAX_DEPTH
    ): List<String> {
        val texts = mutableListOf<String>()
        var visited = 0

        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (visited >= maxNodes || depth > maxDepth) return
            visited++

            // P1-5：contentDescription 只与本节点自己的 text 比较 ——
            // 与「上一个被收集的文本」比较会跨节点串味：
            // 节点1 text="¥100"，节点2 desc="¥100"（text 为空）会被误丢，
            // quickProbe 少收金额文本 → 漏记
            val t = UiTreeTextNormalizer.normalize(node.text?.toString())
            if (t.isNotBlank()) texts += t
            val cd = UiTreeTextNormalizer.normalize(node.contentDescription?.toString())
            if (cd.isNotBlank() && cd != t) texts += cd

            for (i in 0 until node.childCount) {
                if (visited >= maxNodes) return
                node.getChild(i)?.let { visit(it, depth + 1) }
            }
        }

        visit(source, 0)
        return texts
    }

    /**
     * 转储视图**结构**（debug.8）：类名 / 子节点数 / 实际取到的子节点数 /
     * 可见性 / 重要性 / 尺寸，不含文本。
     *
     * 只在 `extract` 一个文本节点都没抓到（空树）时调用 —— 那时
     * 「有什么字」已经问不出东西了，「这棵树长什么样」才是唯一能推进的问题。
     * 因此它的成本只落在**本来就没内容的窗口**上，正常页面一次都不跑。
     *
     * 逐节点 try/catch：遍历过程中节点可能在窗口切换时失效，
     * 单个节点读炸不该让整份转储丢掉（这正是它存在的意义所在）。
     */
    fun extractStructure(
        root: AccessibilityNodeInfo,
        maxNodes: Int = STRUCTURE_MAX_NODES,
        maxDepth: Int = STRUCTURE_MAX_DEPTH
    ): List<UiStructureNode> {
        val out = mutableListOf<UiStructureNode>()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)

        while (queue.isNotEmpty() && out.size < maxNodes) {
            val (node, depth) = queue.removeFirst()
            try {
                val childCount = node.childCount
                val liveChildren = mutableListOf<AccessibilityNodeInfo>()
                for (i in 0 until childCount) {
                    node.getChild(i)?.let { liveChildren += it }
                }
                val rect = Rect()
                node.getBoundsInScreen(rect)
                out += UiStructureNode(
                    depth = depth,
                    className = node.className?.toString(),
                    viewId = node.viewIdResourceName,
                    childCount = childCount,
                    liveChildCount = liveChildren.size,
                    visibleToUser = node.isVisibleToUser,
                    importantForAccessibility = node.isImportantForAccessibility,
                    width = rect.width(),
                    height = rect.height()
                )
                if (depth < maxDepth) {
                    liveChildren.forEach { queue.add(it to depth + 1) }
                }
            } catch (_: Exception) {
                // 失效节点：记一行「读不出」比整份丢掉有用
                out += UiStructureNode(
                    depth = depth,
                    className = null,
                    viewId = null,
                    childCount = -1,
                    liveChildCount = -1,
                    visibleToUser = false,
                    importantForAccessibility = true,
                    width = 0,
                    height = 0
                )
            }
        }
        return out
    }
}
