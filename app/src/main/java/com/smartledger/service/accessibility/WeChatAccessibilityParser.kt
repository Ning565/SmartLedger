package com.smartledger.service.accessibility

/**
 * 微信支付页面解析（方案 5.1 / 5.2）。
 *
 * 支持的场景：
 * - 支付成功页（扫码 / 付款码 / 小程序收银台） → expense
 * - 主动转账成功页（「转账成功 ¥500」） → expense
 * - 收到转账并确认收款（「已收款 + 金额」） → income
 * - 红包详情（「红包已领取 / 已存入零钱 + 金额」） → income
 *
 * 明确不记（方案 5.1 / 5.2 的「不支持自动记账」清单）：
 * - 待收款：钱还没真正转出
 * - 对方已收款：付款方视角的历史聊天，是我方支出且当时已记过
 * - 已退还：红包退回，资金未发生最终变动
 * - 只有状态词没有金额：知道发生了交易但不知道金额（方案 6.2）
 */
object WeChatAccessibilityParser : BasePaymentParser() {

    override val paymentMethod = "微信"

    override val expenseWords = listOf(
        "支付成功",
        "付款成功",
        "已支付",
        "转账成功"
    )

    override val incomeWords = listOf(
        "已存入零钱",
        "红包已领取",
        "已领取",
        "已收款",
        // N3 加回：「转账已到账」是真实收款高频词形；
        // 「积分已到账」的误记由营销词邻近性判断（N2）拦住
        "已到账"
    )

    /** 顺序无关：整页命中任一即放弃（C3：先于状态词检查） */
    override val excludedWords = listOf(
        "待收款",
        "对方已收款",
        "已退还",
        "待确认",
        "等待确认"
    )

    /** P0-3：收入方向的营销排除（对齐通知链路黑名单的核心子集） */
    override val incomeExcludedWords = listOf(
        "积分", "优惠券", "代金券", "奖励", "会员", "签到",
        "理财", "余额宝", "收益", "提现", "红包封面", "微信豆"
    )
}
