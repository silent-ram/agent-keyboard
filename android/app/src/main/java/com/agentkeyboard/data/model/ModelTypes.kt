package com.agentkeyboard.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 服务商状态(10 号设计 §3.1) */
object ProviderStatus {
    const val UNVERIFIED = "UNVERIFIED"
    const val OK = "OK"
    const val ERROR = "ERROR"
}

/** 最近失败分类码(§3.1 errorCode) */
object ProviderErrorCodes {
    const val URL_INVALID = "URL_INVALID"
    const val NETWORK = "NETWORK"
    const val AUTH = "AUTH"
    const val MODELS_EMPTY = "MODELS_EMPTY"
    const val PATH_404 = "PATH_404"
}

/** function calling 自动判定(§3.2 fcAuto 五态) */
object FcAuto {
    const val PRESET_YES = "PRESET_YES"
    const val PRESET_NO = "PRESET_NO"
    const val VERIFIED_YES = "VERIFIED_YES"
    const val VERIFIED_NO = "VERIFIED_NO"
    const val UNKNOWN = "UNKNOWN"
}

/** 模型来源 */
object ModelSource {
    const val FETCHED = "FETCHED"
    const val MANUAL = "MANUAL"
}

/** Provider(§3.1;id 创建后不可改,会话与默认模型引用它) */
@Serializable
data class ProviderConfig(
    val id: String,
    val name: String,
    val baseUrl: String,
    val protocol: String = "OPENAI_COMPAT",
    val status: String = ProviderStatus.UNVERIFIED,
    val errorCode: String = "",
    val createdAt: Long = 0L,
)

/** Model(§3.2;ref = {providerId}/{modelId}) */
@Serializable
data class ModelEntry(
    val ref: String,
    val providerId: String,
    val modelId: String,
    val displayName: String = "",
    val fcAuto: String = FcAuto.UNKNOWN,
    val fcOverride: Int = -1, // -1 未覆盖 / 0 强制关 / 1 强制开
    val contextWindow: Int = 0, // 0=未知
    val source: String = ModelSource.FETCHED,
    val offlineFlag: Boolean = false,
    val lastUsedAt: Long = 0L,
    /** P1(评审修正单):fc 探测连续 INCONCLUSIVE 计数(≥2 停止自动探测,防重复烧钱) */
    val probeFails: Int = 0,
) {
    fun display(): String = displayName.ifBlank { modelId }

    /** fc 生效值(§4.2:覆盖永远优先于自动结果);null=UNKNOWN(未知) */
    fun effectiveFc(): Boolean? = when {
        fcOverride == 0 -> false
        fcOverride == 1 -> true
        else -> when (fcAuto) {
            FcAuto.PRESET_YES, FcAuto.VERIFIED_YES -> true
            FcAuto.PRESET_NO, FcAuto.VERIFIED_NO -> false
            else -> null
        }
    }

    /** 判定来源小字(P7:内置已知列表 / 已自动探测 / 手动指定) */
    fun fcSourceLabel(): String = when {
        fcOverride >= 0 -> "手动指定"
        fcAuto == FcAuto.PRESET_YES || fcAuto == FcAuto.PRESET_NO -> "内置已知列表"
        fcAuto == FcAuto.VERIFIED_YES || fcAuto == FcAuto.VERIFIED_NO -> "已自动探测"
        else -> "未验证"
    }
}

/** model_config.json 顶层(§3.3) */
@Serializable
data class ModelConfigFile(
    val version: Int = 1,
    val providers: List<ProviderConfig> = emptyList(),
    val models: List<ModelEntry> = emptyList(),
    val defaultModelRef: String = "",
)

/** 内置预设(§3.4 App 内置预设表,只读不落盘) */
data class ProviderPreset(val key: String, val name: String, val baseUrl: String) {
    companion object {
        val ALL = listOf(
            ProviderPreset("deepseek", "DeepSeek", "https://api.deepseek.com"),
            ProviderPreset("zhipu", "智谱 GLM", "https://open.bigmodel.cn/api/paas/v4"),
            ProviderPreset("siliconflow", "硅基流动", "https://api.siliconflow.cn/v1"),
            ProviderPreset("openai", "OpenAI", "https://api.openai.com/v1"),
            ProviderPreset("dashscope", "阿里云百炼", "https://dashscope.aliyuncs.com/compatible-mode/v1"),
            ProviderPreset("custom", "OpenAI 兼容", ""),
        )
    }
}

/** fc 预置白名单(§4.2 预置表;命中立即标 PRESET_YES/NO) */
object FcPresetTable {
    private val table = mapOf(
        "deepseek-chat" to FcAuto.PRESET_YES,
        "deepseek-reasoner" to FcAuto.PRESET_NO,
    )

    fun of(modelId: String): String? = table[modelId]
}

/**
 * 模型配置纯逻辑(全部可 JVM 单测;ModelStore 薄壳调用):
 * JSON roundtrip、M0 旧配置迁移、默认模型回退、/models 响应解析、错误分类。
 */
