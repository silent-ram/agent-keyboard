package com.agentkeyboard.agent

import com.agentkeyboard.agent.permission.GateResult
import com.agentkeyboard.agent.permission.LogSanitizer
import com.agentkeyboard.agent.permission.PermissionGate
import com.agentkeyboard.agent.permission.TaintSnapshot
import com.agentkeyboard.agent.permission.UserContentSource
import com.agentkeyboard.agent.ConfirmationKind
import com.agentkeyboard.agent.ConfirmationRequest
import com.agentkeyboard.agent.ConfirmationResolver
import com.agentkeyboard.data.db.PermissionStates
import com.agentkeyboard.data.db.UserContentFlags
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * 权限门控状态机(11-权限设计 §3/§4/§5,FR-E1/E2/E6):
 * UNASKED→本次允许→ASK_EVERY_TIME;→总是允许→ALWAYS_ALLOW;底线不可豁免;llm_call 首次明示。
 * kb_search / web_search 默认可用,不弹授权询问(见 PermissionGate.DEFAULT_ALLOWED_TOOLS)。
 */
class PermissionGateTest {
    @Test
    fun `changing the model endpoint requires new consent`() = runBlocking {
        val requests = mutableListOf<ConfirmationRequest>()
        var allow = true
        val gate = PermissionGate(FakePermissionStateDao(), FakeActivityLogDao(), ConfirmationResolver {
            requests += it
            allow
        })
        assertTrue(gate.ensureLlmDisclosed(1, endpoint = "https://first.example/v1"))
        assertTrue(gate.ensureLlmDisclosed(1, endpoint = "https://first.example/v1/"))
        allow = false
        assertFalse(gate.ensureLlmDisclosed(1, endpoint = "https://second.example/v1"))
        assertEquals(2, requests.size)
        assertTrue(requests.all { it.kind == ConfirmationKind.LLM_DISCLOSURE })
    }

    @Test
    fun `kb and web search run without confirmation whatever the stored permission is`() = runBlocking {
        val states = FakePermissionStateDao()
        val requests = mutableListOf<ConfirmationRequest>()
        val logs = FakeActivityLogDao()
        val gate = PermissionGate(states, logs, ConfirmationResolver {
            requests += it
            true
        })
        // 以前点过「本次允许/拒绝」留下的 ASK_EVERY_TIME 记录也不再触发询问
        states.upsert(com.agentkeyboard.data.db.PermissionStateEntity("kb_search", PermissionStates.ASK_EVERY_TIME, 0))
        states.upsert(com.agentkeyboard.data.db.PermissionStateEntity("web_search", PermissionStates.ASK_EVERY_TIME, 0))
        listOf("kb_search", "web_search").forEach { tool ->
            gate.beginTask() // 每次都当新任务:旧的"每个任务问一次"也不再生效
            repeat(2) {
                val r = gate.gate(tool, "sample question", "", TaintSnapshot.EMPTY, 1)
                assertTrue(r is GateResult.Allowed)
                assertEquals(com.agentkeyboard.data.db.LogOutcomes.AUTO_ALLOW, (r as GateResult.Allowed).outcome)
            }
        }
        assertTrue("默认可用:不弹任何确认条", requests.isEmpty())
        // 每次调用仍逐条记录活动日志
        assertEquals(4, logs.entries.size)
        assertTrue(logs.entries.all { it.preview.isEmpty() })
    }

    @Test
    fun `kb search still asks on the spot when the query carries the user's own text`() = runBlocking {
        val requests = mutableListOf<ConfirmationRequest>()
        val g = gate(ConfirmationResolver { true }, confirmations = requests)
        val r = g.gate("kb_search", "订单3852156789投诉 怎么处理", "", clipTaint, 1L)
        assertTrue(r is GateResult.Allowed)
        assertEquals(ConfirmationKind.DERIVED_BOTTOM_LINE, requests.single().kind)
        assertFalse("派生确认不能记成总是允许", requests.single().canRemember)
        assertEquals(UserContentFlags.CLIPBOARD, (r as GateResult.Allowed).containsUserContent)
    }

