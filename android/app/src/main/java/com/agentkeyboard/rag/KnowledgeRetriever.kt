package com.agentkeyboard.rag

data class RetrievedChunk(
    val chunkId: Long,
    val text: String,
    val docName: String,
    val ordinal: Int,
    val score: Float,
    val matchedText: String? = null,
    val relevanceLabel: String? = null,
)

interface KnowledgeRetriever {
    suspend fun search(query: String, topK: Int = 3): List<RetrievedChunk>
    suspend fun isEmpty(): Boolean
}

object Snippets {
    const val MAX_CHARS = 60

    fun firstSentence(text: String, maxChars: Int = MAX_CHARS): String {
        val value = text.trim()
        if (value.isEmpty()) return ""
        val end = value.indexOfFirst { it in "\n。！？；.!?;" }
        val first = if (end < 0) value else value.substring(0, end + 1)
        return if (first.length <= maxChars) first else first.take(maxChars) + "…"
    }
}
