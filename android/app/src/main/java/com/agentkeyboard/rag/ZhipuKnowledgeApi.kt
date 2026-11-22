package com.agentkeyboard.rag

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.agentkeyboard.data.knowledgeConfigKey
import com.agentkeyboard.data.model.ModelStore
import com.agentkeyboard.data.model.modelConfigStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.serialization.Serializable

data class ZhipuKnowledgeBase(val id: String, val name: String, val documentCount: Int? = null)

data class ZhipuKnowledgeHit(
    val id: String,
    val text: String,
    val documentName: String,
    val ordinal: Int,
    val score: Float?,
)

/** Non-secret settings for the selected remote knowledge base. The key stays in ModelStore's encrypted store. */
@Serializable
data class ZhipuKnowledgeSettings(
    val knowledgeId: String = "",
    val knowledgeName: String = "",
    val documentCount: Int? = null,
    val provider: KnowledgeProvider = KnowledgeProvider.ZHIPU,
    val baseUrl: String = "",
)

class ZhipuKnowledgeSettingsStore(
    private val context: Context,
    private val modelStore: ModelStore = ModelStore(context.applicationContext),
) {
    companion object {
        const val SECRET_ID = "zhipu_knowledge"
    }

    suspend fun load(): ZhipuKnowledgeSettings =
        decodeKnowledgeSettings(context.modelConfigStore.data.first()[knowledgeConfigKey])

    suspend fun save(settings: ZhipuKnowledgeSettings) {
        require(settings.knowledgeId.isNotBlank()) { "请选择知识库" }
        val raw = encodeKnowledgeSettings(settings)
        context.modelConfigStore.edit { it[knowledgeConfigKey] = raw }
    }

    suspend fun saveApiKey(key: String) {
        require(key.isNotBlank() && '\r' !in key && '\n' !in key) { "智谱 API Key 无效" }
        modelStore.saveKey(SECRET_ID, key.trim())
    }

    suspend fun loadApiKey(): String? = modelStore.loadKey(SECRET_ID)?.takeIf(String::isNotBlank)
    suspend fun hasApiKey(): Boolean = modelStore.hasKey(SECRET_ID)
    suspend fun isConfigured(): Boolean {
        val prefs = context.modelConfigStore.data.first()
        return decodeKnowledgeSettings(prefs[knowledgeConfigKey]).knowledgeId.isNotBlank() &&
            prefs[ModelStore.secretKey(SECRET_ID)] != null
    }
    suspend fun deleteApiKey() = modelStore.deleteKey(SECRET_ID)
}

internal fun decodeKnowledgeSettings(raw: String?): ZhipuKnowledgeSettings {
    if (raw.isNullOrBlank()) return ZhipuKnowledgeSettings()
    return runCatching {
        val obj = JSONObject(raw)
        ZhipuKnowledgeSettings(
            knowledgeId = obj.optString("knowledgeId"),
            knowledgeName = obj.optString("knowledgeName"),
            documentCount = if (obj.has("documentCount") && !obj.isNull("documentCount")) obj.optInt("documentCount") else null,
            provider = KnowledgeProvider.valueOf(obj.optString("provider", KnowledgeProvider.ZHIPU.name)),
            baseUrl = obj.optString("baseUrl"),
        )
    }.getOrDefault(ZhipuKnowledgeSettings())
}

internal fun encodeKnowledgeSettings(settings: ZhipuKnowledgeSettings): String = JSONObject()
    .put("knowledgeId", settings.knowledgeId.trim())
    .put("knowledgeName", settings.knowledgeName.trim())
    .put("documentCount", settings.documentCount)
    .put("provider", settings.provider.name)
    .put("baseUrl", settings.baseUrl)
    .toString()

/** API transport keeps bearer credentials off redirects and bounds response memory. */
data class ZhipuHttpRequest(
    val url: String,
    val method: String,
    val headers: Map<String, String>,
    val body: ByteArray? = null,
    val timeoutMs: Int = 30_000,
)

data class ZhipuHttpResponse(val status: Int, val body: String)

fun interface ZhipuHttpTransport {
    suspend fun execute(request: ZhipuHttpRequest): ZhipuHttpResponse
}

object SafeZhipuHttpTransport : ZhipuHttpTransport {
    private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024

    override suspend fun execute(request: ZhipuHttpRequest): ZhipuHttpResponse = withContext(Dispatchers.IO) {
        val connection = URL(request.url).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.requestMethod = request.method
            connection.connectTimeout = request.timeoutMs
            connection.readTimeout = request.timeoutMs
            request.headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            request.body?.let { body ->
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (out.size() <= MAX_RESPONSE_BYTES) {
                    val count = input.read(buffer, 0, minOf(buffer.size, MAX_RESPONSE_BYTES + 1 - out.size()))
                    if (count < 0) break
                    out.write(buffer, 0, count)
                }
                if (out.size() > MAX_RESPONSE_BYTES) throw IOException("智谱响应超过限制")
                out.toByteArray()
            } ?: ByteArray(0)
            ZhipuHttpResponse(status, bytes.toString(Charsets.UTF_8))
        } finally {
            runCatching { connection.disconnect() }
        }
    }
}