    /** 运行时据此决定调用能否与同批调用并发:与 gate() 是否弹条同口径,只读、不落日志 */
    @Test
    fun `mayPrompt mirrors whether gate would ask`() = runBlocking {
        val states = FakePermissionStateDao()
        val logs = FakeActivityLogDao()
        val gate = gate(ConfirmationResolver { true }, states = states, logs = logs)
        // 默认可用的检索与搜索:载荷不含用户原文就不弹
        assertFalse(gate.mayPrompt("kb_search", "内部流程", TaintSnapshot.EMPTY))
        assertFalse(gate.mayPrompt("web_search", "public topic", TaintSnapshot.EMPTY))
        // 派生底线逐次判定、不可豁免
        assertTrue(gate.mayPrompt("kb_search", "订单3852156789投诉 怎么处理", clipTaint))
        // 还没授权过的出网工具会弹确认条
        assertTrue(gate.mayPrompt("fetch_page", "https://a.example/", TaintSnapshot.EMPTY))
        states.upsert(com.agentkeyboard.data.db.PermissionStateEntity("fetch_page", PermissionStates.ALWAYS_ALLOW, 0))
        assertFalse("总是允许后只剩派生判定", gate.mayPrompt("fetch_page", "https://a.example/", TaintSnapshot.EMPTY))

        // 任务内点过「本次允许」的同工具不再弹
        states.upsert(com.agentkeyboard.data.db.PermissionStateEntity("mcp_x", PermissionStates.ASK_EVERY_TIME, 0))
        assertTrue(gate.mayPrompt("mcp_x", "args", TaintSnapshot.EMPTY))
        gate.beginTask()
        assertTrue(gate.gate("mcp_x", "args", "", TaintSnapshot.EMPTY, 1L) is GateResult.Allowed)
        assertFalse(gate.mayPrompt("mcp_x", "args", TaintSnapshot.EMPTY))

        // mayPrompt 只读:只有 gate() 那一次调用留下日志
        assertEquals(1, logs.entries.size)
    }


    private fun gate(
        resolver: ConfirmationResolver,
        states: FakePermissionStateDao = FakePermissionStateDao(),
        logs: FakeActivityLogDao = FakeActivityLogDao(),
        confirmations: MutableList<ConfirmationRequest> = mutableListOf(),
        disclosureTitle: String = "",
        disclosureBody: String = "",
    ): PermissionGate = PermissionGate(
        permissionStateDao = states,
        activityLogDao = logs,
        resolver = resolver,
        emitConfirmation = { confirmations += it.request },
        clock = { 1_000L },
        llmDisclosureTitle = disclosureTitle,
        llmDisclosureBody = disclosureBody,
    )

    private val clipTaint = TaintSnapshot(clipboardText = "订单3852156789投诉三天未发货", contextText = null)

    @Test
    fun `confirmation hides endpoint and query and first derived request cannot be remembered`() = runBlocking {
        val logs = FakeActivityLogDao()
        val requests = mutableListOf<ConfirmationRequest>()
        val gate = gate(ConfirmationResolver { true }, confirmations = requests, logs = logs)
        gate.gate("kb_search", "内部资料片段", "service.example", TaintSnapshot(profileEntries = listOf("内部资料片段")), 1L)
        val request = requests.single()
        assertEquals(ConfirmationKind.DERIVED_BOTTOM_LINE, request.kind)
        assertFalse(request.canRemember)
        assertTrue(request.paramsPreview.isEmpty())
        assertFalse(request.toString().contains("service.example"))
        assertFalse(request.toString().contains("内部资料片段"))
        assertEquals("资料检索", logs.entries.single().target)
    }

