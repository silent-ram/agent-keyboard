package com.agentkeyboard.agent

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.agentkeyboard.data.db.AgentDatabase
import com.agentkeyboard.data.db.PermissionStates
import com.agentkeyboard.data.db.UserContentFlags
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1→v6 迁移回归。
 * Robolectric 手写 M1 四表 schema(agent_sessions/agent_messages/permission_state/activity_log,
 * 列定义与 Room 对当前 M1 实体的生成一致,version=1)并插一行会话/授权/日志
 * → 以 addMigrations 打开 → 断言聊天/授权/日志保留且旧知识库表不再存在。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentDatabaseMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "migration-test.db"

    @Before
    fun setup() {
        context.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    /** 手写 M1 四表 + 种子数据(会话/授权/日志各一行),version=1 */
    private fun createV1AndSeed() {
        val dbFile = context.getDatabasePath(dbName)
        dbFile.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        // agent_sessions(Room 无索引;列序 = 实体声明序)
        db.execSQL(
            "CREATE TABLE agent_sessions (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "task_type TEXT NOT NULL, status TEXT NOT NULL, created_at INTEGER NOT NULL)"
        )
        // agent_messages(FK 级联 + session_id 索引)
        db.execSQL(
            "CREATE TABLE agent_messages (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, session_id INTEGER NOT NULL, " +
                "role TEXT NOT NULL, content TEXT NOT NULL, tool_json TEXT, step_no INTEGER NOT NULL, " +
                "created_at INTEGER NOT NULL, " +
                "FOREIGN KEY(session_id) REFERENCES agent_sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        db.execSQL("CREATE INDEX index_agent_messages_session_id ON agent_messages(session_id)")
        // permission_state(PK tool_id,无自增)
        db.execSQL(
            "CREATE TABLE permission_state (" +
                "tool_id TEXT NOT NULL, state TEXT NOT NULL, updated_at INTEGER NOT NULL, " +
                "PRIMARY KEY(tool_id))"
        )
        // activity_log(session_id 可空,无 FK、无索引)
        db.execSQL(
            "CREATE TABLE activity_log (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, ts INTEGER NOT NULL, tool_id TEXT NOT NULL, " +
                "outcome TEXT NOT NULL, contains_user_content TEXT NOT NULL, target TEXT NOT NULL, " +
                "summary TEXT NOT NULL, preview TEXT NOT NULL, session_id INTEGER)"
        )
        val sessionId = db.insert("agent_sessions", null, ContentValues().apply {
            put("task_type", "ask"); put("status", "DONE"); put("created_at", 1_000L)
        })
        db.insert("agent_messages", null, ContentValues().apply {
            put("session_id", sessionId); put("role", "assistant"); put("content", "旧答案")
            put("step_no", 1); put("created_at", 1_100L)
        })
        db.insert("permission_state", null, ContentValues().apply {
            put("tool_id", "web_search"); put("state", PermissionStates.ALWAYS_ALLOW); put("updated_at", 2_000L)
        })
        db.insert("permission_state", null, ContentValues().apply {
            put("tool_id", "llm_call"); put("state", PermissionStates.ALWAYS_ALLOW); put("updated_at", 2_100L)
        })
        db.insert("activity_log", null, ContentValues().apply {
            put("ts", 3_000L); put("tool_id", "web_search"); put("outcome", "AUTO_ALLOW")
            put("contains_user_content", UserContentFlags.NONE); put("target", "搜索服务")
            put("summary", "s"); put("preview", "p")
        })
        db.version = 1
        db.close()
    }

    @Test
    fun `v1 到 v6 迁移 - 历史数据保留且旧本地库已退役`() = runBlocking {
        createV1AndSeed()
        val db = Room.databaseBuilder(context, AgentDatabase::class.java, dbName)
            .addMigrations(
                AgentDatabase.MIGRATION_1_2,
                AgentDatabase.MIGRATION_2_3,
                AgentDatabase.MIGRATION_3_4,
                AgentDatabase.MIGRATION_4_5,
                AgentDatabase.MIGRATION_5_6,
                AgentDatabase.MIGRATION_6_7,
            )
            .allowMainThreadQueries()
            .build()
        try {
            // M1 数据保留
            val session = db.sessionDao().latest()
            assertEquals("DONE", session?.status)
            assertEquals("ask", session?.taskType)
            assertEquals(1_000L, session?.createdAt)
            val oldMessage = db.messageDao().bySession(session!!.id).single()
            assertEquals("旧答案", oldMessage.content)
            assertEquals(null, oldMessage.citationsJson)
            val deniedQuestionId = db.messageDao().insert(
                com.agentkeyboard.data.db.AgentMessageEntity(
                    sessionId = session.id, role = "user", content = "未获授权的问题",
                    stepNo = 0, createdAt = 1_150L,
                )
            )
            db.messageDao().deleteById(deniedQuestionId)
            assertEquals(listOf("旧答案"), db.messageDao().bySession(session.id).map { it.content })
            db.messageDao().insert(
                com.agentkeyboard.data.db.AgentMessageEntity(
                    sessionId = session.id, role = "assistant", content = "新答案",
                    stepNo = 2, createdAt = 1_200L,
                    citationsJson = com.agentkeyboard.tools.CitationJson.encode(
                        listOf(com.agentkeyboard.tools.Citation("kb://m.md", "m.md §1", "原文片段"))
                    ),
                )
            )
            assertEquals("原文片段", com.agentkeyboard.tools.CitationJson.decode(
                db.messageDao().bySession(session.id).last().citationsJson
            ).single().snippet)
            val visibleSessionId = db.sessionDao().insert(
                com.agentkeyboard.data.db.AgentSessionEntity(
                    taskType = com.agentkeyboard.agent.TaskType.KNOWLEDGE_ASK.name,
                    status = "DONE",
                    createdAt = 5_000L,
                )
            )
            db.messageDao().insert(
                com.agentkeyboard.data.db.AgentMessageEntity(
                    sessionId = visibleSessionId,
                    role = "user",
                    content = "已完成的问题",
                    stepNo = 0,
                    createdAt = 5_100L,
                )
            )
            db.sessionDao().insert(
                com.agentkeyboard.data.db.AgentSessionEntity(
                    taskType = com.agentkeyboard.agent.TaskType.KNOWLEDGE_ASK.name,
                    status = "STOPPED",
                    createdAt = 6_000L,
                )
            )
            assertEquals(
                visibleSessionId,
                db.sessionDao()
                    .recentByTaskTypeWithMessages(com.agentkeyboard.agent.TaskType.KNOWLEDGE_ASK.name, 1)
                    .single()
                    .id,
            )
            val perm = db.permissionStateDao().byTool("web_search")
            assertEquals(PermissionStates.ALWAYS_ALLOW, perm?.state)
            assertEquals(2_000L, perm?.updatedAt)
            assertEquals(null, db.permissionStateDao().byTool("llm_call"))
            assertEquals(1, db.activityLogDao().count())
            val remainingTables = db.openHelper.writableDatabase.query(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name IN ('documents', 'chunks')"
            ).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }
            assertTrue(remainingTables.isEmpty())
        } finally {
            db.close()
        }
    }

    @Test
    fun `旧本地知识库迁移 - 删除正文切片与索引表但保留用户业务数据`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AgentDatabase::class.java)
            .addMigrations(
                AgentDatabase.MIGRATION_1_2,
                AgentDatabase.MIGRATION_2_3,
                AgentDatabase.MIGRATION_3_4,
                AgentDatabase.MIGRATION_4_5,
                AgentDatabase.MIGRATION_5_6,
                AgentDatabase.MIGRATION_6_7,
            )
            .allowMainThreadQueries()
            .build()
        try {
            val sessionId = db.sessionDao().insert(
                com.agentkeyboard.data.db.AgentSessionEntity(taskType = "ask", status = "DONE", createdAt = 9_000L)
            )
            db.messageDao().insert(
                com.agentkeyboard.data.db.AgentMessageEntity(
                    sessionId = sessionId, role = "assistant", content = "保留的会话", stepNo = 1, createdAt = 9_100L,
                )
            )
            db.permissionStateDao().upsert(
                com.agentkeyboard.data.db.PermissionStateEntity("kb_search", PermissionStates.ALWAYS_ALLOW, 9_200L)
            )
            db.activityLogDao().insert(
                com.agentkeyboard.data.db.ActivityLogEntity(
                    ts = 9_300L, toolId = "kb_search", outcome = "CONFIRMED",
                    containsUserContent = UserContentFlags.NONE, target = "智谱知识库",
                    summary = "保留记录", preview = "", sessionId = sessionId,
                )
            )

            val raw = db.openHelper.writableDatabase
            raw.execSQL("CREATE TABLE documents (id INTEGER PRIMARY KEY, source_text TEXT)")
            raw.execSQL("CREATE TABLE chunks (id INTEGER PRIMARY KEY, doc_id INTEGER, text TEXT)")
            raw.execSQL("CREATE TABLE fts_chunks (chunk_id INTEGER, text TEXT)")
            raw.execSQL("CREATE TABLE vec_chunks (chunk_id INTEGER, embedding BLOB)")
            raw.execSQL("INSERT INTO documents VALUES (1, '不得保留的原文')")
            raw.execSQL("INSERT INTO chunks VALUES (1, 1, '不得保留的切片')")
            raw.execSQL("INSERT INTO fts_chunks VALUES (1, '不得保留的索引')")
            raw.execSQL("INSERT INTO vec_chunks VALUES (1, X'00')")

            AgentDatabase.MIGRATION_5_6.migrate(raw)

            val localTables = raw.query(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name IN ('documents', 'chunks', 'fts_chunks', 'vec_chunks')"
            ).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }
            assertTrue(localTables.isEmpty())
            val secureDelete = raw.query("PRAGMA secure_delete").use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getInt(0)
            }
            assertEquals(1, secureDelete)
            assertEquals("保留的会话", db.messageDao().bySession(sessionId).single().content)
            assertEquals(PermissionStates.ALWAYS_ALLOW, db.permissionStateDao().byTool("kb_search")?.state)
            assertEquals(1, db.activityLogDao().count())
        } finally {
            db.close()
        }
    }

    /** G5:v6→v7 仅新增 agent_memories;迁移幂等且既有表不受影响;记忆 CRUD 可用。 */
    @Test
    fun `v6 到 v7 迁移 - 新增 agent_memories 且幂等`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AgentDatabase::class.java)
            .addMigrations(
                AgentDatabase.MIGRATION_1_2,
                AgentDatabase.MIGRATION_2_3,
                AgentDatabase.MIGRATION_3_4,
                AgentDatabase.MIGRATION_4_5,
                AgentDatabase.MIGRATION_5_6,
                AgentDatabase.MIGRATION_6_7,
            )
            .allowMainThreadQueries()
            .build()
        try {
            val raw = db.openHelper.writableDatabase
            AgentDatabase.MIGRATION_6_7.migrate(raw) // 幂等:重复执行不抛错
            AgentDatabase.MIGRATION_6_7.migrate(raw)

            val sessionId = db.sessionDao().insert(
                com.agentkeyboard.data.db.AgentSessionEntity(taskType = "ask", status = "DONE", createdAt = 1L)
            )
            db.activityLogDao().insert(
                com.agentkeyboard.data.db.ActivityLogEntity(
                    ts = 2L, toolId = "web_search", outcome = "AUTO_ALLOW",
                    containsUserContent = UserContentFlags.NONE, target = "搜索服务",
                    summary = "s", preview = "", sessionId = sessionId,
                )
            )
            assertEquals(0, db.memoryDao().count())
            val memId = db.memoryDao().insert(
                com.agentkeyboard.data.db.AgentMemoryEntity(fact = "偏好简洁回答", createdAt = 3L)
            )
            assertEquals("偏好简洁回答", db.memoryDao().list().single().fact)
            db.memoryDao().deleteById(memId)
            assertEquals(0, db.memoryDao().count())
            // 既有数据不受迁移影响
            assertEquals(1, db.activityLogDao().count())
            assertEquals("DONE", db.sessionDao().byId(sessionId)?.status)
        } finally {
            db.close()
        }
    }
}
