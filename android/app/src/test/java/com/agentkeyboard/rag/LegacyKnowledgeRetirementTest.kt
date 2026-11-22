package com.agentkeyboard.rag

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Architectural guard: retired knowledge implementations must not ship as dormant alternatives. */
class LegacyKnowledgeRetirementTest {
    private val sourceRoot = sequenceOf(File("src/main"), File("app/src/main"))
        .first { it.isDirectory }

    @Test
    fun `production does not retain the retired local knowledge stack`() {
        listOf(
            "java/com/agentkeyboard/KnowledgeUi.kt",
            "java/com/agentkeyboard/ShareImportActivity.kt",
            "java/com/agentkeyboard/rag/DocumentIngester.kt",
            "java/com/agentkeyboard/rag/ConfiguredApiEmbedder.kt",
            "java/com/agentkeyboard/rag/EmbeddingSettings.kt",
            "java/com/agentkeyboard/rag/ApiEmbedder.kt",
            "java/com/agentkeyboard/rag/VectorStore.kt",
            "java/com/agentkeyboard/rag/FtsStore.kt",
        ).forEach { path -> assertFalse("Retired production file: $path", File(sourceRoot, path).exists()) }
    }

    @Test
    fun `agent does not fall back to legacy model settings`() {
        val source = File(sourceRoot, "java/com/agentkeyboard/agent/AgentFactory.kt").readText()
        assertFalse(source.contains("settings.loadLlmConfig()"))
        assertTrue(source.contains("ModelStore(context).loadActiveModel()?.toLlmConfig()"))
    }

    @Test
    fun `active agent path uses only hosted knowledge retrieval`() {
        val factory = File(sourceRoot, "java/com/agentkeyboard/agent/AgentFactory.kt").readText()
        val tool = File(sourceRoot, "java/com/agentkeyboard/tools/KbSearchTool.kt").readText()
        val panel = File(sourceRoot, "java/com/agentkeyboard/ime/FcitxAgentPanelController.kt").readText()

        assertFalse(factory.contains("KnowledgeModule"))
        assertFalse(tool.contains("VectorStore"))
        assertFalse(tool.contains("FtsStore"))
        assertFalse(panel.contains("settings.hasConfiguredModel()"))
    }
}
