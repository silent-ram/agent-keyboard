package com.agentkeyboard.ime.panel

import com.agentkeyboard.agent.AgentError
import com.agentkeyboard.agent.AgentEvent
import com.agentkeyboard.agent.ConfirmationKind
import com.agentkeyboard.agent.ConfirmationRequest
import com.agentkeyboard.data.db.AgentMessageEntity
import com.agentkeyboard.ime.panel.EditorSelectionSnapshot
import com.agentkeyboard.tools.Citation
import com.agentkeyboard.tools.CitationJson
import com.agentkeyboard.tools.ToolCall
import com.agentkeyboard.tools.ToolResult
import com.agentkeyboard.tools.ToolResultWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 面板状态映射:AgentEvent → 消息流(12 号设计 §3 四类型) */
class PanelStateTest {

    private val call = ToolCall("c1", "web_search", """{"query":"q"}""")

    @Test
    fun `Thinking - 出现Loading占位`() {
        val out = PanelReducer.onThinking(emptyList())
        assertEquals(1, out.size)
        assertEquals(PanelMessage.Loading, out[0])
        // 重复 Thinking 不叠加占位
        assertEquals(1, PanelReducer.onThinking(out).size)
    }

    @Test
    fun `StepStarted - 追加运行卡且Loading移除`() {
        val msgs = PanelReducer.onThinking(emptyList())
        val out = PanelReducer.onStepStarted(msgs, "c1", "web_search")
        assertTrue(out.none { it == PanelMessage.Loading })
        val card = out.filterIsInstance<PanelMessage.ToolCard>().single()
        assertEquals(ToolCardState.RUNNING, card.state)
        assertEquals("🔍 联网搜索", card.label)
    }

    @Test
    fun `StepFinished ok - DONE卡与摘要(flagged附警示)`() {
        var msgs = PanelReducer.onStepStarted(emptyList(), "c1", "web_search")
        msgs = PanelReducer.onStepFinished(msgs, "c1", "web_search", ok = true, raw = "raw", citationCount = 3, flagged = true)
        val card = msgs.filterIsInstance<PanelMessage.ToolCard>().single()
        assertEquals(ToolCardState.DONE, card.state)
        assertEquals("命中 3 条来源", card.summary)
        assertTrue(card.flagged)
    }

    @Test
    fun `StepFinished failed - FAILED卡且标注不影响后续`() {
        var msgs = PanelReducer.onStepStarted(emptyList(), "c1", "fetch_page")
        msgs = PanelReducer.onStepFinished(msgs, "c1", "fetch_page", ok = false, raw = "搜索服务未配置", citationCount = 0, flagged = false)
        val card = msgs.filterIsInstance<PanelMessage.ToolCard>().single()
        assertEquals(ToolCardState.FAILED, card.state)
        assertEquals("执行失败", card.summary)
    }

    @Test
    fun `Finished - 纯文本无出网 - AI回答徽章不标未验证`() {
        val out = PanelReducer.onFinished(emptyList(), "你好,我是输入法 AI。", emptyList(), "GLM-4.6", 3L)
        val result = out.filterIsInstance<PanelMessage.Result>().single()
        assertFalse(result.unverified)
        assertFalse(result.metaLine!!.contains("GLM-4.6"))
        assertTrue(result.metaLine!!.contains("≈"))
        assertTrue(result.metaLine!!.contains("3s"))
    }

    @Test
    fun `Finished - 保存请求时的输入框与选区快照`() {
        val selection = EditorSelectionSnapshot(2, 5)
        val result = PanelReducer.onFinished(
            emptyList(), "答复", emptyList(), "模型", null,
            editorGeneration = 4L,
            editorSelection = selection,
        ).filterIsInstance<PanelMessage.Result>().single()

        assertEquals(4L, result.editorGeneration)
        assertEquals(selection, result.editorSelection)
    }

