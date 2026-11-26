package com.agentkeyboard.agent

import com.agentkeyboard.ai.EngineMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 多轮上下文折叠(FR-B10):预算 = contextWindow × 70%;预算内不折叠,超预算时逐级收紧,当前轮全文 */
class ContextCompactorTest {

    @Test
    fun `token估算 - 中文1字1token 英文4字符1token`() {
        assertEquals(4, ContextCompactor.estimateTokens("四个中文"))
        assertEquals(2, ContextCompactor.estimateTokens("abcdefgh")) // 8 英文字符 → 2
    }

    @Test
    fun `折叠预算 - 窗口的70百分 下限256`() {
        assertEquals(5600, ContextCompactor.foldBudget(8000))
        assertEquals(140_000, ContextCompactor.foldBudget(200_000))
        assertEquals(256, ContextCompactor.foldBudget(100))
    }

    private fun oldTurnWithTools() = listOf(
        EngineMessage("system", "sys"),
        EngineMessage("user", "第一轮问题"),
        EngineMessage("assistant", "", toolCalls = emptyList()),
        EngineMessage("tool", "very long tool result ".repeat(50), toolCallId = "c1", toolName = "web_search"),
        EngineMessage("user", "第二轮问题"),
        EngineMessage("tool", "当前轮工具结果: kb_search 命中3条 chunk 原文", toolCallId = "c2", toolName = "kb_search"),
    )

    @Test
    fun `预算内不折叠 - 原样发送`() {
        val messages = oldTurnWithTools()
        assertEquals(messages, ContextCompactor.compact(messages, budgetTokens = 4000, currentTurnStart = 4))
    }

    @Test
    fun `超预算 - 旧轮工具结果折叠为摘要行 - 当前轮全文`() {
        val messages = oldTurnWithTools()
        val oldTool = messages[3]
        val folded = ContextCompactor.compact(messages, budgetTokens = 200, currentTurnStart = 4)
        val foldedOld = folded.first { it.toolCallId == "c1" }
        val current = folded.first { it.toolCallId == "c2" }
        assertTrue(foldedOld.content.startsWith("[工具结果已折叠]"))
        assertTrue(foldedOld.content.length < oldTool.content.length)
        assertEquals("当前轮工具结果: kb_search 命中3条 chunk 原文", current.content) // 当前轮全文
        // toolCallId 配对信息保留(协议必需)
        assertEquals("c1", foldedOld.toolCallId)
        // 预算内即停:只有工具结果被折叠,旧轮的问句原样保留
        assertEquals("第一轮问题", folded.first { it.role == "user" && it.content.startsWith("第一轮") }.content)
    }

    /** 短结果加前缀与包裹反而更长:折叠只增不减,这种结果原样保留 */
    @Test
    fun `折叠不增量 - 短工具结果加摘要前缀后反而更长时保持原文`() {
        val short = EngineMessage("tool", "工具结果c1", toolCallId = "c1", toolName = "web_search")
        val out = ContextCompactor.foldOldToolResults(listOf(EngineMessage("system", "sys"), short), currentTurnStart = 2)
        assertEquals(short, out[1])
    }

