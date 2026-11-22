package com.agentkeyboard.ime.pinyin

import org.json.JSONArray
import org.json.JSONObject

/**
 * 本地选词学习:按拼音/九宫格签名记录用户主动选中的词。
 * 只保存词本身、键串和次数,不保存宿主上下文或整句输入。
 */
internal class UserDictionary(private val maxEntries: Int = DEFAULT_MAX_ENTRIES) {
    private data class LearnedWord(val key: String, val word: String, var uses: Int)

    private val entries = LinkedHashMap<String, LearnedWord>()

    init {
        require(maxEntries > 0)
    }

    @Synchronized
    fun learn(rawKey: String, rawWord: String): Boolean {
        val key = normalizeKey(rawKey) ?: return false
        val word = rawWord.trim().takeIf { it.isNotEmpty() && it.length <= MAX_WORD_LENGTH } ?: return false
        if (word == key) return false
        val id = "$key\u0000$word"
        val current = entries[id]
        if (current == null) {
            entries[id] = LearnedWord(key, word, 1)
        } else {
            current.uses = (current.uses + 1).coerceAtMost(MAX_USE_COUNT)
        }
        trimToCapacity()
        return true
    }

    @Synchronized
    fun rank(rawKey: String, baseCandidates: List<String>): List<String> {
        val key = normalizeKey(rawKey) ?: return baseCandidates.distinct()
        val learned = entries.values
            .asSequence()
            .filter { it.key == key }
            .sortedByDescending { it.uses }
            .map { it.word }
        return (learned + baseCandidates.asSequence()).distinct().toList()
    }

    @Synchronized
    fun encode(): String {
        val array = JSONArray()
        entries.values.forEach { entry ->
            array.put(
                JSONObject()
                    .put("key", entry.key)
                    .put("word", entry.word)
                    .put("uses", entry.uses),
            )
        }
        return array.toString()
    }

    @Synchronized
    fun restore(raw: String?) {
        entries.clear()
        if (raw.isNullOrBlank()) return
        runCatching {
            val array = JSONArray(raw)
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val key = normalizeKey(item.optString("key")) ?: continue
                val word = item.optString("word").trim()
                if (word.isEmpty() || word.length > MAX_WORD_LENGTH || word == key) continue
                val uses = item.optInt("uses", 1).coerceIn(1, MAX_USE_COUNT)
                entries["$key\u0000$word"] = LearnedWord(key, word, uses)
            }
            trimToCapacity()
        }.onFailure {
            entries.clear()
        }
    }

    @Synchronized
    fun size(): Int = entries.size

    private fun trimToCapacity() {
        while (entries.size > maxEntries) {
            val leastUsed = entries.values.minWithOrNull(compareBy<LearnedWord> { it.uses }) ?: return
            entries.remove("${leastUsed.key}\u0000${leastUsed.word}")
        }
    }

    private fun normalizeKey(raw: String): String? {
        val key = raw.trim().lowercase()
        if (key.isEmpty() || key.length > MAX_KEY_LENGTH) return null
        if (key.any { !it.isLetterOrDigit() && it != '\'' }) return null
        return key
    }

    private companion object {
        const val DEFAULT_MAX_ENTRIES = 1_000
        const val MAX_KEY_LENGTH = 24
        const val MAX_WORD_LENGTH = 32
        const val MAX_USE_COUNT = 1_000_000
    }
}
