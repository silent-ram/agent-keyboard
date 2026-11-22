package com.agentkeyboard.rag

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import java.net.URI

class ZhipuKnowledgeApiTest {
    @Test
    fun `lists knowledge bases without leaking key into url`() = runBlocking {
        val requests = mutableListOf<ZhipuHttpRequest>()
        val transport = ZhipuHttpTransport { request ->
            requests += request
            ZhipuHttpResponse(200, """{"data":[{"knowledge_id":"kb-1","name":"手册"}]}""")
        }
        val api = ZhipuKnowledgeApi("secret-test-key", transport)

        assertEquals(listOf(ZhipuKnowledgeBase("kb-1", "手册")), api.listKnowledgeBases())
        assertEquals("Bearer secret-test-key", requests.first().headers["Authorization"])
        assertFalse(requests.any { it.url.contains("secret-test-key") })
        val listUrl = URI(requests.first().url)
        assertEquals("open.bigmodel.cn", listUrl.host)
        assertEquals("/api/llm-application/open/knowledge", listUrl.path)
        assertEquals("page=1&size=50", listUrl.query)
        assertEquals(1, requests.size)
    }

    @Test
    fun `retrieval uses documented mixed recall and maps provenance`() = runBlocking {
        lateinit var request: ZhipuHttpRequest
        val api = ZhipuKnowledgeApi("test-key", ZhipuHttpTransport {
            request = it
            ZhipuHttpResponse(
                200,
                """{"data":[{"text":"验收要求正文","score":0.92,"metadata":{"_id":"seg-1","doc_name":"程序规范.txt","doc_id":"doc-4"}}]}""",
            )
        })

        val hits = api.retrieve("kb-1", "验收要求是什么？")

        val payload = JSONObject(request.body!!.toString(Charsets.UTF_8))
        assertEquals("kb-1", payload.getJSONArray("knowledge_ids").getString(0))
        assertEquals("验收要求是什么？", payload.getString("query"))
        assertFalse(payload.has("rerank_status"))
        assertEquals("seg-1", hits.single().id)
        assertEquals("程序规范.txt", hits.single().documentName)
        assertEquals(-1, hits.single().ordinal)
        assertEquals(0.92f, hits.single().score!!, 0.0001f)
    }

    @Test
    fun `authentication failures are status-only and never echo response body`() = runBlocking {
        val api = ZhipuKnowledgeApi("test-key", ZhipuHttpTransport {
            ZhipuHttpResponse(401, "secret-test-key was rejected; request body echoed")
        })

        val error = runCatching { api.listKnowledgeBases() }.exceptionOrNull()!!

        assertEquals("智谱知识库认证失败 (HTTP 401)", error.message)
        assertFalse(error.message.orEmpty().contains("secret-test-key"))
        assertFalse(error.message.orEmpty().contains("request body"))
    }
}
