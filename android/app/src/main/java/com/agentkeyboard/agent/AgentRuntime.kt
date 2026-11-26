package com.agentkeyboard.agent

import com.agentkeyboard.ai.EngineClient
import com.agentkeyboard.ai.EngineMessage
import com.agentkeyboard.ai.EngineToolCall
import com.agentkeyboard.ai.ToolCallResult
import com.agentkeyboard.agent.permission.PermissionGate
import com.agentkeyboard.data.ErrorClassifier
import com.agentkeyboard.data.LlmConfig
import com.agentkeyboard.data.db.AgentMessageDao
import com.agentkeyboard.data.db.AgentSessionDao
import com.agentkeyboard.data.db.AgentMessageEntity
import com.agentkeyboard.data.db.AgentSessionEntity
import com.agentkeyboard.data.db.MessageRoles
import com.agentkeyboard.data.db.RetentionPolicy
import com.agentkeyboard.data.db.SessionStatus
import com.agentkeyboard.data.model.ModelWindows
import com.agentkeyboard.tools.AgentToolRegistry
import com.agentkeyboard.tools.Citation
import com.agentkeyboard.tools.InjectionScanner
import com.agentkeyboard.tools.ToolCall
import com.agentkeyboard.tools.ToolErrorCodes
import com.agentkeyboard.tools.ToolErrorHints
import com.agentkeyboard.tools.ToolIds
import com.agentkeyboard.tools.ToolResult
import com.agentkeyboard.tools.ToolResultWrapper
import com.agentkeyboard.tools.TOOL_USER_DENIED
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** LLM 调用函数抽象(M1-T6 改流式:默认 EngineClient.streamWithTools;单测注入 fake 忽略 onDelta) */
typealias LlmCallFn = suspend (
    baseUrl: String, apiKey: String, model: String,
    messages: List<EngineMessage>, tools: List<com.agentkeyboard.tools.ToolSchema>,
    handle: com.agentkeyboard.ai.StreamingHandle, onDelta: suspend (String) -> Unit,
) -> ToolCallResult

private class LlmCallDeniedException : Exception()

/** 流在已经输出文字后中断且没能续写:[draft] 是已上屏的最佳草稿,交给调用方作为 bestDraft 交付 */
private class StreamInterrupted(
    val draft: String,
    /** true = 本轮时间预算耗尽导致的中断 */
    val budgetExhausted: Boolean,
    cause: Exception,
) : Exception(cause)

/**
 * 单一 Turn/Step 运行时：每次模型请求为一步，工具由应用白名单选择并经过
 * AgentToolRegistry；步数/时间/token 预算耗尽时追加一次禁用工具的收尾请求，
 * 来源拒绝或工具策略违规以 INCOMPLETE 结束。
 */
