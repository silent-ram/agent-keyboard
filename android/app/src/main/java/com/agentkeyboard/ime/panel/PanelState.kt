package com.agentkeyboard.ime.panel

import com.agentkeyboard.agent.AgentEvent
import com.agentkeyboard.agent.AgentRunSummary
import com.agentkeyboard.agent.ConfirmationKind
import com.agentkeyboard.agent.ConfirmationRequest
import com.agentkeyboard.data.db.AgentMessageEntity
import com.agentkeyboard.tools.Citation
import com.agentkeyboard.tools.CitationJson
import com.agentkeyboard.tools.ToolIds

/** 工具卡片四态(12 号设计 §3) */
enum class ToolCardState { RUNNING, DONE, FAILED }

data class EditorSelectionSnapshot(val start: Int, val end: Int)

/**
 * 面板消息(12 号设计 §3 四类型 + 运行占位):
 * 用户气泡 / AI 完成(预览卡)/ 工具卡片 / 系统卡;Loading = 运行条占位。
 */
sealed interface PanelMessage {
    /** [actionTag] = 快捷动作前缀小标签(✨帮写/🔁改写/🌐翻译,12 §3) */
    data class UserText(val text: String, val actionTag: String? = null) : PanelMessage

    data object Loading : PanelMessage

    data class ToolCard(
        val toolCallId: String,
        val toolId: String,
        val label: String,
        val state: ToolCardState,
        val summary: String,
        val flagged: Boolean = false,
    ) : PanelMessage

    /**
     * 预览卡:[unverified]=含出网步骤但无来源(⚠ 未验证);[readonly]=历史回放(无按钮组);
     * [streaming]=M1-T6 逐字中;M3 替换型预览卡(12 §4/§5):[actionTag]=动作徽章,
     * [oldText] 非空 = 替换模式(新旧对照 + 主按钮「🔁 替换」),[fromSelection]=源文本来自选区,
     * [replaceSucceeded]=替换成功后卡片保留「恢复原文」入口(直至会话结束)。
     */
    data class Result(
        val text: String,
        val modelLabel: String?,
        val citations: List<Citation> = emptyList(),
        val unverified: Boolean = false,
        val metaLine: String? = null,
        val readonly: Boolean = false,
        val streaming: Boolean = false,
        val actionTag: String? = null,
        val oldText: String? = null,
        val fromSelection: Boolean = false,
        val replaceSucceeded: Boolean = false,
        /** Result may only be inserted into the input session that requested it. */
        val editorGeneration: Long? = null,
        val editorSelection: EditorSelectionSnapshot? = null,
    ) : PanelMessage {
        val canInsert: Boolean
            get() = !readonly && !streaming && editorGeneration != null && text.isNotBlank()
    }

    /** 错误卡:[kind] = AgentError.kind(BAD_KEY 附去设置;NOT_CONFIGURED 跳 S9) */
    data class Error(val kind: String, val message: String) : PanelMessage

    data class System(val text: String) : PanelMessage
}

/** 出网工具集合(预览卡"未验证"判定) */
val OUTBOUND_TOOL_IDS = setOf(ToolIds.WEB_SEARCH, ToolIds.FETCH_PAGE)

/**
 * 「总是允许」撤销条状态(P1-1,状态提升):
 * 确认条随 resolver 恢复离开组合,撤销状态若在组件内部会随之销毁——
 * 因此提升到服务层持有,面板顶部独立渲染 5s。可注入 clock 便于单测。
 */
class UndoAlwaysController(private val clock: () -> Long = System::currentTimeMillis) {

    companion object {
        const val TTL_MS = 5000L
    }

    private val _toolId = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val toolId: kotlinx.coroutines.flow.StateFlow<String?> get() = _toolId
    private var markedAt = 0L

    fun mark(toolId: String) {
        markedAt = clock()
        _toolId.value = toolId
    }

    fun clear() {
        _toolId.value = null
        markedAt = 0L
    }

    fun isExpired(now: Long = clock()): Boolean =
        _toolId.value != null && now - markedAt >= TTL_MS
}

