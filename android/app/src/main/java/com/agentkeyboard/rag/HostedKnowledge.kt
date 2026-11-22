package com.agentkeyboard.rag

import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

enum class KnowledgeProvider(val label: String, private val apiPath: String) {
    ZHIPU("智谱", ""),
    DIFY("Dify", "/v1"),
    RAGFLOW("RAGFlow", "/api/v1");

    internal fun validBaseUrl(value: String): String? {
        val url = value.trim().trimEnd('/')
        if (this == ZHIPU) return url.takeIf { it.isEmpty() }
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        return url.takeIf {
            uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() &&
                uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
                uri.normalize() == uri && uri.rawPath.endsWith(apiPath)
        }
    }

    internal fun connect(
        address: String,
        key: String,
        transport: ZhipuHttpTransport = SafeZhipuHttpTransport,
    ): HostedKnowledge {
        val url = requireNotNull(validBaseUrl(address))
        require(key.isNotBlank() && key.none(Char::isISOControl))
        if (this == ZHIPU) {
            val api = ZhipuKnowledgeApi(key, transport)
            return HostedKnowledge(
                listPage = api::listKnowledgeBases,
                retrieve = { id, query, limit -> ZhipuKnowledgeRetriever(api, id).search(query, limit) },
            )
        }
        val api = DatasetApi(this, url, key, transport)
        return HostedKnowledge(api::list, api::retrieve)
    }
}

internal class HostedKnowledge(
    private val listPage: suspend (Int, Int) -> List<ZhipuKnowledgeBase>,
    val retrieve: suspend (String, String, Int) -> List<RetrievedChunk>,
) {
    suspend fun list(): List<ZhipuKnowledgeBase> = buildList {
        for (page in 1..20) {
            val batch = listPage(page, 50)
            addAll(batch)
            if (batch.size < 50) break
        }
    }.distinctBy { it.id }

    fun retriever(settings: ZhipuKnowledgeSettings): KnowledgeRetriever = object : KnowledgeRetriever {
        override suspend fun search(query: String, topK: Int) = retrieve(settings.knowledgeId, query, topK)
        override suspend fun isEmpty() = settings.documentCount == 0
    }
}

private class DatasetApi(
    private val provider: KnowledgeProvider,
    private val baseUrl: String,
    private val key: String,
    private val transport: ZhipuHttpTransport,
) {
    private val dify = provider == KnowledgeProvider.DIFY

    suspend fun list(page: Int, size: Int): List<ZhipuKnowledgeBase> {
        val limit = if (dify) "limit" else "page_size"
        return request("/datasets?page=$page&$limit=$size").getJSONArray("data").objects().mapNotNull { row ->
            val id = row.optString("id").takeIf { datasetId.matches(it) } ?: return@mapNotNull null
            ZhipuKnowledgeBase(id, row.optString("name"), (row.opt("document_count") as? Number)?.toInt())
        }
    }

    suspend fun retrieve(id: String, query: String, topK: Int): List<RetrievedChunk> {
        require(datasetId.matches(id) && query.isNotBlank() && query.length <= 1000 && topK in 1..20)
        val rows = if (dify) {
            val body = JSONObject().put("query", query).put("retrieval_model", JSONObject()
                .put("search_method", "semantic_search")
                .put("reranking_enable", false)
                .put("score_threshold_enabled", false)
                .put("top_k", topK))
            request("/datasets/$id/retrieve", body).getJSONArray("records")
        } else {
            val body = JSONObject().put("question", query).put("dataset_ids", JSONArray().put(id))
                .put("page", 1).put("page_size", topK)
            request("/retrieval", body).getJSONObject("data").getJSONArray("chunks")
        }
        return rows.objects().mapIndexedNotNull { index, row ->
            val segment = if (dify) row.optJSONObject("segment") ?: return@mapIndexedNotNull null else row
            val text = segment.optString("content").trim().takeIf(String::isNotEmpty) ?: return@mapIndexedNotNull null
            val document = if (dify) segment.optJSONObject("document")?.optString("name") else segment.optString("document_keyword")
            RetrievedChunk(
                chunkId = (segment.optString("id").hashCode().toLong() shl 32) xor index.toLong(),
                text = text,
                docName = document?.takeIf(String::isNotBlank) ?: "资料文档",
                ordinal = if (dify) segment.optInt("position", 0) - 1 else -1,
                score = (row.opt(if (dify) "score" else "similarity") as? Number)?.toFloat() ?: 0f,
            )
        }.take(topK)
    }

    private suspend fun request(path: String, body: JSONObject? = null): JSONObject {
        val headers = mutableMapOf("Authorization" to "Bearer $key", "Accept" to "application/json")
        if (body != null) headers["Content-Type"] = "application/json"
        val response = transport.execute(ZhipuHttpRequest(
            baseUrl + path, if (body == null) "GET" else "POST", headers, body?.toString()?.encodeToByteArray(),
        ))
        check(response.status in 200..299)
        return JSONObject(response.body).also { if (!dify) check(it.optInt("code", -1) == 0) }
    }

    private fun JSONArray.objects() = (0 until length()).mapNotNull(::optJSONObject)

    private companion object {
        val datasetId = Regex("[A-Za-z0-9_-]{1,128}")
    }
}