    @Test
    fun incompleteAnswerKeepsItsInsertionTargetButGeneratedNoticeDoesNot() {
        val selection = EditorSelectionSnapshot(7, 7)
        val citation = Citation("https://example.com/source", "Source")
        val answer = PanelReducer.onIncomplete(
            emptyList(), "部分答案", listOf(citation), "模型", null, "检索未完成",
            editorGeneration = 4L, editorSelection = selection,
        ).filterIsInstance<PanelMessage.Result>().single()
        assertTrue(answer.unverified)
        assertTrue(answer.canInsert)
        assertEquals(4L, answer.editorGeneration)
        assertEquals(selection, answer.editorSelection)

        val notice = PanelReducer.onIncomplete(
            emptyList(), "", listOf(citation), "模型", null, "检索未完成",
            editorGeneration = 4L, editorSelection = selection,
        ).filterIsInstance<PanelMessage.Result>().single()
        assertFalse(notice.canInsert)
    }

    @Test
    fun `Finished - 含出网工具但无citations - 标注未验证`() {
        var msgs = PanelReducer.onStepFinished(emptyList(), "c1", "web_search", ok = true, raw = "r", citationCount = 0, flagged = false)
        // simulate started card existing
        msgs = PanelReducer.onStepStarted(emptyList(), "c1", "web_search")
        val out = PanelReducer.onFinished(msgs, "答案", emptyList(), "GLM-4.6", null)
        val result = out.filterIsInstance<PanelMessage.Result>().single()
        assertTrue(result.unverified)
    }

    // ==== M3 快捷动作 / 替换型预览卡(12 号设计 §5) ====

    @Test
    fun `快捷动作 - 用户消息带动作标签`() {
        val msgs = listOf<PanelMessage>(PanelMessage.UserText("基于上文生成可直接发送的回复", actionTag = "✨帮写"))
        val user = msgs.filterIsInstance<PanelMessage.UserText>().single()
        assertEquals("✨帮写", user.actionTag)
    }

    @Test
    fun `替换型预览卡 - 改写Finished带oldText与fromSelection`() {
        val msgs = listOf<PanelMessage>(PanelMessage.UserText("原文", actionTag = "🔁改写"))
        val out = PanelReducer.onFinished(
            msgs, "改写后的新文本", emptyList(), "GLM-4.6", 2L,
            actionTag = "🔁改写", oldText = "原文", fromSelection = true,
        )
        val card = out.filterIsInstance<PanelMessage.Result>().single()
        assertEquals("🔁改写", card.actionTag)
        assertEquals("原文", card.oldText)
        assertTrue(card.fromSelection)
        assertFalse(card.replaceSucceeded)
    }

    @Test
    fun `替换型预览卡 - 翻译无选区替换`() {
        val out = PanelReducer.onFinished(
            emptyList(), "The meeting starts at 3pm", emptyList(), "GLM-4.6", null,
            actionTag = "🌐翻译", oldText = "会议下午三点开始", fromSelection = false,
        )
        val card = out.filterIsInstance<PanelMessage.Result>().single()
        assertEquals("会议下午三点开始", card.oldText)
        assertFalse(card.fromSelection)
    }

    @Test
    fun `替换型预览卡 - 帮写为普通插入卡无oldText`() {
        val out = PanelReducer.onFinished(
            emptyList(), "生成的回复", emptyList(), "GLM-4.6", null,
            actionTag = "✨帮写", oldText = null, fromSelection = false,
        )
        val card = out.filterIsInstance<PanelMessage.Result>().single()
        assertEquals("✨帮写", card.actionTag)
        assertNull(card.oldText)
    }

    @Test
    fun `Finished - citations非空 - 已查证徽章`() {
        val citations = listOf(Citation("https://a.com", "A"))
        var msgs = PanelReducer.onStepStarted(emptyList(), "c1", "web_search")
        msgs = PanelReducer.onStepFinished(msgs, "c1", "web_search", ok = true, raw = "r", citationCount = 1, flagged = false)
        val out = PanelReducer.onFinished(msgs, "答案", citations, null, null)
        val result = out.filterIsInstance<PanelMessage.Result>().single()
        assertFalse(result.unverified)
        assertEquals(citations, result.citations)
    }