/** 工具卡片展示名(12 号设计 §3 摘要行) */
fun toolLabel(toolId: String): String = when (toolId) {
    ToolIds.WEB_SEARCH -> "🔍 联网搜索"
    ToolIds.FETCH_PAGE -> "🌐 读取网页"
    ToolIds.READ_CONTEXT -> "📄 读取上下文"
    ToolIds.INSERT_TEXT -> "⬆ 上屏插入"
    ToolIds.KB_SEARCH -> "资料检索"
    ToolIds.ASK_USER -> "❓ 向用户提问"
    else -> "外部工具"
}

object PanelReducer {

    /** 完成卡摘要行(12 号设计 §3 摘要文案示例) */
    fun doneSummary(toolId: String, raw: String, citationCount: Int): String = when (toolId) {
        ToolIds.WEB_SEARCH -> "命中 $citationCount 条来源"
        ToolIds.FETCH_PAGE -> "已读取网页正文"
        ToolIds.READ_CONTEXT -> "已读取输入框上下文"
        ToolIds.INSERT_TEXT -> "已插入"
        ToolIds.KB_SEARCH -> "检索完成"
        else -> "执行完成"
    }

    /** 会话内是否出现过出网工具调用(预览卡"⚠ 未验证"判定) */
    fun hasOutboundCalls(messages: List<PanelMessage>): Boolean =
        messages.any { it is PanelMessage.ToolCard && it.toolId in OUTBOUND_TOOL_IDS }

    /** LLM 思考中:清旧占位 → Loading */
    fun onThinking(messages: List<PanelMessage>): List<PanelMessage> =
        messages.filterNot { it == PanelMessage.Loading } + PanelMessage.Loading

    /** 工具开始:Loading 移除;同 id 卡排队中则转运行,否则追加运行卡 */
    fun onStepStarted(messages: List<PanelMessage>, toolCallId: String, toolId: String): List<PanelMessage> {
        val base = messages.filterNot { it == PanelMessage.Loading }
        val idx = base.indexOfFirst { it is PanelMessage.ToolCard && it.toolCallId == toolCallId }
        return if (idx >= 0) {
            base.mapIndexed { i, m ->
                if (i == idx) (m as PanelMessage.ToolCard).copy(state = ToolCardState.RUNNING, summary = "执行中…") else m
            }
        } else {
            base + PanelMessage.ToolCard(toolCallId, toolId, toolLabel(toolId), ToolCardState.RUNNING, "执行中…")
        }
    }

    /** 工具结束:同 id 卡转完成/失败;命中注入警示时附警示文案(FR-E4) */
    fun onStepFinished(
        messages: List<PanelMessage>,
        toolCallId: String,
        toolId: String,
        ok: Boolean,
        raw: String,
        citationCount: Int,
        flagged: Boolean,
    ): List<PanelMessage> {
        val idx = messages.indexOfFirst { it is PanelMessage.ToolCard && it.toolCallId == toolCallId }
        val card = PanelMessage.ToolCard(
            toolCallId = toolCallId,
            toolId = toolId,
            label = toolLabel(toolId),
            state = if (ok) ToolCardState.DONE else ToolCardState.FAILED,
            summary = if (ok) doneSummary(toolId, raw, citationCount) else "执行失败",
            flagged = flagged,
        )
        return if (idx >= 0) messages.mapIndexed { i, m -> if (i == idx) card else m } else messages + card
    }

    /**
     * 最终回答 → 预览卡。
     * [hadOutbound]=会话含出网工具调用且 citations 空 → unverified(⚠ 未验证,无来源,12 §4/02 §3.2)。
     * M3 替换型预览卡(12 §5):[actionTag]=快捷动作徽章;[oldText] 非空 → 替换模式
     * (新旧对照 + 主按钮「🔁 替换」);[fromSelection]=源文本来自选区(替换执行分支依据)。
     */
    fun onFinished(
        messages: List<PanelMessage>,
        content: String,
        citations: List<Citation>,
        modelLabel: String?,
        elapsedSeconds: Long?,
        actionTag: String? = null,
        oldText: String? = null,
        fromSelection: Boolean = false,
        editorGeneration: Long? = null,
        editorSelection: EditorSelectionSnapshot? = null,
        summary: AgentRunSummary = AgentRunSummary.EMPTY,
    ): List<PanelMessage> {
        // 流式卡(P1-3 同型):Finished 用正式卡替换,避免重复
        val base = messages.filterNot { it == PanelMessage.Loading }
            .filterNot { it is PanelMessage.Result && it.streaming }
        val hadOutbound = hasOutboundCalls(base)
        val meta = buildString {
            append("AI")
            // 优先展示服务端真实用量;服务端未返回时退回本地估算并保留 ≈ 标记
            if (summary.usage.isEmpty) {
                append(" · ≈")
                append(estimateTokens(content))
                append(" tokens")
            } else {
                append(" · ")
                append(summary.usage.promptTokens)
                append(" + ")
                append(summary.usage.completionTokens)
                append(" tokens")
            }
            elapsedSeconds?.let { append(" · ${it}s") }
            val toolSteps = summary.steps.count { it.kind != "llm" }
            if (toolSteps > 0) append(" · ${toolSteps} 次工具调用")
        }
        return base + PanelMessage.Result(
            text = content,
            modelLabel = modelLabel,
            citations = citations,
            unverified = hadOutbound && citations.isEmpty(),
            metaLine = meta,
            readonly = false,
            actionTag = actionTag,
            oldText = oldText,
            fromSelection = fromSelection,
            editorGeneration = editorGeneration,
            editorSelection = editorSelection,
        )
    }

