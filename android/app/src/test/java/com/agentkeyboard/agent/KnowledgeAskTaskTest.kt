package com.agentkeyboard.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeAskTaskTest {
    @Test
    fun `keyboard knowledge question contains only explicit question and local retrieval flag`() {
        val task = knowledgeAskTask(
            userText = "项目下季度目标是什么？",
            sessionId = 17L,
        )

        assertEquals(TaskType.KNOWLEDGE_ASK, task.type)
        assertEquals("项目下季度目标是什么？", task.userText)
        assertEquals(17L, task.sessionId)
        assertEquals("", task.contextBeforeText)
        assertEquals("", task.contextAfterText)
        assertNull(task.clipboardText)
        assertTrue(task.kbEnabled)
        assertFalse(task.singleTurn)
    }
}
