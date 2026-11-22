package com.agentkeyboard.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** 保留策略常量(FR-B9 / 11-权限设计 §5.4) */
object RetentionPolicy {
    const val MAX_SESSIONS = 50
    const val SESSION_TTL_MS = 7L * 24 * 60 * 60 * 1000
    const val MAX_LOG_ENTRIES = 200
    const val LOG_TTL_MS = 30L * 24 * 60 * 60 * 1000
}

@Dao
interface AgentSessionDao {
    @Insert
    suspend fun insert(session: AgentSessionEntity): Long

    @Query("UPDATE agent_sessions SET status = :status WHERE id = :sessionId")
    suspend fun updateStatus(sessionId: Long, status: String)

    @Query("UPDATE agent_sessions SET status = :status, created_at = :updatedAt WHERE id = :sessionId")
    suspend fun touch(sessionId: Long, status: String, updatedAt: Long)

    @Query("SELECT * FROM agent_sessions WHERE id = :id")
    suspend fun byId(id: Long): AgentSessionEntity?

    @Query("SELECT * FROM agent_sessions ORDER BY created_at DESC LIMIT 1")
    suspend fun latest(): AgentSessionEntity?

    /** 会话列表(🕘 弹层,倒序,保留策略内) */
    @Query("SELECT * FROM agent_sessions ORDER BY created_at DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<AgentSessionEntity>

    /**
     * 会话列表(Agent 对话管理页):只含 Agent 任务类型(排除输入法快捷动作),
     * 且带首条用户提问作为标题。
     */
    @Query(
        "SELECT s.* FROM agent_sessions s " +
            "WHERE s.task_type IN ('KNOWLEDGE_ASK', 'ASK', 'VERIFY') " +
            "AND EXISTS (SELECT 1 FROM agent_messages m WHERE m.session_id = s.id) " +
            "ORDER BY s.created_at DESC LIMIT :limit"
    )
    suspend fun recentAgentConversations(limit: Int): List<AgentSessionEntity>

    @Query("SELECT * FROM agent_sessions WHERE task_type = :taskType ORDER BY created_at DESC LIMIT :limit")
    suspend fun recentByTaskType(taskType: String, limit: Int): List<AgentSessionEntity>

    @Query(
        "SELECT * FROM agent_sessions WHERE task_type = :taskType " +
            "AND EXISTS (SELECT 1 FROM agent_messages WHERE agent_messages.session_id = agent_sessions.id) " +
            "ORDER BY created_at DESC LIMIT :limit"
    )
    suspend fun recentByTaskTypeWithMessages(taskType: String, limit: Int): List<AgentSessionEntity>

    /** 删除单条会话(FK 级联删消息) */
    @Query("DELETE FROM agent_sessions WHERE id = :sessionId")
    suspend fun delete(sessionId: Long)

    /** 会话滚动清理:超出最近 [RetentionPolicy.MAX_SESSIONS] 条或早于 7 天的会话(级联删消息) */
    @Query(
        "DELETE FROM agent_sessions WHERE created_at < :cutoff " +
            "OR id NOT IN (SELECT id FROM agent_sessions ORDER BY created_at DESC LIMIT :maxSessions)"
    )
    suspend fun cleanup(cutoff: Long, maxSessions: Int)

    @Query("DELETE FROM agent_sessions")
    suspend fun clearAll()
}

@Dao
interface AgentMessageDao {
    @Insert
    suspend fun insert(message: AgentMessageEntity): Long

    @Query("DELETE FROM agent_messages WHERE id = :messageId")
    suspend fun deleteById(messageId: Long)

    @Query("SELECT * FROM agent_messages WHERE session_id = :sessionId ORDER BY id ASC")
    suspend fun bySession(sessionId: Long): List<AgentMessageEntity>

    @Query("SELECT COUNT(*) FROM agent_messages WHERE session_id = :sessionId")
    suspend fun countBySession(sessionId: Long): Int