    // 以下授权状态机用例用 fetch_page:web_search 已默认可用,不再走询问流程

    @Test
    fun `UNASKED 首次确认 允许 - 落 ASK_EVERY_TIME 并放行`() = runBlocking {
        val states = FakePermissionStateDao()
        var decision = true
        val g = gate(ConfirmationResolver { decision }, states = states)
        val r = g.gate("fetch_page", """{"url":"https://a.example/4"}""", "网页", TaintSnapshot.EMPTY, sessionId = 1L)
        assertTrue(r is GateResult.Allowed)
        assertEquals(PermissionStates.ASK_EVERY_TIME, states.byTool("fetch_page")?.state)
    }

    @Test
    fun `UNASKED 首次确认 拒绝 - 也落 ASK_EVERY_TIME 且 Denied`() = runBlocking {
        val states = FakePermissionStateDao()
        val g = gate(ConfirmationResolver { false }, states = states)
        val r = g.gate("fetch_page", """{"url":"https://a.example/"}""", "网页", TaintSnapshot.EMPTY, 1L)
        assertEquals(GateResult.Denied, r)
        assertEquals(PermissionStates.ASK_EVERY_TIME, states.byTool("fetch_page")?.state)
    }

    @Test
    fun `总是允许记忆 - 后续无原文直接放行且不弹确认`() = runBlocking {
        val states = FakePermissionStateDao()
        val asked = AtomicInteger()
        val g = gate(ConfirmationResolver {
            asked.incrementAndGet(); true
        }, states = states)
        // 第一次:首次询问
        g.gate("fetch_page", """{"url":"https://a.example/"}""", "网页", TaintSnapshot.EMPTY, 1L)
        assertEquals(1, asked.get())
        // 记忆「总是允许」(UI 组合动作)
        g.setAlwaysAllow("fetch_page")
        g.beginTask() // 新任务:任务级"本次允许"缓存失效,走 ALWAYS_ALLOW 分支
        val r = g.gate("fetch_page", """{"url":"https://b.example/"}""", "网页", TaintSnapshot.EMPTY, 1L)
        assertTrue(r is GateResult.Allowed)
        assertEquals(GateResult.Allowed::class.simpleName, (r as GateResult.Allowed)::class.simpleName)
        assertEquals(com.agentkeyboard.data.db.LogOutcomes.AUTO_ALLOW, r.outcome)
        assertEquals(1, asked.get()) // 不再弹
    }

    @Test
    fun `任务级本次允许 - 同任务内同工具不再弹`() = runBlocking {
        val asked = AtomicInteger()
        val g = gate(ConfirmationResolver { asked.incrementAndGet(); true })
        g.beginTask()
        g.gate("fetch_page", """{"url":"https://a.example/"}""", "网页", TaintSnapshot.EMPTY, 1L)
        g.gate("fetch_page", """{"url":"https://b.example/"}""", "网页", TaintSnapshot.EMPTY, 1L)
        assertEquals(1, asked.get())
    }

    @Test
    fun `底线不可豁免 - ALWAYS_ALLOW 含原文仍要当场确认`() = runBlocking {
        val states = FakePermissionStateDao()
        states.upsert(com.agentkeyboard.data.db.PermissionStateEntity("web_search", PermissionStates.ALWAYS_ALLOW, 0))
        var allowed = true
        val g = gate(ConfirmationResolver { allowed }, states = states)
        val r = g.gate("web_search", """{"query":"订单3852156789投诉"}""", "搜索服务", clipTaint, 1L)
        // 允许一次 → 放行,但 outcome=ALLOWED_ONCE 且持久状态不变
        assertTrue(r is GateResult.Allowed)
        assertEquals(com.agentkeyboard.data.db.LogOutcomes.ALLOWED_ONCE, (r as GateResult.Allowed).outcome)
        assertEquals(UserContentFlags.CLIPBOARD, r.containsUserContent)
        assertEquals(PermissionStates.ALWAYS_ALLOW, states.byTool("web_search")?.state)
    }

