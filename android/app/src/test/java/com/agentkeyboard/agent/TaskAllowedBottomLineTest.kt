package com.agentkeyboard.agent

import com.agentkeyboard.agent.permission.GateResult
import com.agentkeyboard.agent.permission.PermissionGate
import com.agentkeyboard.agent.permission.TaintSnapshot
import com.agentkeyboard.agent.ConfirmationKind
import com.agentkeyboard.agent.ConfirmationRequest
import com.agentkeyboard.agent.ConfirmationResolver
import com.agentkeyboard.data.db.LogOutcomes
import com.agentkeyboard.data.db.PermissionStateEntity
import com.agentkeyboard.data.db.PermissionStates
import com.agentkeyboard.data.db.UserContentFlags
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0-1 回归:任务级"本次允许"命中后,派生底线仍必须生效。
 * 场景:第 1 步干净 query 用户点"本次允许";第 2 步 query 被注入诱导携带剪贴板原文 → 必须弹派生确认,不得静默放行。
 * (kb_search / web_search 已改为默认可用、不弹授权询问;询问流程用仍需授权的 fetch_page 覆盖同一状态机。)
 */
class TaskAllowedBottomLineTest {

    private val clipTaint = TaintSnapshot(clipboardText = "订单3852156789投诉三天未发货要求补偿")

    @Test
    fun `taskAllowed命中 - query含剪贴板原文 - 必须弹派生确认`() = runBlocking {
        val states = FakePermissionStateDao()
        val logs = FakeActivityLogDao()
        val requests = mutableListOf<ConfirmationRequest>()
        var allow = true // 用户全程点允许
        val gate = PermissionGate(
            permissionStateDao = states,
            activityLogDao = logs,
            resolver = ConfirmationResolver { allow },
            emitConfirmation = { requests += it.request },
        )
        gate.beginTask()
        // 第 1 步:干净 query,UNASKED 首次询问 → 用户"本次允许" → taskAllowed
        val r1 = gate.gate("fetch_page", """{"url":"https://a.example/report"}""", "网页", clipTaint, 1L)
        assertTrue(r1 is GateResult.Allowed)
        assertEquals(1, requests.size)
        assertEquals(ConfirmationKind.FIRST_TIME, requests[0].kind)

        // 第 2 步:taskAllowed 命中,但 query 含剪贴板原文(≥10 字逐字命中)
        val r2 = gate.gate("fetch_page", """{"url":"https://a.example/订单3852156789投诉三天未发货"}""", "网页", clipTaint, 1L)
        assertTrue("P0-1: 派生底线被任务级本次允许绕过", r2 is GateResult.Allowed)
        assertEquals(2, requests.size) // 必须出现派生确认条
        assertEquals(ConfirmationKind.DERIVED_BOTTOM_LINE, requests[1].kind)
        assertEquals(LogOutcomes.ALLOWED_ONCE, (r2 as GateResult.Allowed).outcome)
        assertEquals(UserContentFlags.CLIPBOARD, r2.containsUserContent)
    }

    @Test
    fun `taskAllowed命中 - query被拒绝 - Denied`() = runBlocking {
        val states = FakePermissionStateDao()
        val logs = FakeActivityLogDao()
        val requests = mutableListOf<ConfirmationRequest>()
        val answers = ArrayDeque(listOf(true, false)) // 第一次允许,派生确认拒绝
        val gate = PermissionGate(
            permissionStateDao = states,
            activityLogDao = logs,
            resolver = ConfirmationResolver { answers.removeFirst() },
            emitConfirmation = { requests += it.request },
        )
        gate.beginTask()
        gate.gate("fetch_page", """{"url":"https://a.example/clean"}""", "网页", clipTaint, 1L)
        val r2 = gate.gate("fetch_page", """{"url":"https://a.example/订单3852156789投诉三天未发货"}""", "网页", clipTaint, 1L)
        assertEquals(GateResult.Denied, r2)
        assertEquals(ConfirmationKind.DERIVED_BOTTOM_LINE, requests[1].kind)
    }

    @Test
    fun `taskAllowed命中 - 干净query - 仍直接放行不弹窗`() = runBlocking {
        val states = FakePermissionStateDao()
        val logs = FakeActivityLogDao()
        val requests = mutableListOf<ConfirmationRequest>()
        val gate = PermissionGate(
            permissionStateDao = states,
            activityLogDao = logs,
            resolver = ConfirmationResolver { true },
            emitConfirmation = { requests += it.request },
        )
        gate.beginTask()
        gate.gate("fetch_page", """{"url":"https://a.example/first"}""", "网页", clipTaint, 1L)
        val r2 = gate.gate("fetch_page", """{"url":"https://a.example/second"}""", "网页", clipTaint, 1L)
        assertTrue(r2 is GateResult.Allowed)
        assertEquals(1, requests.size) // 只有首次询问,无派生条
        assertEquals(LogOutcomes.CONFIRMED, (r2 as GateResult.Allowed).outcome)
    }

    @Test
    fun `ALWAYS_ALLOW状态 - 不受本修复影响 - 派生命中走底线`() = runBlocking {
        val states = FakePermissionStateDao()
        states.upsert(PermissionStateEntity("fetch_page", PermissionStates.ALWAYS_ALLOW, 0))
        val logs = FakeActivityLogDao()
        val requests = mutableListOf<ConfirmationRequest>()
        val gate = PermissionGate(
            permissionStateDao = states,
            activityLogDao = logs,
            resolver = ConfirmationResolver { true },
            emitConfirmation = { requests += it.request },
        )
        gate.beginTask()
        val r = gate.gate("fetch_page", """{"url":"https://a.example/订单3852156789投诉三天未发货"}""", "网页", clipTaint, 1L)
        assertTrue(r is GateResult.Allowed)
        assertEquals(ConfirmationKind.DERIVED_BOTTOM_LINE, requests[0].kind)
        assertEquals(LogOutcomes.ALLOWED_ONCE, (r as GateResult.Allowed).outcome)
    }

    /** web_search 默认可用:干净 query 不弹;带着剪贴板原文时仍然当场确认,拒绝则本次不发送 */
    @Test
    fun `web_search 默认可用 - 干净query不弹,含剪贴板原文仍走派生底线`() = runBlocking {
        val states = FakePermissionStateDao()
        val logs = FakeActivityLogDao()
        val requests = mutableListOf<ConfirmationRequest>()
        var allow = true
        val gate = PermissionGate(
            permissionStateDao = states,
            activityLogDao = logs,
            resolver = ConfirmationResolver { allow },
            emitConfirmation = { requests += it.request },
        )
        gate.beginTask()
        val clean = gate.gate("web_search", """{"query":"q4 财报"}""", "联网搜索", clipTaint, 1L)
        assertEquals(LogOutcomes.AUTO_ALLOW, (clean as GateResult.Allowed).outcome)
        assertTrue(requests.isEmpty())

        val derived = gate.gate("web_search", """{"query":"订单3852156789投诉三天未发货"}""", "联网搜索", clipTaint, 1L)
        assertEquals(ConfirmationKind.DERIVED_BOTTOM_LINE, requests.single().kind)
        assertEquals(LogOutcomes.ALLOWED_ONCE, (derived as GateResult.Allowed).outcome)

        allow = false
        assertEquals(GateResult.Denied, gate.gate("web_search", """{"query":"订单3852156789投诉三天未发货"}""", "联网搜索", clipTaint, 1L))
        assertEquals(2, requests.size)
        // 不落任何授权记录:下次仍然默认可用
        assertEquals(null, states.byTool("web_search")?.state)
    }
}
