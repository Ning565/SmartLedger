package com.smartledger.service

import java.util.concurrent.ConcurrentHashMap

/**
 * 无障碍事件指纹去重（第一层去重，方案 6.1）。
 *
 * 同一个支付结果页在停留期间会连续产生大量
 * TYPE_WINDOW_CONTENT_CHANGED / TYPE_WINDOW_STATE_CHANGED 事件，
 * 每次都全量解析既费电也可能在边界情况下重复入账 ——
 * 同一页面（指纹相同）在 [TTL]（15 秒）内只处理一次。
 *
 * 指纹输入不含时间（金额 / 类型 / 商户 / 状态词），
 * 因此「隔天重新打开同一历史页面」在本层拦不住 ——
 * 那是 AutoRecordProcessor 按 notificationKey 终身去重的职责（C9），
 * 本层只管「当下这一次页面停留」。
 *
 * [shouldProcess] 带 `now` 默认参数：生产传系统时钟，单测注入固定时钟。
 */
object AccessibilityFingerprintCache {

    private const val TTL = 15_000L

    private val cache = ConcurrentHashMap<String, Long>()

    /**
     * @return true = 该指纹在本 TTL 窗口内首次出现，应处理；
     *         false = 15 秒内已处理过，跳过
     */
    fun shouldProcess(key: String, now: Long = System.currentTimeMillis()): Boolean {
        // 顺手清理过期项，避免长期驻留（key 含金额，笔数多时条目会增长）
        cache.entries.removeIf { now - it.value > TTL }

        val last = cache[key]
        if (last != null && now - last < TTL) {
            return false
        }
        cache[key] = now
        return true
    }

    /** 仅测试与诊断重置用 */
    fun clear() = cache.clear()
}