object ModelConfigLogic {

    val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(file: ModelConfigFile): String = json.encodeToString(file)

    fun decode(raw: String): ModelConfigFile =
        runCatching { json.decodeFromString<ModelConfigFile>(raw) }.getOrDefault(ModelConfigFile())

    /** decode 结果(P2:配置损坏不再静默,由 ModelStore 落备份与错误状态) */
    sealed interface DecodeResult {
        data class Ok(val file: ModelConfigFile) : DecodeResult

        /** [reason] 仅记日志,不落用户可读存储 */
        data class Corrupt(val raw: String, val reason: String) : DecodeResult
    }

    fun decodeChecked(raw: String): DecodeResult =
        runCatching { json.decodeFromString<ModelConfigFile>(raw) }
            .fold(
                onSuccess = { DecodeResult.Ok(it) },
                onFailure = { DecodeResult.Corrupt(raw, "${it.javaClass.simpleName}: ${it.message}") },
            )

    fun empty(): ModelConfigFile = ModelConfigFile()

    fun modelRef(providerId: String, modelId: String): String = "$providerId/$modelId"

    /**
     * M0 旧配置迁移(批次 3):旧版 DataStore 的 baseUrl/key/modelOverride 单条配置
     * → 一个 Provider + 一个 MANUAL 模型 + 默认引用。无旧配置返回 null。
     * [decryptLegacyKey] 解旧 Key 密文(可能失败:返回 null 则 provider 无 Key,status UNVERIFIED)。
     *
     * M3-C(audit#4)仅补空语义:传入 [existing](可能已存在的用户配置)时,迁移产物不得覆盖——
     * - 命中同 baseUrl 的既有 provider:原样保留其 name/status/模型(名称仅在空时填);
     * - default_model_ref 非空则跳过默认设置(迁移不得改用户已选默认模型);
     * - 既有模型列表不动,迁移模型仅在未出现同 modelId 时追加。
     */
    fun migrateLegacy(
        baseUrl: String?,
        modelOverride: String?,
        decryptLegacyKey: () -> String?,
        uuid: () -> String,
        now: Long,
        existing: ModelConfigFile? = null,
    ): ModelConfigFile? {
        val url = baseUrl?.trim().orEmpty()
        if (url.isEmpty()) return null
        val providerId = uuid()
        val modelId = modelOverride?.trim().takeUnless { it.isNullOrEmpty() }
            ?: com.agentkeyboard.data.ModelConfig.pickModel(url)
        val provider = ProviderConfig(
            id = providerId,
            name = "已迁移的配置",
            baseUrl = url,
            // P3(评审修正单):原 if/else 两分支相同系笔误;按 §3.1 迁移后一律待验证(有 key 可验证,无 key 先补 key)
            status = ProviderStatus.UNVERIFIED,
            createdAt = now,
        )
        val model = ModelEntry(
            ref = modelRef(providerId, modelId),
            providerId = providerId,
            modelId = modelId,
            source = ModelSource.MANUAL,
            fcAuto = FcPresetTable.of(modelId) ?: FcAuto.UNKNOWN,
            lastUsedAt = now,
        )
        val base = existing
        if (base == null) {
            return ModelConfigFile(
                providers = listOf(provider),
                models = listOf(model),
                defaultModelRef = model.ref,
            )
        }
        // ===== 仅补空合并:既有配置一字段不动,迁移内容只往空位补 =====
        // 同 baseUrl 的既有 provider 优先:迁移 provider 不落盘(名称仅在空时填的载体即它)
        val matched = base.providers.firstOrNull { it.baseUrl == provider.baseUrl }
        val providers = if (matched != null) {
            // 名称仅在空时填;status/model 列表一律不覆盖
            base.providers.map {
                if (it.id == matched.id && it.name.isBlank()) it.copy(name = provider.name) else it
            }
        } else {
            base.providers + provider
        }
        val matchedId = matched?.id
        val models = if (matchedId != null) {
            // 该 provider 下已有同 modelId → 不重复追加
            val hasSame = base.models.any { it.providerId == matchedId && it.modelId == modelId }
            if (hasSame) base.models else base.models + model.copy(
                ref = modelRef(matchedId, modelId),
                providerId = matchedId,
            )
        } else {
            base.models + model
        }
        // default_model_ref 非空 → 保持用户已选;空 → 回退 lastUsedAt 最新(含新迁入模型)
        val defaultRef = base.defaultModelRef.ifBlank {
            ModelConfigFile(providers = providers, models = models).let { resolveDefaultModel(it)?.ref.orEmpty() }
        }
        return base.copy(providers = providers, models = models, defaultModelRef = defaultRef)
    }

    /**
     * 默认模型回退(§6):default_model_ref 失效(被删/其 Provider 被删/offline)
     * → 回退到 lastUsedAt 最新的有效模型;一个都没有 → null(回到零配置空态 P2)。
     */
    fun resolveDefaultModel(file: ModelConfigFile, refOverride: String? = null): ModelEntry? {
        val valid = file.models.filter { m ->
            !m.offlineFlag && file.providers.any { it.id == m.providerId }
        }
        if (valid.isEmpty()) return null
        val want = refOverride ?: file.defaultModelRef
        valid.firstOrNull { it.ref == want }?.let { return it }
        return valid.maxByOrNull { it.lastUsedAt }
    }