    @Test
    fun `Failed - 错误卡替换Loading`() {
        val msgs = PanelReducer.onThinking(emptyList())
        val out = PanelReducer.onFailed(msgs, "BAD_KEY", "密钥无效,请到设置更新 API Key")
        assertTrue(out.none { it == PanelMessage.Loading })
        val err = out.filterIsInstance<PanelMessage.Error>().single()
        assertEquals("BAD_KEY", err.kind)
    }

    @Test
    fun `M1-T6 ContentDelta - 逐字更新流式卡`() {
        var msgs = PanelReducer.onThinking(emptyList())
        msgs = PanelReducer.apply(msgs, AgentEvent.ContentDelta("你"), null, null)
        assertTrue(msgs.none { it == PanelMessage.Loading })
        val card1 = msgs.filterIsInstance<PanelMessage.Result>().single()
        assertTrue(card1.streaming)
        assertEquals("你", card1.text)
        msgs = PanelReducer.apply(msgs, AgentEvent.ContentDelta("你好,世界"), null, null)
        val card2 = msgs.filterIsInstance<PanelMessage.Result>().single()
        assertEquals("你好,世界", card2.text)
        assertEquals(1, msgs.filterIsInstance<PanelMessage.Result>().size) // 更新而非新建
    }

    @Test
    fun `M1-T6 Finished - 流式卡替换为正式卡`() {
        var msgs = PanelReducer.apply(emptyList(), AgentEvent.ContentDelta("草稿"), "GLM", null)
        msgs = PanelReducer.apply(
            msgs, AgentEvent.Finished("最终回答", emptyList(), 1), "GLM", 2L,
        )
        val results = msgs.filterIsInstance<PanelMessage.Result>()
        assertEquals(1, results.size)
        assertFalse(results[0].streaming)
        assertEquals("最终回答", results[0].text)
    }

    @Test
    fun `Incomplete - 部分结果标记未验证并保留原因`() {
        val citation = Citation("https://example.com", "来源")
        var msgs = PanelReducer.onThinking(emptyList())
        msgs = PanelReducer.apply(
            msgs,
            AgentEvent.Incomplete("部分答案", listOf(citation), "达到步骤上限", 1),
            "GLM",
            2L,
        )

        val result = msgs.filterIsInstance<PanelMessage.Result>().single()
        assertEquals("部分答案", result.text)
        assertEquals(listOf(citation), result.citations)
        assertTrue(result.unverified)
        assertTrue(msgs.filterIsInstance<PanelMessage.System>().single().text.contains("达到步骤上限"))
        assertTrue(msgs.none { it == PanelMessage.Loading })
    }

    @Test
    fun `Stopped - 系统注记且Loading移除`() {
        val msgs = PanelReducer.onThinking(emptyList())
        val out = PanelReducer.onStopped(msgs)
        assertTrue(out.none { it == PanelMessage.Loading })
        val sys = out.filterIsInstance<PanelMessage.System>().single()
        assertTrue(sys.text.contains("已停止"))
    }

    @Test
    fun `apply - ConfirmationRequired 不改消息流`() {
        val request = ConfirmationRequest("web_search", ConfirmationKind.FIRST_TIME, "t", "b", "", "搜索服务", null, true)
        val out = PanelReducer.apply(emptyList(), AgentEvent.ConfirmationRequired(request), null, null)
        assertTrue(out.isEmpty())
    }

