package com.smartledger.service.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 原始窗口 → 要不要进候选（debug.8）。
 *
 * ## 为什么单独测这一条
 * 这道过滤是**静默**的：被丢掉的窗口在诊断里不留任何痕迹。
 * debug.7 的真机 dump 显示 `窗口数=1`，而那是过滤后的数 ——
 * 如果支付页在过滤阶段就被丢掉，我们连它存在过都看不出来，
 * 于是连着三轮把「找不到窗口」误读成「窗口是空的」。
 *
 * 同一条过滤逻辑上曾经两次出事：
 * - debug.3：`windows` 恒为空（flag 没声明），多窗口兜底整个空转
 * - debug.7：`pkg == null` 时 `pkg != packageName` 成立 → 窗口被静默丢弃
 *
 * 所以它必须由测试锁住，而不是靠 dump 事后反推。
 */
class WindowDecisionTest {

    private val wechat = "com.tencent.mm"

    @Test
    fun `同包名采纳`() {
        assertEquals(
            WindowDecision.ACCEPT_MATCH,
            decideWindow(wechat, wechat, acceptedSoFar = 0, maxWindows = 4)
        )
    }

    @Test
    fun `包名读不出时仍然采纳 —— 这是 debug_7 那个静默丢弃的坑`() {
        // 节点失效 / 窗口受限时 packageName 就是 null。
        // 旧写法 `if (pkg != packageName) continue` 在 null 时也成立，
        // 窗口于是被丢掉，而它恰恰可能是支付页所在的那个
        val d = decideWindow(null, wechat, acceptedSoFar = 0, maxWindows = 4)
        assertEquals(WindowDecision.ACCEPT_UNKNOWN_PKG, d)
        assertTrue(d.accepted)
    }

    @Test
    fun `读得出的别的包名丢弃`() {
        val d = decideWindow("com.miui.home", wechat, acceptedSoFar = 0, maxWindows = 4)
        assertEquals(WindowDecision.REJECT_OTHER_PKG, d)
        assertFalse(d.accepted)
    }

    @Test
    fun `同包名超出上限丢弃`() {
        assertEquals(
            WindowDecision.REJECT_OVER_LIMIT,
            decideWindow(wechat, wechat, acceptedSoFar = 4, maxWindows = 4)
        )
    }

    @Test
    fun `包名不符时先判包名 而不是先判上限`() {
        // 顺序有意的：无关窗口不该占名额，否则它排在同包名窗口前面时，
        // 真正想看的那个会因「超出上限」被丢掉
        assertEquals(
            WindowDecision.REJECT_OTHER_PKG,
            decideWindow("com.miui.home", wechat, acceptedSoFar = 99, maxWindows = 4)
        )
    }

    @Test
    fun `还不满时同包名都采纳 满一个才开始丢`() {
        val accepted = (0 until 4).map { decideWindow(wechat, wechat, it, 4) }
        assertTrue(accepted.all { it.accepted })
        assertFalse(decideWindow(wechat, wechat, 4, 4).accepted)
    }

    @Test
    fun `支付宝窗口在微信事件里被丢弃 —— 两个包各自独立`() {
        assertEquals(
            WindowDecision.REJECT_OTHER_PKG,
            decideWindow("com.eg.android.AlipayGphone", wechat, 0, 4)
        )
    }
}
