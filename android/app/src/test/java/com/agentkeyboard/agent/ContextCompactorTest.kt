package com.agentkeyboard.agent

import com.agentkeyboard.ai.EngineMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 多轮上下文折叠(FR-B10):预算 = contextWindow × 50%;旧轮工具结果压缩为摘要行,当前轮全文 */
class ContextCompactorTest {

    @Test
    fun `token估算 - 中文1字1token 英文4字符1token`() {
        assertEquals(4, ContextCompactor.estimateTokens("四个中文"))
        assertEquals(2, ContextCompactor.estimateTokens("abcdefgh")) // 8 英文字符 → 2
    }

    @Test
    fun `折叠预算 - 8k窗口取50百分`() {
        assertEquals(4000, ContextCompactor.foldBudget(8000))
    }

    @Test
    fun `旧轮工具结果折叠为摘要行 - 当前轮全文`() {
        val oldTool = EngineMessage("tool", "very long tool result ".repeat(50), toolCallId = "c1", toolName = "web_search")
        val messages = listOf(
            EngineMessage("system", "sys"),
            EngineMessage("user", "第一轮问题"),
            EngineMessage("assistant", "", toolCalls = emptyList()),
            oldTool,
            EngineMessage("user", "第二轮问题"),
            EngineMessage("tool", "当前轮工具结果: kb_search 命中3条 chunk 原文", toolCallId = "c2", toolName = "kb_search"),
        )
        val currentTurnStart = 4
        val folded = ContextCompactor.compact(messages, budgetTokens = 4000, currentTurnStart = currentTurnStart)
        val foldedOld = folded.first { it.toolCallId == "c1" }
        val current = folded.first { it.toolCallId == "c2" }
        assertTrue(foldedOld.content.startsWith("[工具结果已折叠]"))
        assertTrue(foldedOld.content.length < oldTool.content.length)
        assertEquals("当前轮工具结果: kb_search 命中3条 chunk 原文", current.content) // 当前轮全文
        // toolCallId 配对信息保留(协议必需)
        assertEquals("c1", foldedOld.toolCallId)
    }

    @Test
    fun `超预算 - 自最旧整轮丢弃并附注记`() {
        val messages = mutableListOf(
            EngineMessage("system", "sys"),
            EngineMessage("user", "第一轮:" + "很长的旧对话内容".repeat(200)),
            EngineMessage("assistant", "旧回答:" + "内容".repeat(200)),
            EngineMessage("user", "第二轮(当前)"),
            EngineMessage("tool", "当前轮工具结果", toolCallId = "c2", toolName = "web_search"),
        )
        val folded = ContextCompactor.compact(messages, budgetTokens = 300, currentTurnStart = 3)
        assertTrue(totalTokens(folded) <= 300 || folded.none { it.role == "user" && it.content.startsWith("第一轮") })
        // 当前轮完整保留
        assertTrue(folded.any { it.content == "第二轮(当前)" })
        assertTrue(folded.any { it.content.contains("早期对话已精简") })
    }

    @Test
    fun `首条用户输入超200字压缩`() {
        val messages = listOf(
            EngineMessage("system", "sys"),
            EngineMessage("user", "第一轮问题:" + "细节".repeat(150)),
            EngineMessage("assistant", "好的"),
            EngineMessage("user", "当前轮"),
        )
        val folded = ContextCompactor.foldOldToolResults(messages, currentTurnStart = 3)
        val firstUser = folded.first { it.role == "user" }
        assertTrue(firstUser.content.length < "第一轮问题:".length + 300)
        assertTrue(firstUser.content.contains("已精简"))
    }

    @Test
    fun `单轮任务 - 无折叠`() {
        val messages = listOf(
            EngineMessage("system", "sys"),
            EngineMessage("user", "问题"),
            EngineMessage("tool", "结果", toolCallId = "c1", toolName = "web_search"),
        )
        val folded = ContextCompactor.compact(messages, budgetTokens = 4000, currentTurnStart = 1)
        assertEquals(messages, folded)
    }

    @Test
    fun `P1-1 丢弃以轮为单位 - 不产生孤儿tool或无响应toolCalls`() {
        fun toolMsg(id: String) = EngineMessage("tool", "工具结果" + id, toolCallId = id, toolName = "web_search")
        fun assistantMsg(id: String) = EngineMessage("assistant", "", toolCalls = listOf(com.agentkeyboard.ai.EngineToolCall(id, "web_search", "{}")))
        val messages = listOf(
            EngineMessage("system", "sys"),
            EngineMessage("user", "第一问 " + "内容".repeat(200)),
            assistantMsg("c1"),
            toolMsg("c1"),
            EngineMessage("user", "第二问 " + "内容".repeat(200)),
            assistantMsg("c2"),
            toolMsg("c2"),
            EngineMessage("user", "第三问(当前轮)"),
            assistantMsg("c3"),
            toolMsg("c3"),
        )
        // 预算只够 system + 最后一轮 → 前两轮必须整组丢弃
        val folded = ContextCompactor.compact(messages, budgetTokens = 200, currentTurnStart = 7)
        // 断言:每条 tool 消息其前方必存在 toolCalls 含对应 id 的 assistant(无孤儿)
        for (i in folded.indices) {
            val m = folded[i]
            if (m.role == "tool") {
                val id = m.toolCallId ?: error("tool 消息缺 toolCallId")
                val paired = folded.subList(0, i).any { prev ->
                    prev.role == "assistant" && prev.toolCalls.orEmpty().any { it.id == id }
                }
                assertTrue("孤儿 tool 消息:$id", paired)
            }
            if (m.role == "assistant" && !m.toolCalls.isNullOrEmpty()) {
                val ids = m.toolCalls!!.map { it.id }
                val responded = folded.subList(i + 1, folded.size).any { it.role == "tool" && it.toolCallId in ids }
                assertTrue("无响应的 tool_calls:$ids", responded)
            }
        }
        // 当前轮完整
        assertTrue(folded.any { it.content.startsWith("第三问") })
        assertTrue(folded.any { it.toolCallId == "c3" })
    }

    @Test
    fun `P1-1 身份保护 - 内容相同的消息不误判`() {
        val dup = EngineMessage("user", "相同内容 " + "填充".repeat(150))
        val messages = listOf(
            EngineMessage("system", "sys"),
            dup,                                   // 旧轮
            EngineMessage("assistant", "答"),
            dup,                                   // 当前轮起点(内容与旧轮完全相同)
            EngineMessage("assistant", "再答"),
        )
        // 预算超限 → 旧轮的 dup 必须可被丢弃(不被当前轮的相同内容保护);当前轮的 dup 必须保留
        val folded = ContextCompactor.dropOldest(messages, budgetTokens = 100, currentTurnStart = 3)
        assertTrue(folded.count { it.content.startsWith("相同内容") } == 1)
        assertEquals("再答", folded.last().content)
    }

    private fun totalTokens(messages: List<EngineMessage>): Int = messages.sumOf { ContextCompactor.estimateTokens(it.content) }
}
