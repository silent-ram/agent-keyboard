package com.agentkeyboard.agent

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.agentkeyboard.data.db.ActivityLogDao
import com.agentkeyboard.data.db.ActivityLogEntity
import com.agentkeyboard.data.db.AgentDatabase
import com.agentkeyboard.data.db.PermissionStateEntity
import com.agentkeyboard.data.db.PermissionStates
import com.agentkeyboard.data.db.RetentionPolicy
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
 * P0-2 回归:permission_state 真实 Room 写路径(Robolectric 内存库)。
 * 场景:先"本次允许"写 ASK 行,再 upsert"总是允许"/重置——旧实现 @Insert 无 onConflict,
 * 第二次写入抛 SQLiteConstraintException;REPLACE 后必须整行覆盖且读到最新值。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentDatabaseRoomTest {

    private lateinit var db: AgentDatabase

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AgentDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `multi turn history keeps questions answers and tools in insertion order`() = runBlocking {
        val sessionId = db.sessionDao().insert(
            com.agentkeyboard.data.db.AgentSessionEntity(
                taskType = TaskType.KNOWLEDGE_ASK.name, status = "DONE", createdAt = 100L,
            ),
        )
        val turns = listOf(
            Triple("user", "问题一", 0),
            Triple("assistant", "调用检索", 1),
            Triple("tool", "检索结果", 1),
            Triple("assistant", "答案一", 2),
            Triple("user", "问题二", 0),
            Triple("assistant", "答案二", 1),
            Triple("user", "问题三", 0),
            Triple("system", "已停止", -1),
        )
        turns.forEach { (role, content, step) ->
            db.messageDao().insert(
                com.agentkeyboard.data.db.AgentMessageEntity(
                    sessionId = sessionId, role = role, content = content,
                    stepNo = step, createdAt = 100L,
                ),
            )
        }
        assertEquals(turns.map { it.second }, db.messageDao().bySession(sessionId).map { it.content })
    }

    @Test
    fun `deleting a conversation removes only its own messages`() = runBlocking {
        val ids = (1..2).map { index ->
            val id = db.sessionDao().insert(
                com.agentkeyboard.data.db.AgentSessionEntity(
                    taskType = TaskType.KNOWLEDGE_ASK.name, status = "DONE", createdAt = index.toLong(),
                ),
            )
            db.messageDao().insert(
                com.agentkeyboard.data.db.AgentMessageEntity(
                    sessionId = id, role = "user", content = "问题$index", stepNo = 0, createdAt = 1L,
                ),
            )
            id
        }
        db.sessionDao().delete(ids.first())
        assertTrue(db.messageDao().bySession(ids.first()).isEmpty())
        assertEquals(listOf("问题2"), db.messageDao().bySession(ids.last()).map { it.content })
    }

    @Test
    fun `continuing a conversation moves it to the top of history`() = runBlocking {
        val older = db.sessionDao().insert(
            com.agentkeyboard.data.db.AgentSessionEntity(taskType = "KNOWLEDGE_ASK", status = "DONE", createdAt = 1_000L),
        )
        val newer = db.sessionDao().insert(
            com.agentkeyboard.data.db.AgentSessionEntity(taskType = "KNOWLEDGE_ASK", status = "DONE", createdAt = 2_000L),
        )

        db.sessionDao().touch(older, "RUNNING", 3_000L)

        assertEquals(listOf(older, newer), db.sessionDao().recentByTaskType("KNOWLEDGE_ASK", 10).map { it.id })
    }

    @Test
    fun `accepted memory deduplicates edits and deletes in Room`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = AgentProfileStore(db, com.agentkeyboard.data.SettingsRepository(context))
        val suggestion = requireNotNull(ProfileSuggestion.explicitMemory("记住：文件控制程序是质管文档的简称"))
        assertTrue(store.memories().isEmpty())
        assertEquals(ProfileSaveResult.SAVED, store.accept(suggestion))
        assertEquals(ProfileSaveResult.ALREADY_PRESENT, store.accept(suggestion))
        val entry = store.memories().single()
        assertEquals(ProfileSaveResult.SAVED, store.saveMemory("文件控制程序是质控文档的简称", entry.id))
        assertEquals(listOf("文件控制程序是质控文档的简称"),
            store.profileFor(knowledgeAskTask("文件控制程序怎么规定的？", null)).memories)
        assertTrue(store.profileFor(knowledgeAskTask("明天会下雨吗？", null)).memories.isEmpty())
        store.deleteMemory(entry.id)
        assertTrue(store.memories().isEmpty())
    }

    @Test
    fun `permission_state 二次 upsert - 覆盖不抛约束异常`() = runBlocking {
        val dao = db.permissionStateDao()
        dao.upsert(PermissionStateEntity("web_search", PermissionStates.ASK_EVERY_TIME, 1_000L))
        dao.upsert(PermissionStateEntity("web_search", PermissionStates.ALWAYS_ALLOW, 2_000L))
        val row = dao.byTool("web_search")
        assertEquals(PermissionStates.ALWAYS_ALLOW, row?.state)
        assertEquals(2_000L, row?.updatedAt)
        assertEquals(1, dao.all().size) // REPLACE 不产生重复行
    }

    @Test
    fun `permission_state upsert 多工具互不覆盖`() = runBlocking {
        val dao = db.permissionStateDao()
        dao.upsert(PermissionStateEntity("web_search", PermissionStates.ALWAYS_ALLOW, 1L))
        dao.upsert(PermissionStateEntity("fetch_page", PermissionStates.ASK_EVERY_TIME, 2L))
        dao.upsert(PermissionStateEntity("web_search", PermissionStates.UNASKED, 3L)) // 重置授权
        assertEquals(PermissionStates.UNASKED, dao.byTool("web_search")?.state)
        assertEquals(PermissionStates.ASK_EVERY_TIME, dao.byTool("fetch_page")?.state)
    }

    @Test
    fun `updateState 与 upsert 等价覆盖`() = runBlocking {
        val dao = db.permissionStateDao()
        dao.upsert(PermissionStateEntity("llm_call", PermissionStates.UNASKED, 1L))
        dao.updateState("llm_call", PermissionStates.ALWAYS_ALLOW, 9L)
        assertEquals(PermissionStates.ALWAYS_ALLOW, dao.byTool("llm_call")?.state)
    }

    @Test
    fun `activity_log 滚动清理 - 超限与过期`() = runBlocking {
        val dao: ActivityLogDao = db.activityLogDao()
        val now = 100_000L
        repeat(RetentionPolicy.MAX_LOG_ENTRIES + 20) { i ->
            dao.insert(
                ActivityLogEntity(
                    ts = now + i, toolId = "web_search", outcome = "AUTO_ALLOW",
                    containsUserContent = UserContentFlags.NONE, target = "搜索服务",
                    summary = "s", preview = "p", sessionId = null,
                )
            )
        }
        assertEquals(RetentionPolicy.MAX_LOG_ENTRIES + 20, dao.count())
        dao.cleanup(now - 1, RetentionPolicy.MAX_LOG_ENTRIES) // 只触发条数上限
        assertEquals(RetentionPolicy.MAX_LOG_ENTRIES, dao.count())
        dao.cleanup(now + 999_999L, RetentionPolicy.MAX_LOG_ENTRIES) // 只触发过期
        assertEquals(0, dao.count())
        assertTrue(dao.recent(5).toString().isNotEmpty())
    }
}
