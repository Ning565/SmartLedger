package com.smartledger.service.accessibility

import org.junit.Assert.assertEquals
import org.junit.Test

class UiTreeTextNormalizerTest {

    @Test
    fun `null与空白输入返回空串`() {
        assertEquals("", UiTreeTextNormalizer.normalize(null))
        assertEquals("", UiTreeTextNormalizer.normalize(""))
        assertEquals("", UiTreeTextNormalizer.normalize("   \n\t "))
    }

    @Test
    fun `全角货币符转半角`() {
        assertEquals("¥32.00", UiTreeTextNormalizer.normalize("￥32.00"))
    }

    @Test
    fun `全角冒号转半角`() {
        assertEquals("时间:14:22", UiTreeTextNormalizer.normalize("时间：14:22"))
    }

    @Test
    fun `换行合并为空格`() {
        assertEquals("a b c", UiTreeTextNormalizer.normalize("a\nb\r\nc"))
    }

    @Test
    fun `连续空白合一`() {
        assertEquals("a b", UiTreeTextNormalizer.normalize("a   b"))
    }

    @Test
    fun `去首尾空白`() {
        assertEquals("支付成功", UiTreeTextNormalizer.normalize("  支付成功  "))
    }

    @Test
    fun `Tab转空格`() {
        assertEquals("a b", UiTreeTextNormalizer.normalize("a\tb"))
    }

    @Test
    fun `混合形态`() {
        assertEquals("¥32.00 支付:成功", UiTreeTextNormalizer.normalize(" ￥32.00\n支付：成功\t"))
    }
}