    fun onIncomplete(
        messages: List<PanelMessage>,
        content: String,
        citations: List<Citation>,
        modelLabel: String?,
        elapsedSeconds: Long?,
        reason: String,
        editorGeneration: Long? = null,
        editorSelection: EditorSelectionSnapshot? = null,
        summary: AgentRunSummary = AgentRunSummary.EMPTY,
    ): List<PanelMessage> {
        val visibleContent = content.ifBlank {
            if (citations.isNotEmpty()) "已获得部分资料，任务未完成。" else ""
        }
        val base = if (visibleContent.isBlank()) {
            messages.filterNot { it == PanelMessage.Loading }
        } else {
            val hasAnswer = content.isNotBlank()
            val completed = onFinished(
                messages, visibleContent, citations, modelLabel, elapsedSeconds,
                editorGeneration = editorGeneration.takeIf { hasAnswer },
                editorSelection = editorSelection.takeIf { hasAnswer },
                summary = summary,
            )
            val resultIndex = completed.indexOfLast { it is PanelMessage.Result }
            if (resultIndex < 0) completed else completed.mapIndexed { index, message ->
                if (index == resultIndex && message is PanelMessage.Result) message.copy(unverified = true)
                else message
            }
        }
        return base + PanelMessage.System("未完成：$reason")
    }

    /** 流式增量(M1-T6):更新最后一张流式卡,否则新建;Loading 移除 */
    fun onContentDelta(messages: List<PanelMessage>, accumulated: String, modelLabel: String?): List<PanelMessage> {
        val base = messages.filterNot { it == PanelMessage.Loading }
        val last = base.lastOrNull()
        return if (last is PanelMessage.Result && last.streaming) {
            base.dropLast(1) + last.copy(text = accumulated)
        } else {
            base + PanelMessage.Result(
                text = accumulated,
                modelLabel = modelLabel,
                streaming = true,
            )
        }
    }

    /**
     * 撤回流式草稿(安全兜底):去掉所有草稿卡。草稿从不进入编辑器,
     * 撤回后由随后的 Finished/Incomplete 提供唯一可信结果。
     */
    fun onContentReset(messages: List<PanelMessage>): List<PanelMessage> =
        messages.filterNot { it is PanelMessage.Result && it.streaming }

    /** 失败 → 错误卡(Loading 移除) */
    fun onFailed(messages: List<PanelMessage>, kind: String, message: String): List<PanelMessage> =
        messages.filterNot { it == PanelMessage.Loading } + PanelMessage.Error(kind, message)

    /** 停止 → 系统注记(已生成部分保留) */
    fun onStopped(messages: List<PanelMessage>): List<PanelMessage> =
        messages.filterNot { it == PanelMessage.Loading } + PanelMessage.System("已停止(内容不完整)")

    /** 确认请求到达(事件/单槽恢复):消息流不动,确认条由独立状态渲染 */
    fun onConfirmationRequired(messages: List<PanelMessage>): List<PanelMessage> = messages

    /** 确认条形态可用性(11 号 §6.2):本次允许 / 总是允许 / 总是询问 / 拒绝 */
    data class ConfirmationActions(
        val allowOnce: Boolean,
        val allowAlways: Boolean,
        val askEveryTime: Boolean,
        val deny: Boolean,
    )

