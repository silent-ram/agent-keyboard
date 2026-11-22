package com.agentkeyboard.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** MCP 配置 JSON 与工具 id 契约(纯 JVM)。 */
class McpServerConfigTest {

    @Test
    fun `配置编解码 roundtrip`() {
        val servers = listOf(
            McpServerConfig("a-1", "服务A", "https://a.com/mcp", "ENC_VALUE", true),
            McpServerConfig("b-2", "服务B", "https://b.com/mcp", null, false),
        )
        val decoded = McpServerConfigJson.decode(McpServerConfigJson.encode(servers))
        assertEquals(servers, decoded)
    }

    @Test
    fun `解码 - 坏条目跳过 空输入空列表`() {
        assertTrue(McpServerConfigJson.decode(null).isEmpty())
        assertTrue(McpServerConfigJson.decode("").isEmpty())
        assertTrue(McpServerConfigJson.decode("not-json").isEmpty())
        val mixed = """{"servers":[{"id":"ok","name":"好","url":"https://x.com/mcp"},{"id":"","name":"坏","url":""},"junk"]}"""
        assertEquals(listOf("ok"), McpServerConfigJson.decode(mixed).map { it.id })
    }

    @Test
    fun `slug - 清洗为合法工具id前缀`() {
        assertEquals("my-server-1", McpServerConfigJson.slug("My Server 1"))
        assertEquals("server", McpServerConfigJson.slug("中文名称"))
        assertTrue(McpServerConfigJson.slug("a".repeat(100)).length <= 24)
    }

    @Test
    fun `tool ID does not contain the server or tool name`() {
        val server = McpServerConfig("s-1", "n", "https://x.com/mcp")
        val id = McpTool.toolIdFor(server, "get_time")
        assertFalse(id.contains("s-1"))
        assertFalse(id.contains("get_time"))
        assertTrue(id.length <= 64)
        assertEquals(id, McpTool.toolIdFor(server, "get_time"))
        assertFalse(id == McpTool.toolIdFor(server.copy(url = "https://y.com/mcp"), "get_time"))
    }

    @Test
    fun `toolIdFor - 非ASCII字符必须替换 不得进入LLM tool name`() {
        // 回归:此前用 isLetterOrDigit() 判定,中文等 Unicode 字母会原样进入 id,
        // 违反 LLM function name 约束 ^[a-zA-Z0-9_-]{1,64}$ → 整个请求被拒
        val server = McpServerConfig("s-1", "n", "https://x.com/mcp")
        val id = McpTool.toolIdFor(server, "查询天气")
        assertTrue(id.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' })
        assertTrue(id.startsWith(McpTool.prefixFor(server)))
    }

    @Test
    fun `toolIdFor - 超长截断后不撞名`() {
        // 回归:此前直接 take(64),同 server 下两个长名工具可能截断成同一 id 互相覆盖
        val server = McpServerConfig("s-1", "n", "https://x.com/mcp")
        val a = McpTool.toolIdFor(server, "a".repeat(80) + "X")
        val b = McpTool.toolIdFor(server, "a".repeat(80) + "Y")
        assertTrue(a.length <= 64 && b.length <= 64)
        assertTrue(a != b)
    }

    @Test
    fun `McpTool - 出网属性与审计目标`() {
        val server = McpServerConfig("s-1", "我的服务", "https://x.com/mcp")
        val descriptor = McpToolDescriptor("get_time", "d", "{}")
        val tool = McpTool(server, descriptor, McpClient(FakeMcpServer())) { null }
        assertEquals(McpTool.toolIdFor(server, "get_time"), tool.id)
        assertTrue(tool.isOutbound) // 出网属性保证走 PermissionGate
        assertEquals("外部工具", tool.targetLabel)
    }
}
