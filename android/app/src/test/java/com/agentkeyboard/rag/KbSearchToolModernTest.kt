package com.agentkeyboard.rag

import com.agentkeyboard.tools.KbSearchTool
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KbSearchToolModernTest {
    private class RemoteRetriever(
        private val hits: List<RetrievedChunk> = emptyList(),
        private val failure: Exception? = null,
        private val empty: Boolean = false,
    ) : KnowledgeRetriever {
        var calls = 0
        override suspend fun search(query: String, topK: Int): List<RetrievedChunk> {
            calls++
            failure?.let { throw it }
            return hits.take(topK)
        }

        override suspend fun isEmpty(): Boolean = empty
    }

    @Test
    fun `tool description omits provider identity`() {
        val tool = KbSearchTool({ RemoteRetriever() })
        assertTrue(tool.description.contains("uploaded documents"))
        assertFalse(tool.description.contains("Zhipu"))
        assertFalse(tool.description.contains("Purely local"))
    }

    @Test
    fun `remote result preserves document provenance and snippet`() = runBlocking {
        val retriever = RemoteRetriever(
            hits = listOf(RetrievedChunk(1, "渠道下沉安排在第三季度。负责人是项目组。", "roadmap.md", 1, 0.93f)),
        )
        val result = KbSearchTool({ retriever }).execute("""{"query":"下沉计划"}""")

        assertTrue(result.ok)
        assertEquals(1, retriever.calls)
        assertFalse(result.raw.contains("智谱"))
        assertFalse(result.raw.contains("下沉计划"))
        assertTrue(result.raw.contains("渠道下沉安排"))
        assertEquals("kb://roadmap.md", result.citations.single().url)
        assertEquals("roadmap.md §2", result.citations.single().title)
        assertEquals("渠道下沉安排在第三季度。", result.citations.single().snippet)
    }

    @Test
    fun `empty library and no hit are explicit evidence states`() = runBlocking {
        val empty = KbSearchTool({ RemoteRetriever(empty = true) }).execute("""{"query":"项目目标"}""")
        assertTrue(empty.ok)
        assertEquals("暂无可检索的资料。", empty.raw)

        val miss = KbSearchTool({ RemoteRetriever() }).execute("""{"query":"项目目标"}""")
        assertTrue(miss.ok)
        assertTrue(miss.raw.contains("证据不足"))
    }

    @Test
    fun `remote failure is returned without local retry`() = runBlocking {
        val retriever = RemoteRetriever(failure = IllegalStateException("HTTP 503"))
        val result = KbSearchTool({ retriever }).execute("""{"query":"项目目标"}""")
        assertFalse(result.ok)
        assertEquals("资料检索失败", result.raw)
        assertEquals(1, retriever.calls)
    }

    @Test
    fun `unconfigured retriever and disabled feature do not search`() = runBlocking {
        val missing = KbSearchTool({ null }).execute("""{"query":"q"}""")
        assertFalse(missing.ok)
        assertEquals("资料检索未配置", missing.raw)

        val retriever = RemoteRetriever()
        val disabled = KbSearchTool({ retriever }, enabledProvider = { false })
            .execute("""{"query":"q"}""")
        assertFalse(disabled.ok)
        assertEquals(0, retriever.calls)
    }
}
