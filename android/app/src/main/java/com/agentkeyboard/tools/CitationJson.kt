package com.agentkeyboard.tools

import org.json.JSONArray
import org.json.JSONObject

object CitationJson {
    fun encode(citations: List<Citation>): String? {
        if (citations.isEmpty()) return null
        return JSONArray().apply {
            citations.forEach { citation ->
                put(
                    JSONObject()
                        .put("url", citation.url)
                        .put("title", citation.title)
                        .put("snippet", citation.snippet),
                )
            }
        }.toString()
    }

    fun decode(value: String?): List<Citation> {
        if (value.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(value)
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                Citation(
                    url = item.getString("url"),
                    title = item.getString("title"),
                    snippet = item.optString("snippet"),
                )
            }
        }.getOrDefault(emptyList())
    }
}
