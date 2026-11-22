package com.agentkeyboard.agent

import androidx.room.withTransaction
import com.agentkeyboard.data.SettingsRepository
import com.agentkeyboard.data.db.AgentDatabase
import com.agentkeyboard.data.db.AgentMemoryEntity

internal enum class ProfileSaveResult { SAVED, ALREADY_PRESENT, INVALID }

internal class AgentProfileStore(
    private val db: AgentDatabase,
    private val settings: SettingsRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun memories(): List<AgentMemoryEntity> = db.memoryDao().list()
    suspend fun userNote(): String = settings.loadUserMd()

    suspend fun accept(suggestion: ProfileSuggestion): ProfileSaveResult = when (suggestion.kind) {
        ProfileSuggestion.Kind.MEMORY -> saveMemory(suggestion.text)
        ProfileSuggestion.Kind.USER_NOTE -> if (ProfileSuggestion.canSave(suggestion.text)) {
            settings.appendUserMdLine(suggestion.text)
        } else ProfileSaveResult.INVALID
    }

    suspend fun saveMemory(raw: String, id: Long? = null): ProfileSaveResult {
        val text = raw.trim()
        if (!ProfileSuggestion.canSave(text)) return ProfileSaveResult.INVALID
        return db.withTransaction {
            val dao = db.memoryDao()
            when {
                dao.list().any { it.fact.trim() == text && it.id != id } -> ProfileSaveResult.ALREADY_PRESENT
                id == null -> {
                    dao.insert(AgentMemoryEntity(fact = text, createdAt = clock()))
                    ProfileSaveResult.SAVED
                }
                dao.updateFact(id, text) == 1 -> ProfileSaveResult.SAVED
                else -> ProfileSaveResult.INVALID
            }
        }
    }

    suspend fun deleteMemory(id: Long) = db.memoryDao().deleteById(id)

    suspend fun saveUserNote(raw: String): ProfileSaveResult {
        val text = raw.trim()
        if (text.length > SystemPrompts.PROFILE_USER_MD_MAX_CHARS || AgentContentSafety.rejectsInput(text)) {
            return ProfileSaveResult.INVALID
        }
        settings.saveUserMd(text)
        return ProfileSaveResult.SAVED
    }

    suspend fun profileFor(task: Task): AgentProfile {
        if (task.singleTurn || task.type !in setOf(TaskType.ASK, TaskType.VERIFY, TaskType.KNOWLEDGE_ASK)) {
            return AgentProfile.EMPTY
        }
        return AgentProfile(userNote(), relevantMemories(task.userText, memories().map { it.fact }))
    }

    companion object {
        private val terms = Regex("""[\p{IsHan}]+|[a-z0-9]{3,}""")

        // Match only the current question; never send the memory collection for ranking.
        internal fun relevantMemories(question: String, memories: List<String>): List<String> {
            val query = keywords(question)
            if (query.isEmpty()) return emptyList()
            return memories.asSequence().map { fact ->
                val matches = keywords(fact).intersect(query)
                fact to matches.sumOf { it.length }
            }.filter { it.second >= 3 }.sortedByDescending { it.second }.take(4).map { it.first }.toList()
        }

        private fun keywords(text: String): Set<String> = terms.findAll(text.lowercase()).flatMap { match ->
            val value = match.value
            if (value.first().code > 127) value.windowed(2).asSequence() else sequenceOf(value)
        }.toSet()
    }
}
