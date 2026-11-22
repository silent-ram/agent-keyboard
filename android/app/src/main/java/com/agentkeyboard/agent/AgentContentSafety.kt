package com.agentkeyboard.agent

import com.agentkeyboard.tools.Citation
import com.agentkeyboard.tools.InjectionScanner
import com.agentkeyboard.data.db.AgentMessageEntity
import com.agentkeyboard.data.db.MessageRoles
import com.agentkeyboard.tools.CitationJson
import com.agentkeyboard.ui.formatAnswerText
import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

internal class AgentContentSafety(configValues: Collection<String>) {
    private val protectedValues = configValues.asSequence()
        .map(String::trim).filter { it.length >= 4 || it.length >= 2 && it.any { ch -> ch in '\u4e00'..'\u9fff' } }.distinct()
        .sortedByDescending(String::length).toList()

    fun hasConfig(text: String): Boolean = containsCredential(text) ||
        protectedValues.any { text.contains(it, ignoreCase = true) }

    fun redact(text: String): String = protectedValues.fold(credential.replace(text, HIDDEN)) { result, value ->
        result.replace(value, HIDDEN, ignoreCase = true)
    }

    fun unsafeAnswer(text: String): Boolean = text.length > MAX_ANSWER || InjectionScanner.isFlagged(text) ||
        text.contains(SystemPrompts.DATA_BOUNDARY.lineSequence().first(), ignoreCase = true)

    fun displayAnswer(text: String): String {
        val redacted = redact(text)
        val rendered = formatAnswerText(redacted).text
        return if (hasConfig(rendered)) redact(rendered) else redacted
    }

    fun unsafeRenderedAnswer(text: String): Boolean = unsafeAnswer(text) || unsafeAnswer(formatAnswerText(text).text)

    fun unsafeArguments(raw: String): Boolean {
        if (raw.length > MAX_ARGS) return true
        val parsed = runCatching { JSONObject(raw) }.getOrNull() ?: return true
        return hasConfig(raw) || unsafeNode(parsed, 0)
    }

    private fun unsafeNode(node: Any?, depth: Int): Boolean = depth > 16 || when (node) {
        is JSONObject -> node.keys().asSequence().any { key ->
            unsafeNode(key, depth + 1) || unsafeNode(node.opt(key), depth + 1)
        }
        is JSONArray -> (0 until node.length()).any { unsafeNode(node.opt(it), depth + 1) }
        is String -> hasConfig(node) || InjectionScanner.isFlagged(node)
        else -> false
    }

    fun citations(sources: List<Citation>): List<Citation> = sources.mapNotNull { citation ->
        if (hasConfig(citation.url) || unsafeAnswer(citation.title) || unsafeAnswer(citation.snippet)) null
        else citation.copy(title = redact(citation.title), snippet = redact(citation.snippet))
    }

    fun forDisplay(rows: List<AgentMessageEntity>): List<AgentMessageEntity> = rows.map { row ->
        val content = when (row.role) {
            MessageRoles.TOOL -> ""
            MessageRoles.ASSISTANT -> if (unsafeRenderedAnswer(row.content)) "回答已拦截" else displayAnswer(row.content)
            else -> redact(row.content)
        }
        row.copy(content = content, citationsJson = CitationJson.encode(citations(CitationJson.decode(row.citationsJson))))
    }

    companion object {
        private const val MAX_INPUT = 32_000
        private const val MAX_ANSWER = 64_000
        private const val MAX_ARGS = 16_384
        private const val HIDDEN = "[已隐藏]"
        private val credential = Regex("""(?i)\b(?:sk-[a-z0-9._-]{12,}|tvly-[a-z0-9._-]{12,}|Bearer\s+[a-z0-9._~+/=-]{12,})""")

        fun containsCredential(text: String): Boolean = credential.containsMatchIn(text)

        fun rejectsInput(text: String): Boolean =
            text.length > MAX_INPUT || text.any { it.isISOControl() && it !in "\r\n\t" } ||
                containsCredential(text) || InjectionScanner.isFlagged(text)

        fun endpointValues(url: String): List<String> =
            listOfNotNull(url, runCatching { URI(url).host }.getOrNull())
    }
}
