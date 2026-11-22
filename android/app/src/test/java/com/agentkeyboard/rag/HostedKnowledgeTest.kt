package com.agentkeyboard.rag

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.agentkeyboard.libraryOptionLabel

class HostedKnowledgeTest {
    @Test
    fun `picker label contains only an ordinal and optional document count`() {
        assertEquals("资料库 2 · 3 个文档", libraryOptionLabel(1, 3))
        assertEquals("资料库 1", libraryOptionLabel(0, null))
    }

    @Test
    fun `old settings still use the Zhipu endpoint`() {
        val settings = decodeKnowledgeSettings("""{"knowledgeId":"legacy","knowledgeName":"Old library"}""")
        assertEquals(KnowledgeProvider.ZHIPU, settings.provider)
        assertEquals("legacy", settings.knowledgeId)
        assertEquals(settings, decodeKnowledgeSettings(encodeKnowledgeSettings(settings)))
    }

    @Test
    fun `Dify reads libraries and retrieves evidence using the saved endpoint`() = runTest {
        val requests = mutableListOf<ZhipuHttpRequest>()
        val provider = KnowledgeProvider.DIFY
        val service = provider.connect("https://kb.example/v1", "fixture", transport(requests,
            """{"data":[{"id":"library-a","name":"Private library","document_count":3}],"has_more":false}""",
            """{"records":[{"score":0.8,"segment":{"id":"chunk-a","content":"Example text","position":2,"document":{"name":"guide.txt"}}}]}""",
        ))
        val base = service.list().single()
        val hits = service.retrieve(base.id, "example question", 3)
        assertEquals("https://kb.example/v1/datasets?page=1&limit=50", requests[0].url)
        assertEquals("https://kb.example/v1/datasets/library-a/retrieve", requests[1].url)
        assertEquals("example question", JSONObject(requests[1].body!!.decodeToString()).getString("query"))
        assertEquals("Bearer fixture", requests[1].headers["Authorization"])
        assertEquals(3, base.documentCount)
        assertEquals("Example text", hits.single().text)
        assertEquals("guide.txt", hits.single().docName)
        assertEquals(1, hits.single().ordinal)
    }

    @Test
    fun `RAGFlow selects one dataset and maps only evidence text and document metadata`() = runTest {
        val requests = mutableListOf<ZhipuHttpRequest>()
        val provider = KnowledgeProvider.RAGFLOW
        val service = provider.connect("https://kb.example/api/v1/", "fixture", transport(requests,
            """{"code":0,"data":[{"id":"dataset-x","name":"Private dataset","document_count":4}]}""",
            """{"code":0,"data":{"chunks":[{"content":"Sample","id":"chunk-x","document_keyword":"source.txt","similarity":0.7}]}}""",
        ))
        val base = service.list().single()
        assertEquals("source.txt", service.retrieve(base.id, "sample question", 3).single().docName)
        assertEquals("https://kb.example/api/v1/retrieval", requests.last().url)
        val body = JSONObject(requests.last().body!!.decodeToString())
        assertEquals("dataset-x", body.getJSONArray("dataset_ids").getString(0))
        assertEquals(3, body.getInt("page_size"))
        assertFalse(body.has("rerank_id"))
    }

    @Test
    fun `provider URLs reject credentials and unexpected routing components`() {
        val provider = KnowledgeProvider.RAGFLOW
        assertNull(provider.validBaseUrl("http://kb.example/api/v1"))
        assertNull(provider.validBaseUrl("https://name@kb.example/api/v1"))
        assertNull(provider.validBaseUrl("https://kb.example/api/v1?key=value"))
        assertNull(provider.validBaseUrl("https://kb.example/v1"))
        assertTrue(provider.validBaseUrl("https://kb.example/api/v1") != null)
    }

    private fun transport(requests: MutableList<ZhipuHttpRequest>, vararg responses: String) =
        ZhipuHttpTransport { request ->
            requests += request
            ZhipuHttpResponse(200, responses[requests.lastIndex])
        }
}
