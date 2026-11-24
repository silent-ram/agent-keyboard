package com.agentkeyboard.mcp

import com.agentkeyboard.tools.HttpRequest
import com.agentkeyboard.tools.HttpResponse
import com.agentkeyboard.tools.HttpTransport
import com.agentkeyboard.tools.ToolErrorCodes
import com.agentkeyboard.tools.ToolResultWrapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

/** MCP 客户端:握手 / tools/list / tools/call / SSE 解码 / 错误码 / 取消传播。 */
class McpClientTest {

    @Test
    fun `listTools - 完整握手后返回工具与schema`() = runBlocking {
        val server = FakeMcpServer(toolNames = listOf("get_time", "add"))
        val tools = McpClient(server).listTools("https://mcp.example.com/mcp", null)
        assertEquals(listOf("get_time", "add"), tools.map { it.name })
        assertTrue(tools[0].inputSchemaJson.contains("tz"))
        assertEquals(
            listOf("initialize", "notifications/initialized", "tools/list"),
            server.methods(),
        )
    }

    @Test
    fun `listTools - session id 透传到后续请求头`() = runBlocking {
        val server = FakeMcpServer(sessionId = "sess-abc")
        McpClient(server).listTools("https://mcp.example.com/mcp", null)
        val listReq = server.requests.last()
        assertEquals("sess-abc", listReq.headers["Mcp-Session-Id"])
    }

    @Test
    fun `listTools - Authorization 头外发`() = runBlocking {
        val server = FakeMcpServer()
        McpClient(server).listTools("https://mcp.example.com/mcp", "Bearer token-1")
        assertEquals("Bearer token-1", server.requests.first().headers["Authorization"])
    }

    @Test
    fun `listTools - SSE 响应解码`() = runBlocking {
        val server = FakeMcpServer(sse = true)
        val tools = McpClient(server).listTools("https://mcp.example.com/mcp", null)
        assertEquals(listOf("get_time"), tools.map { it.name })
    }

    @Test
    fun `callTool - 文本块拼接并包裹`() = runBlocking {
        val server = FakeMcpServer(
            callContentJson = """[{"type":"text","text":"第一段"},{"type":"text","text":"第二段"}]""",
        )
        val r = McpClient(server).callTool("https://mcp.example.com/mcp", null, "get_time", """{"tz":"UTC"}""")
        assertTrue(r.ok)
        assertEquals("第一段\n\n第二段", r.raw)
        assertTrue(r.wrapped.startsWith(ToolResultWrapper.OPEN))
        val params = JSONObject(server.callRequest().body!!).getJSONObject("params")
        assertEquals("get_time", params.getString("name"))
        assertEquals("UTC", params.getJSONObject("arguments").getString("tz"))
    }

    @Test
    fun `callTool - 非文本块占位不伪装`() = runBlocking {
        val server = FakeMcpServer(callContentJson = """[{"type":"image","data":"...","mimeType":"image/png"}]""")
        val r = McpClient(server).callTool("https://mcp.example.com/mcp", null, "get_time", "{}")
        assertTrue(r.ok)
        assertTrue(r.raw.contains("不支持的 MCP 内容类型:image"))
    }

    @Test
    fun `callTool - isError 转 REMOTE_ERROR`() = runBlocking {
        val server = FakeMcpServer(callIsError = true, callContentJson = """[{"type":"text","text":"boom"}]""")
        val r = McpClient(server).callTool("https://mcp.example.com/mcp", null, "get_time", "{}")
        assertFalse(r.ok)
        assertEquals(ToolErrorCodes.REMOTE_ERROR, r.errorCode)
        assertTrue(r.raw.contains("boom"))
    }

