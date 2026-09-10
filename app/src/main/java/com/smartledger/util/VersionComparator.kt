package com.smartledger.util

/**
 * 版本号解析与比较（纯函数，无 Android 依赖，可 JVM 单测）。
 *
 * ## 为什么单独抽出来
 *
 * 原来的比较是 `remote.substringBefore('-')` 再 `split(".")` 逐段比数字：
 *
 * - `v1.1.0-debug.3` 被拆成 `[1,1,0,3]`，比 `[1,1,0]` 多一段 → **永远判为更新**
 *   （debug.3 实测的误报，已装同版本的用户反复收到提示）
 * - 「剥掉 `-` 后缀」这个补丁把误报修掉了，但代价是 **debug 包之间也永远不提示更新**
 *   （`1.1.0-debug.6` 与 `1.1.0` 的核心段相同 → 判为不更新）
 *
 * 根因是把「预发布后缀」当成了核心版本号的第 4 段。这里按语义重新解析：
 * 核心段（`1.1.0`）+ 是否为预发布 + 预发布序号（`debug.6` 的 6），
 * 再按「核心段 > 正式优先于预发布 > 预发布序号」三层比较。
 *
 * 这样 `debug.5 → debug.6` 能正确判为更新，`debug.3` 也不会再被误判成比 `1.1.0` 高。
 */
internal data class VersionKey(
    /** 核心版本段，如 `1.1.0` → `[1, 1, 0]` */
    val core: List<Int>,
    /** 是否带预发布后缀（`-debug.6` / `-beta.1`） */
    val isPrerelease: Boolean,
    /** 预发布序号：`debug.6` → 6；其它后缀（如 `beta.1`）为 null */
    val prereleaseNo: Int?
)

/**
 * 解析版本串。容忍前缀 `v`、前后空白、构建号 `+`，以及非数字段（按 0 计）。
 *
 * `v1.1.0-debug.6` → core=[1,1,0], isPrerelease=true, prereleaseNo=6
 * `1.1.0`          → core=[1,1,0], isPrerelease=false, prereleaseNo=null
 */
internal fun parseVersion(raw: String): VersionKey {
    val s = raw.trim().removePrefix("v").removePrefix("V").substringBefore('+')
    val dash = s.indexOf('-')
    val corePart = if (dash >= 0) s.substring(0, dash) else s
    val suffix = if (dash >= 0) s.substring(dash + 1).takeIf { it.isNotBlank() } else null

    val core = corePart.split('.').map { it.trim().toIntOrNull() ?: 0 }
    val no = suffix
        ?.takeIf { it.startsWith("debug.", ignoreCase = true) }
        ?.substringAfter('.')
        ?.toIntOrNull()

    return VersionKey(core = core, isPrerelease = suffix != null, prereleaseNo = no)
}

/**
 * 比较两个版本。**返回值 > 0 表示 [remote] 比 [local] 新**（与调用方的直觉一致：
 * 传 `(已装版本, 线上版本)`，正数就是该更新）。
 *
 * 三层规则：
 *  1. 核心段逐段比数字，先分出胜负的直接返回（`1.2.0` > `1.1.9`）
 *  2. 核心段相同 → **正式版高于预发布**（`1.1.0` > `1.1.0-debug.6`），
 *     这条正是 debug.3 误报的正确解法
 *  3. 都是预发布 → 比序号（`debug.7` > `debug.6`）
 */
internal fun compareVersions(local: String, remote: String): Int {
    val l = parseVersion(local)
    val r = parseVersion(remote)

    val maxLen = maxOf(l.core.size, r.core.size)
    for (i in 0 until maxLen) {
        val lv = l.core.getOrElse(i) { 0 }
        val rv = r.core.getOrElse(i) { 0 }
        if (rv != lv) return rv - lv
    }

    // 同核心：远端是预发布而本地不是 → 远端更旧，不更新
    if (r.isPrerelease != l.isPrerelease) return if (r.isPrerelease) -1 else 1

    // 同为预发布：比序号。序号缺失（如 `beta.1`）按 0 计，属已知局限 ——
    // 本仓库只用 `-debug.N`，换其它后缀时需在此补一条分类比较
    return (r.prereleaseNo ?: 0) - (l.prereleaseNo ?: 0)
}

/** [remote] 是否比 [local] 新 */
internal fun isNewerVersion(local: String, remote: String): Boolean =
    compareVersions(local, remote) > 0
