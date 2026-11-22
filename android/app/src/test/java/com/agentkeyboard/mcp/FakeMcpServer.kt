package com.agentkeyboard.mcp

import com.agentkeyboard.tools.HttpRequest
import com.agentkeyboard.tools.HttpResponse
import com.agentkeyboard.tools.HttpTransport
import org.json.JSONObject

/** 测试共用:脚本化 MCP server(按 JSON-RPC method 应答,记录全部请求供断言)。 */
internal class FakeMcpServer(
    var sessionId: String? = null,
    var sse: Boolean = false,
    var httpStatus: Int = 200,
    var toolNames: List<String> = listOf("get_time"),
    var callContentJson: String = """[{"type":"text","text":"2026-11-22 04:31 UTC"}]""",
    var callIsError: Boolean = false,
    var rpcError: Boolean = false,
) : HttpTransport {
    val requests = mutableListOf<HttpRequest>()

    override suspend fun execute(request: HttpRequest): HttpResponse {
        requests += request
        if (httpStatus !in 200..299) return HttpResponse(httpStatus, "")
        val body = JSONObject(request.body!!)
        val method = body.getString("method")
        if (method == "notifications/initialized") return HttpResponse(202, "")
        val id = body.getLong("id")
        val payload = when {
            rpcError -> """{"jsonrpc":"2.0","id":$id,"error":{"code":-32601,"message":"method not found"}}"""
            method == "initialize" ->
                """{"jsonrpc":"2.0","id":$id,"result":{"protocolVersion":"2025-03-26","capabilities":{},"serverInfo":{"name":"fake","version":"1.0"}}}"""
            method == "tools/list" -> {
                val tools = toolNames.joinToString(",") { name ->
                    """{"name":"$name","description":"desc of $name","inputSchema":{"type":"object","properties":{"tz":{"type":"string"}}}}"""
                }
                """{"jsonrpc":"2.0","id":$id,"result":{"tools":[$tools]}}"""
            }
            method == "tools/call" ->
                """{"jsonrpc":"2.0","id":$id,"result":{"content":$callContentJson,"isError":$callIsError}}"""
            else -> """{"jsonrpc":"2.0","id":$id,"result":{}}"""
        }
        val headers = if (method == "initialize" && sessionId != null) {
            mapOf("mcp-session-id" to sessionId!!)
        } else {
            emptyMap()
        }
        val responseBody = if (sse) "event: message\ndata: $payload\n\n" else payload
        return HttpResponse(200, responseBody, headers)
    }

    fun methods(): List<String> = requests.map { JSONObject(it.body!!).getString("method") }
    fun callRequests(): List<HttpRequest> =
        requests.filter { JSONObject(it.body!!).getString("method") == "tools/call" }
    fun callRequest(): HttpRequest = callRequests().last()
}
