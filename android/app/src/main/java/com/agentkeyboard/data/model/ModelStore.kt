package com.agentkeyboard.data.model

import com.agentkeyboard.util.Logs
import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.agentkeyboard.data.ConnectionConfig
import com.agentkeyboard.data.SecureStore
import com.agentkeyboard.data.connectionPreferencesMigration
import com.agentkeyboard.data.knowledgeConfigKey
import com.agentkeyboard.data.tavilyCiphertextKey
import com.agentkeyboard.rag.ZhipuKnowledgeSettingsStore
import com.agentkeyboard.rag.decodeKnowledgeSettings
import com.agentkeyboard.rag.encodeKnowledgeSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** model_config.json(10 号设计 §3:配置量级两位数,DataStore JSON 而非 Room);internal 供单测注入损坏 raw(必须复用同一 DataStore 单例) */
internal val Context.modelConfigStore by preferencesDataStore(
    name = "model_config",
    produceMigrations = { listOf(connectionPreferencesMigration(it)) },
)

/** 引擎调用配置(解密后仅在单次调用内使用):[toolsAllowed]=fc 生效值,null=未知 */
data class ActiveModelConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val toolsAllowed: Boolean?,
    val contextWindow: Int,
    val ref: String,
)

/**
 * LlmConfig 兼容桥(AgentRuntime 使用):model = 解析后的具体 modelId。
 * P0(评审修正单):toolsAllowed/contextWindow/ref 必须一并带入——丢字段会让
 * fc=NO 降级链在 AgentRuntime 处失效(cfg.toolsAllowed 恒 null → 带工具请求不支持
 * 工具的模型 → 服务端 400),contextWindow=0 也会回退任务默认折叠预算。
 */
fun ActiveModelConfig.toLlmConfig(): com.agentkeyboard.data.LlmConfig =
    com.agentkeyboard.data.LlmConfig(
        baseUrl = baseUrl,
        apiKey = apiKey,
        model = model,
        toolsAllowed = toolsAllowed,
        contextWindow = contextWindow,
        ref = ref,
    )

/**
 * 模型配置仓库(10 号设计 §3/§4,M1 批次 3 从零实现):
 * - 模型、智谱知识库与搜索配置共用 model_config DataStore，凭据逐项加密；
 * - 首次访问自动迁移 M0 旧配置([SettingsRepository] baseUrl/key/modelOverride);
 * - 读写统一 JSON roundtrip,纯逻辑在 [ModelConfigLogic](可 JVM 单测)。
 */
