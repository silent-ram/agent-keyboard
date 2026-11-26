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
        // 方案 5.2:错误回喂带下一步建议(模型按错误码拿到默认提示,展示文案不变)
        assertTrue(error.getString("hint").isNotBlank())
        assertEquals(error.getString("hint"), r.hint)
        assertTrue("可重试的错误建议里允许再试一次", error.getString("hint").contains("重试一次"))
    }

    @Test
    fun `error结果 - 自定义hint覆盖默认建议`() {
        val r = ToolResult.error("服务超时", ToolErrorCodes.TIMEOUT, retryable = true, hint = "换个关键词")
        val inner = r.wrapped.removePrefix(ToolResultWrapper.OPEN).removeSuffix(ToolResultWrapper.CLOSE).trim()
        assertEquals("换个关键词", JSONObject(inner).getJSONObject("error").getString("hint"))
        assertEquals("换个关键词", r.hint)
    }

    @Test
    fun `error结果 - 用户拒绝与不存在的工具各有明确建议`() {
        val denied = ToolResult.error("用户拒绝了这次请求", ToolErrorCodes.USER_DENIED)
        assertTrue(denied.hint!!.contains("不要"))
        val missing = ToolResult.error("此工具未注册", ToolErrorCodes.NOT_FOUND)
        assertTrue(missing.hint!!.contains("不要再次调用"))
        assertFalse(missing.retryable)
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

    // 拒绝/询问流程用仍需授权的 fetch_page:web_search / kb_search 已默认可用,不再弹授权询问

    @Test
    fun `registry - 用户拒绝映射USER_DENIED且raw保持协议值`() = runBlocking {
        val gate = PermissionGate(
            FakePermissionStateDao(), FakeActivityLogDao(),
            ConfirmationResolver { req -> req.kind == com.agentkeyboard.agent.ConfirmationKind.LLM_DISCLOSURE },
        )
        val registry = AgentToolRegistry(
            tools = listOf(FetchPageTool(transport = StubTransport(200, "body"))),
            gate = gate, activityLogDao = FakeActivityLogDao(),
        )
        val r = registry.execute(
            ToolCall("c1", "fetch_page", """{"url":"https://a.example/"}"""),
            TaintSnapshot(null, null), sessionId = null,
        )
        assertEquals(TOOL_USER_DENIED, r.raw) // Runtime 终态判定依赖的协议值不变
        assertEquals(ToolErrorCodes.USER_DENIED, r.errorCode)
        assertFalse(r.retryable)
        // 模型看到的不是裸的 user_denied:带错误码与「不要再试」的建议
        val inner = r.wrapped.removePrefix(ToolResultWrapper.OPEN).removeSuffix(ToolResultWrapper.CLOSE).trim()
        val error = JSONObject(inner).getJSONObject("error")
        assertEquals("USER_DENIED", error.getString("code"))
        assertTrue(error.getString("hint").contains("不要"))
    }

    @Test
    fun `registry - web_search 默认放行不弹授权询问`() = runBlocking {
        var confirmations = 0
        val logs = FakeActivityLogDao()
        // resolver 一律拒绝:只要弹了询问,web_search 就不会执行
        val gate = PermissionGate(FakePermissionStateDao(), logs, ConfirmationResolver { confirmations++; false })
        val registry = AgentToolRegistry(
            tools = listOf(
                WebSearchTool(
                    apiKeyProvider = { "k" },
                    transport = StubTransport(200, """{"results":[{"title":"t","url":"https://example.com","content":"c"}]}"""),
                ),
            ),
            gate = gate, activityLogDao = FakeActivityLogDao(),
        )
        val r = registry.execute(ToolCall("c1", "web_search", """{"query":"q"}"""), TaintSnapshot(null, null), sessionId = null)
        assertTrue(r.ok)
        assertEquals(0, confirmations)
        assertEquals(listOf(com.agentkeyboard.data.db.LogOutcomes.AUTO_ALLOW), logs.entries.map { it.outcome })
    }

    @Test
    fun `registry - 参数非JSON直接BAD_PARAMS且不弹授权`() = runBlocking {
        var confirmations = 0
        val gate = PermissionGate(FakePermissionStateDao(), FakeActivityLogDao(), ConfirmationResolver { confirmations++; true })
        val registry = AgentToolRegistry(
            tools = listOf(FetchPageTool(transport = StubTransport(200, "body"))),
            gate = gate, activityLogDao = FakeActivityLogDao(),
        )
        val r = registry.execute(ToolCall("c1", "fetch_page", "not json"), TaintSnapshot(null, null), sessionId = null)
        assertFalse(r.ok)
        assertEquals(ToolErrorCodes.BAD_PARAMS, r.errorCode)
        assertEquals(0, confirmations)
    }

    @Test
    fun `registry - 工具缺参异常映射BAD_PARAMS并带原因`() = runBlocking {
        val tool = object : Tool {
            override val id = "local_echo"
            override val name = "本地回显"
            override val isOutbound = false
            override val targetLabel = "本机"
            override val description = "fake"
            override val schemaJson = """{"type":"object"}"""
            override suspend fun execute(argsJson: String): ToolResult =
                ToolResult.ok(Jsons.requireString(argsJson, "query"), "")
        }
        val gate = PermissionGate(FakePermissionStateDao(), FakeActivityLogDao(), ConfirmationResolver { true })
        val registry = AgentToolRegistry(listOf(tool), gate, FakeActivityLogDao())
        val r = registry.execute(ToolCall("c1", "local_echo", "{}"), TaintSnapshot(null, null), null)
        assertEquals(ToolErrorCodes.BAD_PARAMS, r.errorCode)
        org.junit.Assert.assertTrue(r.raw.contains("缺少参数 query"))
    }

    @Test
    fun `registry - 未知工具映射NOT_FOUND且不回显工具名`() = runBlocking {
        val gate = PermissionGate(FakePermissionStateDao(), FakeActivityLogDao(), ConfirmationResolver { true })
        val registry = AgentToolRegistry(emptyList(), gate, FakeActivityLogDao())
        val r = registry.execute(ToolCall("c1", "ghost_tool", "{}"), TaintSnapshot(null, null), null)
        assertEquals(ToolErrorCodes.NOT_FOUND, r.errorCode)
        assertFalse("模型给的名称不回显进回喂", r.raw.contains("ghost_tool"))
    }
}
