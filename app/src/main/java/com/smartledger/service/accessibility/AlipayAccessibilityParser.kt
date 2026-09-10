package com.smartledger.service.accessibility

/**
 * 支付宝支付页面解析（方案 5.3 / 5.4）。
 *
 * 支持的场景：
 * - 付款成功页 → expense
 * - 主动转账成功页 → expense
 * - 收款成功 / 收到转账 + 金额 → income
 * - 退款成功 → income（沿用现有通知链路的退款=收入语义，不改数据模型）
 *
 * 明确不记：
 * - 待收款 / 等待对方收款 / 处理中：交易未最终完成
 * - 对方已收款：付款方视角
 * - 「成功向你转了一笔钱」这类无金额通知（方案 6.2：等用户打开
 *   显示金额的页面再由无障碍补全，而不是凭空造一条账）
 * - 账单列表 / 余额页：多金额无唯一性，且无强状态词触发信号层
 */
object AlipayAccessibilityParser : BasePaymentParser() {

    override val paymentMethod = "支付宝"

    override val expenseWords = listOf(
        "支付成功",
        "付款成功",
        "已支付",
        "转账成功"
    )

    override val incomeWords = listOf(
        "收款成功",
        "已收款",
        // 场景 G：页面词形是「收到转账 ¥500 交易成功」，
        // 信号层为此补了同一个词（C10），两层用词保持一致
        "收到转账",
        "退款成功",
        "已退款",
        "到账成功",
        // N3 加回：「转账已到账 / 工资已到账」是真实收款高频词形；
        // 「余额宝收益已到账」的误记由营销词邻近性判断（N2）拦住
        "已到账"
    )

    override val excludedWords = listOf(
        "待收款",
        "等待对方收款",
        "对方已收款",
        "处理中",
        "待确认"
    )

    /** P0-3：收入方向的营销排除（对齐通知链路黑名单的核心子集） */
    override val incomeExcludedWords = listOf(
        "积分", "优惠券", "代金券", "奖励", "会员", "签到",
        "理财", "余额宝", "收益", "提现", "积分兑换", "消费金"
    )
}
