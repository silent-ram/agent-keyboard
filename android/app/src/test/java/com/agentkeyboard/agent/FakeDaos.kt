package com.agentkeyboard.agent

import com.agentkeyboard.data.db.AgentMessageDao
import com.agentkeyboard.data.db.AgentMessageEntity
import com.agentkeyboard.data.db.AgentSessionDao
import com.agentkeyboard.data.db.AgentSessionEntity
import com.agentkeyboard.data.db.ActivityLogDao
import com.agentkeyboard.data.db.ActivityLogEntity
import com.agentkeyboard.data.db.PermissionStateDao
import com.agentkeyboard.data.db.PermissionStateEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** 内存版 DAO(engine 单测用;Room DAO 无法在纯 JVM 实例化) */
class FakeSessionDao : AgentSessionDao {
    val sessions = mutableListOf<AgentSessionEntity>()
    private var nextId = 1L

    override suspend fun insert(session: AgentSessionEntity): Long {
        val id = nextId++
        sessions += session.copy(id = id)
        return id
    }

    override suspend fun updateStatus(sessionId: Long, status: String) {
        val i = sessions.indexOfFirst { it.id == sessionId }
        if (i >= 0) sessions[i] = sessions[i].copy(status = status)
    }

    override suspend fun touch(sessionId: Long, status: String, updatedAt: Long) {
        val i = sessions.indexOfFirst { it.id == sessionId }
        if (i >= 0) sessions[i] = sessions[i].copy(status = status, createdAt = updatedAt)
    }

    override suspend fun byId(id: Long): AgentSessionEntity? = sessions.firstOrNull { it.id == id }

    override suspend fun latest(): AgentSessionEntity? = sessions.lastOrNull()

    override suspend fun recent(limit: Int): List<AgentSessionEntity> =
        sessions.sortedByDescending { it.createdAt }.take(limit)

    override suspend fun recentAgentConversations(limit: Int): List<AgentSessionEntity> =
        sessions.filter { it.taskType in AGENT_TASK_TYPES }
            .sortedByDescending { it.createdAt }
            .take(limit)

    companion object {
        /** Agent 对话管理页纳入的任务类型(排除输入法快捷动作) */
        val AGENT_TASK_TYPES = setOf("KNOWLEDGE_ASK", "ASK", "VERIFY")
    }

    override suspend fun recentByTaskType(taskType: String, limit: Int): List<AgentSessionEntity> =
        sessions.filter { it.taskType == taskType }.sortedByDescending { it.createdAt }.take(limit)

    // Fake does not model session-message relations; Room migration test covers this query.
    override suspend fun recentByTaskTypeWithMessages(taskType: String, limit: Int): List<AgentSessionEntity> =
        recentByTaskType(taskType, limit)

    override suspend fun delete(sessionId: Long) {
        sessions.removeAll { it.id == sessionId }
        // 消息级联删除由 Room FK 保证(真实库),fake 不模拟
    }

    override suspend fun cleanup(cutoff: Long, maxSessions: Int) {
        val keep = sessions.sortedByDescending { it.createdAt }.take(maxSessions).filter { it.createdAt >= cutoff }
        sessions.removeAll { it !in keep }
    }

    override suspend fun clearAll() = sessions.clear()
}

class FakeMessageDao : AgentMessageDao {
    val messages = mutableListOf<AgentMessageEntity>()
    private var nextId = 1L

    override suspend fun insert(message: AgentMessageEntity): Long {
        val id = nextId++
        messages += message.copy(id = id)
        return id
    }

    override suspend fun deleteById(messageId: Long) {
        messages.removeAll { it.id == messageId }
    }

    override suspend fun bySession(sessionId: Long): List<AgentMessageEntity> =
        messages.filter { it.sessionId == sessionId }.sortedBy { it.id }

    override suspend fun countBySession(sessionId: Long): Int = messages.count { it.sessionId == sessionId }

    override suspend fun firstUserMessage(sessionId: Long): String? =
        messages.filter { it.sessionId == sessionId && it.role == "user" }
            .minByOrNull { it.id }?.content

    override suspend fun deleteBySession(sessionId: Long) {
        messages.removeAll { it.sessionId == sessionId }
    }
}

class FakePermissionStateDao : PermissionStateDao {
    val states = mutableMapOf<String, PermissionStateEntity>()

    override suspend fun byTool(toolId: String): PermissionStateEntity? = states[toolId]

    override suspend fun all(): List<PermissionStateEntity> = states.values.toList()

    override suspend fun upsert(entity: PermissionStateEntity) {
        // Map 赋值即"整行覆盖"——与真实 DAO 的 OnConflictStrategy.REPLACE 同语义(P0-2 对齐);
        // 真实写路径由 AgentDatabaseRoomTest(Robolectric)单独覆盖,fake 不再掩盖冲突行为
        states[entity.toolId] = entity
    }

    override suspend fun updateState(toolId: String, state: String, updatedAt: Long) {
        states[toolId] = PermissionStateEntity(toolId, state, updatedAt)
    }

    override suspend fun clearAll() = states.clear()
}

class FakeActivityLogDao : ActivityLogDao {
    val entries = mutableListOf<ActivityLogEntity>()
    private val recentFlow = MutableStateFlow(0)

    override suspend fun insert(entry: ActivityLogEntity): Long {
        entries += entry.copy(id = entries.size.toLong() + 1)
        recentFlow.value = entries.size
        return entries.size.toLong()
    }

    override fun recent(limit: Int): Flow<List<ActivityLogEntity>> =
        recentFlow.map { entries.takeLast(limit).reversed() }

    override fun bySession(sessionId: Long, limit: Int): Flow<List<ActivityLogEntity>> =
        recentFlow.map { entries.filter { it.sessionId == sessionId }.takeLast(limit).reversed() }

    override suspend fun count(): Int = entries.size

    override suspend fun cleanup(cutoff: Long, maxEntries: Int) {
        val keep = entries.sortedByDescending { it.ts }.take(maxEntries).filter { it.ts >= cutoff }
        entries.removeAll { it !in keep }
    }

    override suspend fun clearAll() = entries.clear()
}