    /** 会话标题:该会话首条用户提问(前 40 字);无用户消息则返回 null。 */
    @Query(
        "SELECT content FROM agent_messages " +
            "WHERE session_id = :sessionId AND role = 'user' " +
            "ORDER BY id ASC LIMIT 1"
    )
    suspend fun firstUserMessage(sessionId: Long): String?

    @Query("DELETE FROM agent_messages WHERE session_id = :sessionId")
    suspend fun deleteBySession(sessionId: Long)
}

@Dao
interface PermissionStateDao {
    @Query("SELECT * FROM permission_state WHERE tool_id = :toolId")
    suspend fun byTool(toolId: String): PermissionStateEntity?

    @Query("SELECT * FROM permission_state")
    suspend fun all(): List<PermissionStateEntity>

    /** PK 冲突时整行覆盖(REPLACE):同一工具二次写入(本次允许→总是允许/重置)是正常路径,必须覆盖而非抛约束异常 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PermissionStateEntity)

    /** 总是允许/重置等状态转移(11-权限设计 §3:任何状态可被设置页改写) */
    @Query("UPDATE permission_state SET state = :state, updated_at = :updatedAt WHERE tool_id = :toolId")
    suspend fun updateState(toolId: String, state: String, updatedAt: Long)

    @Query("DELETE FROM permission_state")
    suspend fun clearAll()
}

@Dao
interface ActivityLogDao {
    @Insert
    suspend fun insert(entry: ActivityLogEntity): Long

    /** 最近活动(面板 🕘 弹层,倒序) */
    @Query("SELECT * FROM activity_log ORDER BY ts DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<ActivityLogEntity>>

    /** 单会话的独立活动记录(对话管理入口;倒序) */
    @Query("SELECT * FROM activity_log WHERE session_id = :sessionId ORDER BY ts DESC LIMIT :limit")
    fun bySession(sessionId: Long, limit: Int): Flow<List<ActivityLogEntity>>

    @Query("SELECT COUNT(*) FROM activity_log")
    suspend fun count(): Int

    /** 活动日志滚动清理:最近 200 条或 30 天取先到 */
    @Query(
        "DELETE FROM activity_log WHERE ts < :cutoff " +
            "OR id NOT IN (SELECT id FROM activity_log ORDER BY ts DESC LIMIT :maxEntries)"
    )
    suspend fun cleanup(cutoff: Long, maxEntries: Int)

    @Query("DELETE FROM activity_log")
    suspend fun clearAll()
}

@Dao
interface AgentMemoryDao {
    @Insert
    suspend fun insert(memory: AgentMemoryEntity): Long

    @Query("SELECT * FROM agent_memories ORDER BY created_at DESC")
    suspend fun list(): List<AgentMemoryEntity>

    @Query("UPDATE agent_memories SET fact = :fact WHERE id = :id")
    suspend fun updateFact(id: Long, fact: String): Int

    @Query("DELETE FROM agent_memories WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM agent_memories")
    suspend fun count(): Int

    @Query("DELETE FROM agent_memories")
    suspend fun clearAll()
}

/**
 * Agent 业务库(M1 会话/授权/日志 + M4 引用持久化 + G5 显式记忆,version 7)。
 * 与既有 DataStore(模型配置)并存:DataStore 管 kv 配置,Room 管会话/授权/日志。
 *
 * 仅在迁移含 vec0 虚表的旧数据库时临时加载 sqlite-vec 扩展；新版本不再创建本地索引。
 */
@Database(
    entities = [
        AgentSessionEntity::class,
        AgentMessageEntity::class,
        PermissionStateEntity::class,
        ActivityLogEntity::class,
        AgentMemoryEntity::class,
    ],
    version = 7,
    exportSchema = false,
)
abstract class AgentDatabase : RoomDatabase() {
    abstract fun sessionDao(): AgentSessionDao
    abstract fun messageDao(): AgentMessageDao
    abstract fun permissionStateDao(): PermissionStateDao
    abstract fun activityLogDao(): ActivityLogDao
    abstract fun memoryDao(): AgentMemoryDao

