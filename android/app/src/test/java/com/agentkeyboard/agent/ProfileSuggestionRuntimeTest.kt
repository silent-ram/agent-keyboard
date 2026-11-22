package com.agentkeyboard.agent

import com.agentkeyboard.agent.permission.PermissionGate
import com.agentkeyboard.ai.EngineToolCall
import com.agentkeyboard.ai.ToolCallResult
import com.agentkeyboard.data.LlmConfig
import com.agentkeyboard.tools.AgentToolRegistry
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileSuggestionRuntimeTest {
    @Test
    fun `proposal is available on first step only and does not enter conversation history`() = runBlocking {
        val userText = "以后都先给结论。"
        val callId = "test-proposal-id"
        val logs = FakeActivityLogDao()
        val gate = PermissionGate(FakePermissionStateDao(), logs, ConfirmationResolver { true })
        val dao = FakeMessageDao()
        val events = mutableListOf<AgentEvent>()
        var calls = 0
        val engine = AgentRuntime(
            scope = this, sessionDao = FakeSessionDao(), messageDao = dao, gate = gate,
            registry = AgentToolRegistry(emptyList(), gate, logs),
            llmConfigProvider = { LlmConfig("https://api.example.com/v1", "placeholder") },
            llmCall = { _, _, _, messages, tools, _, _ ->
                assertTrue(messages.none { message -> message.toolCalls.orEmpty().any { it.id == callId } })
                if (calls++ == 0) {
                    assertTrue(tools.any { it.id == ProfileSuggestion.ID })
                    ToolCallResult.ToolCalls("", listOf(EngineToolCall(callId, ProfileSuggestion.ID,
                        """{"kind":"user_note","text":"$userText"}""")))
                } else {
                    assertFalse(tools.any { it.id == ProfileSuggestion.ID })
                    ToolCallResult.Content("好的。")
                }
            },
        )
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { engine.events.collect { events += it } }
        try {
            engine.start(knowledgeAskTask(userText, null, allowProfileSuggestion = true)).join()
            val result = events.filterIsInstance<AgentEvent.Finished>().single()
            assertEquals(userText, result.profileSuggestion?.text)
            assertEquals(2, dao.bySession(result.sessionId).size)
            assertTrue(dao.bySession(result.sessionId).none { it.toolJson?.contains(callId) == true })
            engine.start(knowledgeAskTask("下一题", result.sessionId)).join()
            assertNull(events.filterIsInstance<AgentEvent.Finished>().last().profileSuggestion)
        } finally {
            collector.cancelAndJoin()
        }
    }
}