    /**
     * 方案 3.2:每一级折叠都不能破坏 assistant.toolCalls 与 tool 回复的配对——
     * 孤儿的 tool 消息或没有回复的 tool_calls 会被 OpenAI 兼容 API 以 400 拒绝。
     */
    @Test
    fun compact_keepsToolCallIdsWhenFolding() {
        fun call(id: String) = com.agentkeyboard.ai.EngineToolCall(id, "web_search", "{}")
        fun tool(id: String) = EngineMessage("tool", "very long tool result ".repeat(50), toolCallId = id, toolName = "web_search")
        val messages = listOf(
            EngineMessage("system", "sys"),
            EngineMessage("user", "第一轮问题"),
            EngineMessage("assistant", "", toolCalls = listOf(call("c1"))),
            tool("c1"),
            EngineMessage("user", "第二轮问题"),
            EngineMessage("assistant", "第二轮回答"),
            EngineMessage("assistant", "", toolCalls = listOf(call("c2"))),
            tool("c2"),
            EngineMessage("user", "当前问题"),
            EngineMessage("assistant", "", toolCalls = listOf(call("c3"))),
            tool("c3"),
            EngineMessage("assistant", "当前回答"),
        )
        // 800 = 只折叠旧轮工具结果;350 = 旧轮再整轮丢弃;200 = 当前轮也超预算
        for (budget in listOf(800, 350, 200)) {
            val out = sanitizePairs(ContextCompactor.compact(messages, budgetTokens = budget, currentTurnStart = 8))
            for (i in out.indices) {
                val m = out[i]
                if (m.role == "tool") {
                    val id = m.toolCallId ?: error("tool 消息缺 toolCallId")
                    assertTrue("孤儿 tool 消息:$id(预算 $budget)", out.subList(0, i).any { prev -> prev.toolCalls.orEmpty().any { it.id == id } })
                }
                m.toolCalls?.forEach { declared ->
                    assertTrue("没有回复的 tool_call:${declared.id}(预算 $budget)", out.subList(i + 1, out.size).any { it.role == "tool" && it.toolCallId == declared.id })
                }
            }
            // 当前轮从不丢弃
            assertTrue(out.any { it.content == "当前问题" })
            assertTrue(out.any { it.toolCallId == "c3" })
        }
        // 800:两轮旧工具结果都折成摘要,toolCallId 不变
        val folded = ContextCompactor.compact(messages, budgetTokens = 800, currentTurnStart = 8)
        assertEquals(setOf("c1", "c2"), folded.filter { it.content.startsWith("[工具结果已折叠]") }.mapNotNull { it.toolCallId }.toSet())
        // 350:更早的一轮放不下被整轮丢弃(没有回答可留),后一轮只留问答;只剩当前轮的 c3
        val dropped = ContextCompactor.compact(messages, budgetTokens = 350, currentTurnStart = 8)
        assertEquals(listOf("c3"), dropped.mapNotNull { it.toolCallId })
        assertTrue(dropped.any { it.content == ContextCompactor.TRIMMED_NOTE })
    }

    @Test
    fun `超预算 - 旧轮先精简为问句加回答 角色不变并带注记`() {
        val messages = listOf(
            EngineMessage("system", "sys"),
            EngineMessage("user", "第一问:" + "细节".repeat(100)),
            EngineMessage("assistant", "第一答:" + "内容".repeat(300)),
            EngineMessage("user", "第二问"),
            EngineMessage("assistant", "第二答"),
            EngineMessage("user", "当前轮"),
            EngineMessage("assistant", "当前回答"),
        )
        val folded = ContextCompactor.compact(messages, budgetTokens = 300, currentTurnStart = 5)
        assertEquals(listOf("system", "system", "user", "assistant", "user", "assistant", "user", "assistant"), folded.map { it.role })
        assertEquals(ContextCompactor.TRIMMED_NOTE, folded[1].content)
        val question = folded[2]
        val answer = folded[3]
        assertTrue(question.content.startsWith("第一问:"))
        assertTrue("问句被截短并标注", question.content.endsWith("…(已精简)") && question.content.length < messages[1].content.length)
        assertTrue("回答被截短并标注", answer.content.endsWith("…(已精简)") && answer.content.length < messages[2].content.length)
        // 较新的旧轮没动
        assertEquals("第二问", folded[4].content)
        assertEquals("第二答", folded[5].content)
    }

    @Test
    fun `超预算 - 精简后仍放不下 自最旧整轮丢弃`() {
        val messages = listOf(
            EngineMessage("system", "sys"),
            EngineMessage("user", "第一问:" + "细节".repeat(100)),
            EngineMessage("assistant", "第一答:" + "内容".repeat(300)),
            EngineMessage("user", "第二问"),
            EngineMessage("assistant", "第二答"),
            EngineMessage("user", "当前轮"),
            EngineMessage("assistant", "当前回答"),
        )
        val folded = ContextCompactor.compact(messages, budgetTokens = 60, currentTurnStart = 5)
        assertEquals(listOf("system", "system", "user", "assistant", "user", "assistant"), folded.map { it.role })
        assertTrue(folded.none { it.content.startsWith("第一问") || it.content.startsWith("第一答") })
        assertTrue(folded.any { it.content == "第二问" })
    }