    fun resolveProvider(file: ModelConfigFile, providerId: String): ProviderConfig? =
        file.providers.firstOrNull { it.id == providerId }

    /** 未验证能力时仅显示模型名。 */
    fun chipLabel(model: ModelEntry?, providerName: String? = null): String? {
        if (model == null) return null
        val name = model.display()
        return when (model.effectiveFc()) {
            true -> "⚡ $name"
            false -> "$name · 单轮"
            null -> name
        }
    }

    /** GET /models 响应解析:提取 data[].id(openai 列表格式),容忍缺字段 */
    fun parseModelList(body: String): List<String> =
        runCatching {
            val root = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(body)
            val data = root.get("data") ?: return emptyList()
            (0 until data.size()).mapNotNull { data[it].get("id")?.asText() }
        }.getOrDefault(emptyList())

    /** 拉取失败分类(§4.1 错误分支 → errorCode 五态) */
    fun classifyFetchError(url: String?, status: Int?, timedOut: Boolean, connectivity: Boolean): String = when {
        !com.agentkeyboard.data.ModelConfig.validateBaseUrl(url ?: "") -> ProviderErrorCodes.URL_INVALID
        status == 401 || status == 403 -> ProviderErrorCodes.AUTH
        status == 404 -> ProviderErrorCodes.PATH_404
        timedOut || connectivity -> ProviderErrorCodes.NETWORK
        else -> ProviderErrorCodes.NETWORK
    }

    /** 重新拉取:FETCHED 模型与最新列表 diff,缺失者标 offline;MANUAL 永不自动删(§6) */
    fun applyFetchedModels(
        file: ModelConfigFile,
        providerId: String,
        fetched: List<String>,
        now: Long,
    ): ModelConfigFile {
        val existing = file.models.filter { it.providerId == providerId }
        val fetchedDistinct = fetched.distinct()
        val newRefs = fetchedDistinct.map { modelRef(providerId, it) }.toSet()
        val kept = existing.map { m ->
            if (m.source == ModelSource.MANUAL) m
            else if (m.modelId in fetchedDistinct) m.copy(offlineFlag = false)
            else m.copy(offlineFlag = true)
        }
        val keptIds = kept.map { it.modelId }.toSet()
        val added = fetchedDistinct.filterNot { it in keptIds }.map { modelId ->
            ModelEntry(
                ref = modelRef(providerId, modelId),
                providerId = providerId,
                modelId = modelId,
                fcAuto = FcPresetTable.of(modelId) ?: FcAuto.UNKNOWN,
                source = ModelSource.FETCHED,
                lastUsedAt = 0L,
            )
        }
        val def = file.defaultModelRef
        // 默认引用失效(被删或重拉后标 offline)→ 回退 lastUsedAt 最新有效模型(§6)
        val defStillValid = (kept + added).any { it.ref == def && !it.offlineFlag }
        val newDefault = if (!defStillValid) {
            ModelConfigFile(providers = file.providers, models = kept + added).let {
                resolveDefaultModel(it)?.ref.orEmpty()
            }
        } else def
        return file.copy(
            providers = file.providers,
            models = kept + added,
            defaultModelRef = newDefault,
        )
    }

    /** 删除模型后的默认回退(§6:自动回退到 lastUsedAt 最新) */
    fun afterRemoveModel(file: ModelConfigFile, ref: String): ModelConfigFile {
        val models = file.models.filterNot { it.ref == ref }
        val newDefault = if (file.defaultModelRef == ref) {
            ModelConfigFile(providers = file.providers, models = models)
                .let { resolveDefaultModel(it)?.ref.orEmpty() }
        } else file.defaultModelRef
        return file.copy(models = models, defaultModelRef = newDefault)
    }

    /** 删除服务商:级联删除其模型(§3.2);默认引用回退 */
    fun afterRemoveProvider(file: ModelConfigFile, providerId: String): ModelConfigFile {
        val models = file.models.filterNot { it.providerId == providerId }
        val defGone = file.models.any { it.ref == file.defaultModelRef && it.providerId == providerId }
        val newDefault = if (defGone) {
            ModelConfigFile(providers = file.providers, models = models)
                .let { resolveDefaultModel(it)?.ref.orEmpty() }
        } else file.defaultModelRef
        return file.copy(
            providers = file.providers.filterNot { it.id == providerId },
            models = models,
            defaultModelRef = newDefault,
        )
    }

    /** 记录模型使用(chip 选中/发送后):刷新 lastUsedAt 排序(P1 最近使用) */
    fun markUsed(file: ModelConfigFile, ref: String, now: Long): ModelConfigFile =
        file.copy(models = file.models.map { if (it.ref == ref) it.copy(lastUsedAt = now) else it })
}
