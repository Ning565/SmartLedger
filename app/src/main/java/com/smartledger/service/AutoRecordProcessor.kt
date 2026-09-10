package com.smartledger.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.smartledger.ConfirmPaymentActivity
import com.smartledger.data.db.AppDatabase
import com.smartledger.data.db.entity.Transaction
import com.smartledger.util.CurrencyUtil
import java.util.Locale

/**
 * 统一自动入账处理器（方案任务 1）。
 *
 * 通知（NotificationParser）与无障碍（WeChat/Alipay Parser）解析出的
 * [ParsedPayment] 汇入这里，共用同一条
 * 「确认 → 去重 → 分类 → 入库 → 通知」链路 —— 新增采集渠道不再复制保存逻辑。
 *
 * 去重职责划分（方案 6.1 三层的落点）：
 *  1. 事件层：AccessibilityFingerprintCache（Service 侧，15 秒）
 *  2. 终身层（C9）：`a11y:` 前缀的 notificationKey 精确查重 ——
 *     指纹不含时间，同一历史支付页永远同 key，隔天重开也只记一次
 *  3. 时间窗层：DedupHelper（金额/类型/商户/渠道，120 秒窗口，
 *     通知 + 无障碍捕获同笔交易时合并为一条）
 */
class AutoRecordProcessor(private val context: Context) {

    companion object {
        private const val TAG = "AutoRecord"
    }

    /**
     * @param transactionTime 交易时间：通知链路传 sbn.postTime，
     *        无障碍链路传页面快照的 capturedAt
     */
    suspend fun process(parsed: ParsedPayment, transactionTime: Long, source: AutoCaptureSource) {
        if (isDebugEnabled()) {
            showDebugToast(parsed)
        }

        if (shouldAskConfirm(parsed, source)) {
            askUserConfirm(parsed, transactionTime)
            return
        }

        try {
            saveAutoTransaction(parsed, transactionTime, source)
        } catch (e: Exception) {
            // 与原 listener 行为一致：单条失败只记日志，绝不让异常冒泡到采集层
            Log.e(TAG, "Failed to save transaction", e)
        }
    }

    // ═══ 确认链路（原 PaymentNotificationListener 逻辑平移） ═══

    /** 设置：模糊需确认（默认开）；或全部自动记账都确认；
     *  无障碍识别另设独立开关（P0-3，默认关） */
    private fun shouldAskConfirm(parsed: ParsedPayment, source: AutoCaptureSource): Boolean {
        val prefs = context.getSharedPreferences("smart_ledger", Context.MODE_PRIVATE)
        // P0-3：读屏识别本质上比通知解析更不确定，给谨慎用户一个独立开关；
        // 默认关闭以免每次支付都被弹窗打断（六步判定已把误记率压低）
        if (source == AutoCaptureSource.ACCESSIBILITY &&
            prefs.getBoolean("confirm_accessibility", false)
        ) return true
        if (prefs.getBoolean("confirm_all_auto", false)) return true
        if (!prefs.getBoolean("confirm_uncertain", true)) return false
        return parsed.confidence == ParseConfidence.UNCERTAIN
    }

    private fun askUserConfirm(parsed: ParsedPayment, postTime: Long) {
        val pendingId = PendingConfirmStore.put(
            amount = parsed.amount,
            type = parsed.type,
            merchant = parsed.merchant,
            paymentMethod = parsed.paymentMethod,
            notificationKey = parsed.notificationKey,
            transactionTime = postTime,
            reason = parsed.uncertainReason ?: "识别结果不够确定",
            rawSnippet = parsed.rawSnippet
        )
        Log.d(TAG, "Ask confirm pendingId=$pendingId reason=${parsed.uncertainReason}")
        // 原实现在主线程同步执行；抽到 IO 协程后切回主线程，
        // 保证 startActivity / 通知弹出的时机与行为和通知链路改造前完全一致
        Handler(Looper.getMainLooper()).post {
            com.smartledger.util.NotificationStyle.notifyNeedsConfirm(
                context,
                pendingId,
                parsed.amount,
                parsed.paymentMethod,
                parsed.uncertainReason
            )
            try {
                val intent = android.content.Intent(context, ConfirmPaymentActivity::class.java).apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(ConfirmPaymentActivity.EXTRA_PENDING_ID, pendingId)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                // 部分机型后台禁弹 Activity：仍可通过「待确认」通知点开
                Log.w(TAG, "Confirm activity blocked, use notification tap", e)
            }
        }
    }

    // ═══ 入库链路（原 saveAutoTransaction 平移 + C9） ═══

    private suspend fun saveAutoTransaction(parsed: ParsedPayment, postTime: Long, source: AutoCaptureSource) {
        val db = AppDatabase.getInstance(context)

        // C9：无障碍来源做 key 终身精确去重（用枚举判断而非字符串前缀，P2-8；
        // 通知链路的 key 无此保证，不做此检查）
        if (source == AutoCaptureSource.ACCESSIBILITY) {
            if (db.transactionDao().getByNotificationKeyOnce(parsed.notificationKey) != null) {
                Log.d(TAG, "A11y page already recorded, skip: ${parsed.notificationKey}")
                return
            }
        }

        val amountCents = CurrencyUtil.toCents(parsed.amount)
        val duplicate = DedupHelper.findDuplicate(
            db.transactionDao(),
            amountCents,
            parsed.type,
            parsed.merchant,
            parsed.paymentMethod,
            postTime
        )

        if (duplicate != null) {
            Log.d(TAG, "Duplicate: existing=${duplicate.paymentMethod}, new=${parsed.paymentMethod}, amount=${parsed.amount}")
            DedupHelper.mergeIfDuplicate(db.transactionDao(), duplicate, parsed.paymentMethod, parsed.merchant)
            return
        }

        // 分类先算好再一次性 insert，避免 insert+update 触发首页两次全量刷新卡顿
        var categoryId: Long? = null
        try {
            val categories = db.categoryDao().getAllOnce()
            categoryId = SmartCategorizer.categorize(
                merchant = parsed.merchant,
                paymentMethod = parsed.paymentMethod,
                note = null,
                categories = categories,
                type = parsed.type
            )
        } catch (e: Exception) {
            Log.e(TAG, "Auto categorize failed", e)
        }

        val transaction = Transaction(
            amount = parsed.amount,
            type = parsed.type,
            categoryId = categoryId,
            merchant = parsed.merchant,
            paymentMethod = parsed.paymentMethod,
            note = null,
            source = "auto",
            notificationKey = parsed.notificationKey,
            transactionTime = postTime
        )
        val id = db.transactionDao().insert(transaction)
        Log.d(TAG, "Transaction saved: id=$id, type=${parsed.type}")

        com.smartledger.util.NotificationStyle.notifyPaymentDetected(
            context,
            parsed.amount,
            parsed.merchant,
            parsed.paymentMethod,
            parsed.type,
            id
        )
    }

    // ═══ 调试提示（原 listener 行为平移，采集渠道统一生效） ═══

    private fun isDebugEnabled(): Boolean {
        return context.getSharedPreferences("smart_ledger", Context.MODE_PRIVATE)
            .getBoolean("debug_toasts", false)
    }

    private fun showDebugToast(parsed: ParsedPayment) {
        Handler(Looper.getMainLooper()).post {
            val typeLabel = if (parsed.type == "income") "收入" else "支出"
            val conf = if (parsed.confidence == ParseConfidence.UNCERTAIN) " · 待确认" else ""
            Toast.makeText(
                context,
                "$typeLabel ¥${String.format(Locale.getDefault(), "%.2f", parsed.amount)} · ${parsed.paymentMethod}$conf",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}