class AgentRuntime(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val sessionDao: AgentSessionDao,
    private val messageDao: AgentMessageDao,
    private val gate: com.agentkeyboard.agent.permission.PermissionGate,
    private val registry: AgentToolRegistry,
    private val llmConfigProvider: suspend () -> LlmConfig?,
    private val llmCall: LlmCallFn = { baseUrl, apiKey, model, messages, tools, handle, onDelta ->
        EngineClient.streamWithTools(baseUrl, apiKey, model, messages, tools, handle, onDelta)
    },
    private val clock: () -> Long = System::currentTimeMillis,
    /** 耗时测量用单调时钟:系统时间被用户/NTP 校正时 currentTimeMillis 会回拨,产生负的 elapsedMs */
    private val monotonicClock: () -> Long = System::nanoTime,
    private val toolMaxSteps: Int = DEFAULT_TOOL_STEPS,
    /** 可注入共享事件流(工厂场景:PermissionGate 的确认条事件与 engine 事件同流) */
    eventsFlow: MutableSharedFlow<AgentEvent>? = null,
    /** 重试退避序列(ms):attempt 0/1 → 1s/2s(指数);单测可注入常数 0 */
    private val backoffMs: (Int) -> Long = { attempt -> (1L shl attempt) * 1000L },
    /** 退避等待函数(可注入记录调用时序,单测「退避时序」) */
    private val retryDelay: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    /**
     * G12:任务开始前的工具同步钩子(生产注入 McpToolBridge.syncInto)。
     * 同步失败不阻塞任务——MCP 工具本轮缺席,其余能力照常,错误在设置页可见。
     */
    private val beforeRun: suspend () -> Unit = {},
    /** G5:任务开始时的用户画像(user.md + 显式记忆),注入系统提示;默认空画像(单测不受影响) */
    private val profileProvider: suspend (Task) -> AgentProfile = { AgentProfile.EMPTY },
    private val protectedConfigProvider: suspend () -> ProtectedConfig = { ProtectedConfig() },
    /**
     * 资料命中指令样规则后的模型复核([ContentJudge])。null = 不复核,规则命中即隔离该条资料。
     * 生产注入;单测用脚本控制结论。
     */
    private val contentJudge: ContentJudgeCall? = null,
) : AgentEngine {

    companion object {
        /**
         * 带工具任务的实际步数上限(硬上限 [MAX_STEPS_HARD_CAP])。
         * 4 步只够"检索一次 + 回答",多轮检索/多工具场景会以未完成结束;
         * 单轮快捷动作(singleTurn)仍只走 1 步,不受影响。
         */
        const val DEFAULT_TOOL_STEPS = 6
        /** 防止模型无进展地反复调工具导致费用失控 */
        const val MAX_STEPS_HARD_CAP = 12


        /**
         * 单次模型调用的重试上限:连接/DNS 失败、首 token 超时、429、5xx 各只重试 1 次,
         * 400/401/403/内容过滤不重试。最坏延迟由 RunBudget 的时间预算约束,
         * 退避等待放不进剩余预算时直接放弃重试。
         */
        const val MAX_RETRIES = 1
        /** 429 Retry-After 等待上限,防止服务端给出过长等待导致键盘长时间无响应 */
        const val MAX_RETRY_AFTER_MS = 10_000L
        /** 收尾请求至少需要的时间;剩余不足时不再尝试 */
        const val FINALIZE_MIN_MS = 3_000L

        /** 同一批次里可并发的工具调用数上限(免确认的调用才并发) */
        const val MAX_PARALLEL_TOOLS = 3

        /** 同一工具在一轮里失败达到这个次数,就从后续步骤的工具列表移除 */
        const val MAX_TOOL_FAILURES = 2

        /** 服务端报上下文超长但报文里没有上限时,窗口按此比例收紧 */
        const val CONTEXT_SHRINK_DIVISOR = 4

        /** 一轮里因上下文超长重发的最多次数 */
        const val MAX_CONTEXT_SHRINKS = 3
    }

    private val _events = eventsFlow ?: MutableSharedFlow<AgentEvent>(
        replay = 0, extraBufferCapacity = 128, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: SharedFlow<AgentEvent> = _events

    /**
     * 当前待决确认请求(P1-2 单槽可恢复):events 为 replay=0 的 SharedFlow,
     * 面板未 collect 时确认事件会丢;读此 StateFlow 重建确认条,确认后自动清空。
     */
    val pendingConfirmation: kotlinx.coroutines.flow.StateFlow<com.agentkeyboard.agent.ConfirmationRequest?>
        get() = gate.pendingConfirmation

    @Volatile
    private var job: Job? = null

    /** 每轮独立句柄，避免旧流 finally 清理新流的取消回调。 */
    @Volatile
    private var activeStream = com.agentkeyboard.ai.StreamingHandle()

    /** 进行中的模型复核请求句柄;停轮时和主请求一起取消 */
    @Volatile
    private var activeJudgeHandle: com.agentkeyboard.ai.StreamingHandle? = null
    private val runLock = Any()

    override fun start(task: Task): Job = start(
        buildRequest = {
            // 白名单在协程内(beforeRun 之后)才快照:G12 MCP 工具同步发生在 beforeRun,
            // 若在 start 入口快照,首轮任务永远看不到动态注册的 MCP 工具
            TaskRequest.create(task, registry.schemas().mapTo(linkedSetOf()) { it.id })
        },
        input = task,
    )

    fun start(request: TaskRequest): Job =
        start(buildRequest = { request }, input = request.task)

    private fun start(buildRequest: () -> TaskRequest, input: Task): Job {
        synchronized(runLock) {
            job?.cancel()
            // 先关旧流，再给新任务分配独立句柄，避免旧请求的 finally 清掉新请求回调。
            activeStream.cancel()
            activeJudgeHandle?.cancel()
            val stream = com.agentkeyboard.ai.StreamingHandle()
            activeStream = stream
            val terminalEventEmitted = AtomicBoolean(false)
            val nextJob = scope.launch(start = CoroutineStart.LAZY) {
                run(buildRequest, input, stream, terminalEventEmitted)
            }
            job = nextJob
            nextJob.invokeOnCompletion { cause ->
                if (
                    cause is CancellationException &&
                    terminalEventEmitted.compareAndSet(false, true)
                ) {
                    _events.tryEmit(AgentEvent.Stopped(input.sessionId ?: -1))
                }
            }
            nextJob.start()
            return nextJob
        }
    }

    override fun cancel() {
        synchronized(runLock) {
            activeStream.cancel()
            activeJudgeHandle?.cancel()
            job?.cancel()
        }
    }

    private suspend fun run(
        buildRequest: () -> TaskRequest,
        input: Task,
        stream: com.agentkeyboard.ai.StreamingHandle,
        terminalEventEmitted: AtomicBoolean,
    ) {
        val inputTexts = listOfNotNull(input.userText, input.contextBeforeText, input.contextAfterText, input.clipboardText)
        // 用户自己的话只看格式和凭据:对助手说什么由用户决定,讨论、翻译一句注入句式也很正常。
        // 输入框上下文和剪贴板是外部文本,不是用户对 Agent 说的话,带指令样文字照样拒收。
        fun inputProblem(text: String, ownWords: Boolean): String? = when {
            AgentContentSafety.malformedInput(text) -> AgentContentSafety.FORMAT
            AgentContentSafety.containsCredential(text) -> AgentContentSafety.SECRET
            !ownWords && InjectionScanner.isFlagged(text) -> AgentContentSafety.INJECTION
            else -> null
        }
        val inputReason = inputProblem(input.userText, ownWords = true)
            ?: listOfNotNull(input.contextBeforeText, input.contextAfterText, input.clipboardText)
                .firstNotNullOfOrNull { inputProblem(it, ownWords = false) }
        if (inputReason != null) {
            _events.emit(AgentEvent.Failed(AgentError("UNSAFE_INPUT", inputReason), input.sessionId ?: -1))
            return
        }
        val task = input
        var persistedSessionId: Long? = null
        var currentUserMessageId: Long? = null
        val unresolvedCalls = mutableListOf<EngineToolCall>()
            val traceSteps = mutableListOf<AgentTraceStep>()
            var runUsage = com.agentkeyboard.ai.TokenUsage.ZERO
        // 诊断用:本轮时间预算(配置缺失等早退路径上尚无预算)与首个 token 的时刻
        var deadlineForSummary: RunDeadline? = null
        var firstTokenNs: Long? = null
        /** 首个 token 之前等待用户确认的时长:首 token 耗时与总计一样只算机器时间 */
        var firstTokenWaitMs = 0L
        fun runSummary(partial: Boolean = false, stopReason: StopReason? = null): AgentRunSummary = AgentRunSummary(
            steps = traceSteps.toList(),
            usage = runUsage,
            partial = partial,
            stopReason = stopReason,
            firstTokenMs = deadlineForSummary?.let { d ->
                firstTokenNs?.let { (d.sinceStartMs(it) - firstTokenWaitMs).coerceAtLeast(0L) }
            },
            totalMs = deadlineForSummary?.elapsedMs() ?: 0L,
        )
        /** 终态事件统一从这里发出:先记一条诊断(只有分类、计数与耗时),再下发 */
        suspend fun terminate(event: AgentEvent) {
            diagnosticRecord(clock(), task.type, event)?.let(RunDiagnostics::record)
            _events.emit(event)
        }
        try {
            val cfg = llmConfigProvider()
            if (cfg == null) {
                terminate(AgentEvent.Failed(AgentError("NOT_CONFIGURED", "尚未配置模型,请到设置页配置"), -1))
                return
            }
            val protected = protectedConfigProvider()
            val safety = AgentContentSafety(
                identifiers = protected.identifiers + listOfNotNull(cfg.model) +
                    AgentContentSafety.endpointValues(cfg.baseUrl),
                secrets = protected.secrets + listOfNotNull(cfg.apiKey),
            )
            if (inputTexts.any(safety::hasSecret)) {
                terminate(AgentEvent.Failed(AgentError("UNSAFE_INPUT", AgentContentSafety.SECRET), task.sessionId ?: -1))
                return
            }
            safety.noteUserText(task.userText)
            try {
                beforeRun()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Status remains available in the settings page.
            }
            val request = try {
                buildRequest()
            } catch (_: IllegalArgumentException) {
                terminate(AgentEvent.Failed(AgentError("INVALID_TASK", ""), task.sessionId ?: -1))
                return
            }
            if (task.type == TaskType.KNOWLEDGE_ASK && (cfg.toolsAllowed == false || task.singleTurn)) {
                terminate(AgentEvent.Failed(AgentError("KNOWLEDGE_TOOLS_UNSUPPORTED", ""), -1))
                return
            }
            // 会话:复用或新建 + 保留策略滚动清理(插入时触发,FR-B9)
            val sessionId = task.sessionId ?: sessionDao.insert(
                AgentSessionEntity(taskType = task.type.name, status = SessionStatus.RUNNING, createdAt = clock())
            )
            persistedSessionId = sessionId
            // 先 touch 再清理:续问复用的旧会话若已超期/超量,先刷新 created_at 才不会被
            // cleanup 级联删除,否则本轮消息会落在外键已失活的会话上触发约束异常。
            sessionDao.touch(sessionId, SessionStatus.RUNNING, clock())
            sessionDao.cleanup(clock() - RetentionPolicy.SESSION_TTL_MS, RetentionPolicy.MAX_SESSIONS)
            _events.emit(AgentEvent.Started(sessionId))

            // 历史:恢复既有会话(多轮追问)或从零开始
            val previousRows = messageDao.bySession(sessionId)
            // 本会话此前用户说过的话:其中出现的配置值不算模型泄露(追问时回答里照常显示)
            previousRows.filter { it.role == MessageRoles.USER }.forEach { safety.noteUserText(it.content) }
            val history = sanitizePairs(loadHistoryInternal(previousRows, safety)).toMutableList()
            val currentTurnStart = history.size
            currentUserMessageId = messageDao.insert(
                AgentMessageEntity(
                    sessionId = sessionId, role = MessageRoles.USER,
                    content = task.userText, toolJson = null,
                    stepNo = 0, createdAt = clock(),
                )
            )
            history += EngineMessage(EngineClient.ROLE_USER, task.userText)

            // 画像文本会进系统提示:带凭据的不进;模型名这类标识不是秘密,用户自己写的照常用
            val savedProfile = profileProvider(task)
            val profile = AgentProfile(
                userMd = savedProfile.userMd.takeUnless { safety.hasSecret(it) || AgentContentSafety.rejectsInput(it) }.orEmpty(),
                memories = savedProfile.memories.filterNot { safety.hasSecret(it) || AgentContentSafety.rejectsInput(it) },
            )
            val systemPrompt = SystemPrompts.build(
                task.type, task.contextBeforeText, task.userText, profile,
            )
            val fullMessages = (listOf(EngineMessage(EngineClient.ROLE_SYSTEM, systemPrompt)) + history).toMutableList()
            val currentTurnStartFull = 1 + currentTurnStart

            val taint = com.agentkeyboard.agent.permission.TaintSnapshot(
                contextText = task.contextBeforeText.takeIf { it.isNotBlank() },
                clipboardText = task.clipboardText,
                profileEntries = listOf(profile.userMd) + profile.memories,
            )
            gate.beginTask()

            // 批次 3:上下文窗口按「服务端报告过的上限 → 模型设置 → 任务指定 → 内置表 → 保守默认」解析;
            // 服务端报上下文超长时在循环里收紧(shrinkContext)
            val modelId = modelName(cfg)
            var contextWindow = ModelWindows.resolve(cfg.contextWindow, task.contextWindowTokens, modelId)
            var contextShrinks = 0
            // cfg.toolsAllowed == false(fc=NO)→ 单轮降级:整个循环不带 tools;
            // M3 快捷动作:task.singleTurn = true 同样整轮不带 tools(单轮模板路径,一次直出)。
            val fcDisabled = cfg.toolsAllowed == false || task.singleTurn
            // 单轮模板路径固定 1 步;工具任务用可配置上限(受硬顶约束)
            // 批次 1:按任务类型取预算;构造参数显式覆盖时以其为准(受硬顶约束)。
            // 步数 = 模型请求次数,工具步数 N 时最多 N+1 次(最后一次作答)。
            val baseBudget = budgetFor(task.type)
            val budget = if (toolMaxSteps != DEFAULT_TOOL_STEPS) {
                baseBudget.copy(maxToolSteps = toolMaxSteps.coerceIn(0, MAX_STEPS_HARD_CAP))
            } else baseBudget
            // 时间预算从首个模型请求前起算;等待用户确认的时长不计入(见 RunDeadline)
            val deadline = RunDeadline(monotonicClock(), budget.wallClockMs, gate, monotonicClock)
            deadlineForSummary = deadline
            val stepLimit = if (task.singleTurn || fcDisabled || budget.maxToolSteps == 0) 1
                else (budget.maxToolSteps + 1).coerceIn(1, MAX_STEPS_HARD_CAP + 1)
            val canPropose = task.allowProfileSuggestion && task.type == TaskType.KNOWLEDGE_ASK && !fcDisabled && stepLimit > 1
            val tools = if (fcDisabled) emptyList() else registry.schemas().filter { schema ->
                schema.id in request.allowedToolIds &&
                    (!schema.id.startsWith(com.agentkeyboard.mcp.McpTool.ID_PREFIX) ||
                        (!safety.hasSecret(schema.id) && !safety.looksLikeInjection(schema.description) &&
                            safety.schemaProblem(schema.schemaJson) == null))
            }
            // 本轮提供给模型的工具:同一工具失败达到上限后从中移除(见 feedBack)
            var offeredTools = tools
            val offeredIds = tools.mapTo(hashSetOf()) { it.id }

            val citations = mutableListOf<Citation>()
            var finalText: String? = null
            var suggestion: ProfileSuggestion? = null
            var incompleteReason: String? = null
            /** 循环提前退出的原因;非空时随后发收尾请求 */
            var stopReason: StopReason? = null
            /** 流在输出文字后中断且没能续写:携带已上屏的草稿 */
            var interrupted: StreamInterrupted? = null
            /** 本轮已拿到的工具结果数(超时后据此判断是否值得发收尾请求) */
            var toolResults = 0
            var hadToolFailure = false
            var knowledgeSearchUsed = false
            var knowledgeCitationCount = 0
            /** 检索类工具本轮确实执行过(回答前自检用) */
            var searchRan = false
            /** 同一工具本轮的执行失败次数 */
            val toolFailures = mutableMapOf<String, Int>()
            /** 本轮因连续失败被移出工具列表的工具 */
            val disabledInRun = mutableSetOf<String>()
            var step = 0
            // 首 token 时刻:真实流由客户端在首个数据块回调;单测 fake 不回调,由首个文字增量补记
            stream.onFirstChunk = {
                if (firstTokenNs == null) {
                    firstTokenNs = monotonicClock()
                    firstTokenWaitMs = gate.userWaitMs
                }
            }
            /** 批次内并发的上限(Semaphore):免确认的调用也不会无限制同时出网 */
            val toolSlots = Semaphore(MAX_PARALLEL_TOOLS)

            /** 工具在本轮可用:已提供给模型且仍在注册表里 */
            fun isOffered(call: EngineToolCall): Boolean = call.name in offeredIds && registry.get(call.name) != null

            /** 未注册、本轮未开放或本轮已停用的工具:回喂结构化错误,不执行 */
            fun unavailableResult(call: EngineToolCall): ToolResult =
                if (call.name in disabledInRun) ToolResult.error("该工具本轮已停用", ToolErrorCodes.DISABLED)
                else ToolResult.error("此工具未注册或不在本轮允许范围内", ToolErrorCodes.NOT_FOUND)

            /** 这次调用是否可能弹确认条(可能弹的必须单独按序执行) */
            suspend fun needsConfirmation(call: EngineToolCall): Boolean {
                val tool = registry.get(call.name) ?: return false
                if (!tool.isOutbound) return false
                return gate.mayPrompt(call.name, tool.paramsText(call.argumentsJson), taint)
            }

            /**
             * 批次 4:把一批调用按原顺序切段。不会弹确认条的相邻调用成组并发;
             * 可能弹确认条的出网工具、以及写输入框的 insert_text 各自单独执行——
             * 前一个被用户拒绝时,后面的调用还没发出。
             */
            suspend fun planSegments(calls: List<EngineToolCall>): List<ToolSegment> {
                val segments = mutableListOf<ToolSegment>()
                var group = mutableListOf<EngineToolCall>()
                for (call in calls) {
                    if (call.name == ToolIds.INSERT_TEXT || needsConfirmation(call)) {
                        if (group.isNotEmpty()) {
                            segments += ToolSegment.Group(group)
                            group = mutableListOf()
                        }
                        segments += ToolSegment.Single(call)
                    } else {
                        group += call
                    }
                }
                if (group.isNotEmpty()) segments += ToolSegment.Group(group)
                return segments
            }

            /** 本步里参数不可外发的调用 id(见 runTool 的 blockedArgsResult) */
            var blockedArgsIds: Set<String> = emptySet()

            /** 参数带凭据或用户没提过的配置值:不执行,把原因回喂,模型可以去掉后重试 */
            fun blockedArgsResult(): ToolResult = ToolResult.error(
                "参数里带有密钥或用户没有提到的配置信息，未执行", ToolErrorCodes.BAD_PARAMS, retryable = true,
                "参数里带有密钥或用户没有提到的配置信息，不能发给工具：去掉这些内容后重新调用。",
            )

            /** 执行一次调用(已过截止、参数不可外发或工具不可用时直接给出原因);[ran] = 是否真的交给了注册表 */
            suspend fun runTool(call: EngineToolCall): ToolExecution = toolSlots.withPermit {
                when {
                    deadline.remainingMs() <= 0L -> ToolExecution(
                        // 时间预算已用完:余下工具不再执行,回喂超时结果,避免整轮被拖过截止点
                        ToolResult.error("时间预算已用完，未执行", ToolErrorCodes.TIMEOUT, retryable = true), ran = false,
                    )
                    call.id in blockedArgsIds -> ToolExecution(blockedArgsResult(), ran = false)
                    isOffered(call) -> ToolExecution(
                        registry.execute(ToolCall(call.id, call.name, call.argumentsJson), taint, sessionId), ran = true,
                    )
                    else -> ToolExecution(unavailableResult(call), ran = false)
                }
            }

            /** 模型复核:true = 是注入;false = 不是;null = 无法复核 */
            suspend fun judgeSource(excerpt: String): Boolean? {
                val judge = contentJudge ?: return null
                val handle = com.agentkeyboard.ai.StreamingHandle()
                handle.requestTimeout = EngineClient.judgeRequestTimeout()
                activeJudgeHandle = handle
                val reply = try {
                    withTimeoutOrNull(ContentJudge.TIMEOUT_MS) { judge(cfg, ContentJudge.messages(excerpt), handle) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                } finally {
                    activeJudgeHandle = null
                }
                return ContentJudge.parse(reply)
            }

            /**
             * 资料不可采用的原因(凭据、超长,或指令样文字且复核没有放行);null = 可采用。
             * 规则命中只是触发:分不清「在对助手下指令」和「在讨论、引用这种句式」的是规则,不是模型,
             * 所以命中处前后的片段交给模型复核([ContentJudge])。复核不可用(未配置、超时、没给结论)按命中处理。
             */
            suspend fun sourceProblem(raw: String): String? {
                val problem = safety.toolResultProblem(raw) ?: return null
                if (problem != AgentContentSafety.INJECTION) return problem
                val excerpt = InjectionScanner.suspiciousExcerpt(raw) ?: return problem
                val startedAtNs = monotonicClock()
                val verdict = judgeSource(excerpt)
                traceSteps += AgentTraceStep(
                    step = step, kind = AgentTraceStep.KIND_JUDGE,
                    elapsedMs = (monotonicClock() - startedAtNs) / 1_000_000,
                    ok = verdict == false, errorCode = if (verdict == false) null else ToolErrorCodes.UNSAFE_CONTENT,
                )
                return if (verdict == false) null else problem
            }

            /**
             * 回喂一条工具结果:安全检查 → 落盘 → trace → 事件 → 加入上下文。每条调用都必须走到这里
             * (没有执行的要给出原因),否则 assistant.toolCalls 没有对应的回复。
             * [execution] 为 null = 这条调用没有轮到执行;没开始过的调用也就没有卡片,不发 StepFinished。
             * 返回 true = 本轮就此停止(用户拒绝了出网请求)。
             */
            suspend fun feedBack(call: EngineToolCall, execution: ToolExecution?, startedAtNs: Long): Boolean {
                if (!currentCoroutineContext().isActive) throw CancellationException("engine stopped")
                val offered = isOffered(call)
                if (offered && call.name == ToolIds.KB_SEARCH) knowledgeSearchUsed = true
                // 批次里排在用户拒绝的调用之后:没有执行
                val received = execution?.result ?: ToolResult.error("前序步骤已停止，未执行")
                // 被隔离的资料只影响这一条:模型拿到结构化错误,可以换来源或基于其余资料作答,本轮继续
                val flagged = sourceProblem(received.raw) != null
                var toolResult = when {
                    flagged -> ToolResult.error("资料包含疑似不安全内容，已拦截", ToolErrorCodes.UNSAFE_CONTENT)
                    received.ok -> received.copy(
                        wrapped = ToolResultWrapper.wrap(received.raw),
                        citations = safety.citations(received.citations),
                    )
                    received.raw == TOOL_USER_DENIED -> received
                    else -> ToolResult.error(received.raw, received.errorCode ?: ToolErrorCodes.INTERNAL, received.retryable, received.hint)
                }
                val denied = toolResult.raw == TOOL_USER_DENIED
                // 同一工具本轮失败达到上限:从后续步骤的工具列表移除,并在回喂里告诉模型
                if (!toolResult.ok && execution?.ran == true && !denied) {
                    val failures = (toolFailures[call.name] ?: 0) + 1
                    toolFailures[call.name] = failures
                    if (failures >= MAX_TOOL_FAILURES && offeredIds.remove(call.name)) {
                        offeredTools = offeredTools.filterNot { it.id == call.name }
                        disabledInRun += call.name
                        toolResult = ToolResult.error(
                            toolResult.raw, toolResult.errorCode ?: ToolErrorCodes.INTERNAL, toolResult.retryable,
                            ToolErrorHints.disabledInRun(failures),
                        )
                    }
                }
                if (toolResult.ok) {
                    citations += toolResult.citations
                    if (call.name == ToolIds.KB_SEARCH) {
                        knowledgeCitationCount += toolResult.citations.size
                    }
                }
                if (!toolResult.ok) hadToolFailure = true
                if (execution?.ran == true && call.name in SEARCH_TOOL_IDS) searchRan = true
                // 参数不可外发的调用:落盘和卡片上的参数也遮盖
                val shownCall =
                    if (call.id in blockedArgsIds) call.copy(argumentsJson = safety.redact(call.argumentsJson)) else call
                persistToolResult(sessionId, shownCall, toolResult, flagged, step)
                toolResults++
                traceSteps += AgentTraceStep(
                    step = step, kind = if (execution?.ran == true) call.name else AgentTraceStep.KIND_REJECTED,
                    elapsedMs = (monotonicClock() - startedAtNs) / 1_000_000,
                    ok = toolResult.ok, errorCode = toolResult.errorCode,
                )
                unresolvedCalls.remove(call)
                fullMessages += EngineMessage(
                    EngineClient.ROLE_TOOL,
                    toolResult.wrapped,
                    toolCallId = call.id,
                    toolName = call.name,
                )
                if (execution != null) {
                    _events.emit(
                        AgentEvent.StepFinished(
                            ToolCall(toolCallId = shownCall.id, toolId = shownCall.name, argsJson = shownCall.argumentsJson),
                            toolResult, step, flagged,
                        ),
                    )
                }

                return if (denied) {
                    incompleteReason = "你拒绝了本次出网请求，任务未完成"
                    true
                } else {
                    false
                }
            }

            /** 名称无效的工具调用:记一笔 trace,并告诉模型这些调用没有执行 */
            fun noteMalformed(count: Int) {
                traceSteps += AgentTraceStep(
                    step = step, kind = AgentTraceStep.KIND_REJECTED, elapsedMs = 0L,
                    ok = false, errorCode = ToolErrorCodes.NOT_FOUND,
                )
                fullMessages += EngineMessage(
                    EngineClient.ROLE_SYSTEM,
                    "有 $count 个工具调用的名称无效，未执行。只能调用本轮提供的工具。",
                )
            }

            /**
             * 服务端报「上下文超长」时收紧本轮窗口,让下一步按更小的预算折叠后重发。
             * 返回 false = 不是这类错误,或已经没有可收紧的余地。
             */
            fun shrinkContext(e: Exception, startedAtNs: Long): Boolean {
                if (contextShrinks >= MAX_CONTEXT_SHRINKS || contextWindow <= ModelWindows.MIN_WINDOW_TOKENS) return false
                if (!ErrorClassifier.isContextLengthExceeded(e)) return false
                // 报文里带真实上限就直接采用(并记住),否则按比例收紧
                val reported = ErrorClassifier.contextLimit(e)
                contextWindow = minOf(contextWindow - 1, maxOf(reported ?: contextWindow / CONTEXT_SHRINK_DIVISOR, ModelWindows.MIN_WINDOW_TOKENS))
                contextShrinks++
                if (reported != null) ModelWindows.learn(modelId, reported)
                traceSteps += AgentTraceStep(
                    step = step, kind = AgentTraceStep.KIND_CONTEXT,
                    elapsedMs = (monotonicClock() - startedAtNs) / 1_000_000,
                    ok = false, errorKind = "CONTEXT_LENGTH",
                    httpStatus = ErrorClassifier.serviceError(e)?.httpStatus,
                )
                return true
            }
            while (step < stepLimit) {
                // 时间预算剩不到 25%,或累计输入 token 超限时退出循环,交给收尾请求
                if (deadline.remainingMs() < budget.wallClockMs / 4) {
                    stopReason = StopReason.TIME_BUDGET
                    break
                }
                if (runUsage.promptTokens > budget.maxPromptTokens) {
                    stopReason = StopReason.TOKEN_BUDGET
                    break
                }
                step++
                _events.emit(AgentEvent.Thinking(step))
                val folded = sanitizePairs(ContextCompactor.compact(
                    fullMessages, ContextCompactor.foldBudget(contextWindow), currentTurnStartFull
                ))
                val llmStartedAt = monotonicClock()
                val result: ToolCallResult? = try {
                    callWithRetry(
                        cfg, folded, sessionId = sessionId,
                        tools = if (canPropose && step == 1) offeredTools + ProfileSuggestion.schema else offeredTools,
                        stream = stream,
                        includesProfile = profile.userMd.isNotBlank() || profile.memories.any(String::isNotBlank),
                        budget = budget,
                        deadline = deadline,
                        onRetry = { attempt, e, startedAt ->
                            traceSteps += AgentTraceStep(
                                step = step, kind = AgentTraceStep.KIND_RETRY,
                                elapsedMs = (monotonicClock() - startedAt) / 1_000_000,
                                ok = false,
                                errorKind = ErrorClassifier.kind(e).name,
                                httpStatus = ErrorClassifier.extractStatusCode(e),
                                attempt = attempt,
                            )
                        },
                    )
                } catch (e: StreamInterrupted) {
                    interrupted = e
                    null
                } catch (e: Exception) {
                    // 服务端报上下文超长:收紧窗口后把这一步重发一遍(不计步数)
                    if (e !is CancellationException && e !is LlmCallDeniedException && shrinkContext(e, llmStartedAt)) {
                        step--
                        continue
                    }
                    // 已拿到工具结果后的超时:不直接 Failed,转入收尾请求让模型用已有资料作答
                    val finalizeInstead =
                        e !is CancellationException && e !is LlmCallDeniedException &&
                            toolResults > 0 && ErrorClassifier.isTimedOut(e) &&
                            deadline.remainingMs() >= FINALIZE_MIN_MS
                    if (finalizeInstead) {
                        stopReason = StopReason.TIME_BUDGET
                        null
                    } else {
                        throw e
                    }
                }
                if (result == null) break
                runUsage += result.usage
                traceSteps += AgentTraceStep(
                    step = step, kind = AgentTraceStep.KIND_LLM,
                    elapsedMs = (monotonicClock() - llmStartedAt) / 1_000_000, usage = result.usage,
                )
                when (result) {                    is ToolCallResult.Content -> {
                        val answerProblem = safety.renderedAnswerProblem(result.text)
                        if (answerProblem != null) {
                            // 草稿已在屏上,拦截后必须撤回,不能留下未通过检查的文字
                            _events.emit(AgentEvent.ContentReset)
                            throw UnsafeAgentOutput(answerProblem)
                        }
                        finalText = safety.displayAnswer(result.text)
                        // 翻译/脱敏后可能与草稿不同(如命中的配置值被遮盖),以审核后的文本为准重新展示
                        if (finalText != result.text) _events.emit(AgentEvent.ContentDelta(finalText))
                        persistAssistant(sessionId, step, finalText, null, citations)
                        // 长度上限/内容过滤/分片丢失:保留已生成内容,但不能声称任务完整完成
                        result.incompleteReason?.let {
                            incompleteReason = it
                            stopReason = when {
                                result.truncatedByLength -> StopReason.MODEL_LENGTH
                                result.filtered -> StopReason.FILTERED
                                else -> null
                            }
                        }
                        break
                    }
                    is ToolCallResult.ToolCalls -> {
                        // 本轮转为工具调用:模型可能先流出了"我来查一下"之类的草稿,随后并不作为回答保留
                        _events.emit(AgentEvent.ContentReset)
                        // 模型重复返回同一 toolCallId 视为一次调用:双份 tool 回复会破坏
                        // 历史配对(续问时同 id 两条 tool 消息会被 API 拒绝)。
                        val returned = result.calls.distinctBy { it.id }
                        // 回答草稿的硬性条件不变:超长,或把数据边界声明原文带了出来,整轮拦截
                        safety.answerProblem(result.content)?.let { throw UnsafeAgentOutput(it) }
                        // id 或工具名不合规的调用不回显、不执行:回显等于把模型的乱码原样发回服务端
                        val named = returned.filter { SAFE_CALL_ID.matches(it.id) && SAFE_TOOL_NAME.matches(it.name) }
                        val malformedCount = returned.size - named.size
                        val proposals = named.filter { it.name == ProfileSuggestion.ID }
                        if (canPropose && step == 1) {
                            suggestion = proposals.singleOrNull()?.let {
                                ProfileSuggestion.parse(it.argumentsJson, task.userText)
                            }?.takeUnless { it.isAlreadyIn(profile) }
                        }
                        // Keep proposal arguments out of stored history and future model requests.
                        val calls = named.filterNot { it.name == ProfileSuggestion.ID }
                        if (calls.isEmpty() && (proposals.isNotEmpty() || malformedCount > 0)) {
                            // A proposal alone is not an answer; use the next normal step without that schema.
                            if (malformedCount > 0) noteMalformed(malformedCount)
                            continue
                        }
                        // 参数带凭据或用户没提过的配置值的调用不执行(runTool),其余照常;落盘时遮盖参数里的这些值
                        blockedArgsIds = calls.filter { safety.argumentsProblem(it.argumentsJson) != null }.mapTo(hashSetOf()) { it.id }
                        val storedCalls = calls.map {
                            if (it.id in blockedArgsIds) it.copy(argumentsJson = safety.redact(it.argumentsJson)) else it
                        }
                        val intermediate = safety.redact(result.content)
                        persistAssistant(sessionId, step, intermediate, storedCalls)
                        fullMessages += EngineMessage(
                            EngineClient.ROLE_ASSISTANT, intermediate, toolCalls = calls
                        )
                        unresolvedCalls.addAll(calls)
                        // 按原顺序分段执行:免确认的相邻调用成组并发,可能弹确认条的单独执行
                        val callById = calls.associateBy { it.id }
                        val starts = mutableMapOf<String, Long>()
                        val executions = mutableMapOf<String, ToolExecution>()
                        val fedBack = mutableSetOf<String>()
                        var stopped = false
                        for (segment in planSegments(calls)) {
                            val batch = segment.calls
                            batch.forEach {
                                starts[it.id] = monotonicClock()
                                _events.emit(AgentEvent.StepStarted(ToolCall(it.id, it.name, it.argumentsJson), step))
                            }
                            val ran: List<Pair<String, ToolExecution>> = if (batch.size > 1) {
                                coroutineScope {
                                    batch.map { call -> async { call.id to runTool(call) } }.awaitAll()
                                }
                            } else {
                                batch.map { it.id to runTool(it) }
                            }
                            if (!currentCoroutineContext().isActive) throw CancellationException("engine stopped")
                            for ((id, execution) in ran) {
                                executions[id] = execution
                                fedBack += id
                                if (feedBack(callById.getValue(id), execution, starts.getValue(id))) {
                                    stopped = true
                                    break
                                }
                            }
                            if (stopped) break
                        }
                        // 还没回喂的调用:已经执行过的如实回喂;停止之后没执行的说明「未执行」。
                        // 每条调用都必须有回复,否则 assistant.toolCalls 没有对应的 tool 消息
                        for (call in calls) {
                            if (call.id !in fedBack) {
                                feedBack(call, executions[call.id], starts[call.id] ?: monotonicClock())
                            }
                        }
                        if (malformedCount > 0) noteMalformed(malformedCount)
                        if (incompleteReason != null) break
                    }
                }
            }
            // 循环退出时仍无答案:断流则已上屏的草稿就是最佳结果,不再发请求;否则发一次禁用工具的收尾请求
            var partial = false
            val breakInfo = interrupted
            if (breakInfo != null) {
                val draft = breakInfo.draft
                safety.renderedAnswerProblem(draft)?.let { problem ->
                    // 草稿已在屏上,拦截后必须撤回,不能留下未通过检查的文字
                    _events.emit(AgentEvent.ContentReset)
                    throw UnsafeAgentOutput(problem)
                }
                finalText = safety.displayAnswer(draft)
                if (finalText != draft) _events.emit(AgentEvent.ContentDelta(finalText))
                persistAssistant(sessionId, step, finalText, null, citations)
                incompleteReason = if (breakInfo.budgetExhausted) "回答超时中断，已保留已生成的部分" else "回答传输中断，已保留已生成的部分"
                if (breakInfo.budgetExhausted) stopReason = StopReason.TIME_BUDGET
            }
            if (breakInfo == null && incompleteReason == null && finalText == null) {
                stopReason = stopReason ?: StopReason.STEP_LIMIT
                var finalizeDraft: String? = null
                val finalizeStartedAt = monotonicClock()
                var finalized: ToolCallResult? = null
                // 收尾请求被报上下文超长:收紧窗口后再试(工具结果全文是本轮的主要内容,折叠后通常放得下)
                var finalizeShrinks = 0
                while (deadline.remainingMs() >= FINALIZE_MIN_MS) {
                    val attemptStartedAt = monotonicClock()
                    try {
                        val prompt = fullMessages + EngineMessage(EngineClient.ROLE_SYSTEM, FINALIZE_PROMPT)
                        finalized = callWithRetry(
                            cfg,
                            sanitizePairs(ContextCompactor.compact(prompt, ContextCompactor.foldBudget(contextWindow), currentTurnStartFull)),
                            sessionId = sessionId, tools = emptyList(), stream = stream,
                            budget = budget, deadline = deadline, maxRetries = 0,
                        )
                        break
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: LlmCallDeniedException) {
                        throw e
                    } catch (e: StreamInterrupted) {
                        finalizeDraft = e.draft
                        break
                    } catch (e: Exception) {
                        if (finalizeShrinks < MAX_CONTEXT_SHRINKS && shrinkContext(e, attemptStartedAt)) {
                            finalizeShrinks++
                            continue
                        }
                        break
                    }
                }
                if (finalized is ToolCallResult.Content && finalized.text.isNotBlank() &&
                    !safety.unsafeRenderedAnswer(finalized.text)
                ) {
                    runUsage += finalized.usage
                    traceSteps += AgentTraceStep(
                        step = step + 1, kind = AgentTraceStep.KIND_LLM,
                        elapsedMs = (monotonicClock() - finalizeStartedAt) / 1_000_000, usage = finalized.usage,
                    )
                    val answer = safety.displayAnswer(finalized.text)
                    if (answer != finalized.text) _events.emit(AgentEvent.ContentDelta(answer))
                    persistAssistant(sessionId, step + 1, answer, null, citations)
                    finalText = answer
                    partial = true
                } else {
                    val draft = finalizeDraft
                    if (draft.isNullOrBlank() || safety.unsafeRenderedAnswer(draft)) {
                        if (finalized != null || draft != null) _events.emit(AgentEvent.ContentReset)
                    } else {
                        // 收尾请求自己也断了:已流出的文字作为草稿交付,不给空结果
                        finalText = safety.displayAnswer(draft)
                        if (finalText != draft) _events.emit(AgentEvent.ContentDelta(finalText))
                        persistAssistant(sessionId, step + 1, finalText, null, citations)
                    }
                    incompleteReason = when (stopReason) {
                        StopReason.TIME_BUDGET -> "已达到本轮时间预算，任务未完成"
                        StopReason.TOKEN_BUDGET -> "已达到本轮 token 预算，任务未完成"
                        else -> "已达到本轮 $stepLimit 步上限，任务未完成"
                    }
                }
            }
            if (
                incompleteReason == null &&
                task.type == TaskType.KNOWLEDGE_ASK &&
                knowledgeSearchUsed &&
                knowledgeCitationCount == 0
            ) {
                incompleteReason = "知识库没有返回可引用的资料证据，回答未完成核验"
            }
            if (incompleteReason == null && hadToolFailure && citations.isEmpty()) {
                incompleteReason = "检索或搜索步骤失败，回答未能完成来源核验"
            }
            // 回答前自检(查证类):检索确实执行过却没有任何可引用的来源,不能算完成核验
            if (incompleteReason == null && task.type == TaskType.VERIFY && searchRan && citations.isEmpty()) {
                incompleteReason = "没有找到可引用的来源，未能完成核验"
            }
            val uniqueCitations = citations.distinctBy { "${it.url}\u0001${it.title}" }
            if (incompleteReason != null) {
                persistRunNote(sessionId, incompleteReason, step)
                sessionDao.updateStatus(sessionId, SessionStatus.INCOMPLETE)
                terminate(
                    AgentEvent.Incomplete(
                        content = finalText.orEmpty(),
                        citations = uniqueCitations,
                        reason = incompleteReason,
                        sessionId = sessionId,
                        summary = runSummary(stopReason = stopReason),
                        bestDraft = finalText?.takeIf { it.isNotBlank() },
                        stopReason = stopReason,
                    ),
                )
            } else {
                sessionDao.updateStatus(sessionId, SessionStatus.DONE)
                terminate(
                    AgentEvent.Finished(
                        finalText.orEmpty(), uniqueCitations, sessionId, suggestion,
                        summary = runSummary(partial = partial, stopReason = stopReason),
                    ),
                )
            }
        } catch (_: LlmCallDeniedException) {
            withContext(NonCancellable) {
                currentUserMessageId?.let { messageDao.deleteById(it) }
                persistedSessionId?.let { sessionDao.updateStatus(it, SessionStatus.STOPPED) }
            }
            terminate(AgentEvent.Failed(AgentError("LLM_DENIED", ""), persistedSessionId ?: -1, runSummary()))
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                persistedSessionId?.let { sessionId ->
                    unresolvedCalls.toList().forEach { call ->
                        persistToolResult(
                            sessionId, call, ToolResult.error("已取消，步骤没有结果"),
                            flagged = false, stepNo = -2,
                        )
                        unresolvedCalls.remove(call)
                    }
                    messageDao.insert(
                        AgentMessageEntity(
                            sessionId = sessionId, role = MessageRoles.SYSTEM,
                            content = "已停止(用户取消)", toolJson = null,
                            stepNo = -1, createdAt = clock(),
                        )
                    )
                    sessionDao.updateStatus(sessionId, SessionStatus.STOPPED)
                }
                terminalEventEmitted.set(true)
                terminate(AgentEvent.Stopped(persistedSessionId ?: task.sessionId ?: -1, runSummary()))
            }
            throw e
        } catch (e: Exception) {
            withContext(NonCancellable) {
                persistedSessionId?.let { sessionId ->
                    unresolvedCalls.toList().forEach { call ->
                        persistToolResult(
                            sessionId, call, ToolResult.error("运行失败，步骤没有结果"),
                            flagged = false, stepNo = -2,
                        )
                        unresolvedCalls.remove(call)
                    }
                    sessionDao.updateStatus(sessionId, SessionStatus.FAILED)
                }
            }
            val error = if (e is UnsafeAgentOutput) AgentError("UNSAFE_OUTPUT", e.reason) else toAgentError(e)
            terminate(AgentEvent.Failed(error, persistedSessionId ?: -1, runSummary()))
        }
    }

    /** 测试可见:历史重建(含孤儿过滤) */
    internal suspend fun loadHistoryForTest(sessionId: Long): List<EngineMessage> =
        loadHistoryInternal(messageDao.bySession(sessionId))

    private fun loadHistoryInternal(rows: List<AgentMessageEntity>, safety: AgentContentSafety? = null): List<EngineMessage> = run {
        /**
         * 这一行不能回灌给模型。判据与写入时一致、按角色分:用户自己的话只看凭据(带模型名、资料库名
         * 不算问题);资料看凭据和指令样规则;模型的回答看凭据、长度和是否带出数据边界声明。
         */
        fun blocked(text: String, role: String): Boolean = safety?.let {
            when (role) {
                MessageRoles.TOOL -> it.toolResultProblem(text) != null
                MessageRoles.USER -> it.hasSecret(text)
                else -> it.hasSecret(text) || it.unsafeRenderedAnswer(text)
            }
        } == true
        // 只恢复完整的 assistant tool-call 批次及其 tool 回复，避免半截历史被 API 拒绝。
        val answeredCallIds = rows.mapNotNull { m ->
            if (m.role != MessageRoles.TOOL) return@mapNotNull null
            if (blocked(m.content, MessageRoles.TOOL)) return@mapNotNull null
            m.toolJson?.let { raw ->
                runCatching {
                    val meta = JSONObject(raw)
                    meta.optString("toolCallId")
                        .takeUnless { blocked(meta.optString("name"), MessageRoles.TOOL) || blocked(it, MessageRoles.TOOL) }
                }.getOrNull()
            }
        }.filter { it.isNotEmpty() }.toSet()
        val callsByMessageId = rows.filter { it.role == MessageRoles.ASSISTANT }.associate { message ->
            message.id to parseToolCalls(message.toolJson)
        }
        val usableAssistantIds = rows.filter { it.role == MessageRoles.ASSISTANT && !blocked(it.content, MessageRoles.ASSISTANT) }
            .mapTo(mutableSetOf()) { it.id }
        val validCallBatches = callsByMessageId.filter { (messageId, calls) ->
            messageId in usableAssistantIds && calls.isNotEmpty() &&
                calls.all {
                    it.id in answeredCallIds && !blocked(it.id, MessageRoles.ASSISTANT) && !blocked(it.name, MessageRoles.ASSISTANT) &&
                        safety?.unsafeArguments(it.argumentsJson) != true
                }
        }
        val validCallIds = validCallBatches.values
            .flatten()
            .mapTo(mutableSetOf()) { it.id }
        rows.mapNotNull { m ->
            val meta = m.toolJson?.let { runCatching { JSONObject(it) }.getOrNull() }
            when (m.role) {
                MessageRoles.ASSISTANT -> {
                    if (blocked(m.content, MessageRoles.ASSISTANT)) return@mapNotNull null
                    val calls = callsByMessageId[m.id].orEmpty()
                    val completeCalls = calls.takeIf { m.id in validCallBatches }
                    if (calls.isNotEmpty() && completeCalls == null && m.content.isBlank()) {
                        null
                    } else {
                        EngineMessage(
                            EngineClient.ROLE_ASSISTANT, m.content,
                            toolCalls = completeCalls,
                        )
                    }
                }
                MessageRoles.TOOL -> {
                    val callId = meta?.optString("toolCallId").orEmpty()
                    if (callId !in validCallIds || blocked(m.content, MessageRoles.TOOL)) null
                    else EngineMessage(
                        EngineClient.ROLE_TOOL, replayedToolBody(meta, m.content),
                        toolCallId = callId, toolName = meta?.optString("name"),
                    )
                }
                MessageRoles.SYSTEM -> if (safety == null) EngineMessage(EngineClient.ROLE_SYSTEM, m.content) else null
                else -> if (blocked(m.content, MessageRoles.USER)) null else EngineMessage(EngineClient.ROLE_USER, m.content)
            }
        }
    }

    private fun parseToolCalls(toolJson: String?): List<EngineToolCall> = runCatching {
        val array = toolJson?.let { JSONObject(it) }?.optJSONArray("calls")
            ?: return@runCatching emptyList()
        (0 until array.length()).map { index ->
            val call = array.getJSONObject(index)
            EngineToolCall(
                id = call.getString("id"),
                name = call.getString("name"),
                argumentsJson = call.getString("arguments"),
            )
        }
    }.getOrDefault(emptyList())

    /**
     * 带重试的模型调用:
     * - 只对可重试的错误(连接/DNS 失败、超时、429、5xx)重试,从头重试至多 [maxRetries] 次;
     *   429 遵守 Retry-After,退避等待放不进剩余预算时放弃;
     * - 还没输出文字:发 [AgentEvent.Retrying] 后从头重试,不撤回;
     * - 已经输出文字:不从头重试——把已上屏内容作为 assistant 前缀,让模型从中断处续写一次;
     *   续写失败或没有预算续写则抛 [StreamInterrupted](携带草稿),由调用方作为 bestDraft 交付。
     */
    private suspend fun callWithRetry(
        cfg: LlmConfig,
        messages: List<EngineMessage>,
        sessionId: Long,
        tools: List<com.agentkeyboard.tools.ToolSchema>,
        stream: com.agentkeyboard.ai.StreamingHandle,
        includesProfile: Boolean = false,
        budget: RunBudget,
        deadline: RunDeadline,
        maxRetries: Int = MAX_RETRIES,
        onRetry: (attempt: Int, e: Exception, startedAt: Long) -> Unit = { _, _, _ -> },
    ): ToolCallResult {
        val model = modelName(cfg)
        var prompt = messages
        var callTools = tools
        /** 续写时已上屏的前缀;空串 = 尚未进入续写 */
        var prefix = ""
        var fromStartRetries = 0
        while (true) {
            if (!gate.ensureLlmDisclosed(sessionId, includesProfile, cfg.baseUrl)) throw LlmCallDeniedException()
            stream.requestTimeout = requestTimeout(budget, deadline.remainingMs())
            /** 本次尝试已上屏的文本 */
            var draft = ""
            val startedAt = monotonicClock()
            try {
                val result = callWithinDeadline(deadline.remainingMs(), stream) {
                    // 流式放开:草稿先上屏供阅读,但草稿卡不可插入;完整回答须再通过内容检查才转为可交付结果。
                    // 检查不通过 / 即将转入工具调用时发 ContentReset 撤回草稿。
                    // 挂起式 emit(非 tryEmit):onDelta 在 IO 线程且首帧常早于面板收集器就绪,
                    // tryEmit 在无订阅者时会丢弃 first 增量,导致逐字效果从第二段才开始
                    llmCall(cfg.baseUrl, cfg.apiKey, model, prompt, callTools, stream) { accumulated ->
                        // 已被新任务取代(本任务流被 cancel):残留回调不得冒到新任务的界面上
                        if (stream.cancelRequested) return@llmCall
                        stream.onFirstChunk?.invoke()
                        draft = accumulated
                        _events.emit(AgentEvent.ContentDelta(prefix + accumulated))
                    }
                }
                if (prefix.isEmpty()) return result
                val continued = result as? ToolCallResult.Content
                    ?: throw IllegalStateException("续写请求返回了工具调用")
                if (continued.text.isBlank()) throw IllegalStateException("续写没有产出内容")
                return continued.copy(text = prefix + continued.text)
            } catch (e: CancellationException) {
                // 撤回必须可靠：与 ContentDelta 同用挂起式 emit
                _events.emit(AgentEvent.ContentReset)
                throw e
            } catch (e: Exception) {
                /** 屏上已有的文本:首发的草稿,或前缀加上本次续写的草稿 */
                val shown = prefix + draft
                suspend fun giveUp(cause: Exception): Nothing {
                    // 重试类错误(或续写失败)且已有文字:保留草稿交给调用方,不撤回
                    val keepDraft = shown.isNotBlank() && (prefix.isNotEmpty() || ErrorClassifier.isRetryable(cause))
                    if (keepDraft) throw StreamInterrupted(shown, deadline.remainingMs() <= 0L, cause)
                    if (shown.isNotEmpty()) _events.emit(AgentEvent.ContentReset)
                    throw cause
                }
                if (!ErrorClassifier.isRetryable(e)) giveUp(e)
                val delayMs = (ErrorClassifier.extractRetryAfterMs(e) ?: backoffMs(fromStartRetries))
                    .coerceIn(0L, MAX_RETRY_AFTER_MS)
                val fromStart = shown.isBlank()
                val nextAttempt = if (fromStart) fromStartRetries + 1 else 1
                // 续写只做一次:续写再断开就直接交付草稿
                val mayRetry = if (fromStart) fromStartRetries < maxRetries else prefix.isEmpty() && maxRetries > 0
                // 退避等待放不进剩余预算:不再重试
                if (!mayRetry || delayMs >= deadline.remainingMs()) giveUp(e)
                val kind = ErrorClassifier.extractStatusCode(e)?.toString()
                    ?: if (ErrorClassifier.isTimedOut(e)) "TIMEOUT" else "NETWORK"
                onRetry(nextAttempt, e, startedAt)
                _events.emit(AgentEvent.Retrying(nextAttempt, delayMs, kind))
                retryDelay(delayMs)
                if (fromStart) {
                    fromStartRetries++
                } else {
                    // 续写:已上屏内容作为 assistant 前缀,不带工具,从中断处接着写
                    prompt = prompt + EngineMessage(EngineClient.ROLE_ASSISTANT, shown) +
                        EngineMessage(EngineClient.ROLE_USER, CONTINUE_PROMPT)
                    callTools = emptyList()
                    prefix = shown
                }
            }
        }
    }

    /**
     * 给一次模型调用加硬截止(整轮的时间预算落到每次请求上):到点先关底层流——SDK 的阻塞读
     * 不响应协程取消,不关流就得等读自己返回;随后调用以超时失败。等待用户确认发生在调用之外,不受影响。
     * 响应头回来之前底层流还不存在,这一段由 [requestTimeout] 里的整次请求超时约束。
     */
    private suspend fun <T> callWithinDeadline(
        timeoutMs: Long,
        stream: com.agentkeyboard.ai.StreamingHandle,
        block: suspend () -> T,
    ): T = coroutineScope {
        val done = AtomicBoolean(false)
        val watchdog = launch {
            delay(timeoutMs.coerceAtLeast(1L))
            if (done.get()) return@launch
            stream.cancel()
            throw SocketTimeoutException("time budget exhausted")
        }
        try {
            block()
        } finally {
            done.set(true)
            watchdog.cancel()
        }
    }

    private suspend fun persistToolResult(
        sessionId: Long,
        call: EngineToolCall,
        result: ToolResult,
        flagged: Boolean,
        stepNo: Int,
    ) {
        withContext(NonCancellable) {
            messageDao.insert(
                AgentMessageEntity(
                    sessionId = sessionId,
                    role = MessageRoles.TOOL,
                    content = result.raw,
                    toolJson = toolJson(call, result, flagged),
                    stepNo = stepNo,
                    createdAt = clock(),
                ),
            )
        }
    }

    private suspend fun persistRunNote(sessionId: Long, reason: String, stepNo: Int) {
        withContext(NonCancellable) {
            messageDao.insert(
                AgentMessageEntity(
                    sessionId = sessionId,
                    role = MessageRoles.SYSTEM,
                    content = reason,
                    toolJson = null,
                    stepNo = stepNo,
                    createdAt = clock(),
                ),
            )
        }
    }

    private fun toolJson(call: EngineToolCall, result: ToolResult, flagged: Boolean): String =
        JSONObject()
            .put("toolCallId", call.id)
            .put("name", call.name)
            .put("arguments", call.argumentsJson)
            .put("ok", result.ok)
            .put("flagged", flagged)
            // 失败时记下错误码:续问回放时据此重新包成结构化错误(见 replayedToolBody)
            .put("errorCode", result.errorCode)
            .put("retryable", result.retryable)
            .toString()

    /**
     * 回放历史里的工具回复。失败的结果重新包成结构化错误(错误码 + 给模型的下一步建议),
     * 续问时模型看到的信息与本轮一致——比如上轮被拒绝的调用,不会被当成可以再试一次。
     * 成功的结果,以及没有记录错误码的旧数据,照旧只包原文。
     */
    private fun replayedToolBody(meta: JSONObject?, content: String): String {
        val code = meta?.takeIf { !it.optBoolean("ok", true) }
            ?.optString("errorCode")?.takeIf { it.isNotEmpty() }
            ?: return ToolResultWrapper.wrap(content)
        return ToolResultWrapper.wrap(
            ToolErrorHints.errorJson(code, meta.optBoolean("retryable"), content, ToolErrorHints.forCode(code)),
        )
    }

    private suspend fun persistAssistant(
        sessionId: Long,
        step: Int,
        content: String,
        calls: List<EngineToolCall>?,
        citations: List<Citation> = emptyList(),
    ) {
        val toolJson = calls?.let { list ->
            JSONObject().put(
                "calls",
                JSONArray().apply {
                    list.forEach { c ->
                        put(
                            JSONObject()
                                .put("id", c.id)
                                .put("name", c.name)
                                .put("arguments", c.argumentsJson)
                        )
                    }
                },
            ).toString()
        }
        messageDao.insert(
            AgentMessageEntity(
                sessionId = sessionId, role = MessageRoles.ASSISTANT,
                content = content, toolJson = toolJson,
                stepNo = step, createdAt = clock(),
                citationsJson = if (calls == null) com.agentkeyboard.tools.CitationJson.encode(citations) else null,
            )
        )
    }

    /** 错误分类(P3-8:文案统一由 UI 按 kind 取 strings 资源,engine 不再硬编码);
     *  调试信息只带状态码与服务商错误码标识,不带异常原文 */
    private fun toAgentError(e: Exception): AgentError = if (e is CancellationException) {
        AgentError("STOPPED", "")
    } else {
        val service = ErrorClassifier.serviceError(e)
        val kind = if (ErrorClassifier.isContextLengthExceeded(e)) "CONTEXT_LENGTH" else ErrorClassifier.kind(e).name
        AgentError(kind = kind, message = "", httpStatus = service?.httpStatus, providerCode = service?.let { it.code ?: it.type })
    }
}

/** 一次工具执行的结果;[ran] = 是否真的交给了注册表执行 */
private class ToolExecution(val result: ToolResult, val ran: Boolean)

/** 一批工具调用的执行分段:Group = 组内并发,Single = 单独执行(可能弹确认条,或写输入框) */
private sealed interface ToolSegment {
    val calls: List<EngineToolCall>

    class Group(override val calls: List<EngineToolCall>) : ToolSegment

    class Single(val call: EngineToolCall) : ToolSegment {
        override val calls: List<EngineToolCall> get() = listOf(call)
    }
}

/** 检索类工具:执行过而没有可引用来源时,查证类任务不能算完成核验 */
private val SEARCH_TOOL_IDS = setOf(ToolIds.KB_SEARCH, ToolIds.WEB_SEARCH)

/** 实际请求的模型 ID:显式指定优先,未指定时按 baseUrl 主机推断(与 ModelStore 口径一致) */
internal fun modelName(cfg: LlmConfig): String =
    cfg.model?.trim().takeUnless { it.isNullOrEmpty() } ?: com.agentkeyboard.data.ModelConfig.pickModel(cfg.baseUrl)

/** 终态事件 → 诊断记录;非终态事件不记 */
private fun diagnosticRecord(at: Long, type: TaskType, event: AgentEvent): RunRecord? = when (event) {
    is AgentEvent.Finished -> RunRecord(at, type, "FINISHED", error = null, summary = event.summary)
    is AgentEvent.Incomplete -> RunRecord(at, type, "INCOMPLETE", error = null, summary = event.summary)
    is AgentEvent.Failed -> RunRecord(at, type, "FAILED", error = event.error, summary = event.summary)
    is AgentEvent.Stopped -> RunRecord(at, type, "STOPPED", error = null, summary = event.summary)
    else -> null
}

/** 单次 run 的预算:工具步数、总时限、首 token 超时、累计输入 token 上限 */
data class RunBudget(
    val maxToolSteps: Int,
    val wallClockMs: Long,
    val firstTokenTimeoutMs: Long,
    val maxPromptTokens: Int,
)

/** 按任务类型分档的预算(输入法场景:用户在打字框前等待) */
internal fun budgetFor(type: TaskType): RunBudget =
    if (type == TaskType.REWRITE || type == TaskType.TRANSLATE) RunBudget(0, 12_000, 8_000, 16_000)
    else if (type == TaskType.HELP_WRITE) RunBudget(2, 20_000, 10_000, 32_000)
    else if (type == TaskType.ASK) RunBudget(4, 30_000, 12_000, 48_000)
    else if (type == TaskType.KNOWLEDGE_ASK) RunBudget(8, 45_000, 15_000, 96_000)
    else RunBudget(10, 60_000, 15_000, 128_000)

/**
 * 按预算拆分的超时:连接固定 5s;读超时 = 首 token 超时(流式下近似首 token 延迟与数据块之间的间隔);
 * 整次请求 = 距本轮截止的剩余时间(上限为总时限)。经 [com.agentkeyboard.ai.StreamingHandle.requestTimeout] 按请求下发。
 *
 * 整次请求超时必须跟着剩余时间走:响应头回来之前底层流还不存在,运行时的看门狗关不掉它,
 * 建连与等待响应头这一段只有 HTTP 层自己的超时能约束。
 */
internal fun requestTimeout(b: RunBudget, remainingMs: Long = b.wallClockMs): com.openai.core.Timeout =
    com.openai.core.Timeout.builder()
        .connect(java.time.Duration.ofSeconds(BUDGET_CONNECT_TIMEOUT_SECONDS))
        .read(java.time.Duration.ofMillis(b.firstTokenTimeoutMs))
        .write(java.time.Duration.ofMillis(b.firstTokenTimeoutMs))
        .request(java.time.Duration.ofMillis(minOf(b.wallClockMs, remainingMs).coerceAtLeast(1L)))
        .build()

private const val BUDGET_CONNECT_TIMEOUT_SECONDS = 5L

/**
 * 本轮的时间预算:从开始到硬截止。等待用户确认(确认条/明示条)的时间不计入——
 * 确认条按设计不超时,用户考虑多久都不该让任务被预算掐掉。
 */
private class RunDeadline(
    private val startedAtNs: Long,
    private val budgetMs: Long,
    private val gate: PermissionGate,
    private val clock: () -> Long,
) {
    /** 距硬截止剩余的毫秒数(已过点为 0):预算 − 已流逝时间,再把其中的用户等待加回去 */
    fun remainingMs(): Long =
        (budgetMs + gate.userWaitMs - (clock() - startedAtNs) / 1_000_000).coerceAtLeast(0L)

    /** 已消耗的机器时间(不含等待用户;截止后仍在增长,便于诊断看到超了多少) */
    fun elapsedMs(): Long =
        ((clock() - startedAtNs) / 1_000_000 - gate.userWaitMs).coerceAtLeast(0L)

    /** [atNs] 距本轮开始多久(毫秒) */
    fun sinceStartMs(atNs: Long): Long = ((atNs - startedAtNs) / 1_000_000).coerceAtLeast(0L)
}

/** 收尾请求的指令:禁用工具,让模型基于已有结果作答 */
private const val FINALIZE_PROMPT =
    "不要再调用工具。根据上面已有的工具结果，直接给出最佳答案；信息不足的地方明确说明“未能确认”，不要编造。"

/** 断流续写的指令 */
private const val CONTINUE_PROMPT = "请从中断处继续，不要重复。"

/**
 * 发请求前的最后一道配对检查(批次 1):
 * - tool 消息必须能在它之前找到声明该 id 的 assistant tool_call,否则丢弃;
 * - 同一 tool_call_id 只保留第一条回复;
 * - assistant 的 tool_call 批次若有任何一个缺少回复,去掉其 toolCalls(内容为空则整条丢弃)。
 * 保证任何路径(回灌、折叠、收尾)都不会发出孤立或残缺的 tool 交换。
 */
internal fun sanitizePairs(messages: List<EngineMessage>): List<EngineMessage> {
    val answered = messages.filter { it.role == EngineClient.ROLE_TOOL }
        .mapNotNull { it.toolCallId }.toSet()
    val declared = hashSetOf<String>()
    val replied = hashSetOf<String>()
    val out = ArrayList<EngineMessage>(messages.size)
    for (m in messages) {
        if (m.role == EngineClient.ROLE_ASSISTANT && !m.toolCalls.isNullOrEmpty()) {
            val calls = m.toolCalls.orEmpty()
            if (calls.all { it.id in answered }) {
                calls.forEach { declared += it.id }
                out += m
            } else if (m.content.isNotBlank()) {
                out += m.copy(toolCalls = null)
            }
        } else if (m.role == EngineClient.ROLE_TOOL) {
            val id = m.toolCallId
            if (id != null && id in declared && replied.add(id)) out += m
        } else {
            out += m
        }
    }
    return out
}

/** 回答没通过硬性检查;[reason] 是进错误卡的稳定标识(AgentContentSafety.TOO_LONG 等),不含内容 */
private class UnsafeAgentOutput(val reason: String) : Exception()
private val SAFE_CALL_ID = Regex("[A-Za-z0-9_-]{1,256}")
private val SAFE_TOOL_NAME = Regex("[A-Za-z0-9_-]{1,64}")
