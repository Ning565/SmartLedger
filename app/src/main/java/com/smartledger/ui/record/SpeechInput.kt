package com.smartledger.ui.record

import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent

/**
 * 语音输入。
 *
 * ## 为什么用 ACTION_RECOGNIZE_SPEECH 而不是 SpeechRecognizer
 *
 * 直接持有 `SpeechRecognizer` 有三个现实问题：
 *
 * 1. **Android 11+ 包可见性**：不在 Manifest 里声明 `<queries>` 时，
 *    `SpeechRecognizer.isRecognitionAvailable()` 恒返回 false，
 *    功能看起来"坏了"但查不出原因。
 * 2. **国产 ROM 覆盖率低**：大量设备没有预装 `RecognitionService`
 *    （或依赖 Google 服务），直接持有会得到一个静默失败的识别器，
 *    需要自己处理录音状态、音量回调、超时、错误码，代码量大且难测。
 * 3. **需要 RECORD_AUDIO 权限**：对一个记账 App 来说申请麦克风权限
 *    是很高的用户心理成本，各应用市场对麦克风权限的审核也在收紧。
 *
 * 改用系统语音识别 Intent 后：
 *  - **不需要 RECORD_AUDIO 权限**（录音由系统语音应用完成）；
 *  - 用户看到的是熟悉的系统语音面板；
 *  - SmartLedger 全程拿不到音频，只拿到系统返回的文本 ——
 *    这一条要写进隐私政策。
 *
 * 代价：多一次 Activity 跳转、无法"边说边显示"。
 * 对"说一句话记一笔账"的场景完全够用。
 */
object SpeechInput {

    const val EXTRA_RESULTS_KEY = RecognizerIntent.EXTRA_RESULTS

    /**
     * 设备上是否有可用的系统语音识别。
     *
     * 不可用时 UI 应**隐藏麦克风图标**并只保留文字输入，
     * 不要弹任何错误 —— 这不是用户能修的问题。
     */
    fun isAvailable(context: Context): Boolean = try {
        context.packageManager
            .queryIntentActivities(buildIntent(), 0)
            .isNotEmpty()
    } catch (_: Exception) {
        false
    }

    fun buildIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
        )
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
        putExtra(RecognizerIntent.EXTRA_PROMPT, "说出这笔消费，例如：昨天老乡鸡32元微信")
        // 只要第一条：记账场景不需要候选列表，多给反而增加用户选择成本
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        // 部分识别器支持，能减少"三十两块"被识别成"302"这类问题
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
    }

    /**
     * 从 ActivityResult 里取识别文本。
     *
     * 用户取消、识别失败、返回空列表都归一为 null，
     * 调用方一律静默处理（不提示错误）。
     */
    fun extractResult(data: Intent?): String? {
        if (data == null) return null
        val results = try {
            data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
        } catch (_: Exception) {
            null
        }
        return results?.firstOrNull { it.isNotBlank() }?.trim()
    }
}
