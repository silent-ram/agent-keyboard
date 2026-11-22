package com.agentkeyboard.agent

import com.agentkeyboard.agent.permission.PermissionGate
import com.agentkeyboard.ai.ToolCallResult
import com.agentkeyboard.data.LlmConfig
import com.agentkeyboard.tools.AgentToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test

/** G5:任务开始时 profileProvider 读取 user.md 与显式记忆,注入 system 提示。 */
class AgentProfileInjectionTest {

    @Test
    fun `profile 注入 - system 提示含 userMd 与记忆`() = runBlocking<Unit> {
        val sessionDao = FakeSessionDao()
        val messageDao = FakeMessageDao()
        val logs = FakeActivityLogDao()
        val gate = PermissionGate(
            FakePermissionStateDao(), logs,
            ConfirmationResolver { true }, emitConfirmation = {},
        )
        val registry = AgentToolRegistry(emptyList(), gate, logs)
        val received = mutableListOf<List<com.agentkeyboard.ai.EngineMessage>>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val engine = AgentRuntime(
            scope = scope,
            sessionDao = sessionDao,
            messageDao = messageDao,
            gate = gate,
            registry = registry,
            llmConfigProvider = { LlmConfig("https://api.example.com/v1", "sk-fake", null) },
            llmCall = { _, _, _, messages, _, _, _ ->
                received += messages
                ToolCallResult.Content("好的")
            },
            backoffMs = { 0L },
            retryDelay = {},
            profileProvider = {
                AgentProfile(userMd = "我在制造业做质量管理", memories = listOf("回答要简洁"))
            },
        )
        val collector = scope.launch { engine.events.collect {} }
        withTimeout(5000) { engine.start(Task(TaskType.ASK, "介绍一下你自己")).join() }
        delay(100)
        val system = received.single().first()
        assertTrue(system.role == com.agentkeyboard.ai.EngineClient.ROLE_SYSTEM)
        assertTrue(system.content.contains("我在制造业做质量管理"))
        assertTrue(system.content.contains("回答要简洁"))
        collector.cancel(); scope.coroutineContext[kotlinx.coroutines.Job]?.cancelChildren()
    }

    @Test
    fun `profile 缺省为空画像 - system 提示不含画像段`() = runBlocking<Unit> {
        val sessionDao = FakeSessionDao()
        val messageDao = FakeMessageDao()
        val logs = FakeActivityLogDao()
        val gate = PermissionGate(FakePermissionStateDao(), logs, ConfirmationResolver { true }, emitConfirmation = {})
        val registry = AgentToolRegistry(emptyList(), gate, logs)
        val received = mutableListOf<List<com.agentkeyboard.ai.EngineMessage>>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val engine = AgentRuntime(
            scope = scope,
            sessionDao = sessionDao,
            messageDao = messageDao,
            gate = gate,
            registry = registry,
            llmConfigProvider = { LlmConfig("https://api.example.com/v1", "sk-fake", null) },
            llmCall = { _, _, _, messages, _, _, _ ->
                received += messages
                ToolCallResult.Content("好的")
            },
            backoffMs = { 0L },
            retryDelay = {},
        )
        val collector = scope.launch { engine.events.collect {} }
        withTimeout(5000) { engine.start(Task(TaskType.ASK, "hi")).join() }
        delay(100)
        assertTrue(received.single().first().content.contains("user.md").not())
        collector.cancel(); scope.coroutineContext[kotlinx.coroutines.Job]?.cancelChildren()
    }
}
