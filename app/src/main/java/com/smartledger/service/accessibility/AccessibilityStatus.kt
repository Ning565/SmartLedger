package com.smartledger.service.accessibility

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.util.Log
import android.view.accessibility.AccessibilityManager
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 无障碍服务连接状态（C4，参照 ListenerStatus 的模式）。
 *
 * 两个状态口径，各自用途不同：
 * - [isEnabledInSettings]：系统设置里是否勾选了本服务 —— **UI 展示唯一口径**
 *   （「已开启 / 去开启」）。权限授予与撤销以此为准。
 * - [isServiceAlive]：服务实例当前是否存活 —— 仅用于诊断。
 *   Android 可能在服务未被撤销权限时重建服务（内存回收再拉起），
 *   存活标志会短暂为 false，不代表权限被关。
 */
object AccessibilityStatus {

    private const val TAG = "A11yStatus"
    private const val PREFS = "smart_ledger"
    private const val KEY_CONNECTED = "a11y_service_connected"

    private val serviceAlive = AtomicBoolean(false)

    fun setConnected(context: Context, connected: Boolean) {
        val was = serviceAlive.getAndSet(connected)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_CONNECTED, connected)
            .apply()
        if (was != connected) {
            Log.d(TAG, "serviceAlive=$connected")
        }
    }

    fun isServiceAlive(): Boolean = serviceAlive.get()

    /** 系统设置里是否已开启本 App 的页面辅助识别服务（方案 2.4） */
    fun isEnabledInSettings(context: Context): Boolean {
        return try {
            val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE)
                as AccessibilityManager
            manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any {
                    it.resolveInfo?.serviceInfo?.packageName == context.packageName &&
                        it.resolveInfo?.serviceInfo?.name?.endsWith("PaymentAccessibilityService") == true
                }
        } catch (e: Exception) {
            Log.w(TAG, "isEnabledInSettings failed", e)
            false
        }
    }
}
