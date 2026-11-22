package com.agentkeyboard.ime

/**
 * Explicitly collected clipboard history for the keyboard.
 *
 * The service calls [record] only after the user opens the clipboard panel.
 * Nothing observes the system clipboard in the background.
 */
internal class ClipboardHistory(
    private val maxItems: Int = DEFAULT_MAX_ITEMS,
    private val maxChars: Int = DEFAULT_MAX_CHARS,
) {
    init {
        require(maxItems > 0)
        require(maxChars > 0)
    }

    private val entries = ArrayDeque<String>()

    fun record(raw: String): Boolean {
        if (raw.isBlank() || looksSensitive(raw)) return false
        val normalized = raw.take(maxChars)
        entries.remove(normalized)
        entries.addFirst(normalized)
        while (entries.size > maxItems) entries.removeLast()
        return true
    }

    fun snapshot(): List<String> = entries.toList()

    fun clear() {
        entries.clear()
    }

    private fun looksSensitive(text: String): Boolean {
        val value = text.trim()
        if (value.length < 8) return false
        return SENSITIVE_PREFIXES.any { value.startsWith(it, ignoreCase = true) } ||
            SENSITIVE_ASSIGNMENT.containsMatchIn(value) ||
            BEARER_TOKEN.containsMatchIn(value)
    }

    private companion object {
        const val DEFAULT_MAX_ITEMS = 12
        const val DEFAULT_MAX_CHARS = 2_000
        val SENSITIVE_PREFIXES = listOf(
            "sk-",
            "ghp_",
            "github_pat_",
            "AIza",
            "AKIA",
            "bearer ",
        )
        val SENSITIVE_ASSIGNMENT =
            Regex("(?i)\\b(password|passwd|secret|api[_-]?key|access[_-]?token)\\s*[:=]")
        val BEARER_TOKEN = Regex("(?i)\\bauthorization\\s*:\\s*bearer\\s+\\S+")
    }
}
