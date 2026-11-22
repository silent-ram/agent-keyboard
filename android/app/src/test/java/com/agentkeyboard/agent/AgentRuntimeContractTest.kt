package com.agentkeyboard.agent

import com.agentkeyboard.tools.ToolIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRuntimeContractTest {

    @Test
    fun `单轮快捷动作不开放任何工具`() {
        val request = TaskRequest.create(
            Task(TaskType.REWRITE, "改写", singleTurn = true),
            setOf(ToolIds.KB_SEARCH, ToolIds.WEB_SEARCH),
        )

        assertTrue(request.allowedToolIds.isEmpty())
    }

    @Test
    fun `知识库关闭时仅从任务白名单移除知识库工具`() {
        val request = TaskRequest.create(
            Task(TaskType.ASK, "问问题", kbEnabled = false),
            setOf(ToolIds.KB_SEARCH, ToolIds.WEB_SEARCH),
        )

        assertEquals(setOf(ToolIds.WEB_SEARCH), request.allowedToolIds)
    }

    @Test
    fun `模型不能通过请求扩大注册工具白名单`() {
        val request = TaskRequest.create(
            Task(TaskType.ASK, "问问题"),
            setOf(ToolIds.WEB_SEARCH),
        )

        assertEquals(setOf(ToolIds.WEB_SEARCH), request.allowedToolIds)
        assertTrue(ToolIds.KB_SEARCH !in request.allowedToolIds)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `空白任务不可构造`() {
        TaskRequest.create(Task(TaskType.ASK, " "), emptySet())
    }
}
