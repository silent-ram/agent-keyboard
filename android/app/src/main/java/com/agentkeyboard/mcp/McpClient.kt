package com.agentkeyboard.mcp

import com.agentkeyboard.tools.DefaultHttpTransport
import com.agentkeyboard.tools.HttpRequest
import com.agentkeyboard.tools.HttpResponse
import com.agentkeyboard.tools.HttpTransport
import com.agentkeyboard.tools.ToolErrorCodes
import com.agentkeyboard.tools.ToolResult
import com.agentkeyboard.tools.ToolResultWrapper
import com.agentkeyboard.tools.httpErrorCode
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicLong

/** MCP 工具描述(tools/list 响应单项;inputSchemaJson 原样透传给 LLM)。 */
data class McpToolDescriptor(
    val name: String,
    val description: String,
    val inputSchemaJson: String,
)

/** MCP 调用异常:携带 G2 稳定错误码,桥接层据此生成 ToolResult 或同步状态。 */
class McpException(
    val code: String,
    val retryable: Boolean,
    message: String,
) : Exception(message)

/**
 * MCP 客户端(G12):JSON-RPC 2.0 over Streamable HTTP(POST 单端点)。
 *
 * 设计取舍(端侧轻量):
 * - 只支持用户显式配置的远程 HTTP server;不支持本地 stdio(Android 无现实土壤),
 *   不做后台静默发现;
 * - 每次 list/call 独立握手(initialize → notifications/initialized → 请求),
 *   不维护常驻 session,状态在服务端会话内的语义由 server 自己保证;
 * - 响应兼容 application/json 与 text/event-stream(取与请求 id 匹配的 data 帧);
 * - initialize 响应的 Mcp-Session-Id 透传到同一次握手的后续请求;
 * - 能力探测(tools/list)不携带用户内容,不经授权弹窗;tools/call 的授权在
 *   AgentToolRegistry/PermissionGate 统一完成,本类不感知。
 */
