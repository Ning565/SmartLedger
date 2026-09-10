package com.smartledger.service.accessibility

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
}
