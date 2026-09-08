package com.smartledger.data.preferences

import android.content.Context
import android.content.SharedPreferences
import com.smartledger.data.ai.model.AiConfig
import com.smartledger.data.ai.model.AiProviderPreset
import com.smartledger.data.security.SecureSecretStore

/**
 * AI 配置的底层存储。
 *
 * 非敏感项（preset / baseUrl / model / 各种开关）走 SharedPreferences，
 * 与项目现有的 nickname、theme_mode、initial_balance 保持同一范式，
 * **不为此引入 DataStore**（收益不足以抵消一个新依赖 + 一套协程 API 的成本）。
 *
 * API Key 单独走 [SecureSecretStore] 加密。
 *
 * prefs 文件与密文用的是同一个 `smart_ledger_ai`，
 * 已在 `res/xml/backup_rules.xml` 与 `data_extraction_rules.xml` 中整体排除自动备份 ——
 * 因为 AndroidKeyStore 密钥不随备份迁移，把密文备份过去只会在新设备上
 * 变成一份永远解不开的垃圾数据。
 */
object AiPreferences {

    private const val PREFS = SecureSecretStore.PREFS_NAME

    private const val KEY_PRESET = "ai_preset"
    private const val KEY_BASE_URL = "ai_base_url"
    private const val KEY_MODEL = "ai_model"
    private const val KEY_API_KEY = "ai_api_key"

    private const val KEY_PRIVACY_ACK = "ai_privacy_ack"
    private const val KEY_REPORT_TTL_HOURS = "ai_report_ttl_hours"

    /** AI 解析质量自查用，不入库、不展示 */
    private const val KEY_PARSE_TOTAL = "ai_parse_total"
    private const val KEY_PARSE_ACCEPTED = "ai_parse_accepted"

    /** AI 报告缓存有效期（小时） */
    const val DEFAULT_REPORT_TTL_HOURS = 6

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * 读取完整配置。
     *
     * @param keyDecryptionFailed 输出参数：密文存在但解不开（换机 / 恢复备份 / 密钥被吊销）。
     *        此时 baseUrl 与 model 仍保留，只提示用户重填 Key，比让他从头配一遍友好得多。
     */
    fun load(context: Context, keyDecryptionFailed: (Boolean) -> Unit = {}): AiConfig {
        val p = prefs(context)
        val hadCiphertext = SecureSecretStore.exists(context, KEY_API_KEY)
        val apiKey = SecureSecretStore.get(context, KEY_API_KEY)
        // get() 失败时会顺手清掉脏密文，所以「之前有密文、现在解出 null」即解密失败
        keyDecryptionFailed(hadCiphertext && apiKey == null)

        return AiConfig(
            preset = AiProviderPreset.fromName(p.getString(KEY_PRESET, null)),
            baseUrl = p.getString(KEY_BASE_URL, "").orEmpty(),
            apiKey = apiKey.orEmpty(),
            model = p.getString(KEY_MODEL, "").orEmpty()
        )
    }

    /**
     * 保存配置。
     *
     * @param apiKeyUnchanged 为 true 时**不重写** Key ——
     *        UI 上 Key 默认以掩码显示，若用户没改动就把掩码串当新 Key 存进去，
     *        配置会被彻底毁掉。这是掩码输入框最经典的坑。
     * @return Key 加密是否成功（Keystore 不可用时为 false）
     */
    fun save(
        context: Context,
        config: AiConfig,
        apiKeyUnchanged: Boolean
    ): Boolean {
        prefs(context).edit()
            .putString(KEY_PRESET, config.preset.name)
            .putString(KEY_BASE_URL, config.baseUrl)
            .putString(KEY_MODEL, config.model)
            .apply()

        if (apiKeyUnchanged) return true
        return SecureSecretStore.put(context, KEY_API_KEY, config.apiKey)
    }

    fun clear(context: Context) {
        SecureSecretStore.clear(context, KEY_API_KEY)
        prefs(context).edit()
            .remove(KEY_PRESET)
            .remove(KEY_BASE_URL)
            .remove(KEY_MODEL)
            .apply()
    }

    // ═══ 开关与统计 ═══

    fun isPrivacyAcknowledged(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PRIVACY_ACK, false)

    fun acknowledgePrivacy(context: Context) {
        prefs(context).edit().putBoolean(KEY_PRIVACY_ACK, true).apply()
    }

    fun reportTtlMillis(context: Context): Long {
        val hours = prefs(context).getInt(KEY_REPORT_TTL_HOURS, DEFAULT_REPORT_TTL_HOURS)
        val safe = if (hours <= 0) DEFAULT_REPORT_TTL_HOURS else hours
        return safe * 3_600_000L
    }

    /**
     * 记一次「解析尝试」（无论成功或失败）。
     *
     * 语义拆分的原因：total 表示「用户触发了几次 AI 解析」，accepted 表示
     * 「其中有几次最终被确认保存」。旧的 `bumpParseCounter(accepted=Boolean)`
     * 在解析成功时既记 total 又记 accepted，保存时又记一遍，导致一次
     * 「解析 + 保存」把两个计数各 +2，接受率永远失真。现在解析只记 attempt、
     * 保存只记 accepted，两个计数各自独立、互不重复。
     */
    fun recordParseAttempt(context: Context) {
        val p = prefs(context)
        p.edit()
            .putInt(KEY_PARSE_TOTAL, p.getInt(KEY_PARSE_TOTAL, 0) + 1)
            .apply()
    }

    /** 记一次「AI 回填的草稿被用户确认保存」。只在保存来自 AI 的账单时调用。 */
    fun recordParseAccepted(context: Context) {
        val p = prefs(context)
        p.edit()
            .putInt(KEY_PARSE_ACCEPTED, p.getInt(KEY_PARSE_ACCEPTED, 0) + 1)
            .apply()
    }

    fun parseStats(context: Context): Pair<Int, Int> {
        val p = prefs(context)
        return p.getInt(KEY_PARSE_TOTAL, 0) to p.getInt(KEY_PARSE_ACCEPTED, 0)
    }
}