    @Test
    fun `callTool - 非法参数 JSON 转 BAD_PARAMS 且不出网`() = runBlocking {
        val server = FakeMcpServer()
        val r = McpClient(server).callTool("https://mcp.example.com/mcp", null, "get_time", "not-json")
        assertEquals(ToolErrorCodes.BAD_PARAMS, r.errorCode)
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun `callTool - HTTP 401 映射 AUTH 不可重试`() = runBlocking {
        val server = FakeMcpServer(httpStatus = 401)
        val r = McpClient(server).callTool("https://mcp.example.com/mcp", null, "get_time", "{}")
        assertEquals(ToolErrorCodes.AUTH, r.errorCode)
        assertFalse(r.retryable)
    }

    @Test
    fun `callTool - HTTP 429 映射 RATE_LIMIT 可重试`() = runBlocking {
        val server = FakeMcpServer(httpStatus = 429)
        val r = McpClient(server).callTool("https://mcp.example.com/mcp", null, "get_time", "{}")
        assertEquals(ToolErrorCodes.RATE_LIMIT, r.errorCode)
        assertTrue(r.retryable)
    }

    @Test
    fun `callTool - JSON-RPC error 映射 REMOTE_ERROR`() = runBlocking {
        val server = FakeMcpServer(rpcError = true)
        val r = McpClient(server).callTool("https://mcp.example.com/mcp", null, "get_time", "{}")
        assertEquals(ToolErrorCodes.REMOTE_ERROR, r.errorCode)
        assertTrue(r.raw.contains("method not found"))
    }

    @Test
    fun `callTool - 超时与网络错误映射可重试`() = runBlocking {
        val timeout = object : HttpTransport {
            override suspend fun execute(request: HttpRequest): HttpResponse = throw SocketTimeoutException("t")
        }
        val r1 = McpClient(timeout).callTool("https://mcp.example.com/mcp", null, "t", "{}")
        assertEquals(ToolErrorCodes.TIMEOUT, r1.errorCode)
        assertTrue(r1.retryable)
        val offline = object : HttpTransport {
            override suspend fun execute(request: HttpRequest): HttpResponse = throw IOException("dns")
        }
        val r2 = McpClient(offline).callTool("https://mcp.example.com/mcp", null, "t", "{}")
        assertEquals(ToolErrorCodes.NETWORK, r2.errorCode)
        assertTrue(r2.retryable)
    }

    @Test
    fun `callTool - 取消必须传播`() = runBlocking {
        val cancelled = object : HttpTransport {
            override suspend fun execute(request: HttpRequest): HttpResponse =
                throw CancellationException("engine cancelled")
        }
        try {
            McpClient(cancelled).callTool("https://mcp.example.com/mcp", null, "t", "{}")
            fail("取消被吞")
        } catch (e: CancellationException) {
            // 预期
        }
    }

    @Test
    fun `callTool - 超长结果截断标注`() = runBlocking {
        val server = FakeMcpServer(
            callContentJson = """[{"type":"text","text":"${"长".repeat(McpClient.MAX_RESULT_CHARS + 100)}"}]""",
        )
        val r = McpClient(server).callTool("https://mcp.example.com/mcp", null, "t", "{}")
        assertTrue(r.ok)
        assertTrue(r.raw.length < McpClient.MAX_RESULT_CHARS + 200)
        assertTrue(r.raw.contains("已截断"))
    }

    @Test
    fun `decodeRpcBody - 多帧 SSE 取 id 匹配帧`() {
        val client = McpClient(FakeMcpServer())
        val body = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\"}\n\n" +
            "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{\"ok\":true}}\n\n"
        val decoded = client.decodeRpcBody(body, expectedId = 7)
        assertNotNull(decoded.optJSONObject("result"))
    }

    @Test
    fun `decodeRpcBody - 纯 JSON 响应 id 不匹配抛 BAD_RESPONSE`() {
        val client = McpClient(FakeMcpServer())
        try {
            client.decodeRpcBody("""{"jsonrpc":"2.0","id":3,"result":{}}""", expectedId = 4)
            fail("应抛 McpException")
        } catch (e: McpException) {
            assertEquals(ToolErrorCodes.BAD_RESPONSE, e.code)
        }
    }

    @Test
    fun `listTools - 工具缺少 name 转 BAD_RESPONSE 而非抛 JSONException`() = runBlocking {
        val transport = HttpTransport { request ->
            val body = JSONObject(request.body!!)
            if (!body.has("id")) return@HttpTransport HttpResponse(202, "")
            val id = body.getLong("id")
            val result = if (body.getString("method") == "tools/list") {
                """{"tools":[{"description":"no name"}]}"""
            } else {
                "{}"
            }
            HttpResponse(200, """{"jsonrpc":"2.0","id":$id,"result":$result}""")
        }
        try {
            McpClient(transport).listTools("https://mcp.example.com/mcp", null)
            fail("应抛 McpException")
        } catch (e: McpException) {
            assertEquals(ToolErrorCodes.BAD_RESPONSE, e.code)
        }
    }

    @Test
    fun `callTool - content 项不是对象时返回 BAD_RESPONSE 结果`() = runBlocking {
        val server = FakeMcpServer(callContentJson = """["plain string"]""")
        val r = McpClient(server).callTool("https://mcp.example.com/mcp", null, "t", "{}")
        assertFalse(r.ok)
        assertEquals(ToolErrorCodes.BAD_RESPONSE, r.errorCode)
    }

    @Test
    fun `callTool - isError 文本超长被截断`() = runBlocking {
        val long = "x".repeat(McpClient.MAX_ERROR_CHARS * 3)
        val server = FakeMcpServer(
            callContentJson = """[{"type":"text","text":"$long"}]""",
            callIsError = true,
        )
        val r = McpClient(server).callTool("https://mcp.example.com/mcp", null, "t", "{}")
        assertFalse(r.ok)
        assertTrue(r.raw.length < McpClient.MAX_ERROR_CHARS + 100)
    }

    @Test
    fun `decodeRpcBody - 坏 JSON 抛 BAD_RESPONSE`() {
        val client = McpClient(FakeMcpServer())
        try {
            client.decodeRpcBody("{not json", expectedId = 1)
            fail("应抛 McpException")
        } catch (e: McpException) {
            assertEquals(ToolErrorCodes.BAD_RESPONSE, e.code)
        }
    }
}