class ModelStore internal constructor(
    private val context: Context,
    private val encrypt: (String) -> String,
    private val decrypt: (String) -> String,
) {
    constructor(context: Context) : this(context, SecureStore::encryptSecret, SecureStore::decryptSecret)

    companion object {
        private val JSON_KEY = stringPreferencesKey("config_json")
        /** P2(评审修正单):配置 JSON decode 失败时的坏 raw 备份键(独立,不参与 load) */
        private val CORRUPT_JSON_KEY = stringPreferencesKey("config_json_corrupt")
        /** P2:配置损坏标记(非空 = 用户需重新配置;重新保存后清除) */
        private val CONFIG_ERROR_KEY = stringPreferencesKey("config_error")
        private const val TAG = "ModelStore"

        fun secretKey(providerId: String) = stringPreferencesKey("key_$providerId")

        /**
         * P1(评审修正单):读改写互斥。ModelStore 在 IME/设置页被多次 new,
         * Mutex 必须挂 companion(进程级单例)——否则跨实例并发(探测 30s 写回 vs
         * 用户改 fcOverride/displayName vs 连点 chip 刷 lastUsedAt)仍会丢更新。
         */
        private val writeMutex = Mutex()
    }

    private val json = ModelConfigLogic.json

    private suspend fun readRaw(): String =
        context.modelConfigStore.data.first()[JSON_KEY].orEmpty()

    /**
     * 读入 + 必要时迁移(M0 旧配置 → 新结构,幂等)。
     * P2(评审修正单):decode 失败不再静默回零配置——坏 raw 备份到独立键 + Log.w +
     * 置 CONFIG_ERROR 状态(提示用户重新配置),坏键清除后按零配置继续。
     */
    suspend fun load(): ModelConfigFile = writeMutex.withLock { loadLocked() }

    private suspend fun loadLocked(): ModelConfigFile {
        val raw = readRaw()
        if (raw.isNotBlank()) {
            when (val decoded = ModelConfigLogic.decodeChecked(raw)) {
                is ModelConfigLogic.DecodeResult.Ok -> return decoded.file
                is ModelConfigLogic.DecodeResult.Corrupt -> {
                    Log.w(TAG, "model config decode failed (${decoded.reason}); raw backed up, config reset")
                    context.modelConfigStore.edit {
                        it[CORRUPT_JSON_KEY] = raw
                        it[CONFIG_ERROR_KEY] = "CONFIG_CORRUPT"
                        it.remove(JSON_KEY)
                    }
                    return ModelConfigLogic.empty()
                }
            }
        }
        val migrated = migrateIfNeeded()
        return migrated ?: ModelConfigLogic.empty()
    }

    /** P2:配置损坏状态(非空 = 已重置,需重新配置);设置页/面板据此提示 */
    suspend fun configError(): String? =
        context.modelConfigStore.data.first()[CONFIG_ERROR_KEY]

    private suspend fun write(file: ModelConfigFile) {
        context.modelConfigStore.edit {
            it[JSON_KEY] = ModelConfigLogic.encode(file)
            it.remove(CONFIG_ERROR_KEY) // 重新保存即视为已重新配置
        }
    }

    /**
     * 迁移:仅当 JSON 为空且旧配置存在;旧键保留不动(可回滚)。
     * M3-C(audit#4):迁移仅补空,不覆盖用户配置——
     * - 入口处再查一次 JSON 键(非空即退出,load 之外被调用也不会覆盖);
     * - [ModelConfigLogic.migrateLegacy] 的 existing 参数保证既有 default/name/status 不被迁移产物覆盖。
     * 审计场景(重启后默认模型被改、服务商被改名「已迁移的配置」):JSON 空时迁移以旧配置
     * 全量生成并写入,压掉了用户已在 M3 UI 配好的模型——现合并语义下只补空位。
     * M3-C 评审 P2:CONFIG_ERROR 标记存在(配置损坏已重置)时绝不迁移——否则用户被告知
     * 「需重新配置」后一次重启即被旧配置迁移回流,错误提示也被 write() 清除。
     * 损坏态保持零配置 + 提示,直至用户重新配置保存。
     */
    private suspend fun migrateIfNeeded(): ModelConfigFile? {
        if (!configError().isNullOrBlank()) return null // 损坏态不迁移,保持「需重配」提示
        if (readRaw().isNotBlank()) return null // 防线:已有配置绝不迁移覆盖
        val settings = com.agentkeyboard.data.SettingsRepository(context)
        val cfg = settings.load()
        if (cfg.baseUrl.isNullOrBlank()) {
            write(ModelConfigLogic.empty())
            return null
        }
        val file = ModelConfigLogic.migrateLegacy(
            baseUrl = cfg.baseUrl,
            modelOverride = cfg.modelOverride,
            decryptLegacyKey = {
                cfg.apiKeyEncrypted?.let { enc ->
                    runCatching { SecureStore.decryptSecret(enc) }
                        .onFailure { Log.w(TAG, "legacy key decrypt failed: ${it.javaClass.simpleName}") }
                        .getOrNull()
                }
            },
            uuid = { java.util.UUID.randomUUID().toString() },
            now = System.currentTimeMillis(),
            existing = null, // 此时 JSON 已空(load 短路保证),无可合并的既有配置
        )
        if (file != null) {
            // 迁移出的 provider 带 Key(明文瞬间,重新加密落 model_secrets)
            cfg.apiKeyEncrypted?.let { enc ->
                runCatching { SecureStore.decryptSecret(enc) }.getOrNull()?.let { plain ->
                    saveKey(file.providers.first().id, plain)
                }
            }
            write(file)
            Logs.i(TAG, "legacy model config migrated: ${file.providers.size} provider(s)")
        }
        return file
    }

    // ==================== 密钥(§3.4:secrets 单独 DataStore,排除备份) ====================

    suspend fun saveKey(providerId: String, plain: String) = withContext(Dispatchers.IO) {
        val encrypted = encrypt(plain)
        context.modelConfigStore.edit { it[secretKey(providerId)] = encrypted }
    }

    suspend fun loadKey(providerId: String): String? = withContext(Dispatchers.IO) {
        val prefs = context.modelConfigStore.data.first()
        decryptKey(prefs, providerId)
    }

    private fun decryptKey(prefs: Preferences, providerId: String): String? =
        prefs[secretKey(providerId)]?.let { encrypted -> runCatching { decrypt(encrypted) }
            .onFailure { Log.w(TAG, "provider key decrypt failed: ${it.javaClass.simpleName}") }
            .getOrNull()
        }

    suspend fun hasKey(providerId: String): Boolean =
        context.modelConfigStore.data.first()[secretKey(providerId)] != null

    suspend fun deleteKey(providerId: String) {
        context.modelConfigStore.edit { it.remove(secretKey(providerId)) }
    }

    // ==================== 写操作(UI/Service 调用) ====================

    /** P1:所有读改写经进程级 Mutex 串行化(探测写回 vs UI 编辑 vs chip 连点不再互踩) */
    suspend fun update(file: (ModelConfigFile) -> ModelConfigFile) = writeMutex.withLock {
        write(file(loadLocked()))
    }

    internal suspend fun exportConnections(): ConnectionConfig = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val file = loadLocked()
            check(configError().isNullOrBlank())
            val prefs = context.modelConfigStore.data.first()
            val keys = file.providers.mapNotNull { provider ->
                prefs[secretKey(provider.id)]?.let { provider.id to decrypt(it) }
            }.toMap()
            ConnectionConfig(
                file, keys, decodeKnowledgeSettings(prefs[knowledgeConfigKey]),
                prefs[secretKey(ZhipuKnowledgeSettingsStore.SECRET_ID)]?.let(decrypt),
                prefs[tavilyCiphertextKey]?.let(decrypt),
            )
        }
    }

    internal suspend fun privacySnapshot(): ConnectionConfig = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val file = loadLocked()
            val prefs = context.modelConfigStore.data.first()
            ConnectionConfig(
                file,
                file.providers.mapNotNull { provider ->
                    decryptKey(prefs, provider.id)?.let { provider.id to it }
                }.toMap(),
                decodeKnowledgeSettings(prefs[knowledgeConfigKey]),
                decryptKey(prefs, ZhipuKnowledgeSettingsStore.SECRET_ID),
                prefs[tavilyCiphertextKey]?.let { runCatching { decrypt(it) }.getOrNull() },
            )
        }
    }

    internal suspend fun restoreConnections(config: ConnectionConfig) = withContext(Dispatchers.IO) {
        config.validate()
        val modelJson = ModelConfigLogic.encode(config.models)
        val knowledgeJson = encodeKnowledgeSettings(config.knowledge)
        val providerKeys = config.providerKeys.mapValues { (_, key) -> encrypt(key) }
        val knowledgeKey = config.knowledgeKey?.let(encrypt)
        val tavilyKey = config.tavilyKey?.let(encrypt)
        writeMutex.withLock {
            context.modelConfigStore.edit { prefs ->
                prefs.asMap().keys.filter { it.name.startsWith("key_") }.forEach { prefs.remove(it) }
                prefs[JSON_KEY] = modelJson
                prefs.remove(CONFIG_ERROR_KEY)
                prefs.remove(CORRUPT_JSON_KEY)
                providerKeys.forEach { (id, value) -> prefs[secretKey(id)] = value }
                prefs[knowledgeConfigKey] = knowledgeJson
                if (knowledgeKey == null) prefs.remove(secretKey(ZhipuKnowledgeSettingsStore.SECRET_ID))
                else prefs[secretKey(ZhipuKnowledgeSettingsStore.SECRET_ID)] = knowledgeKey
                if (tavilyKey == null) prefs.remove(tavilyCiphertextKey)
                else prefs[tavilyCiphertextKey] = tavilyKey
            }
        }
        Unit
    }

    suspend fun upsertProvider(provider: ProviderConfig) = update { file ->
        file.copy(providers = file.providers.filterNot { it.id == provider.id } + provider)
    }

    internal suspend fun updateProviderConnection(
        previous: ProviderConfig,
        updated: ProviderConfig,
        replacementKey: String?,
    ): String = withContext(Dispatchers.IO) {
        require(previous.id == updated.id)
        require(com.agentkeyboard.data.ModelConfig.validateBaseUrl(updated.baseUrl))
        require(previous.baseUrl.trimEnd('/') == updated.baseUrl.trimEnd('/') || !replacementKey.isNullOrBlank())
        writeMutex.withLock {
            val file = loadLocked()
            check(file.providers.firstOrNull { it.id == previous.id } == previous)
            val keyPref = secretKey(previous.id)
            val newCiphertext = replacementKey?.let(encrypt)
            var effectiveKey: String? = null
            val updatedFile = file.copy(providers = file.providers.map { if (it.id == updated.id) updated else it })
            context.modelConfigStore.edit { prefs ->
                effectiveKey = replacementKey ?: decryptKey(prefs, previous.id)
                check(!effectiveKey.isNullOrBlank())
                if (newCiphertext != null) prefs[keyPref] = newCiphertext
                prefs[JSON_KEY] = ModelConfigLogic.encode(updatedFile)
                prefs.remove(CONFIG_ERROR_KEY)
            }
            checkNotNull(effectiveKey)
        }
    }

    suspend fun upsertModel(model: ModelEntry) = update { file ->
        file.copy(models = file.models.filterNot { it.ref == model.ref } + model)
    }

    /**
     * P1 局部更新:按 ref 对单条模型打补丁——写回前重新 load 最新值,
     * 不用调用方持有的旧快照整条覆盖(否则会丢掉期间其他写入方改的字段)。
     */
    suspend fun updateModel(ref: String, patch: (ModelEntry) -> ModelEntry) = update { file ->
        file.copy(models = file.models.map { if (it.ref == ref) patch(it) else it })
    }

    suspend fun removeModel(ref: String) = update { ModelConfigLogic.afterRemoveModel(it, ref) }

    suspend fun removeProvider(providerId: String) = update { ModelConfigLogic.afterRemoveProvider(it, providerId) }

    suspend fun setDefaultModel(ref: String) = update { it.copy(defaultModelRef = ref) }

    suspend fun markUsed(ref: String) = update { ModelConfigLogic.markUsed(it, ref, System.currentTimeMillis()) }

    /**
     * 生效模型解析(引擎每次 LLM 步骤调用):默认模型 + Key → ActiveModelConfig。
     * default 缺失/失效自动回退(§6);全空返回 null(面板空态 P2)。
     */
    suspend fun loadActiveModel(refOverride: String? = null): ActiveModelConfig? =
        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                val file = loadLocked()
                val model = ModelConfigLogic.resolveDefaultModel(file, refOverride) ?: return@withLock null
                val provider = ModelConfigLogic.resolveProvider(file, model.providerId) ?: return@withLock null
                val key = decryptKey(context.modelConfigStore.data.first(), provider.id)
                if (key.isNullOrBlank()) return@withLock null
                ActiveModelConfig(
                    baseUrl = provider.baseUrl,
                    apiKey = key,
                    model = model.modelId,
                    toolsAllowed = model.effectiveFc(),
                    contextWindow = model.contextWindow,
                    ref = model.ref,
                )
            }
        }

    internal suspend fun loadKnowledgeConnection() = withContext(Dispatchers.IO) {
        val prefs = context.modelConfigStore.data.first()
        decodeKnowledgeSettings(prefs[knowledgeConfigKey]) to
            decryptKey(prefs, ZhipuKnowledgeSettingsStore.SECRET_ID)
    }

    internal suspend fun saveKnowledgeConnection(
        settings: com.agentkeyboard.rag.ZhipuKnowledgeSettings,
        key: String,
        expected: Pair<com.agentkeyboard.rag.ZhipuKnowledgeSettings, String?>,
    ) = withContext(Dispatchers.IO) {
        val secretId = ZhipuKnowledgeSettingsStore.SECRET_ID
        val encrypted = encrypt(key)
        context.modelConfigStore.edit { prefs ->
            check((decodeKnowledgeSettings(prefs[knowledgeConfigKey]) to decryptKey(prefs, secretId)) == expected)
            prefs[knowledgeConfigKey] = encodeKnowledgeSettings(settings)
            prefs[secretKey(secretId)] = encrypted
        }
        Unit
    }
}