    @Test
    fun `底线不可豁免 - 派生确认请求没有总是允许键`() = runBlocking {
        val states = FakePermissionStateDao()
        states.upsert(com.agentkeyboard.data.db.PermissionStateEntity("web_search", PermissionStates.ALWAYS_ALLOW, 0))
        val requests = mutableListOf<ConfirmationRequest>()
        val g = gate(ConfirmationResolver { true }, states = states, confirmations = requests)
        g.gate("web_search", """{"query":"订单3852156789投诉"}""", "搜索服务", clipTaint, 1L)
        assertEquals(1, requests.size)
        assertEquals(ConfirmationKind.DERIVED_BOTTOM_LINE, requests[0].kind)
        assertFalse(requests[0].canRemember)
    }

    @Test
    fun `派生确认拒绝 - Denied 且状态不变`() = runBlocking {
        val states = FakePermissionStateDao()
        states.upsert(com.agentkeyboard.data.db.PermissionStateEntity("web_search", PermissionStates.ALWAYS_ALLOW, 0))
        val g = gate(ConfirmationResolver { false }, states = states)
        val r = g.gate("web_search", """{"query":"订单3852156789投诉"}""", "搜索服务", clipTaint, 1L)
        assertEquals(GateResult.Denied, r)
        assertEquals(PermissionStates.ALWAYS_ALLOW, states.byTool("web_search")?.state)
    }

    @Test
    fun `llm_call 首次明示 - 同意后记忆不再问`() = runBlocking {
        val states = FakePermissionStateDao()
        val logs = FakeActivityLogDao()
        val confirmations = mutableListOf<ConfirmationRequest>()
        val asked = AtomicInteger()
        val g = gate(
            ConfirmationResolver { asked.incrementAndGet(); true },
            states = states,
            logs = logs,
            confirmations = confirmations,
            disclosureTitle = "AI 首次明示",
            disclosureBody = "提交的问题和命中的相关文档片段会发送;较短文档可能会整篇发送。",
        )
        assertTrue(g.ensureLlmDisclosed(1L))
        assertEquals(PermissionStates.ALWAYS_ALLOW, states.byTool(com.agentkeyboard.agent.permission.TOOL_LLM_CALL)?.state)
        assertEquals(ConfirmationKind.LLM_DISCLOSURE, confirmations.single().kind)
        assertEquals("AI 首次明示", confirmations.single().title)
        assertTrue(confirmations.single().body.contains("命中的相关文档片段"))
        assertTrue(confirmations.single().body.contains("较短文档可能会整篇发送"))
        assertTrue(g.ensureLlmDisclosed(1L)) // 第二次直接放行
        assertEquals(1, asked.get())
        assertEquals(2, logs.entries.size)
        assertEquals(com.agentkeyboard.data.db.LogOutcomes.AUTO_ALLOW, logs.entries.last().outcome)
    }

    @Test
    fun `llm_call 拒绝 - 不放行且不落 ALWAYS_ALLOW`() = runBlocking {
        val states = FakePermissionStateDao()
        val g = gate(ConfirmationResolver { false }, states = states)
        assertFalse(g.ensureLlmDisclosed(1L))
        assertEquals(null, states.byTool(com.agentkeyboard.agent.permission.TOOL_LLM_CALL)?.state)
    }

    @Test
    fun `old llm consent does not cover sending a saved profile`() = runBlocking {
        val confirmations = mutableListOf<ConfirmationRequest>()
        val logs = FakeActivityLogDao()
        var approve = false
        val g = gate(ConfirmationResolver { approve }, logs = logs, confirmations = confirmations)
        g.setAlwaysAllow("llm_call")
        assertTrue(g.ensureLlmDisclosed(1L))
        assertFalse(g.ensureLlmDisclosed(1L, includesProfile = true))
        approve = true
        assertTrue(g.ensureLlmDisclosed(1L, includesProfile = true))
        assertTrue(g.ensureLlmDisclosed(1L, includesProfile = true))
        assertEquals(2, confirmations.size)
        assertTrue(confirmations.all { it.paramsPreview.isEmpty() })
        assertTrue(logs.entries.all { it.preview.isEmpty() })
    }

