package com.smartledger.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/**
 * 剪贴板写入的统一入口。
 *
 * 抽出来的原因：debug.4 的「采集诊断 → 复制全部」与反馈页的
 * 「无邮件客户端时复制联系方式」是同一个动作 —— 此前全仓只有反馈页里
 * 一段 inline 实现，再加一处就会出现两份写法（其中一份迟早会漏判 null
 * 或漏兜异常，而剪贴板失败在部分 ROM 上是会抛的）。
 */
object AppClipboard {

    /**
     * @param label 剪贴板条目的标签（部分 ROM 会用它做系统提示文案）
     * @return true = 已写入；false = 内容为空或系统剪贴板不可用
     */
    fun copy(context: Context, label: String, text: String): Boolean {
        if (text.isBlank()) return false
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return false
        return try {
            manager.setPrimaryClip(ClipData.newPlainText(label, text))
            true
        } catch (_: Exception) {
            false
        }
    }
}
