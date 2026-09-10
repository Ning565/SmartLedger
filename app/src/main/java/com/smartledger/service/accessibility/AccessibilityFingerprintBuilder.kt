package com.smartledger.service.accessibility

import com.smartledger.service.ParsedPayment
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 无障碍指纹构建（方案 1.3 / 6.1，统一了原文两处不一致的描述，C8）。
 *
 * 指纹 = 页面稳定要素的 SHA-256：
 * ```
 * pkg | type | amountCents | merchant | 命中状态词(sorted) | 交易时间
 * ```
 *
 * 时间字段（P0-2）是「终身去重」与「同额新交易」的区分器：
 * - 页面有交易时间文本（支付成功页几乎都有）→ 直接纳入：
 *   重开历史页时间不变（同 key，去重），新交易时间不同（正常记账）；
 * - 页面无时间文本（如红包页）→ 退化到 capturedAt 归一到「天」：
 *   同一天内同商户同金额仍会被去重（概率极低），隔天正常。
 *   这把原来「永久漏记」的窗口压缩到一天，且隔天重开的误记用户能发现并删。
 *
 * 产出形如 `a11y:wechat:9f86d081884c7d65`，同时充当：
 * - Transaction.notificationKey（终身去重键）
 * - AccessibilityFingerprintCache 的 key（15 秒事件去重）
 */
object AccessibilityFingerprintBuilder {

    const val WECHAT_PACKAGE = "com.tencent.mm"
    const val ALIPAY_PACKAGE = "com.eg.android.AlipayGphone"

    private const val PREFIX = "a11y:"
    private const val HASH_LENGTH = 16

    fun build(packageName: String, snapshot: UiSnapshot, parsed: ParsedPayment): String {
        // 状态词取整页命中并排序 —— 排序保证与节点顺序无关的稳定性
        val pageText = snapshot.nodes.joinToString(" ") { it.text }
        val statusWords = PaymentSignalDetector.strongWordsIn(pageText).sorted().joinToString("|")

        val merchant = parsed.merchant.orEmpty()
        val amountCents = Math.round(parsed.amount * 100.0)
        val timeToken = TransactionTimeExtractor.extract(snapshot.nodes)
            ?: dayKeyOf(snapshot.capturedAt)

        val input = "$packageName|${parsed.type}|$amountCents|$merchant|$statusWords|$timeToken"
        val hash = sha256Hex(input).take(HASH_LENGTH)
        return PREFIX + appAlias(packageName) + ":" + hash
    }

    /** 退化策略：无页面时间文本时按天区分（P0-2） */
    private fun dayKeyOf(timestamp: Long): String =
        SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(timestamp))

    private fun appAlias(packageName: String): String = when (packageName) {
        WECHAT_PACKAGE -> "wechat"
        ALIPAY_PACKAGE -> "alipay"
        else -> packageName.substringAfterLast('.').ifBlank { "unknown" }
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
