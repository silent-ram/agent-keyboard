package com.agentkeyboard.tools

import com.agentkeyboard.agent.ConfirmationResolver
import com.agentkeyboard.agent.FakeActivityLogDao
import com.agentkeyboard.agent.FakePermissionStateDao
import com.agentkeyboard.agent.permission.PermissionGate
import com.agentkeyboard.agent.permission.TaintSnapshot
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** G2 工具结果契约:稳定错误码 / retryable / 结构化 JSON 回喂。 */
class ToolContractTest {

    private class StubTransport(val status: Int = 200, val body: String) : HttpTransport {
        override suspend fun execute(request: HttpRequest): HttpResponse = HttpResponse(status, body)
    }

    @Test
    fun `error结果 - wrapped为结构化JSON 含code与retryable`() {
        val r = ToolResult.error("服务超时", ToolErrorCodes.TIMEOUT, retryable = true)
        assertEquals(ToolErrorCodes.TIMEOUT, r.errorCode)
        assertTrue(r.retryable)
        assertTrue(r.wrapped.startsWith(ToolResultWrapper.OPEN))
        assertTrue(r.wrapped.endsWith(ToolResultWrapper.CLOSE))
        val inner = r.wrapped
            .removePrefix(ToolResultWrapper.OPEN).removeSuffix(ToolResultWrapper.CLOSE).trim()
        val error = JSONObject(inner).getJSONObject("error")
        assertEquals("TIMEOUT", error.getString("code"))
        assertTrue(error.getBoolean("retryable"))
        assertEquals("服务超时", error.getString("message"))
        assertEquals("服务超时", r.raw) // raw 保持展示文案不变
    }

    @Test
    fun `httpErrorCode - 状态码映射`() {
        assertEquals(ToolErrorCodes.AUTH, httpErrorCode(401).first)
        assertEquals(ToolErrorCodes.AUTH, httpErrorCode(403).first)
        assertEquals(ToolErrorCodes.RATE_LIMIT, httpErrorCode(429).first)
        assertTrue(httpErrorCode(429).second)
        assertEquals(ToolErrorCodes.REMOTE_ERROR, httpErrorCode(500).first)
        assertTrue(httpErrorCode(500).second)
        assertFalse(httpErrorCode(404).second)
    }

    @Test
    fun `web_search - 未配置与限流错误码`() = runBlocking {
        val noKey = WebSearchTool(apiKeyProvider = { null })
        assertEquals(ToolErrorCodes.NOT_CONFIGURED, noKey.execute("""{"query":"q"}""").errorCode)
        val limited = WebSearchTool(apiKeyProvider = { "k" }, transport = StubTransport(429, ""))
        val r = limited.execute("""{"query":"q"}""")
        assertEquals(ToolErrorCodes.RATE_LIMIT, r.errorCode)
        assertTrue(r.retryable)
    }

    @Test
    fun `web_search - 空结果转EMPTY建议换词`() = runBlocking {
        val tool = WebSearchTool(apiKeyProvider = { "k" }, transport = StubTransport(200, """{"results":[]}"""))
        val r = tool.execute("""{"query":"q"}""")
        assertEquals(ToolErrorCodes.EMPTY, r.errorCode)
        assertFalse(r.retryable)
    }

    @Test
    fun `web_search - 超时转TIMEOUT可重试`() = runBlocking {
        val timeout = object : HttpTransport {
            override suspend fun execute(request: HttpRequest): HttpResponse =
                throw java.net.SocketTimeoutException("t")
        }
        val tool = WebSearchTool(apiKeyProvider = { "k" }, transport = timeout)
        val r = tool.execute("""{"query":"q"}""")
        assertEquals(ToolErrorCodes.TIMEOUT, r.errorCode)
        assertTrue(r.retryable)
    }

    @Test
    fun `kb_search - 未开启与未配置错误码`() = runBlocking {
        val disabled = KbSearchTool(retrieverProvider = { null }, enabledProvider = { false })
        assertEquals(ToolErrorCodes.DISABLED, disabled.execute("""{"query":"q"}""").errorCode)
        val unconfigured = KbSearchTool(retrieverProvider = { null })
        assertEquals(ToolErrorCodes.NOT_CONFIGURED, unconfigured.execute("""{"query":"q"}""").errorCode)
    }

    @Test
    fun `registry - 用户拒绝映射USER_DENIED且raw保持协议值`() = runBlocking {
        val gate = PermissionGate(
            FakePermissionStateDao(), FakeActivityLogDao(),
            ConfirmationResolver { req -> req.kind == com.agentkeyboard.agent.ConfirmationKind.LLM_DISCLOSURE },
        )
        val registry = AgentToolRegistry(
            tools = listOf(WebSearchTool(apiKeyProvider = { "k" }, transport = StubTransport(200, """{"results":[]}"""))),
            gate = gate, activityLogDao = FakeActivityLogDao(),
        )
        val r = registry.execute(
            ToolCall("c1", "web_search", """{"query":"q"}"""),
            TaintSnapshot(null, null), sessionId = null,
        )
        assertEquals(TOOL_USER_DENIED, r.raw) // Runtime 终态判定依赖的协议值不变
        assertEquals(ToolErrorCodes.USER_DENIED, r.errorCode)
        assertFalse(r.retryable)
    }

    @Test
    fun `registry - 未知工具映射DISABLED`() = runBlocking {
        val gate = PermissionGate(FakePermissionStateDao(), FakeActivityLogDao(), ConfirmationResolver { true })
        val registry = AgentToolRegistry(emptyList(), gate, FakeActivityLogDao())
        val r = registry.execute(ToolCall("c1", "ghost_tool", "{}"), TaintSnapshot(null, null), null)
        assertEquals(ToolErrorCodes.DISABLED, r.errorCode)
    }
}
