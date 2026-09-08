package com.smartledger.data.repository

import android.content.Context
import com.smartledger.data.ai.model.AiConfig
import com.smartledger.data.preferences.AiPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AI 配置仓库。
 *
 * 持有一个 [StateFlow]，让设置页、统计页、记账页三处入口共享同一份状态：
 * 在设置页保存后，统计页的「去设置」按钮要立刻变成「生成报告」，
 * 靠各自读 SharedPreferences 做不到（Compose 不会感知 prefs 变化）。
 *
 * 首次加载放在 IO 线程：Keystore 读取虽然只要几毫秒，
 * 但 `SmartLedgerApp.onCreate` 已经很忙（监听重绑、看门狗、保活服务），
 * 没必要再加一笔同步磁盘 IO 拖慢冷启动。
 *
 * 线程纪律：所有触碰 Keystore / SharedPreferences 的入口（load/save/clear）
 * 一律 suspend + `Dispatchers.IO`，调用方禁止在主线程同步等待。
 */
class AiSettingsRepository(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _config = MutableStateFlow(AiConfig())
    val config: StateFlow<AiConfig> = _config.asStateFlow()

    /**
     * 密文存在但解不开（换机 / 恢复备份 / 密钥被系统吊销）。
     *
     * AndroidKeyStore 密钥不参与备份迁移，而本 App `allowBackup="true"`，
     * 所以这是**必然会发生的正常场景**，不是异常。
     * 此时 baseUrl 与 model 仍保留，只提示用户重填 Key。
     */
    private val _needsKeyReentry = MutableStateFlow(false)
    val needsKeyReentry: StateFlow<Boolean> = _needsKeyReentry.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    init {
        scope.launch { load() }
    }

    /**
     * 加载配置并刷新 StateFlow（IO 线程）。
     *
     * Keystore AES/GCM 解密 + SharedPreferences 磁盘读必须离开主线程：
     * StrongBox 或低端机一次解密可达几十毫秒，放 Main 上会掉帧，极端情况 ANR。
     * 返回 [AiConfig]，调用方拿返回值用即可，不必再读一次
     * （旧实现 ViewModel 侧又同步 load 了一遍，同一密文解了两回）。
     */
    suspend fun load(): AiConfig = withContext(Dispatchers.IO) {
        var failed = false
        val cfg = AiPreferences.load(context) { failed = it }
        _config.value = cfg
        _needsKeyReentry.value = failed
        _loaded.value = true
        cfg
    }

    /**
     * 保存配置。
     *
     * @param apiKeyUnchanged UI 上 Key 以掩码显示；用户没改动时必须传 true，
     *        否则会把掩码串「sk-••••••1a2b」当成新 Key 存进去，直接毁掉配置。
     */
    suspend fun save(config: AiConfig, apiKeyUnchanged: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            val ok = AiPreferences.save(context, config, apiKeyUnchanged)
            _config.value = if (apiKeyUnchanged) {
                // Key 没变，保留内存里已有的明文，避免 UI 立刻显示成未配置
                config.copy(apiKey = _config.value.apiKey)
            } else {
                config
            }
            _needsKeyReentry.value = false
            ok
        }

    suspend fun clear() = withContext(Dispatchers.IO) {
        AiPreferences.clear(context)
        _config.value = AiConfig()
        _needsKeyReentry.value = false
    }

    // ═══ 开关与统计（同步读，都是轻量 prefs）═══

    val privacyAcknowledged: Boolean
        get() = AiPreferences.isPrivacyAcknowledged(context)

    fun acknowledgePrivacy() = AiPreferences.acknowledgePrivacy(context)

    fun reportTtlMillis(): Long = AiPreferences.reportTtlMillis(context)

    fun recordParseAttempt() = AiPreferences.recordParseAttempt(context)

    fun recordParseAccepted() = AiPreferences.recordParseAccepted(context)

    fun parseStats(): Pair<Int, Int> = AiPreferences.parseStats(context)
}
