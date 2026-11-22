package com.agentkeyboard.mcp

import com.agentkeyboard.tools.Tool
import com.agentkeyboard.tools.ToolResult
import java.security.MessageDigest

/**
 * MCP 工具适配(G12):把远端 server 的一个工具映射为现有 [Tool] 契约。
 * isOutbound=true → 自动继承 PermissionGate 首次询问/逐次询问/总是允许/派生底线与活动审计,
 * 桥接层不另开授权通道;结果经 ToolResultWrapper 包裹,由 Runtime 统一过 InjectionScanner。
 */
class McpTool(
    private val server: McpServerConfig,
    private val descriptor: McpToolDescriptor,
    private val client: McpClient,
    private val authProvider: suspend () -> String?,
) : Tool {

    override val id: String = toolIdFor(server, descriptor.name)
    override val name: String = "外部工具"
    override val isOutbound: Boolean = true
    override val targetLabel: String = "外部工具"

    override val description: String =
        descriptor.description.ifBlank { "External tool ${descriptor.name}" }.take(1000)

    /** inputSchema 原样透传(server 声明即契约);非法 JSON 回退空 object schema。 */
    override val schemaJson: String = runCatching {
        org.json.JSONObject(descriptor.inputSchemaJson).toString()
    }.getOrDefault("""{"type":"object","properties":{}}""")

    override suspend fun execute(argsJson: String): ToolResult =
        client.callTool(server.url, authProvider(), descriptor.name, argsJson)

    companion object {
        const val ID_PREFIX = "mcp_"

        fun prefixFor(server: McpServerConfig): String =
            "$ID_PREFIX${fingerprint(server.id + "\u0000" + server.url)}__"

        fun toolIdFor(server: McpServerConfig, toolName: String): String =
            prefixFor(server) + fingerprint(toolName)

        private fun fingerprint(value: String): String =
            MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray())
                .take(8).joinToString("") { "%02x".format(it) }
    }
}