    @Test
    fun `saved profile in a tool payload needs confirmation even when always allowed`() = runBlocking {
        val confirmations = mutableListOf<ConfirmationRequest>()
        val g = gate(ConfirmationResolver { false }, confirmations = confirmations)
        g.setAlwaysAllow("web_search")
        val taint = TaintSnapshot(profileEntries = listOf("内部项目甲"))
        assertEquals(GateResult.Denied, g.gate("web_search", "内部项目甲 查询", "搜索服务", taint, 1L))
        assertEquals(ConfirmationKind.DERIVED_BOTTOM_LINE, confirmations.single().kind)
        assertFalse(confirmations.single().canRemember)
    }

    @Test
    fun `activity_log 脱敏 - 手机号打码且剪贴板来源只存20字`() = runBlocking {
        val logs = FakeActivityLogDao()
        val states = FakePermissionStateDao()
        states.upsert(com.agentkeyboard.data.db.PermissionStateEntity("web_search", PermissionStates.ALWAYS_ALLOW, 0))
        val taint = TaintSnapshot(clipboardText = "13812345678 这是一个超过二十字的剪贴板内容用来验证截断行为")
        val g = gate(ConfirmationResolver { true }, states = states, logs = logs)
        g.gate("web_search", """{"query":"13812345678 号主是谁"}""", "搜索服务", taint, 1L)
        val entry = logs.entries.last()
        assertFalse(entry.preview.contains("13812345678"))
        assertEquals(UserContentFlags.CLIPBOARD, entry.containsUserContent)
        // 规则 A 命中(参数含剪贴板 ≥10 字片段)→ ALLOWED_ONCE
        assertEquals(com.agentkeyboard.data.db.LogOutcomes.ALLOWED_ONCE, entry.outcome)
    }

    @Test
    fun `设置页重置 - 回 UNASKED 重新走首次询问`() = runBlocking {
        val states = FakePermissionStateDao()
        val asked = AtomicInteger()
        val g = gate(ConfirmationResolver { asked.incrementAndGet(); true }, states = states)
        g.setAlwaysAllow("fetch_page")
        g.reset("fetch_page")
        assertEquals(PermissionStates.UNASKED, states.byTool("fetch_page")?.state)
        g.gate("fetch_page", """{"url":"https://a.example/x"}""", "网页", TaintSnapshot.EMPTY, 1L)
        assertEquals(1, asked.get())
    }

    @Test
    fun `重置授权 - kb 与 web 仍然默认可用`() = runBlocking {
        val states = FakePermissionStateDao()
        val asked = AtomicInteger()
        val g = gate(ConfirmationResolver { asked.incrementAndGet(); true }, states = states)
        g.reset("web_search")
        g.reset("kb_search")
        assertTrue(g.gate("web_search", "query", "搜索服务", TaintSnapshot.EMPTY, 1L) is GateResult.Allowed)
        assertTrue(g.gate("kb_search", "query", "资料检索", TaintSnapshot.EMPTY, 1L) is GateResult.Allowed)
        assertEquals(0, asked.get())
    }

    @Test
    fun `脱敏器 - 邮箱与手机号同时存在`() {
        val out = LogSanitizer.mask("邮箱 zhangsan@example.com 手机 13812345678")
        assertFalse(out.contains("zhangsan@example.com"))
        assertFalse(out.contains("13812345678"))
        assertTrue(out.contains("@example.com"))
    }

