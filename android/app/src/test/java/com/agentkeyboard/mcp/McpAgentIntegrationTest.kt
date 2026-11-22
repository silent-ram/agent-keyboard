package com.agentkeyboard.mcp

import com.agentkeyboard.agent.AgentEvent
import com.agentkeyboard.agent.AgentRuntime
import com.agentkeyboard.agent.ConfirmationKind
import com.agentkeyboard.agent.ConfirmationResolver
import com.agentkeyboard.agent.FakeActivityLogDao
import com.agentkeyboard.agent.FakeMessageDao
import com.agentkeyboard.agent.FakePermissionStateDao
import com.agentkeyboard.agent.FakeSessionDao
import com.agentkeyboard.agent.Task
import com.agentkeyboard.agent.TaskType
import com.agentkeyboard.agent.permission.PermissionGate
import com.agentkeyboard.ai.EngineToolCall
import com.agentkeyboard.ai.ToolCallResult
import com.agentkeyboard.data.LlmConfig
import com.agentkeyboard.data.db.LogOutcomes
import com.agentkeyboard.tools.AgentToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G12 端到端验收:fake MCP server + 真实 AgentRuntime/PermissionGate/审计。
 * 覆盖 gap-analysis G12 验收口径:桥接 server 的工具经完整门控与审计;
 * 拒绝时不发出 tools/call;beforeRun 自动同步。
 */
class McpAgentIntegrationTest {

    companion object {
        private val serverConfig = McpServerConfig(
            id = "test-abc123", name = "测试服务", url = "https://mcp.example.com/mcp",
        )
        private val toolId = McpTool.toolIdFor(serverConfig, "get_time")
    }

    private class Harness(val server: FakeMcpServer) {
        val sessionDao = FakeSessionDao()
        val messageDao = FakeMessageDao()
        val logs = FakeActivityLogDao()
        var allowOutbound = true
        val gate = PermissionGate(
            FakePermissionStateDao(), logs,
            ConfirmationResolver { req ->
                if (req.kind == ConfirmationKind.LLM_DISCLOSURE) true else allowOutbound
            },
            emitConfirmation = {},
        )
        val registry = AgentToolRegistry(emptyList(), gate, logs)
        val bridge = McpToolBridge(
            serversProvider = { listOf(serverConfig) },
            authProvider = { null },
            client = McpClient(server),
        )
        val llmToolsSeen = mutableListOf<List<com.agentkeyboard.tools.ToolSchema>>()
        val llmScript = mutableListOf<ToolCallResult>()
        val receivedMessages = mutableListOf<List<com.agentkeyboard.ai.EngineMessage>>()
        val llmCall: suspend (String, String, String, List<com.agentkeyboard.ai.EngineMessage>, List<com.agentkeyboard.tools.ToolSchema>, com.agentkeyboard.ai.StreamingHandle, (String) -> Unit) -> ToolCallResult =
            { _, _, _, messages, tools, _, _ ->
                receivedMessages += messages
                llmToolsSeen += tools
                llmScript.removeAt(0)
            }

        fun engine(scope: CoroutineScope) = AgentRuntime(
            scope = scope,
            sessionDao = sessionDao,
            messageDao = messageDao,
            gate = gate,
            registry = registry,
            llmConfigProvider = { LlmConfig("https://api.example.com/v1", "sk-fake", null) },
            llmCall = llmCall,
            backoffMs = { 0L },
            retryDelay = {},
            beforeRun = { bridge.syncInto(registry) },
        )
    }