    companion object {
        /** M1→M2:documents/chunks 两表(列定义须与 Room 生成 schema 完全一致);internal 供迁移测试复用 */
        internal val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS documents (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "name TEXT NOT NULL, mime TEXT NOT NULL, size INTEGER NOT NULL, " +
                        "chunk_count INTEGER NOT NULL, created_at INTEGER NOT NULL, status TEXT NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_documents_created_at ON documents(created_at)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS chunks (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "doc_id INTEGER NOT NULL, ordinal INTEGER NOT NULL, text TEXT NOT NULL, " +
                        "token_count INTEGER NOT NULL, " +
                        "FOREIGN KEY(doc_id) REFERENCES documents(id) ON DELETE CASCADE)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_chunks_doc_id ON chunks(doc_id)")
            }
        }

        /**
         * M2→M4:保存答案引用,并让应用内问答重新完成模型数据流明示。
         * 旧版输入法的 llm_call 同意覆盖不同上下文范围,不可直接沿用。
         */
        internal val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE agent_messages ADD COLUMN citations_json TEXT")
                db.execSQL("DELETE FROM permission_state WHERE tool_id = 'llm_call'")
            }
        }

        internal val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE documents ADD COLUMN embedding_fingerprint TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE documents ADD COLUMN chunker_version INTEGER NOT NULL DEFAULT 0")
            }
        }

        internal val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE documents ADD COLUMN source_text TEXT")
            }
        }

        /** 删除设备本地知识库及其索引；会话、凭据、授权和活动记录不变。 */
        internal val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.query("PRAGMA secure_delete = ON").use { }
                db.execSQL("DROP TABLE IF EXISTS fts_chunks")
                db.execSQL("DROP TABLE IF EXISTS vec_chunks")
                db.execSQL("DROP TABLE IF EXISTS chunks")
                db.execSQL("DROP TABLE IF EXISTS documents")
            }
        }

        /** G5 显式记忆表:新建空表,既有数据(会话/授权/活动)不变。 */
        internal val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS agent_memories (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "fact TEXT NOT NULL, created_at INTEGER NOT NULL)"
                )
            }
        }

        @Volatile
        private var instance: AgentDatabase? = null

        /**
         * 旧版数据库迁移时用 vec0 扩展删除虚表；新版本不再读写此索引。
         */
        internal fun vecOpenHelperFactory(): io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory =
            io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory(
                listOf(
                    io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory.ConfigurationOptions { config ->
                        com.agentkeyboard.rag.VecExtensionInstaller.soPath?.let { path ->
                            config.customExtensions.add(
                                io.requery.android.database.sqlite.SQLiteCustomExtension(path, "sqlite3_vec_init")
                            )
                        }
                        config
                    }
                )
            )

        fun get(context: Context): AgentDatabase = instance ?: synchronized(this) {
            instance ?: run {
                // vec0 虚表必须先注册原扩展才能 DROP；缺少扩展时明确中止旧库迁移。
                if (hasLegacyVectorTable(context.applicationContext)) {
                    check(com.agentkeyboard.rag.VecExtensionInstaller.prepare(context.applicationContext)) {
                        "无法加载旧向量索引清理扩展，知识库迁移未执行"
                    }
                }
                Room.databaseBuilder(context.applicationContext, AgentDatabase::class.java, "agent.db")
                    .openHelperFactory(vecOpenHelperFactory())
                    .addMigrations(
                        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4,
                        MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
                    )
                    .build()
                    .also { instance = it }
            }
        }

        private fun hasLegacyVectorTable(context: Context): Boolean {
            val file = context.getDatabasePath("agent.db")
            if (!file.isFile) return false
            val db = android.database.sqlite.SQLiteDatabase.openDatabase(
                file.absolutePath,
                null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
            )
            return db.use {
                it.rawQuery(
                    "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'vec_chunks' LIMIT 1",
                    null,
                ).use { cursor -> cursor.moveToFirst() }
            }
        }
    }
}
