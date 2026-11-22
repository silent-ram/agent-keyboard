package com.agentkeyboard.mcp

import com.agentkeyboard.agent.ConfirmationResolver
import com.agentkeyboard.agent.FakeActivityLogDao
import com.agentkeyboard.agent.FakePermissionStateDao
import com.agentkeyboard.agent.permission.PermissionGate
import com.agentkeyboard.tools.AgentToolRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/** MCP 桥:同步注册 / TTL / 失败保留 / 停用清理 / 零配置零开销。 */
class McpToolBridgeTest {

    private val serverConfig = McpServerConfig(
        id = "test-abc123", name = "测试服务", url = "https://mcp.example.com/mcp",
    )

    private class Harness(
        val server: FakeMcpServer,
        servers: () -> List<McpServerConfig>,
    ) {
        val gate = PermissionGate(FakePermissionStateDao(), FakeActivityLogDao(), ConfirmationResolver { true })
        val registry = AgentToolRegistry(emptyList(), gate, FakeActivityLogDao())
        var clockValue = 0L
        val bridge = McpToolBridge(
            serversProvider = { servers() },
            authProvider = { null },
            client = McpClient(server),
            clock = { clockValue },
            syncTtlMs = 60_000L,
        )
    }

    @Test
    fun `syncInto - 注册 server 工具并暴露 schema`() = runBlocking {
        val h = Harness(FakeMcpServer(toolNames = listOf("get_time", "add"))) { listOf(serverConfig) }
        h.bridge.syncInto(h.registry)
        assertEquals(
            listOf("get_time", "add").map { McpTool.toolIdFor(serverConfig, it) },
            h.registry.schemas().map { it.id },
        )
        assertEquals(2, h.bridge.lastStatus.single().toolCount)
        assertNull(h.bridge.lastStatus.single().error)
    }

    @Test
    fun `syncInto - 未配置 server 零网络开销`() = runBlocking {
        val server = FakeMcpServer()
        val h = Harness(server) { emptyList() }
        h.bridge.syncInto(h.registry)
        assertTrue(server.requests.isEmpty())
        assertTrue(h.registry.schemas().isEmpty())
        assertTrue(h.bridge.lastStatus.isEmpty())
    }

    @Test
    fun `syncInto - TTL 内不重复出网 force 强制同步`() = runBlocking {
        val server = FakeMcpServer()
        val h = Harness(server) { listOf(serverConfig) }
        h.bridge.syncInto(h.registry)
        val afterFirst = server.requests.size
        h.clockValue += 1_000 // TTL 内
        h.bridge.syncInto(h.registry)
        assertEquals(afterFirst, server.requests.size)
        h.clockValue += 60_001 // 超过 TTL
        h.bridge.syncInto(h.registry)
        assertTrue(server.requests.size > afterFirst)
        val afterTtl = server.requests.size
        h.bridge.syncInto(h.registry, force = true)
        assertTrue(server.requests.size > afterTtl)
    }

    @Test
    fun `syncInto - 同步失败保留旧工具并记录错误`() = runBlocking {
        val server = FakeMcpServer(toolNames = listOf("get_time"))
        val h = Harness(server) { listOf(serverConfig) }
        h.bridge.syncInto(h.registry)
        assertNotNull(h.registry.get(McpTool.toolIdFor(serverConfig, "get_time")))
        server.httpStatus = 500
        h.bridge.syncInto(h.registry, force = true)
        // 失败诚实:旧工具保留(调用将得到带错误码的失败),状态行展示错误
        assertNotNull(h.registry.get(McpTool.toolIdFor(serverConfig, "get_time")))
        assertTrue(h.bridge.lastStatus.single().error!!.contains("REMOTE_ERROR"))
    }

    @Test
    fun `syncInto - 停用后工具移除`() = runBlocking {
        val server = FakeMcpServer()
        var enabled = true
        val h = Harness(server) { listOf(serverConfig.copy(enabled = enabled)) }
        h.bridge.syncInto(h.registry)
        assertEquals(1, h.registry.schemas().size)
        enabled = false
        h.bridge.syncInto(h.registry, force = true)
        assertTrue(h.registry.schemas().isEmpty())
    }

    @Test
    fun `syncInto - 多server时停用其中一个 只移除它的工具`() = runBlocking {
        // 回归:此前只按 enabled 列表重建,被停用 server 的工具残留在注册表,
        // LLM 仍可调用用户已停用的 server
        val serverA = FakeMcpServer(toolNames = listOf("a_tool"))
        val serverB = FakeMcpServer(toolNames = listOf("b_tool"))
        val configA = McpServerConfig(id = "srv-a", name = "A", url = "https://a.example.com/mcp")
        val configB = McpServerConfig(id = "srv-b", name = "B", url = "https://b.example.com/mcp")
        val gate = PermissionGate(FakePermissionStateDao(), FakeActivityLogDao(), ConfirmationResolver { true })
        val registry = AgentToolRegistry(emptyList(), gate, FakeActivityLogDao())
        var enabledB = true
        // 同一 bridge 顺序同步两个 server(fake client 复用同一 FakeMcpServer 即可,按 URL 不区分)
        val bridge = McpToolBridge(
            serversProvider = { listOf(configA, configB.copy(enabled = enabledB)) },
            authProvider = { null },
            client = McpClient(serverA),
            clock = { 0L },
            syncTtlMs = 60_000L,
        )
        bridge.syncInto(registry)
        assertTrue(registry.schemas().any { it.id.startsWith(McpTool.prefixFor(configA)) })
        assertTrue(registry.schemas().any { it.id.startsWith(McpTool.prefixFor(configB)) })
        enabledB = false
        bridge.syncInto(registry, force = true)
        // B 的工具已摘除,A 的工具保留
        assertTrue(registry.schemas().any { it.id.startsWith(McpTool.prefixFor(configA)) })
        assertTrue(registry.schemas().none { it.id.startsWith(McpTool.prefixFor(configB)) })
    }

    @Test
    fun `syncInto - 配置变化立即重新同步 不被TTL挡住`() = runBlocking {
        // 回归:此前 TTL 60s 内即使新增了 server 也不同步,用户配置的 server 迟迟不生效
        val server = FakeMcpServer(toolNames = listOf("get_time"))
        var servers = listOf(serverConfig)
        val h = Harness(server) { servers }
        h.bridge.syncInto(h.registry)
        val afterFirst = server.requests.size
        h.clockValue += 1_000 // TTL 内
        servers = listOf(serverConfig, serverConfig.copy(id = "srv-2", name = "第二个"))
        h.bridge.syncInto(h.registry)
        assertTrue(server.requests.size > afterFirst) // 配置指纹变化 → 立即同步
    }

    @Test
    fun `syncInto preserves tool parameters without exposing configured service identity`() = runBlocking {
        val h = Harness(FakeMcpServer()) { listOf(serverConfig) }
        h.bridge.syncInto(h.registry)
        val schema = h.registry.schemas().single()
        assertTrue(schema.schemaJson.contains("tz"))
        assertTrue(schema.description.startsWith("desc of "))
        assertFalse(schema.description.contains("测试服务"))
        assertFalse(schema.description.contains("mcp.example.com"))
    }
}