    @Test
    fun `MCP工具全链路 - 自动同步 授权后调用 结果回喂 审计落盘`() { runBlocking {
        val server = FakeMcpServer()
        val h = Harness(server)
        h.llmScript += ToolCallResult.ToolCalls(
            content = "",
            calls = listOf(EngineToolCall("c1", toolId, """{"tz":"UTC"}""")),
        )
        h.llmScript += ToolCallResult.Content("现在是 2026-11-22")
        val events = mutableListOf<AgentEvent>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val engine = h.engine(scope)
        val collector = scope.launch { engine.events.collect { events += it } }

        withTimeout(5000) { engine.start(Task(TaskType.ASK, "现在几点")).join() }
        delay(100)

        // beforeRun 自动同步:首轮 LLM 请求即携带 MCP 工具 schema
        assertTrue(h.llmToolsSeen.first().any { it.id == toolId })
        // tools/call 真实发出,参数正确
        val callParams = JSONObject(server.callRequest().body!!).getJSONObject("params")
        assertEquals("get_time", callParams.getString("name"))
        assertEquals("UTC", callParams.getJSONObject("arguments").getString("tz"))
        // 结果回喂:第二步 LLM 收到包裹后的工具文本
        val toolMessage = h.receivedMessages[1].last()
        assertTrue(toolMessage.content.contains("2026-11-22 04:31 UTC"))
        // 终态与来源事件
        assertEquals("现在是 2026-11-22", events.filterIsInstance<AgentEvent.Finished>().single().content)
        assertTrue(events.filterIsInstance<AgentEvent.StepFinished>().single().result.ok)
        // 审计:授权(CONFIRMED)落盘,toolId 为 MCP 工具 id
        assertTrue(h.logs.entries.any { it.toolId == toolId && it.outcome == LogOutcomes.CONFIRMED })
        collector.cancel(); scope.coroutineContext[Job]?.cancelChildren()
    } }

    @Test
    fun `MCP工具被拒绝 - 不发出tools-call 任务INCOMPLETE`() { runBlocking {
        val server = FakeMcpServer()
        val h = Harness(server).apply { allowOutbound = false }
        h.llmScript += ToolCallResult.ToolCalls(
            content = "",
            calls = listOf(EngineToolCall("c1", toolId, """{"tz":"UTC"}""")),
        )
        h.llmScript += ToolCallResult.Content("不应到达")
        val events = mutableListOf<AgentEvent>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val engine = h.engine(scope)
        val collector = scope.launch { engine.events.collect { events += it } }

        withTimeout(5000) { engine.start(Task(TaskType.ASK, "现在几点")).join() }
        delay(100)

        assertTrue(server.callRequests().isEmpty()) // 拒绝即不出网
        val incomplete = events.filterIsInstance<AgentEvent.Incomplete>().single()
        assertTrue(incomplete.reason.contains("拒绝"))
        assertTrue(h.logs.entries.any { it.toolId == toolId && it.outcome == LogOutcomes.DENIED })
        collector.cancel(); scope.coroutineContext[Job]?.cancelChildren()
    } }

    @Test
    fun `MCP调用失败 - 结构化错误回喂 不中断其余步骤`() { runBlocking {
        val server = FakeMcpServer() // 先以健康 server 同步注册工具
        val h = Harness(server)
        h.bridge.syncInto(h.registry)
        server.httpStatus = 500 // 同步后切换故障:模拟运行期 MCP server 宕机
        h.llmScript += ToolCallResult.ToolCalls(
            content = "",
            calls = listOf(EngineToolCall("c1", toolId, """{"tz":"UTC"}""")),
        )
        h.llmScript += ToolCallResult.Content("时间服务暂时不可用")
        val events = mutableListOf<AgentEvent>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val engine = AgentRuntime(
            scope = scope,
            sessionDao = h.sessionDao, messageDao = h.messageDao, gate = h.gate, registry = h.registry,
            llmConfigProvider = { LlmConfig("https://api.example.com/v1", "sk-fake", null) },
            llmCall = h.llmCall, backoffMs = { 0L }, retryDelay = {},
        )
        val collector = scope.launch { engine.events.collect { events += it } }

        withTimeout(5000) { engine.start(Task(TaskType.ASK, "现在几点")).join() }
        delay(100)

        val toolMessage = h.receivedMessages[1].last()
        assertTrue(toolMessage.content.contains("REMOTE_ERROR"))
        assertTrue(toolMessage.content.contains("retryable"))
        // 失败诚实:工具失败且无任何引用 → INCOMPLETE,不伪装成功
        events.filterIsInstance<AgentEvent.Incomplete>().single()
        collector.cancel(); scope.coroutineContext[Job]?.cancelChildren()
    } }
}