/** Direct Zhipu knowledge API. No custom rerank model is configured for retrieval. */
class ZhipuKnowledgeApi(
    private val apiKey: String,
    private val transport: ZhipuHttpTransport = SafeZhipuHttpTransport,
) {
    init {
        require(apiKey.isNotBlank() && '\r' !in apiKey && '\n' !in apiKey) { "智谱 API Key 无效" }
    }

    suspend fun listKnowledgeBases(page: Int = 1, size: Int = 50): List<ZhipuKnowledgeBase> {
        require(page > 0 && size in 1..100)
        val response = request(
            method = "GET",
            path = "/knowledge?page=$page&size=$size",
        )
        return dataArray(response).mapNotNull { row ->
            val id = row.firstString("knowledge_id", "id") ?: return@mapNotNull null
            val name = row.firstString("name", "knowledge_name") ?: id
            // 2026-11-19 真实 API 验证:列表接口字段为 document_size;旧键保留兼容
            val count = row.firstNumber("document_count", "document_num", "file_count", "document_size")?.toInt()
            ZhipuKnowledgeBase(id, name, count)
        }
    }

    suspend fun retrieve(knowledgeId: String, query: String, topK: Int = 3): List<ZhipuKnowledgeHit> {
        require(knowledgeId.isNotBlank()) { "尚未配置智谱知识库" }
        require(query.isNotBlank()) { "检索问题不能为空" }
        require(query.length <= 1000) { "检索问题超过 1000 字限制" }
        require(topK in 1..20)
        val payload = JSONObject()
            .put("query", query)
            .put("knowledge_ids", JSONArray().put(knowledgeId))
            .put("request_id", UUID.randomUUID().toString())
            .put("top_k", topK)
            .put("top_n", maxOf(topK, 20))
            .put("recall_method", "mixed")
            .put("recall_ratio", 80)
        val response = request("POST", "/knowledge/retrieve", payload.toString().toByteArray(Charsets.UTF_8))
        return dataArray(response).mapIndexedNotNull { index, row ->
            val text = row.firstString("text", "content", "chunk_content") ?: return@mapIndexedNotNull null
            val metadata = row.optJSONObject("metadata") ?: row.optJSONObject("meta") ?: JSONObject()
            val id = metadata.firstString("_id", "segment_id") ?: row.firstString("segment_id", "chunk_id", "id") ?: "$knowledgeId-$index"
            val documentName = metadata.firstString("doc_name", "document_name", "file_name")
                ?: row.firstString("document_name", "file_name", "document")
                ?: "资料文档"
            val ordinal = (row.opt("ordinal") as? Number)?.toInt()
                ?: (row.opt("chunk_index") as? Number)?.toInt()
                ?: -1
            val score = (row.opt("score") as? Number)?.toFloat()
                ?: (row.opt("similarity") as? Number)?.toFloat()
            ZhipuKnowledgeHit(id, text, documentName, ordinal, score)
        }
    }

    private suspend fun request(
        method: String,
        path: String,
        body: ByteArray? = null,
        contentType: String = "application/json",
        timeoutMs: Int = 30_000,
    ): String {
        require(path.startsWith('/') && !path.contains(".."))
        val headers = mutableMapOf(
            "Authorization" to "Bearer $apiKey",
            "Accept" to "application/json",
            "Accept-Encoding" to "identity",
        )
        if (body != null) headers["Content-Type"] = contentType
        val response = transport.execute(ZhipuHttpRequest(BASE_URL + path, method, headers, body, timeoutMs))
        if (response.status !in 200..299) {
            val reason = when (response.status) {
                401, 403 -> "智谱知识库认证失败 (HTTP ${response.status})"
                404 -> "智谱知识库或接口不存在 (HTTP 404)"
                413 -> "文档超过服务端大小限制 (HTTP 413)"
                429 -> "智谱服务请求过于频繁 (HTTP 429)"
                in 500..599 -> "智谱知识库暂不可用 (HTTP ${response.status})"
                else -> "智谱知识库请求失败 (HTTP ${response.status})"
            }
            throw IllegalStateException(reason)
        }
        val root = runCatching { JSONObject(response.body) }.getOrElse { throw IllegalStateException("智谱知识库响应格式无效") }
        if (root.has("code") && root.optInt("code") != 200) {
            throw IllegalStateException("智谱知识库请求失败 (API ${root.optInt("code")})")
        }
        if (root.has("success") && !root.optBoolean("success", true)) {
            throw IllegalStateException("智谱知识库请求未成功")
        }
        return response.body
    }

    private fun dataArray(raw: String): List<JSONObject> {
        val root = JSONObject(raw)
        val data = root.opt("data")
        val array = when (data) {
            is JSONArray -> data
            is JSONObject -> data.optJSONArray("list") ?: data.optJSONArray("items") ?: data.optJSONArray("results")
            else -> null
        } ?: root.optJSONArray("results") ?: JSONArray()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }

    private fun JSONObject.firstString(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
        optString(key).trim().takeIf { it.isNotEmpty() && it != "null" }
    }

    private fun JSONObject.firstNumber(vararg keys: String): Number? = keys.firstNotNullOfOrNull { key -> opt(key) as? Number }

    companion object {
        private const val BASE_URL = "https://open.bigmodel.cn/api/llm-application/open"
    }
}

/** Cloud adapter for the existing Agent retrieval contract. */
class ZhipuKnowledgeRetriever(
    private val api: ZhipuKnowledgeApi,
    private val knowledgeId: String,
    private val documentCount: Int? = null,
) : KnowledgeRetriever {
    override suspend fun search(query: String, topK: Int): List<RetrievedChunk> =
        api.retrieve(knowledgeId, query, topK).mapIndexed { index, hit ->
            RetrievedChunk(
                chunkId = (hit.id.hashCode().toLong() shl 32) xor index.toLong(),
                text = hit.text,
                docName = hit.documentName,
                ordinal = hit.ordinal,
                score = hit.score ?: 0f,
                matchedText = hit.text,
                relevanceLabel = hit.score?.let { String.format(java.util.Locale.US, "相关度 %.2f", it) },
            )
        }

    override suspend fun isEmpty(): Boolean = documentCount == 0
}
