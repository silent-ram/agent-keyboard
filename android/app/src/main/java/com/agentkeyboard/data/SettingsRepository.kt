package com.agentkeyboard.data

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.agentkeyboard.agent.ProfileSaveResult
import com.agentkeyboard.agent.SystemPrompts
import com.agentkeyboard.data.model.modelConfigStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

internal val Context.agentKeyboardDataStore by preferencesDataStore(name = "agent_keyboard_settings")

/** 解密后的 LLM 调用配置(仅在单次调用内使用,不驻留、不落日志)。
 * M1 批次 3:[toolsAllowed]=fc 生效值(null=未知,尝试带工具;false=单轮降级,不带工具);
 * [contextWindow]>0 时覆盖 Task 默认折叠预算;[ref]=默认模型引用(chip 切换)。 */
data class LlmConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String? = null,
    val toolsAllowed: Boolean? = null,
    val contextWindow: Int = 0,
    val ref: String = "",
)

/**
 * 模型配置仓库:DataStore Preferences。
 * baseUrl 明文;API Key 经 [SecureStore] AES-256-GCM 加密后以 Base64 存密文。
 * 应用 allowBackup=false + dataExtractionRules,密文不参与云备份/迁移。
 * 加解密(Keystore 操作)统一在 Dispatchers.IO 执行。
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val BASE_URL = stringPreferencesKey("base_url")
        val API_KEY_ENC = stringPreferencesKey("api_key_enc")
        val MODEL_OVERRIDE = stringPreferencesKey("model_override")
        val KNOWLEDGE_ENABLED = booleanPreferencesKey("knowledge_enabled")
        val STREAM_TO_IME = booleanPreferencesKey("stream_to_ime")
        /** M3-C(audit#8)按键震动开关,默认开 */
        val HAPTIC_ENABLED = booleanPreferencesKey("haptic_enabled")
        /** 键盘布局:QWERTY/T9,默认 QWERTY */
        val KEYBOARD_LAYOUT = stringPreferencesKey("keyboard_layout")
        /** 键盘高度档位:0.85/1.0/1.15,默认 1.0 */
        val KEYBOARD_HEIGHT_SCALE = floatPreferencesKey("keyboard_height_scale")
        /** 本地用户词典 JSON;只保存拼音/九宫格签名、词和选中次数 */
        val USER_DICTIONARY = stringPreferencesKey("user_dictionary")
        /** M3-C(audit#1)设置引导卡「不再显示」,默认 false(显示) */
        val GUIDE_DISMISSED = booleanPreferencesKey("guide_dismissed")
        /** Agent user.md:用户自己撰写的背景/偏好说明,注入系统提示(G5 配置界面) */
        val USER_MD = stringPreferencesKey("agent_user_md")
    }

    data class StoredConfig(val baseUrl: String?, val apiKeyEncrypted: String?, val modelOverride: String? = null)

    suspend fun save(baseUrl: String, apiKeyPlain: String?, modelOverride: String? = null) {
        val encrypted = apiKeyPlain
            ?.takeIf { it.isNotEmpty() }
            ?.let { withContext(Dispatchers.IO) { SecureStore.encryptSecret(it) } }
        context.agentKeyboardDataStore.edit { prefs ->
            prefs[Keys.BASE_URL] = baseUrl.trim()
            prefs[Keys.MODEL_OVERRIDE] = modelOverride?.trim().orEmpty()
            // encrypted == null 表示仅更新 URL,保留已存密钥
            if (encrypted != null) {
                prefs[Keys.API_KEY_ENC] = encrypted
            }
        }
    }

    suspend fun load(): StoredConfig {
        val prefs = context.agentKeyboardDataStore.data.first()
        return StoredConfig(
            baseUrl = prefs[Keys.BASE_URL],
            apiKeyEncrypted = prefs[Keys.API_KEY_ENC],
            modelOverride = prefs[Keys.MODEL_OVERRIDE],
        )
    }

    /** 是否已配置模型(面板 S9 空态判断依据) */
    suspend fun hasConfiguredModel(): Boolean {
        val cfg = load()
        return !cfg.baseUrl.isNullOrBlank() && !cfg.apiKeyEncrypted.isNullOrBlank()
    }

    /** 取解密后的调用配置;未配置或解密失败返回 null(异常仅记类型,不记内容) */
    suspend fun loadLlmConfig(): LlmConfig? {
        val cfg = load()
        val url = cfg.baseUrl ?: return null
        val enc = cfg.apiKeyEncrypted ?: return null
        return withContext(Dispatchers.IO) {
            runCatching { LlmConfig(url, SecureStore.decryptSecret(enc), cfg.modelOverride) }
                .onFailure {
                    Log.w("AgentKeyboard", "decrypt stored key failed: ${it.javaClass.simpleName}")
                }
                .getOrNull()
        }
    }

    /** 清空全部配置(含密文;Keystore 主钥一并删除) */
    suspend fun clear() {
        context.agentKeyboardDataStore.edit { it.clear() }
        context.modelConfigStore.edit { it.remove(tavilyCiphertextKey) }
        runCatching { SecureStore.deleteMasterKey() }
    }

    /**
     * Tavily 搜索 Key(web_search 工具用):AES-256-GCM 加密存储,策略与模型 Key 一致。
     * keyPlain 为 null 时仅改 URL 语义对搜索 Key 无意义:保存即覆盖,传空串表示清除。
     */
    suspend fun saveTavilyKey(keyPlain: String?) {
        val encrypted = keyPlain
            ?.takeIf { it.isNotEmpty() }
            ?.let { withContext(Dispatchers.IO) { SecureStore.encryptSecret(it) } }
        context.modelConfigStore.edit { prefs ->
            if (encrypted != null) {
                prefs[tavilyCiphertextKey] = encrypted
            } else {
                prefs.remove(tavilyCiphertextKey)
            }
        }
    }

    /** 取解密后的 Tavily Key;未配置/解密失败返回 null */
    suspend fun loadTavilyKey(): String? {
        val enc = context.modelConfigStore.data.first()[tavilyCiphertextKey] ?: return null
        return withContext(Dispatchers.IO) {
            runCatching { SecureStore.decryptSecret(enc) }
                .onFailure { Log.w("AgentKeyboard", "decrypt tavily key failed: ${it.javaClass.simpleName}") }
                .getOrNull()
        }
    }

    /** 📚 知识库开关(全局记忆,12 号设计 §2;M2 前仅影响显示,kb_search 为 stub) */
    suspend fun setKnowledgeEnabled(enabled: Boolean) {
        context.agentKeyboardDataStore.edit { it[Keys.KNOWLEDGE_ENABLED] = enabled }
    }

    suspend fun isKnowledgeEnabled(): Boolean =
        context.agentKeyboardDataStore.data.first()[Keys.KNOWLEDGE_ENABLED] ?: true

    /** 流式上屏 opt-in(FR-D2,默认关):回答逐字 setComposingText 直接上屏 */
    suspend fun setStreamToIme(enabled: Boolean) {
        context.agentKeyboardDataStore.edit { it[Keys.STREAM_TO_IME] = enabled }
    }

    suspend fun isStreamToIme(): Boolean =
        context.agentKeyboardDataStore.data.first()[Keys.STREAM_TO_IME] ?: false

    /** 按键震动(audit#8,默认开):键盘按键按下时 KEYBOARD_TAP 触感 */
    suspend fun setHapticEnabled(enabled: Boolean) {
        context.agentKeyboardDataStore.edit { it[Keys.HAPTIC_ENABLED] = enabled }
    }

    suspend fun isHapticEnabled(): Boolean =
        context.agentKeyboardDataStore.data.first()[Keys.HAPTIC_ENABLED] ?: true

    /** 记住用户上次选择的键盘布局,未知值回退到 26 键。 */
    suspend fun setKeyboardLayout(layout: String) {
        context.agentKeyboardDataStore.edit { it[Keys.KEYBOARD_LAYOUT] = layout }
    }

    suspend fun keyboardLayout(): String =
        context.agentKeyboardDataStore.data.first()[Keys.KEYBOARD_LAYOUT] ?: "QWERTY"

    /** 记住键盘高度档位；异常输入统一收敛到三档合法值。 */
    suspend fun setKeyboardHeightScale(scale: Float) {
        context.agentKeyboardDataStore.edit {
            it[Keys.KEYBOARD_HEIGHT_SCALE] = KeyboardHeight.normalize(scale)
        }
    }

    suspend fun keyboardHeightScale(): Float =
        KeyboardHeight.normalize(
            context.agentKeyboardDataStore.data.first()[Keys.KEYBOARD_HEIGHT_SCALE]
                ?: KeyboardHeight.DEFAULT_SCALE,
        )

    suspend fun saveUserDictionary(json: String) {
        context.agentKeyboardDataStore.edit { it[Keys.USER_DICTIONARY] = json }
    }

    suspend fun userDictionary(): String? =
        context.agentKeyboardDataStore.data.first()[Keys.USER_DICTIONARY]

    /** 设置引导卡「不再显示」(audit#1;全部完成后也自动隐藏,见 GuideCardLogic.shouldHide) */
    suspend fun setGuideDismissed(dismissed: Boolean) {
        context.agentKeyboardDataStore.edit { it[Keys.GUIDE_DISMISSED] = dismissed }
    }

    suspend fun isGuideDismissed(): Boolean =
        context.agentKeyboardDataStore.data.first()[Keys.GUIDE_DISMISSED] ?: false

    /** user.md:保存用户撰写的 Agent 背景说明;空串=未配置 */
    suspend fun saveUserMd(content: String) {
        context.agentKeyboardDataStore.edit { it[Keys.USER_MD] = content }
    }

    suspend fun loadUserMd(): String =
        context.agentKeyboardDataStore.data.first()[Keys.USER_MD].orEmpty()

    internal suspend fun appendUserMdLine(line: String): ProfileSaveResult {
        var result = ProfileSaveResult.INVALID
        context.agentKeyboardDataStore.edit { prefs ->
            val old = prefs[Keys.USER_MD].orEmpty().trimEnd()
            val updated = if (old.isBlank()) line else "$old\n$line"
            result = when {
                old.lineSequence().any { it.trim() == line } -> ProfileSaveResult.ALREADY_PRESENT
                updated.length > SystemPrompts.PROFILE_USER_MD_MAX_CHARS -> ProfileSaveResult.INVALID
                else -> {
                    prefs[Keys.USER_MD] = updated
                    ProfileSaveResult.SAVED
                }
            }
        }
        return result
    }
}
