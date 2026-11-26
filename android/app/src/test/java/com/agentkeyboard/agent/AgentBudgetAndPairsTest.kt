package com.agentkeyboard.agent

import com.agentkeyboard.ai.EngineClient
import com.agentkeyboard.ai.EngineMessage
import com.agentkeyboard.ai.EngineToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 批次 1:按任务分档预算 + 发请求前的 tool 配对检查 */
class AgentBudgetAndPairsTest {

    @Test
    fun `budget per task type matches table`() {
        assertEquals(0, budgetFor(TaskType.REWRITE).maxToolSteps)
        assertEquals(0, budgetFor(TaskType.TRANSLATE).maxToolSteps)
        assertEquals(2, budgetFor(TaskType.HELP_WRITE).maxToolSteps)
        assertEquals(4, budgetFor(TaskType.ASK).maxToolSteps)
        assertEquals(8, budgetFor(TaskType.KNOWLEDGE_ASK).maxToolSteps)
        assertEquals(10, budgetFor(TaskType.VERIFY).maxToolSteps)
        assertEquals(60_000L, budgetFor(TaskType.VERIFY).wallClockMs)
        TaskType.values().forEach {
            assertTrue(budgetFor(it).maxToolSteps <= AgentRuntime.MAX_STEPS_HARD_CAP)
        }
    }

    /** 按预算拆分超时:读超时 = 首 token 超时,整次请求 = 总时限,连接固定 5s */
    @Test
    fun `request timeout is split from the budget`() {
        val ask = requestTimeout(budgetFor(TaskType.ASK))
        assertEquals(java.time.Duration.ofSeconds(5), ask.connect())
        assertEquals(java.time.Duration.ofMillis(12_000), ask.read())
        assertEquals(java.time.Duration.ofMillis(12_000), ask.write())
        assertEquals(java.time.Duration.ofMillis(30_000), ask.request())
        // 各任务类型取各自的预算,不是全局常量
        assertEquals(java.time.Duration.ofMillis(8_000), requestTimeout(budgetFor(TaskType.REWRITE)).read())
        assertEquals(java.time.Duration.ofMillis(60_000), requestTimeout(budgetFor(TaskType.VERIFY)).request())
    }

    /** 整次请求超时跟着距截止的剩余时间走:迟发的请求不能越过本轮截止点 */
    @Test
    fun `request total timeout follows the remaining budget`() {
        val budget = budgetFor(TaskType.ASK) // 总时限 30s
        assertEquals(java.time.Duration.ofMillis(4_500), requestTimeout(budget, remainingMs = 4_500).request())
        assertEquals("剩余时间多于总时限时以总时限为上限", java.time.Duration.ofMillis(30_000), requestTimeout(budget, remainingMs = 90_000).request())
        assertEquals("已过截止点也要给一个正的超时", java.time.Duration.ofMillis(1), requestTimeout(budget, remainingMs = 0).request())
        assertEquals("剩余时间不影响首 token 超时", java.time.Duration.ofMillis(12_000), requestTimeout(budget, remainingMs = 4_500).read())
    }

    private fun sys() = EngineMessage(EngineClient.ROLE_SYSTEM, "sys")
    private fun user(t: String) = EngineMessage(EngineClient.ROLE_USER, t)
    private fun call(id: String) = EngineToolCall(id, "echo", "{}")
    private fun asst(text: String, vararg ids: String) =
        EngineMessage(EngineClient.ROLE_ASSISTANT, text, toolCalls = ids.map(::call))
    private fun tool(id: String) =
        EngineMessage(EngineClient.ROLE_TOOL, "r", toolCallId = id, toolName = "echo")

    @Test
    fun `complete exchange is kept intact`() {
        val input = listOf(sys(), user("q"), asst("", "a", "b"), tool("a"), tool("b"), user("q2"))
        assertEquals(input, sanitizePairs(input))
    }

    @Test
    fun `orphan tool message is removed`() {
        val out = sanitizePairs(listOf(sys(), user("q"), tool("x"), user("q2")))
        assertTrue(out.none { it.role == EngineClient.ROLE_TOOL })
        assertEquals(3, out.size)
    }

    @Test
    fun `tool reply before its call is removed`() {
        val out = sanitizePairs(listOf(sys(), tool("a"), asst("", "a"), user("q")))
        // 回复出现在声明之前 -> 回复被丢;声明的调用虽然全局有回复,但顺序非法,回复不会保留
        assertTrue(out.none { it.role == EngineClient.ROLE_TOOL })
    }

    @Test
    fun `duplicate reply keeps only the first`() {
        val out = sanitizePairs(listOf(sys(), asst("", "a"), tool("a"), tool("a")))
        assertEquals(1, out.count { it.role == EngineClient.ROLE_TOOL })
    }

    @Test
    fun `batch with a missing reply drops calls but keeps text`() {
        val out = sanitizePairs(listOf(sys(), asst("我查一下", "a", "b"), tool("a"), user("q")))
        val a = out.single { it.role == EngineClient.ROLE_ASSISTANT }
        assertEquals("我查一下", a.content)
        assertTrue(a.toolCalls.isNullOrEmpty())
        assertTrue("批次失效后其 tool 回复也不能保留", out.none { it.role == EngineClient.ROLE_TOOL })
    }

    @Test
    fun `blank assistant with incomplete batch is dropped entirely`() {
        val out = sanitizePairs(listOf(sys(), asst("", "a"), user("q")))
        assertNull(out.firstOrNull { it.role == EngineClient.ROLE_ASSISTANT })
        assertEquals(2, out.size)
    }
}