    @Test
    fun `P1-2 pendingConfirmation - 未collect事件流也能取到请求`() = runBlocking {
        val states = FakePermissionStateDao()
        val hold = kotlinx.coroutines.CompletableDeferred<Boolean>()
        val gate = PermissionGate(
            permissionStateDao = states,
            activityLogDao = FakeActivityLogDao(),
            resolver = ConfirmationResolver { hold.await() },
            emitConfirmation = { }, // 故意不收集:模拟面板未订阅 events
        )
        val job = launch {
            gate.gate("fetch_page", """{"url":"https://a.example/"}""", "网页", TaintSnapshot.EMPTY, 1L)
        }
        delay(200)
        // 不订阅 events:直接读单槽 StateFlow 可恢复确认条
        val pending = gate.pendingConfirmation.value
        assertTrue("pendingConfirmation 应有值", pending != null)
        assertEquals(ConfirmationKind.FIRST_TIME, pending?.kind)
        hold.complete(true)
        delay(200)
        // 用户决定后清空
        assertEquals(null, gate.pendingConfirmation.value)
        job.cancel()
    }

    @Test
    fun `确认条组合契约 - 总是允许先落盘再放行(M1-T4 面板resolver语义)`() = runBlocking {
        val states = FakePermissionStateDao()
        val logs = FakeActivityLogDao()
        // 面板 resolver 组合:点「总是允许」→ 先 setAlwaysAllow 落盘 → 再 resolve(true)
        lateinit var gateRef: PermissionGate
        val gate = PermissionGate(
            permissionStateDao = states,
            activityLogDao = logs,
            resolver = ConfirmationResolver { request ->
                gateRef.setAlwaysAllow(request.toolId)
                true
            },
            emitConfirmation = { },
        )
        gateRef = gate
        val r1 = gate.gate("fetch_page", """{"url":"https://a.example/"}""", "网页", TaintSnapshot.EMPTY, 1L)
        assertTrue(r1 is GateResult.Allowed)
        // resolver 已落盘 ALWAYS_ALLOW → gate 不降级为 ASK_EVERY_TIME
        assertEquals(PermissionStates.ALWAYS_ALLOW, states.byTool("fetch_page")?.state)
        // 新任务:直接走 ALWAYS_ALLOW 自动放行(不再弹确认)
        gate.beginTask()
        val r2 = gate.gate("fetch_page", """{"url":"https://b.example/"}""", "网页", TaintSnapshot.EMPTY, 2L)
        assertTrue(r2 is GateResult.Allowed)
        assertEquals(com.agentkeyboard.data.db.LogOutcomes.AUTO_ALLOW, (r2 as GateResult.Allowed).outcome)
    }

    @Test
    fun `fetch_page - 显式注册时仍经权限门控`() = runBlocking {
        val tool = com.agentkeyboard.tools.FetchPageTool(transport = object : com.agentkeyboard.tools.HttpTransport {
            override suspend fun execute(request: com.agentkeyboard.tools.HttpRequest) =
                com.agentkeyboard.tools.HttpResponse(200, "body")
        })
        var confirmationCount = 0
        val gate = PermissionGate(FakePermissionStateDao(), FakeActivityLogDao(), ConfirmationResolver {
            confirmationCount++
            false
        })
        val registry = com.agentkeyboard.tools.AgentToolRegistry(listOf(tool), gate, FakeActivityLogDao())
        val result = registry.execute(
            com.agentkeyboard.tools.ToolCall("c1", "fetch_page", """{"url":"https://a.com"}"""),
            com.agentkeyboard.agent.permission.TaintSnapshot.EMPTY,
            sessionId = 1L,
        )
        assertFalse(result.ok)
        assertEquals(com.agentkeyboard.tools.TOOL_USER_DENIED, result.raw)
        assertEquals(1, confirmationCount)
    }

    private fun gateRef() = PermissionGate(FakePermissionStateDao(), FakeActivityLogDao(), ConfirmationResolver { true })
}