    fun actionsFor(request: ConfirmationRequest): ConfirmationActions = when (request.kind) {
        ConfirmationKind.FIRST_TIME -> ConfirmationActions(true, true, true, true)
        ConfirmationKind.EVERY_TIME -> ConfirmationActions(true, true, false, true)
        ConfirmationKind.DERIVED_BOTTOM_LINE -> ConfirmationActions(true, false, false, true)
        ConfirmationKind.LLM_DISCLOSURE -> ConfirmationActions(true, false, false, true)
    }

    /**
     * 会话载入映射(🕘 会话列表点击):Room 消息 → 只读消息流。
     * user → 气泡;assistant 文本 → 只读预览卡;tool → 完成/失败卡;system → 系统卡;
     * assistant 的 toolCalls 载荷(无文本)由对应 tool 消息渲染,此处跳过。
     */
    fun sessionToPanelMessages(rows: List<AgentMessageEntity>, modelLabel: String?): List<PanelMessage> =
        rows.mapNotNull { row ->
            when (row.role) {
                "user" -> PanelMessage.UserText(row.content)
                "assistant" ->
                    if (row.content.isNotBlank() && row.toolJson == null) {
                        PanelMessage.Result(
                            text = row.content,
                            citations = CitationJson.decode(row.citationsJson),
                            modelLabel = modelLabel,
                            readonly = true,
                        )
                    } else null
                "tool" -> {
                    val meta = runCatching { org.json.JSONObject(row.toolJson ?: "{}") }.getOrNull()
                    val ok = meta?.optBoolean("ok", true) ?: true
                    val toolId = meta?.optString("name") ?: "tool"
                    PanelMessage.ToolCard(
                        toolCallId = meta?.optString("toolCallId") ?: row.id.toString(),
                        toolId = toolId,
                        label = toolLabel(toolId),
                        state = if (ok) ToolCardState.DONE else ToolCardState.FAILED,
                        summary = if (ok) doneSummary(toolId, "", 0) else "执行失败",
                        flagged = meta?.optBoolean("flagged", false) ?: false,
                    )
                }
                "system" -> PanelMessage.System(row.content)
                else -> null
            }
        }

    /** 会话标题 = 首条用户输入前 20 字(12 号设计 §6) */
    fun sessionTitle(firstUserText: String): String =
        firstUserText.trim().replace('\n', ' ').take(20).ifBlank { "(空会话)" }

    /** token 估算(与折叠器同口径):中文 1 字 1 token,其余 4 字符 1 token */
    fun estimateTokens(text: String): Int {
        var cjk = 0
        var other = 0
        for (c in text) {
            if ((c in '\u4E00'..'\u9FFF') || (c in '\u3400'..'\u4DBF') || (c in '\u3000'..'\u303F') || (c in '\uFF00'..'\uFFEF')) cjk++ else other++
        }
        return cjk + (other + 3) / 4
    }

    /** AgentEvent 应用(面板事件收集循环统一入口;返回新消息列表) */
    fun apply(messages: List<PanelMessage>, event: AgentEvent, modelLabel: String?, elapsedSeconds: Long?): List<PanelMessage> =
        when (event) {
            is AgentEvent.Thinking -> onThinking(messages)
            is AgentEvent.StepStarted -> onStepStarted(messages, event.call.toolCallId, event.call.toolId)
            is AgentEvent.StepFinished -> onStepFinished(
                messages, event.call.toolCallId, event.call.toolId,
                ok = event.result.ok, raw = event.result.raw,
                citationCount = event.result.citations.size, flagged = event.flagged,
            )
            is AgentEvent.ContentDelta -> onContentDelta(messages, event.accumulated, modelLabel)
            is AgentEvent.ContentReset -> onContentReset(messages)
            is AgentEvent.Finished -> onFinished(
                messages, event.content, event.citations, modelLabel, elapsedSeconds, summary = event.summary,
            )
            is AgentEvent.Incomplete -> onIncomplete(
                messages, event.content, event.citations, modelLabel, elapsedSeconds, event.reason,
                summary = event.summary,
            )
            is AgentEvent.Failed -> onFailed(messages, event.error.kind, event.error.message)
            is AgentEvent.Stopped -> onStopped(messages)
            is AgentEvent.Started, is AgentEvent.ConfirmationRequired -> onConfirmationRequired(messages)
        }
}
