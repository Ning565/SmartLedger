package com.smartledger.service.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MerchantPickerTest {

    private fun nodes(vararg texts: String): List<UiTextNode> =
        texts.mapIndexed { i, t -> UiTextNode(text = t, viewId = null, className = null, depth = 0, index = i) }

    @Test
    fun `金额附近正常商户名`() {
        assertEquals("老乡鸡", MerchantPicker.pick(nodes("支付成功", "¥32.00", "老乡鸡", "付款方式 零钱"), 1))
    }

    @Test
    fun `转账场景取收款人`() {
        assertEquals("张三", MerchantPicker.pick(nodes("转账成功", "¥500.00", "张三"), 1))
    }

    @Test
    fun `全部被排除时返回null`() {
        assertNull(MerchantPicker.pick(nodes("支付成功", "¥32.00", "查看账单", "完成"), 1))
    }

    @Test
    fun `日期时间不当商户`() {
        assertNull(MerchantPicker.pick(nodes("支付成功", "¥32.00", "2026-09-09 14:22"), 1))
        assertNull(MerchantPicker.pick(nodes("支付成功", "¥32.00", "2026年9月9日"), 1))
    }

    @Test
    fun `金额形态不当商户`() {
        assertNull(MerchantPicker.pick(nodes("支付成功", "¥32.00", "500.00"), 1))
    }

    @Test
    fun `中英混合商户名`() {
        assertEquals("Manner咖啡", MerchantPicker.pick(nodes("支付成功", "¥32.00", "Manner咖啡"), 1))
    }

    @Test
    fun `金额前方的商户同样可取`() {
        // 商户名在金额上方（真实支付页常见排版：商户 → 金额）
        assertEquals("老乡鸡", MerchantPicker.pick(nodes("老乡鸡", "¥32.00", "支付成功"), 1))
    }

    @Test
    fun `距离过远不取`() {
        // 超出 4 节点扫描半径
        assertNull(
            MerchantPicker.pick(
                nodes("支付成功", "¥32.00", "a", "b", "c", "d", "很远的某个店名"),
                1
            )
        )
    }

    // ═══ P1-4：真实支付页的「标签： 值」交替结构 ═══

    @Test
    fun `标签值结构取收款方的值`() {
        // review 实测的微信支付页结构：原实现会选「收款方」当商户
        val ns = nodes(
            "支付成功", "¥32.00", "付款方式", "零钱", "收款方", "某某便利店",
            "交易时间", "2026-09-09 14:22"
        )
        assertEquals("某某便利店", MerchantPicker.pick(ns, 1))
    }

    @Test
    fun `标签词不再被当成商户`() {
        val ns = nodes("支付成功", "¥32.00", "收款方", "交易时间")
        assertNull(MerchantPicker.pick(ns, 1))
    }

    @Test
    fun `标签带冒号的形态同样识别`() {
        val ns = nodes("支付成功", "¥32.00", "收款方:", "全家便利店")
        assertEquals("全家便利店", MerchantPicker.pick(ns, 1))
    }

    @Test
    fun `营销文案不被当成商户`() {
        // review 实测场景：原实现选出「满200元可用」，
        // 修复后营销模式与操作按钮词都被排除
        val ns = nodes("已领取", "¥50.00", "满200元可用", "立即使用")
        assertNull(MerchantPicker.pick(ns, 1))
    }

    @Test
    fun `满减文案不被当成商户`() {
        val ns = nodes("支付成功", "¥32.00", "满30减5")
        assertNull(MerchantPicker.pick(ns, 1))
    }

    // ═══ P1-4 次要修复：通用标签词只做整节点相等，不误杀真实商户名 ═══

    @Test
    fun `含通用词的真实商户名不误杀 - 商品街便利店`() {
        // 「商品」用 contains 会误杀「商品街便利店」，改成整节点相等判断
        val ns = nodes("支付成功", "¥32.00", "商品街便利店")
        assertEquals("商品街便利店", MerchantPicker.pick(ns, 1))
    }

    @Test
    fun `含通用词的真实商户名不误杀 - 订单来了餐厅`() {
        val ns = nodes("支付成功", "¥32.00", "订单来了餐厅")
        assertEquals("订单来了餐厅", MerchantPicker.pick(ns, 1))
    }

    @Test
    fun `整节点恰好是通用标签时排除`() {
        val ns = nodes("支付成功", "¥32.00", "商品", "商品街便利店")
        // 「商品」整节点是标签 → 取标签后的值（标签-值结构不在 VALUE_LABELS，
        // 走距离回退：商品被 GENERIC_LABELS 排除，取下一个合法文本）
        assertEquals("商品街便利店", MerchantPicker.pick(ns, 1))
    }

    // ═══ N4：支付方式值不当商户（反义标签结构化排除）═══

    @Test
    fun `支付宝付款方式的值不当商户 - 余额`() {
        // review 实测场景：EXCLUDED_WORDS 只覆盖微信侧的「零钱」「银行卡」，
        // 支付宝收款页的「余额」会被当成商户名（账单显示「商户: 余额」，
        // 还会让 SmartCategorizer 学到「余额 → 某分类」这条脏规则）
        val ns = nodes("收款成功", "¥100.00", "付款方式", "余额", "完成")
        assertNull(MerchantPicker.pick(ns, 1))
    }

    @Test
    fun `未列入词表的支付方式值同样排除 - 结构化方案普适性`() {
        // 堆词表方案的死穴：下面这些名称都**没有**出现在 EXCLUDED_WORDS 里，
        // 但它们都是「付款方式」的值 —— ANTI_VALUE_LABELS 一律排除，
        // 将来出现新的支付方式名称也无需再改代码
        listOf("花呗", "余额宝", "招商银行信用卡(1234)", "数字人民币", "网商银行").forEach { v ->
            val ns = nodes("支付成功", "¥32.00", "付款方式", v)
            assertNull("支付方式值「$v」不应被当成商户名", MerchantPicker.pick(ns, 1))
        }
    }

    @Test
    fun `反义标签不误伤真实商户名 - 收款方路径仍生效`() {
        // 反向用例：ANTI_VALUE_LABELS 只排除「付款方式」类标签的值，
        // 同页同时存在「收款方 → 某某便利店」时必须照常取出。
        // 「付款方」在 VALUE_LABELS、「付款方式」在 ANTI_VALUE_LABELS，
        // 两者都是整节点相等判断，不得混淆
        val ns = nodes(
            "支付成功", "¥32.00", "付款方式", "花呗", "收款方", "某某便利店",
            "交易时间", "2026-09-09 14:22"
        )
        assertEquals("某某便利店", MerchantPicker.pick(ns, 1))
    }
}
