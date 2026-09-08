package com.smartledger.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.smartledger.SmartLedgerApp
import com.smartledger.data.ai.AiResult
import com.smartledger.data.ai.BaseUrlNormalizer
import com.smartledger.data.ai.model.AiConfig
import com.smartledger.data.ai.model.AiProviderPreset
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * AI 设置页状态。
 *
 * 输入框内容与「已保存的配置」是两份独立状态：
 * 用户改了 Base URL 但没保存就退出，不应该生效；
 * 而测试连接要用**当前输入框里的值**（还没保存也能测），
 * 所以两者必须分开持有。
 */
class AiSettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as SmartLedgerApp
    private val settings = app.aiSettingsRepository
    private val aiClient = app.aiClient
    private val advisor = app.aiAdvisorRepository

    /** 表单当前值 */
    data class Form(
        val preset: AiProviderPreset = AiProviderPreset.DEEPSEEK,
        val baseUrl: String = "",
        val apiKey: String = "",
        val model: String = ""
    )

    sealed interface TestState {
        data object Idle : TestState
        data object Running : TestState
        data class Ok(val reply: String, val elapsedMs: Long, val viaReasoning: Boolean) : TestState
        data class Failed(val message: String) : TestState
    }

    private val _form = MutableStateFlow(Form())
    val form: StateFlow<Form> = _form.asStateFlow()

    private val _saved = MutableStateFlow(AiConfig())
    val saved: StateFlow<AiConfig> = _saved.asStateFlow()

    private val _needsKeyReentry = MutableStateFlow(false)
    val needsKeyReentry: StateFlow<Boolean> = _needsKeyReentry.asStateFlow()

    private val _testState = MutableStateFlow<TestState>(TestState.Idle)
    val testState: StateFlow<TestState> = _testState.asStateFlow()

    private val _savedNotice = MutableStateFlow<String?>(null)
    val savedNotice: StateFlow<String?> = _savedNotice.asStateFlow()

    /** 首次保存配置时弹一次隐私告知（合规必做） */
    private val _showPrivacyDialog = MutableStateFlow(false)
    val showPrivacyDialog: StateFlow<Boolean> = _showPrivacyDialog.asStateFlow()

    /**
     * Key 输入框是否被用户改过。
     *
     * 掩码输入框最经典的坑：已保存的 Key 显示为 `sk-••••••1a2b`，
     * 用户只改了 Model 就点保存 —— 若不区分「改过」与「没改过」，
     * 掩码串会被当成新 Key 写进去，配置彻底毁掉。
     */
    private val _keyDirty = MutableStateFlow(false)
    val keyDirty: StateFlow<Boolean> = _keyDirty.asStateFlow()

    /** 用户是否手动改过 baseUrl / model；改过之后再切预设要二次确认 */
    private val _fieldsTouched = MutableStateFlow(false)
    val fieldsTouched: StateFlow<Boolean> = _fieldsTouched.asStateFlow()

    private var testJob: Job? = null

    init {
        reload()
    }

    fun reload() {
        viewModelScope.launch {
            // load() 在 IO 线程做 Keystore 解密并返回结果 ——
            // 主线程只消费返回值，不再自己调 AiPreferences（旧实现同一密文解了两遍，
            // 且第二遍在 Main 线程）
            val cfg = settings.load()
            _saved.value = cfg
            _form.value = Form(
                preset = cfg.preset,
                baseUrl = cfg.baseUrl,
                // Key 以掩码显示；未改过时保存走 apiKeyUnchanged 分支
                apiKey = if (cfg.apiKey.isBlank()) "" else AiConfig.maskSecret(cfg.apiKey),
                model = cfg.model
            )
            _keyDirty.value = false
            _fieldsTouched.value = false
            // 解密失败状态已由 load() 写进 repository，这里镜像给本页 UI
            _needsKeyReentry.value = settings.needsKeyReentry.value
        }
    }

    fun selectPreset(preset: AiProviderPreset) {
        val f = _form.value
        _form.value = f.copy(
            preset = preset,
            baseUrl = preset.defaultBaseUrl,
            model = preset.defaultModel,
            // 切预设不动 Key：用户可能只是想换个模型，Key 是通用的
            apiKey = f.apiKey
        )
        // 选预设等于用推荐值覆盖，视为未手改
        _fieldsTouched.value = false
    }

    fun onBaseUrlChange(v: String) {
        _form.value = _form.value.copy(baseUrl = v)
        _fieldsTouched.value = true
    }

    fun onModelChange(v: String) {
        _form.value = _form.value.copy(model = v)
        _fieldsTouched.value = true
    }

    fun onApiKeyChange(v: String) {
        _form.value = _form.value.copy(apiKey = v)
        // 一旦用户敲了键盘就认为是新 Key
        _keyDirty.value = true
    }

    /** 把当前表单拼成 AiConfig。Key 未改动时沿用已保存的明文。 */
    fun currentConfig(): AiConfig {
        val f = _form.value
        return AiConfig(
            preset = f.preset,
            // 存归一化后的地址，避免用户填的形态差异被固化进配置
            baseUrl = BaseUrlNormalizer.normalize(f.baseUrl),
            apiKey = if (_keyDirty.value) f.apiKey.trim() else _saved.value.apiKey,
            model = f.model.trim()
        )
    }

    /** 表单级校验，返回错误文案；null 表示可保存 */
    fun validate(): String? {
        val f = _form.value
        if (BaseUrlNormalizer.isCleartext(f.baseUrl)) {
            return "系统禁止明文 HTTP 请求，请改用 https 地址"
        }
        if (!BaseUrlNormalizer.looksValid(f.baseUrl)) {
            return "Base URL 格式不正确，例如 https://api.deepseek.com/v1"
        }
        if (f.model.isBlank()) return "请填写 Model 名称"
        if (!_keyDirty.value && _saved.value.apiKey.isBlank()) return "请填写 API Key"
        if (_keyDirty.value && f.apiKey.isBlank()) return "请填写 API Key"
        return null
    }

    fun save() {
        val error = validate()
        if (error != null) {
            _savedNotice.value = error
            return
        }
        val cfg = currentConfig()
        viewModelScope.launch {
            // Keystore 加密在 IO 线程做：StrongBox 设备上同步加密会卡主线程
            val ok = settings.save(cfg, apiKeyUnchanged = !_keyDirty.value)
            _saved.value = cfg
            // 保存成功后掩码状态复位：明文只留在内存里，输入框重新显示掩码
            _form.value = _form.value.copy(
                baseUrl = cfg.baseUrl,
                model = cfg.model,
                apiKey = AiConfig.maskSecret(cfg.apiKey)
            )
            _keyDirty.value = false
            _needsKeyReentry.value = false
            _savedNotice.value = if (ok) {
                "已保存（${cfg.preset.label} · ${cfg.model}）"
            } else {
                "配置已保存，但 API Key 加密失败，请重试"
            }
            // 首次保存要弹隐私告知
            if (ok && !settings.privacyAcknowledged) {
                _showPrivacyDialog.value = true
            }
        }
    }

    fun dismissPrivacyDialog(acknowledged: Boolean) {
        if (acknowledged) settings.acknowledgePrivacy()
        _showPrivacyDialog.value = false
    }

    fun dismissNotice() {
        _savedNotice.value = null
    }

    /**
     * 测试连接。
     *
     * 用**当前输入框**的值而不是已保存的值 —— 用户填完就能测，
     * 不必先保存再测再回来改。
     */
    fun testConnection() {
        val error = validate()
        if (error != null) {
            _testState.value = TestState.Failed(error)
            return
        }
        testJob?.cancel()
        _testState.value = TestState.Running
        testJob = viewModelScope.launch {
            val started = System.currentTimeMillis()
            when (val result = aiClient.testConnection(currentConfig())) {
                is AiResult.Success -> _testState.value = TestState.Ok(
                    reply = result.text.trim().ifBlank { "OK" },
                    elapsedMs = System.currentTimeMillis() - started,
                    viaReasoning = result.fromReasoningFallback
                )

                is AiResult.Failure -> _testState.value =
                    TestState.Failed(result.error.userMessage)
            }
        }
    }

    fun cancelTest() {
        testJob?.cancel()
        _testState.value = TestState.Idle
    }

    /** 清除配置 + 本地 AI 报告 */
    fun clearAll(onDone: () -> Unit) {
        viewModelScope.launch {
            advisor.clearReports()
            settings.clear()
            _saved.value = AiConfig()
            _form.value = Form()
            _keyDirty.value = false
            _needsKeyReentry.value = false
            _testState.value = TestState.Idle
            _savedNotice.value = "已清除 AI 配置与本地报告缓存"
            onDone()
        }
    }

    /** 当前表单选的是不是推理模型（额度提示用） */
    fun isReasoningModel(): Boolean = currentConfig().isReasoningModel
}