    @Test
    fun `actionsFor - 三形态按钮组合(11号设计§6_2)`() {
        val first = PanelReducer.actionsFor(req(ConfirmationKind.FIRST_TIME))
        assertTrue(first.allowOnce && first.allowAlways && first.askEveryTime && first.deny) // 四钮
        val every = PanelReducer.actionsFor(req(ConfirmationKind.EVERY_TIME))
        assertTrue(every.allowOnce && every.allowAlways && !every.askEveryTime && every.deny) // 三钮
        val derived = PanelReducer.actionsFor(req(ConfirmationKind.DERIVED_BOTTOM_LINE))
        assertTrue(derived.allowOnce && !derived.allowAlways && !derived.askEveryTime && derived.deny) // 两键,底线无总是允许
        val llm = PanelReducer.actionsFor(req(ConfirmationKind.LLM_DISCLOSURE))
        assertTrue(llm.allowOnce && !llm.allowAlways && llm.deny)
    }

    private fun req(kind: ConfirmationKind) =
        ConfirmationRequest("web_search", kind, "t", "b", "p", "搜索服务", null, canRemember = kind != ConfirmationKind.DERIVED_BOTTOM_LINE)

    // ---------- 会话载入映射(M1-T7c) ----------

    @Test
    fun `会话载入 - Room消息映射为只读消息流`() {
        val rows = listOf(
            AgentMessageEntity(sessionId = 1, role = "user", content = "帮我查一下", stepNo = 0, createdAt = 1),
            AgentMessageEntity(
                sessionId = 1, role = "assistant", content = "",
                toolJson = """{"calls":[{"id":"c1","name":"web_search","arguments":"{}"}]}""",
                stepNo = 1, createdAt = 2,
            ),
            AgentMessageEntity(
                sessionId = 1, role = "tool", content = "搜索结果原文",
                toolJson = """{"toolCallId":"c1","name":"web_search","ok":true,"flagged":false}""",
                stepNo = 1, createdAt = 3,
            ),
            AgentMessageEntity(sessionId = 1, role = "assistant", content = "这是答案", stepNo = 2, createdAt = 4),
            AgentMessageEntity(sessionId = 1, role = "system", content = "已停止(用户取消)", stepNo = -1, createdAt = 5),
        )
        val out = PanelReducer.sessionToPanelMessages(rows, "GLM-4.6")
        assertEquals(4, out.size) // assistant 空文本 toolCalls 载荷跳过
        assertTrue(out[0] is PanelMessage.UserText)
        val card = out[1] as PanelMessage.ToolCard
        assertEquals(ToolCardState.DONE, card.state)
        assertTrue(out[2] is PanelMessage.Result && (out[2] as PanelMessage.Result).readonly)
        assertTrue(out[3] is PanelMessage.System)
    }

    @Test
    fun `会话载入 - 失败工具卡映射`() {
        val rows = listOf(
            AgentMessageEntity(
                sessionId = 1, role = "tool", content = "搜索服务无响应",
                toolJson = """{"toolCallId":"c1","name":"web_search","ok":false,"flagged":false}""",
                stepNo = 1, createdAt = 1,
            ),
        )
        val out = PanelReducer.sessionToPanelMessages(rows, null)
        val card = out.filterIsInstance<PanelMessage.ToolCard>().single()
        assertEquals(ToolCardState.FAILED, card.state)
    }

    @Test
    fun `会话载入 - 答案恢复已保存的知识库出处`() {
        val citation = Citation(
            url = "kb://roadmap.md",
            title = "roadmap.md §2",
            snippet = "下一季度重点是稳定性。",
        )
        val row = AgentMessageEntity(
            sessionId = 1,
            role = "assistant",
            content = "下一季度重点是稳定性。",
            citationsJson = CitationJson.encode(listOf(citation)),
            stepNo = 2,
            createdAt = 1,
        )

        val result = PanelReducer.sessionToPanelMessages(listOf(row), "model")
            .filterIsInstance<PanelMessage.Result>()
            .single()

        assertEquals(listOf(citation), result.citations)
    }

    @Test
    fun `会话标题 - 首条用户输入前20字`() {
        assertEquals("12345678901234567890", PanelReducer.sessionTitle("1234567890123456789012345"))
        assertEquals("短标题", PanelReducer.sessionTitle("短标题"))
        assertEquals("(空会话)", PanelReducer.sessionTitle("   "))
    }
}
