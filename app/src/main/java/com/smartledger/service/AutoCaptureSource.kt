package com.smartledger.service

/**
 * 自动记账的采集来源。
 *
 * Transaction.source 字段统一仍是 "auto"（不改 schema，与 CSV 导出口径一致），
 * 来源区分靠 notificationKey 前缀：
 * - 通知链路沿用 NotificationParser 生成的 key
 * - 无障碍链路用 "a11y:wechat:…" / "a11y:alipay:…" 前缀
 *
 * [SMS] 当前为预留：SmsReceiver 是独立保存路径（source="sms"），
 * 本阶段不迁移，待后续统一时使用。
 */
enum class AutoCaptureSource {
    NOTIFICATION,
    ACCESSIBILITY,
    SMS
}
