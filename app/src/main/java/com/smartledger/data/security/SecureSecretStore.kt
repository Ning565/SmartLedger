package com.smartledger.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API Key 加密存储。
 *
 * 用 AndroidKeyStore 的 AES/GCM，密钥**不出安全硬件**（支持的设备上位于 TEE/StrongBox），
 * 落到 SharedPreferences 里的只有密文。
 *
 * 不引 `androidx.security:security-crypto`：该库已被 Google 标记为 deprecated，
 * 且 minSdk 26 可以直接用平台 API，少一个依赖就少一份 R8 与体积风险。
 *
 * ## 必须优雅处理的三种失败
 *
 * AndroidKeyStore 的密钥**不参与云备份、不参与换机迁移**。而本 App
 * `allowBackup="true"`，所以「备份 → 恢复到新设备」之后，密文还在、密钥没了，
 * 解密必然失败。这不是异常，是**必然会发生的正常场景**：
 *
 *  1. 密钥不存在（换机 / 恢复备份 / 用户清除了凭据）
 *  2. `AEADBadTagException`（密文与密钥不配对，即上一条的表现）
 *  3. `KeyPermanentlyInvalidatedException`（密钥被系统吊销）
 *
 * 三种情况一律：返回 null + 清掉脏密文，让 UI 提示「需要重新填写 API Key」。
 * 绝不能抛异常上去 —— 那会让用户一打开设置页就崩。
 */
object SecureSecretStore {

    private const val TAG = "SecureSecretStore"

    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "smartledger_ai_secret_v1"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128
    private const val KEY_BITS = 256

    /** 独立的 prefs 文件，已在 res/xml/backup_rules.xml 中排除自动备份 */
    const val PREFS_NAME = "smart_ledger_ai"

    private const val SEP = ":"

    /**
     * 加密写入。value 为 null 或空白表示清除。
     *
     * 加密失败时**不写入**并返回 false（而不是存明文兜底）——
     * 存明文等于没加密，宁可让用户重填。
     */
    fun put(context: Context, key: String, value: String?): Boolean {
        val prefs = prefs(context)
        if (value.isNullOrBlank()) {
            prefs.edit().remove(key).apply()
            return true
        }
        return try {
            val secretKey = getOrCreateKey() ?: return false
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            val encoded = Base64.encodeToString(iv, Base64.NO_WRAP) + SEP +
                    Base64.encodeToString(encrypted, Base64.NO_WRAP)
            prefs.edit().putString(key, encoded).apply()
            true
        } catch (e: Exception) {
            // 只打异常类型，不打 message：message 里可能带 Key 片段
            Log.w(TAG, "encrypt failed: ${e.javaClass.simpleName}")
            false
        }
    }

    /**
     * 读取并解密。
     *
     * 任何失败都返回 null 并清掉脏密文（见类注释），调用方据此提示用户重填。
     */
    fun get(context: Context, key: String): String? {
        val encoded = prefs(context).getString(key, null) ?: return null

        val sepIndex = encoded.indexOf(SEP)
        if (sepIndex <= 0 || sepIndex == encoded.length - 1) {
            // 格式损坏，清掉避免每次都重试
            clear(context, key)
            return null
        }

        return try {
            val iv = Base64.decode(encoded.substring(0, sepIndex), Base64.NO_WRAP)
            val ct = Base64.decode(encoded.substring(sepIndex + 1), Base64.NO_WRAP)

            val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            if (!ks.containsAlias(KEY_ALIAS)) {
                // 换机 / 恢复备份：密钥不在了，密文已不可解
                Log.i(TAG, "keystore key missing, clearing stale ciphertext")
                clear(context, key)
                return null
            }
            val secretKey = ks.getKey(KEY_ALIAS, null) as? SecretKey
                ?: run {
                    clear(context, key)
                    return null
                }

            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            // AEADBadTagException / KeyPermanentlyInvalidatedException / ProviderException 等
            Log.w(TAG, "decrypt failed (${e.javaClass.simpleName}), clearing stale ciphertext")
            clear(context, key)
            null
        }
    }

    fun clear(context: Context, key: String) {
        prefs(context).edit().remove(key).apply()
    }

    /** 是否存在密文（不触发解密，供 UI 显示「已配置」状态） */
    fun exists(context: Context, key: String): Boolean =
        prefs(context).getString(key, null) != null

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 取或建密钥。
     *
     * **不设 setUserAuthenticationRequired**：那会让锁屏后读不到 Key，
     * 用户每次生成 AI 报告都要先解锁验证一次，完全不可接受。
     * 这里的威胁模型是「防止密文被导出后离线破解」，不是「防止机主本人使用」。
     */
    private fun getOrCreateKey(): SecretKey? = try {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey) ?: run {
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .setRandomizedEncryptionRequired(true)
                .build()
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
                .apply { init(spec) }
                .generateKey()
        }
    } catch (e: Exception) {
        Log.w(TAG, "keystore unavailable: ${e.javaClass.simpleName}")
        null
    }
}
