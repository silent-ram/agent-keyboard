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
    /**
     * 模型上下文窗口(token 数)。0 = 未指定:按「模型设置 → 内置窗口表 → 保守默认」解析,
     * 见 [com.agentkeyboard.data.model.ModelWindows.resolve]。
     */
    val contextWindowTokens: Int = 0,
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

/**
 * 错误信息(面板错误卡;[kind] 为稳定分类,[message] 为成品文案 key 对应的稳定标识,不携带原始异常文本)。
 * [httpStatus]/[providerCode] 只用于调试:状态码与错误体里的错误码/类型标识符,可进界面、诊断信息和日志。
 */
data class AgentError(
    val kind: String,
    val message: String,
    /** HTTP 状态码;网络层失败没有状态码时为 null */
    val httpStatus: Int? = null,
    /** 服务商错误体里的错误码或类型(仅标识符字符,不含请求内容) */
    val providerCode: String? = null,
)

/**
 * 单步追踪(可观测性):一次模型请求、一次重试或一次工具执行的耗时、token 与结果。
 * 只记录时长与状态,不记录请求/响应正文,避免与现有脱敏策略冲突。
 */
data class AgentTraceStep(
    val step: Int,
    /** [KIND_LLM] / [KIND_RETRY] / [KIND_CONTEXT] / [KIND_REJECTED] / [KIND_JUDGE] / 工具 id */
    val kind: String,
    val elapsedMs: Long,
    val usage: com.agentkeyboard.ai.TokenUsage = com.agentkeyboard.ai.TokenUsage.ZERO,
    val ok: Boolean = true,
    /** 稳定错误码(ToolErrorCodes / AgentError.kind);正常为 null */
    val errorCode: String? = null,
    /** 仅重试步:第几次重试(从 1 起) */
    val attempt: Int? = null,
    /** 仅重试步:被重试那次失败的分类(ErrorKind.name) */
    val errorKind: String? = null,
    /** 仅重试步:HTTP 状态码;网络层失败没有状态码时为 null */
    val httpStatus: Int? = null,
) {
    /** 是否为工具执行步(模型请求、重试、上下文收紧、复核与未执行的调用都不算) */
    val isTool: Boolean get() = kind !in NON_TOOL_KINDS

    companion object {
        const val KIND_LLM = "llm"
        const val KIND_RETRY = "retry"
        /** 服务端报上下文超长后收紧窗口重发 */
        const val KIND_CONTEXT = "context"
        /** 模型发起但没有执行的工具调用:名称无效、工具不存在或本轮未开放、参数不可外发、时间用完 */
        const val KIND_REJECTED = "rejected"
        /** 资料命中指令样规则后的模型复核(ContentJudge);ok = 复核结论是「并非注入」 */
        const val KIND_JUDGE = "judge"
        private val NON_TOOL_KINDS = setOf(KIND_LLM, KIND_RETRY, KIND_CONTEXT, KIND_REJECTED, KIND_JUDGE)
    }
}

/** 循环提前结束的原因(批次 1:收尾回答 / 诊断用) */
enum class StopReason { STEP_LIMIT, TIME_BUDGET, TOKEN_BUDGET, MODEL_LENGTH, FILTERED }

/**
 * 一轮任务的运行摘要(与终态事件一起下发)。
 * [firstTokenMs]/[totalMs]/[retries] 供诊断使用,不进面板文案。
 */
data class AgentRunSummary(
    val steps: List<AgentTraceStep> = emptyList(),
    val usage: com.agentkeyboard.ai.TokenUsage = com.agentkeyboard.ai.TokenUsage.ZERO,
    /** true = 预算耗尽后由无工具收尾请求作答,UI 标注「基于部分信息」 */
    val partial: Boolean = false,
    val stopReason: StopReason? = null,
    /** 从本轮开始到首个 token 的耗时;全程没有收到 token 时为 null */
    val firstTokenMs: Long? = null,
    /** 本轮总耗时(不含等待用户确认的时间) */
    val totalMs: Long = 0L,
) {
    val elapsedMs: Long get() = steps.sumOf { it.elapsedMs }
    val promptTokens: Long get() = usage.promptTokens
    val completionTokens: Long get() = usage.completionTokens
    /** 模型调用重试次数(从头重试 + 断流续写) */
    val retries: Int get() = steps.count { it.kind == AgentTraceStep.KIND_RETRY }
    /** 因上下文超长收紧窗口重发的次数 */
    val contextShrinks: Int get() = steps.count { it.kind == AgentTraceStep.KIND_CONTEXT }
    val llmCalls: Int get() = steps.count { it.kind == AgentTraceStep.KIND_LLM }
    /** 资料命中指令样规则后交给模型复核的次数 */
    val judgeCalls: Int get() = steps.count { it.kind == AgentTraceStep.KIND_JUDGE }
    val toolCalls: Int get() = steps.count { it.isTool }

    companion object {
        val EMPTY = AgentRunSummary()
    }
}

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

    /**
     * 模型调用遇到可重试错误(超时/限流/5xx/偶发网络失败),即将在 [delayMs] 后发起第 [attempt] 次重试;
     * 已经输出过文字时这次重试是续写(保留已上屏内容,从中断处接着写)。
     * 面板据此显示"网络不稳,重试中…"并把草稿灰显;[kind] 为 HTTP 状态码字符串、"TIMEOUT" 或 "NETWORK"。
     */
    data class Retrying(val attempt: Int, val delayMs: Long, val kind: String) : AgentEvent()

    data class StepStarted(val call: ToolCall, val step: Int) : AgentEvent()

    /** [flagged] = 这条资料已被隔离(带凭据、超长,或指令样文字且模型复核没有放行),模型拿不到原文 */
    data class StepFinished(val call: ToolCall, val result: ToolResult, val step: Int, val flagged: Boolean = false) : AgentEvent()

    /** 确认条出现,agent 挂起等待(不超时、不自动放行) */
    data class ConfirmationRequired(val request: ConfirmationRequest) : AgentEvent()

    data class Finished(
        val content: String,
        val citations: List<Citation>,
        val sessionId: Long,
        val profileSuggestion: ProfileSuggestion? = null,
        val summary: AgentRunSummary = AgentRunSummary.EMPTY,
    ) : AgentEvent()

    /** 有可展示的部分结果，但证据或步骤不足以声称任务完整完成。 */
    data class Incomplete(
        val content: String,
        val citations: List<Citation>,
        val reason: String,
        val sessionId: Long,
        val summary: AgentRunSummary = AgentRunSummary.EMPTY,
        /** 收尾失败时已流出的最佳草稿(与 [content] 相同),避免交付空结果 */
        val bestDraft: String? = null,
        val stopReason: StopReason? = null,
    ) : AgentEvent()

    data class Failed(val error: AgentError, val sessionId: Long, val summary: AgentRunSummary = AgentRunSummary.EMPTY) : AgentEvent()

    /** 用户取消(已生成部分保留,标注已停止) */
    data class Stopped(val sessionId: Long, val summary: AgentRunSummary = AgentRunSummary.EMPTY) : AgentEvent()
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