class McpClient(
    private val transport: HttpTransport = DefaultHttpTransport,
    private val timeoutMs: Int = 20_000,
) {

    companion object {
        const val PROTOCOL_VERSION = "2025-03-26"
        const val CLIENT_NAME = "agent-keyboard"
        const val CLIENT_VERSION = "0.1.0"

        /** MCP 文本结果预算:超出截断并标注,防超长结果挤爆上下文窗口。 */
        const val MAX_RESULT_CHARS = 16_000

        /** 服务端错误文本上限:错误同样回喂模型,不能成为绕过结果预算的通道。 */
        const val MAX_ERROR_CHARS = 2_000
        private const val HEADER_SESSION_ID = "mcp-session-id"
    }

    private val ids = AtomicLong(0)

    /** tools/list:同步 server 当前工具集;失败抛 [McpException]。 */
    suspend fun listTools(serverUrl: String, authHeader: String?): List<McpToolDescriptor> =
        withSession(serverUrl, authHeader) { sessionId ->
            val result = rpc(serverUrl, authHeader, "tools/list", null, sessionId)
            val tools = result.optJSONArray("tools")
                ?: throw McpException(ToolErrorCodes.BAD_RESPONSE, false, "tools/list 响应缺少 tools")
            (0 until tools.length()).map { i ->
                val t = tools.getJSONObject(i)
                McpToolDescriptor(
                    name = t.getString("name"),
                    description = t.optString("description"),
                    inputSchemaJson = t.optJSONObject("inputSchema")?.toString()
                        ?: """{"type":"object","properties":{}}""",
                )
            }
        }

    /** tools/call:文本 content 块拼接回喂;isError/协议/传输错误 → 带稳定码的 error 结果。 */
    suspend fun callTool(
        serverUrl: String,
        authHeader: String?,
        toolName: String,
        argsJson: String,
    ): ToolResult {
        val arguments = try {
            if (argsJson.isBlank()) JSONObject() else JSONObject(argsJson)
        } catch (e: Exception) {
            return ToolResult.error("MCP 工具参数不是合法 JSON", ToolErrorCodes.BAD_PARAMS)
        }
        return try {
            withSession(serverUrl, authHeader) { sessionId ->
                val params = JSONObject()
                    .put("name", toolName)
                    .put("arguments", arguments)
                val result = rpc(serverUrl, authHeader, "tools/call", params, sessionId)
                val text = extractText(result)
                if (result.optBoolean("isError", false)) {
                    ToolResult.error("MCP 工具返回错误:${text.take(MAX_ERROR_CHARS)}", ToolErrorCodes.REMOTE_ERROR)
                } else {
                    val clipped = if (text.length > MAX_RESULT_CHARS) {
                        text.take(MAX_RESULT_CHARS) + "\n…(MCP 结果过长,已截断)"
                    } else {
                        text
                    }
                    ToolResult.ok(raw = clipped, wrapped = ToolResultWrapper.wrap(clipped))
                }
            }
        } catch (e: CancellationException) {
            throw e // 取消必须传播(FR-B1)
        } catch (e: McpException) {
            ToolResult.error("MCP 调用失败:${e.message}", e.code, e.retryable)
        }
    }

    /** 一次独立握手:initialize → initialized 通知 → 业务请求。 */
    private suspend fun <T> withSession(
        serverUrl: String,
        authHeader: String?,
        block: suspend (sessionId: String?) -> T,
    ): T {
        val initParams = JSONObject()
            .put("protocolVersion", PROTOCOL_VERSION)
            .put("capabilities", JSONObject())
            .put(
                "clientInfo",
                JSONObject().put("name", CLIENT_NAME).put("version", CLIENT_VERSION),
            )
        val (initId, initResponse) = rawRequest(serverUrl, authHeader, "initialize", initParams, null)
        val sessionId = initResponse.headers[HEADER_SESSION_ID]
        // 先确认握手有效(id 匹配且无 JSON-RPC error)再发通知
        unwrapResult(decodeRpcBody(initResponse.body, expectedId = initId), "initialize")
        // 通知无 id,202/空体/JSON 均合法;失败不阻断后续调用(server 实现差异);取消必须传播
        try {
            notify(serverUrl, authHeader, "notifications/initialized", sessionId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 通知失败容忍:不同 server 实现对 initialized 通知的处理不一致
        }
        return try {
            block(sessionId)
        } catch (e: org.json.JSONException) {
            // 服务端结构不合规(缺 name、content 项不是对象等):统一成稳定错误码,不让原始异常逃逸
            throw McpException(ToolErrorCodes.BAD_RESPONSE, false, "响应结构不符合 MCP 规范")
        }
    }

    private suspend fun rpc(
        serverUrl: String,
        authHeader: String?,
        method: String,
        params: JSONObject?,
        sessionId: String?,
    ): JSONObject {
        // 用本次请求自己的 id 解码:读共享计数器的"当前值"在并发请求下会错配(后台同步与工具调用重叠)
        val (id, response) = rawRequest(serverUrl, authHeader, method, params, sessionId)
        return unwrapResult(decodeRpcBody(response.body, expectedId = id), method)
    }

    private fun unwrapResult(message: JSONObject, method: String): JSONObject {
        val error = message.optJSONObject("error")
        if (error != null) {
            val code = error.optInt("code", 0)
            val msg = error.optString("message").ifBlank { "未知 JSON-RPC 错误" }.take(MAX_ERROR_CHARS)
            throw McpException(ToolErrorCodes.REMOTE_ERROR, false, "RPC $code: $msg")
        }
        return message.optJSONObject("result")
            ?: throw McpException(ToolErrorCodes.BAD_RESPONSE, false, "$method 响应缺少 result")
    }

    private suspend fun notify(serverUrl: String, authHeader: String?, method: String, sessionId: String?) {
        val body = JSONObject()
            .put("jsonrpc", "2.0")
            .put("method", method)
            .toString()
        post(serverUrl, authHeader, body, sessionId)
    }

    private suspend fun rawRequest(
        serverUrl: String,
        authHeader: String?,
        method: String,
        params: JSONObject?,
        sessionId: String?,
    ): Pair<Long, HttpResponse> {
        val id = ids.incrementAndGet()
        val body = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("method", method)
            .apply { params?.let { put("params", it) } }
            .toString()
        return id to post(serverUrl, authHeader, body, sessionId)
    }

    private suspend fun post(
        serverUrl: String,
        authHeader: String?,
        body: String,
        sessionId: String?,
    ): HttpResponse {
        val headers = buildMap {
            put("Content-Type", "application/json")
            put("Accept", "application/json, text/event-stream")
            if (!authHeader.isNullOrBlank()) put("Authorization", authHeader)
            if (!sessionId.isNullOrBlank()) put("Mcp-Session-Id", sessionId)
        }
        val response = try {
            transport.execute(
                HttpRequest(
                    url = serverUrl,
                    method = "POST",
                    headers = headers,
                    body = body,
                    timeoutMs = timeoutMs,
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw McpException(ToolErrorCodes.TIMEOUT, true, "连接超时")
        } catch (e: IOException) {
            throw McpException(ToolErrorCodes.NETWORK, true, "网络错误:${e.javaClass.simpleName}")
        } catch (e: Exception) {
            throw McpException(ToolErrorCodes.INTERNAL, false, "本地错误:${e.javaClass.simpleName}")
        }
        if (response.status !in 200..299) {
            val (code, retryable) = httpErrorCode(response.status)
            throw McpException(code, retryable, "HTTP ${response.status}")
        }
        return response
    }

    /** content[] 文本块拼接;非文本块以占位说明(不伪装成文本);无 content 时退到 structuredContent。 */
    internal fun extractText(result: JSONObject): String {
        val content = result.optJSONArray("content")
        if (content != null && content.length() > 0) {
            val parts = (0 until content.length()).map { i ->
                val block = content.getJSONObject(i)
                when (block.optString("type")) {
                    "text" -> block.optString("text")
                    else -> "[不支持的 MCP 内容类型:${block.optString("type").ifBlank { "未知" }},未包含在回答中]"
                }
            }.filter { it.isNotBlank() }
            if (parts.isNotEmpty()) return parts.joinToString("\n\n")
        }
        result.optJSONObject("structuredContent")?.let { return it.toString() }
        return "工具执行完成,无文本输出"
    }

    /**
     * 响应解码:body 为 JSON 直接解析;text/event-stream 则扫描 data 帧,
     * 返回与请求 id 匹配的 JSON-RPC 消息;通知帧(无 id)跳过。
     */
    internal fun decodeRpcBody(body: String, expectedId: Long): JSONObject {
        val trimmed = body.trim()
        if (trimmed.startsWith("{")) {
            val json = try {
                JSONObject(trimmed)
            } catch (e: Exception) {
                throw McpException(ToolErrorCodes.BAD_RESPONSE, false, "响应不是合法 JSON")
            }
            // 纯 JSON 响应同样校验 id;id 为 null 仅在 JSON-RPC 解析错误时合法(此时必带 error)
            val idMatches = when {
                !json.has("id") || json.isNull("id") -> json.has("error")
                else -> json.optLong("id", -1L) == expectedId
            }
            if (!idMatches) throw McpException(ToolErrorCodes.BAD_RESPONSE, false, "响应 id 与请求不匹配")
            return json
        }
        // SSE 帧:event 行忽略,data 行(同一 event 多行)以 \n 拼接,空行分帧
        var dataLines = mutableListOf<String>()
        fun flush(): JSONObject? {
            if (dataLines.isEmpty()) return null
            val payload = dataLines.joinToString("\n")
            dataLines = mutableListOf()
            val json = runCatching { JSONObject(payload) }.getOrNull() ?: return null
            val id = json.optLong("id", -1L)
            return if (id == expectedId) json else null
        }
        trimmed.lines().forEach { line ->
            when {
                line.isBlank() -> flush()?.let { return it }
                line.startsWith("data:") -> dataLines += line.removePrefix("data:").trim()
                else -> Unit // event:/id:/retry:/注释行忽略
            }
        }
        flush()?.let { return it }
        throw McpException(ToolErrorCodes.BAD_RESPONSE, false, "SSE 响应中没有与请求匹配的结果")
    }
}
