package com.agentkeyboard.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** 会话状态(02-架构 §2.5) */
object SessionStatus {
    const val RUNNING = "RUNNING"
    const val DONE = "DONE"
    const val INCOMPLETE = "INCOMPLETE"
    const val FAILED = "FAILED"
    const val STOPPED = "STOPPED"
}

/** 授权状态机(11-权限设计 §3) */
object PermissionStates {
    const val UNASKED = "UNASKED"
    const val ALWAYS_ALLOW = "ALWAYS_ALLOW"
    const val ASK_EVERY_TIME = "ASK_EVERY_TIME"
}

/** 活动日志结果(11-权限设计 §5.1) */
object LogOutcomes {
    const val AUTO_ALLOW = "AUTO_ALLOW"
    const val CONFIRMED = "CONFIRMED"
    const val ALLOWED_ONCE = "ALLOWED_ONCE"
    const val DENIED = "DENIED"
    const val FAILED = "FAILED"
}

/** 活动日志:是否携带用户内容(11-权限设计 §5.1;read_context 不单独记,只在出网条目标注) */
object UserContentFlags {
    const val NONE = "NONE"
    const val CONTEXT = "CONTEXT"
    const val CLIPBOARD = "CLIPBOARD"
}

/** 消息角色 */
object MessageRoles {
    const val USER = "user"
    const val ASSISTANT = "assistant"
    const val TOOL = "tool"
    const val SYSTEM = "system"
}

/** agent_sessions:一次 agent 会话(02-架构 §2.5,列名 snake_case 对齐文档) */
@Entity(tableName = "agent_sessions")
data class AgentSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "task_type") val taskType: String,
    val status: String = SessionStatus.RUNNING,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** agent_messages:会话内每一步消息;toolJson 仅 assistant(发起的 toolCalls)与 tool(参数)用 */
@Entity(
    tableName = "agent_messages",
    foreignKeys = [
        ForeignKey(
            entity = AgentSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("session_id")],
)
data class AgentMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "session_id") val sessionId: Long,
    val role: String,
    val content: String,
    @ColumnInfo(name = "tool_json") val toolJson: String? = null,
    @ColumnInfo(name = "step_no") val stepNo: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "citations_json") val citationsJson: String? = null,
)

/** permission_state:每工具独立持久授权状态(11-权限设计 §3) */
@Entity(tableName = "permission_state")
data class PermissionStateEntity(
    @PrimaryKey @ColumnInfo(name = "tool_id") val toolId: String,
    val state: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/** activity_log:最近 AI 活动(11-权限设计 §5;preview 为脱敏后内容) */
@Entity(tableName = "activity_log")
data class ActivityLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    @ColumnInfo(name = "tool_id") val toolId: String,
    val outcome: String,
    @ColumnInfo(name = "contains_user_content") val containsUserContent: String = UserContentFlags.NONE,
    val target: String,
    val summary: String,
    val preview: String,
    @ColumnInfo(name = "session_id") val sessionId: Long? = null,
)

/** agent_memories:用户显式确认要记住的事实(G5;完全显式、完全可删、不出网) */
@Entity(tableName = "agent_memories")
data class AgentMemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fact: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
