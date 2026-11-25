package com.agentkeyboard.agent

import com.agentkeyboard.tools.Citation
import com.agentkeyboard.tools.ToolCall
import com.agentkeyboard.tools.ToolResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharedFlow

/**
 * 任务类型(决定 system 提示词场景模板;12 号设计 §5):
 * ASK 自由问答 / VERIFY 查证 / HELP_WRITE 帮写 / REWRITE 改写 / TRANSLATE 翻译。
 * 后三个为工具条快捷动作,一律单轮(不带工具)运行。
 */
enum class TaskType { ASK, VERIFY, KNOWLEDGE_ASK, HELP_WRITE, REWRITE, TRANSLATE }

/**
 * 一次 agent 任务输入。
 * [snapshot] 为 IME 上下文快照(read_context 工具读取 + TaintSnapshot 同源);password 场景上层不会发起。
 */
data class Task(
    val type: TaskType,
    val userText: String,
    val sessionId: Long? = null,
    val contextBeforeText: String = "",
    val contextAfterText: String = "",
    val clipboardText: String? = null,
    /** 模型上下文窗口( token 数,缺省 8k;折叠预算 = 50%) */
    val contextWindowTokens: Int = 8000,
    /** 📚 知识库开关(面板显示用;kb_search 为 stub,M2 接入本地库后生效) */
    val kbEnabled: Boolean = false,
    /**
     * 单轮模板路径(M3 快捷动作):true = 整轮不带工具,一次 LLM 直出结果
     * (帮写/改写/翻译,12 号设计 §5:动作语义固定,无需工具)。
     */
    val singleTurn: Boolean = false,
    val allowProfileSuggestion: Boolean = false,
)

/**
 * Keyboard knowledge Q&A only sends the question typed into its own panel.
 * Host editor context and clipboard data are deliberately excluded.
 */
internal fun knowledgeAskTask(
    userText: String,
    sessionId: Long?,
    allowProfileSuggestion: Boolean = false,
): Task = Task(
    type = TaskType.KNOWLEDGE_ASK,
    userText = userText,
    sessionId = sessionId,
    kbEnabled = true,
    allowProfileSuggestion = allowProfileSuggestion,
)

/** 错误信息(面板错误卡;message 为成品文案 key 对应的稳定标识,不携带原始异常文本) */
data class AgentError(val kind: String, val message: String)

/**
 * 确认请求三形态 + llm_call 首次明示(11-权限设计 §6.2)。
 * [canRemember]=false 的形态仅 允许一次/拒绝 两键——派生底线不可豁免。
 */
enum class ConfirmationKind { FIRST_TIME, EVERY_TIME, DERIVED_BOTTOM_LINE, LLM_DISCLOSURE }

data class ConfirmationRequest(
    val toolId: String,
    val kind: ConfirmationKind,
    val title: String,
    val body: String,
    val paramsPreview: String,
    val targetLabel: String,
    val reason: String? = null,
    val canRemember: Boolean,
)

/**
 * 确认解析器(面板实现确认条;单测用假实现)。
 * 返回 true = 用户允许(本次);false = 拒绝。
 *
 * **组合契约(11-权限设计 §6.2,必须遵守)**:
 * - 「总是允许」:实现方必须**先落盘再放行**——`gate.setAlwaysAllow(request.toolId)` 成功后返回 true;
 *   返回 true 而 gate 未感知"总是允许"意图时,授权状态只会落为 ASK_EVERY_TIME(下次仍询问)。
 * - 「本次允许/允许一次/同意并继续/总是询问」:直接返回 true
 *   (「总是询问」= 放行本次,gate 会把持久状态落为 ASK_EVERY_TIME,无需 setAlwaysAllow)。
 * - 「拒绝/暂不使用」:返回 false。
 */
fun interface ConfirmationResolver {
    suspend fun resolve(request: ConfirmationRequest): Boolean
}

/**
 * Agent 事件(面板渲染的唯一来源,02-架构 §2.2)。
 */
sealed class AgentEvent {
    /** 会话已创建并开始 */
    data class Started(val sessionId: Long) : AgentEvent()

    /** LLM 正在思考(第 [step] 步) */
    data class Thinking(val step: Int) : AgentEvent()

    /** 流式增量(M1-T6):[accumulated] 为当前累积的 assistant 文本(面板逐字 / FR-D2 上屏) */
    data class ContentDelta(val accumulated: String) : AgentEvent()

    /**
     * 撤回已流式展示的草稿(流式放开后的安全兜底):
     * 完整回答未通过内容检查、或即将转入工具调用时发出,面板丢弃对应草稿卡,
     * 草稿卡本身不可插入 [PanelMessage.Result.canInsert],不会误上屏。
     */
    data object ContentReset : AgentEvent()

    data class StepStarted(val call: ToolCall, val step: Int) : AgentEvent()

    /** [flagged] = 工具结果命中注入扫描警示(FR-E4) */
    data class StepFinished(val call: ToolCall, val result: ToolResult, val step: Int, val flagged: Boolean = false) : AgentEvent()

    /** 确认条出现,agent 挂起等待(不超时、不自动放行) */
    data class ConfirmationRequired(val request: ConfirmationRequest) : AgentEvent()

    data class Finished(
        val content: String,
        val citations: List<Citation>,
        val sessionId: Long,
        val profileSuggestion: ProfileSuggestion? = null,
    ) : AgentEvent()

    /** 有可展示的部分结果，但证据或步骤不足以声称任务完整完成。 */
    data class Incomplete(
        val content: String,
        val citations: List<Citation>,
        val reason: String,
        val sessionId: Long,
    ) : AgentEvent()

    data class Failed(val error: AgentError, val sessionId: Long) : AgentEvent()

    /** 用户取消(已生成部分保留,标注已停止) */
    data class Stopped(val sessionId: Long) : AgentEvent()
}

/**
 * AgentEngine(02-架构 §2.2):
 * - start 返回可取消 Job;cancel 立即生效且状态落盘(STOPPED);
 * - events 为 UI 订阅唯一来源,订阅须在 start 之前完成。
 */
interface AgentEngine {
    fun start(task: Task): Job
    fun cancel()
    val events: SharedFlow<AgentEvent>
}