    @Test
    fun `语义压缩 - 保留开头与结尾,中间标注省略`() {
        val head = "开头要点。"
        val mid = "中间无关内容。".repeat(50)
        val tail = "末尾结论:订单已创建。"
        val clipped = ContextCompactor.semanticClip(head + mid + tail, 120)
        assertTrue(clipped.length < head.length + mid.length + tail.length)
        assertTrue("应保留开头", clipped.startsWith("开头要点"))
        assertTrue("应保留结尾结论", clipped.endsWith("订单已创建。") || clipped.contains("订单已创建"))
        assertTrue(clipped.contains(ContextCompactor.OMITTED_MARK))
    }

    @Test
    fun `语义压缩 - 不把句子切成两半`() {
        val text = "第一句话内容。" + "第二句话内容。"
        // 边界落在句号附近时应整句保留
        val clipped = ContextCompactor.clipAtBoundary(text, 8)
        assertTrue(clipped.endsWith("。"))
    }

    @Test
    fun `语义压缩 - 未超限时原样返回`() {
        assertEquals("短文本", ContextCompactor.semanticClip("短文本", 100))
    }

    @Test
    fun `旧轮工具结果折叠 - 摘要取正文且分隔符成对`() {
        val wrapped = com.agentkeyboard.tools.ToolResultWrapper.wrap("正文开头" + "x".repeat(500))
        val messages = listOf(
            EngineMessage("system", "sys"),
            EngineMessage("user", "第一轮"),
            EngineMessage("tool", wrapped, toolCallId = "c1", toolName = "web_search"),
            EngineMessage("user", "第二轮"),
        )
        val folded = ContextCompactor.foldOldToolResults(messages, currentTurnStart = 3)[2].content
        val open = com.agentkeyboard.tools.ToolResultWrapper.OPEN
        val close = com.agentkeyboard.tools.ToolResultWrapper.CLOSE
        assertTrue(folded.startsWith("[工具结果已折叠] web_search:"))
        assertEquals(1, folded.split(open).size - 1)
        assertEquals(1, folded.split(close).size - 1)
        assertTrue(folded.contains("正文开头"))
        assertTrue(folded.length < wrapped.length)
    }

    @Test
    fun `token估算 - 计入assistant工具调用参数`() {
        val args = """{"query":"${"a".repeat(400)}"}"""
        val withCalls = EngineMessage(
            "assistant", "",
            toolCalls = listOf(com.agentkeyboard.ai.EngineToolCall("c1", "web_search", args)),
        )
        assertTrue(ContextCompactor.totalTokens(listOf(withCalls)) >= 100)
    }

    @Test
    fun `当前轮超预算 - 折叠较早步骤工具结果 最新一批保留全文`() {
        val big = "检索结果".repeat(300)
        val messages = listOf(
            EngineMessage("system", "sys"),
            EngineMessage("user", "当前问题"),
            EngineMessage("assistant", "", toolCalls = listOf(com.agentkeyboard.ai.EngineToolCall("c1", "web_search", "{}"))),
            EngineMessage("tool", big, toolCallId = "c1", toolName = "web_search"),
            EngineMessage("assistant", "", toolCalls = listOf(com.agentkeyboard.ai.EngineToolCall("c2", "fetch_page", "{}"))),
            EngineMessage("tool", big, toolCallId = "c2", toolName = "fetch_page"),
        )
        val out = ContextCompactor.compact(messages, budgetTokens = 1500, currentTurnStart = 1)
        assertEquals(messages.size, out.size) // 配对不变,不丢消息
        assertTrue(out.first { it.toolCallId == "c1" }.content.startsWith("[工具结果已折叠]"))
        assertEquals(big, out.first { it.toolCallId == "c2" }.content)
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
