package com.agentkeyboard.agent

import com.agentkeyboard.ai.EngineClient
import com.agentkeyboard.ai.EngineMessage
import com.agentkeyboard.ai.EngineToolCall
import com.agentkeyboard.ai.ToolCallResult
import com.agentkeyboard.data.ErrorClassifier
import com.agentkeyboard.data.ErrorKind
import com.agentkeyboard.data.LlmConfig
import com.agentkeyboard.data.db.AgentMessageDao
import com.agentkeyboard.data.db.AgentSessionDao
import com.agentkeyboard.data.db.AgentMessageEntity
import com.agentkeyboard.data.db.AgentSessionEntity
import com.agentkeyboard.data.db.MessageRoles
import com.agentkeyboard.data.db.RetentionPolicy
import com.agentkeyboard.data.db.SessionStatus
import com.agentkeyboard.tools.AgentToolRegistry
import com.agentkeyboard.tools.Citation
import com.agentkeyboard.tools.InjectionScanner
import com.agentkeyboard.tools.ToolCall
import com.agentkeyboard.tools.ToolErrorCodes
import com.agentkeyboard.tools.ToolIds
import com.agentkeyboard.tools.ToolResult
import com.agentkeyboard.tools.ToolResultWrapper
import com.agentkeyboard.tools.TOOL_USER_DENIED
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/** LLM 调用函数抽象(M1-T6 改流式:默认 EngineClient.streamWithTools;单测注入 fake 忽略 onDelta) */
typealias LlmCallFn = suspend (
    baseUrl: String, apiKey: String, model: String,
    messages: List<EngineMessage>, tools: List<com.agentkeyboard.tools.ToolSchema>,
    handle: com.agentkeyboard.ai.StreamingHandle, onDelta: suspend (String) -> Unit,
) -> ToolCallResult

private class LlmCallDeniedException : Exception()

/**
 * 单一 Turn/Step 运行时：每次模型请求为一步，工具由应用白名单选择并串行经过
 * AgentToolRegistry；预算耗尽、来源拒绝或工具策略违规都以 INCOMPLETE 结束，不追加收尾请求。
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
    internal val maxSteps: Int = MAX_STEPS,
    /** 可注入共享事件流(工厂场景:PermissionGate 的确认条事件与 engine 事件同流) */
    eventsFlow: MutableSharedFlow<AgentEvent>? = null,
    /** 超时重试退避序列(ms):attempt 0/1/2 → 1s/2s/4s(指数);单测可注入常数 0 */
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
    private val protectedConfigProvider: suspend () -> Collection<String> = { emptyList() },
) : AgentEngine {

    companion object {
        const val MAX_STEPS = 4

        /**
         * 超时重试次数上限(M3 欠账清零:由 2 提到 3,配合指数退避)。
         * 退避序列 1s/2s/4s([backoffMs]),总等待 7s——计入整体超时预算的注释:
         * EngineClient 单次请求 client timeout 60s,含退避的最坏耗时 ≈ 3×60s + 7s ≈ 187s,
         * 面板运行条无超时上限(用户可手动「■ 停止」),与 FR-B1「60s×2 次重试」的放宽
         * 在 M3 一并生效(退避降低限流/抖动场景下的连续失败率)。
         */
        const val MAX_TIMEOUT_RETRIES = 3
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
        if (inputTexts.any(AgentContentSafety::rejectsInput)) {
            _events.emit(AgentEvent.Failed(AgentError("UNSAFE_INPUT", ""), input.sessionId ?: -1))
            return
        }
        val task = input
        var persistedSessionId: Long? = null
        var currentUserMessageId: Long? = null
        val unresolvedCalls = mutableListOf<EngineToolCall>()
        try {
            val cfg = llmConfigProvider()
            if (cfg == null) {
                _events.emit(AgentEvent.Failed(AgentError("NOT_CONFIGURED", "尚未配置模型,请到设置页配置"), -1))
                return
            }
            val safety = AgentContentSafety(
                protectedConfigProvider() + cfg.apiKey + listOfNotNull(cfg.model) +
                    AgentContentSafety.endpointValues(cfg.baseUrl),
            )
            if (inputTexts.any(safety::hasConfig)) {
                _events.emit(AgentEvent.Failed(AgentError("UNSAFE_INPUT", ""), task.sessionId ?: -1))
                return
            }
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
                _events.emit(AgentEvent.Failed(AgentError("INVALID_TASK", ""), task.sessionId ?: -1))
                return
            }
            if (task.type == TaskType.KNOWLEDGE_ASK && (cfg.toolsAllowed == false || task.singleTurn)) {
                _events.emit(AgentEvent.Failed(AgentError("KNOWLEDGE_TOOLS_UNSUPPORTED", ""), -1))
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
            val history = loadHistoryInternal(previousRows, safety).toMutableList()
            val currentTurnStart = history.size
            currentUserMessageId = messageDao.insert(
                AgentMessageEntity(
                    sessionId = sessionId, role = MessageRoles.USER,
                    content = task.userText, toolJson = null,
                    stepNo = 0, createdAt = clock(),
                )
            )
            history += EngineMessage(EngineClient.ROLE_USER, task.userText)

            val savedProfile = profileProvider(task)
            val profile = AgentProfile(
                userMd = savedProfile.userMd.takeUnless { safety.hasConfig(it) || AgentContentSafety.rejectsInput(it) }.orEmpty(),
                memories = savedProfile.memories.filterNot { safety.hasConfig(it) || AgentContentSafety.rejectsInput(it) },
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

            // M1 批次 3(10 号设计 §4.2):模型 contextWindow > 0 时覆盖任务默认折叠预算;
            // cfg.toolsAllowed == false(fc=NO)→ 单轮降级:整个循环不带 tools;
            // M3 快捷动作:task.singleTurn = true 同样整轮不带 tools(单轮模板路径,一次直出)。
            val contextWindow = cfg.contextWindow.takeIf { it > 0 } ?: task.contextWindowTokens
            val fcDisabled = cfg.toolsAllowed == false || task.singleTurn
            val canPropose = task.allowProfileSuggestion && task.type == TaskType.KNOWLEDGE_ASK && !fcDisabled && maxSteps > 1
            val tools = if (fcDisabled) emptyList() else registry.schemas().filter { schema ->
                schema.id in request.allowedToolIds &&
                    (!schema.id.startsWith(com.agentkeyboard.mcp.McpTool.ID_PREFIX) ||
                        (!safety.hasConfig(schema.id) && !safety.unsafeAnswer(schema.description) && !safety.hasConfig(schema.description) &&
                            !safety.unsafeArguments(schema.schemaJson)))
            }
            val exposedToolIds = tools.mapTo(hashSetOf()) { it.id }

            val citations = mutableListOf<Citation>()
            var finalText: String? = null
            var suggestion: ProfileSuggestion? = null
            var incompleteReason: String? = null
            var hadToolFailure = false
            var knowledgeSearchUsed = false
            var knowledgeCitationCount = 0
            var step = 0
            val traceSteps = mutableListOf<AgentTraceStep>()
            var runUsage = com.agentkeyboard.ai.TokenUsage.ZERO
            while (step < maxSteps) {
                step++
                _events.emit(AgentEvent.Thinking(step))
                val folded = ContextCompactor.compact(
                    fullMessages, ContextCompactor.foldBudget(contextWindow), currentTurnStartFull
                )
                val llmStartedAt = clock()
                val result = callWithRetry(                    cfg, folded, sessionId = sessionId,
                    tools = if (canPropose && step == 1) tools + ProfileSuggestion.schema else tools,
                    stream = stream,
                    includesProfile = profile.userMd.isNotBlank() || profile.memories.any(String::isNotBlank),
                )
                runUsage += result.usage
                traceSteps += AgentTraceStep(
                    step = step, kind = "llm", elapsedMs = clock() - llmStartedAt, usage = result.usage,
                )
                when (result) {                    is ToolCallResult.Content -> {
                        if (safety.unsafeRenderedAnswer(result.text)) {
                            // 草稿已在屏上,拦截后必须撤回,不能留下未通过检查的文字
                            _events.emit(AgentEvent.ContentReset)
                            throw UnsafeAgentOutput()
                        }
                        finalText = safety.displayAnswer(result.text)
                        // 翻译/脱敏后可能与草稿不同(如命中的配置值被遮盖),以审核后的文本为准重新展示
                        if (finalText != result.text) _events.emit(AgentEvent.ContentDelta(finalText))
                        persistAssistant(sessionId, step, finalText, null, citations)
                        // 长度上限/内容过滤/分片丢失:保留已生成内容,但不能声称任务完整完成
                        result.incompleteReason?.let { incompleteReason = it }
                        break
                    }
                    is ToolCallResult.ToolCalls -> {
                        // 本轮转为工具调用:模型可能先流出了"我来查一下"之类的草稿,随后并不作为回答保留
                        _events.emit(AgentEvent.ContentReset)
                        // 模型重复返回同一 toolCallId 视为一次调用:双份 tool 回复会破坏
                        // 历史配对(续问时同 id 两条 tool 消息会被 API 拒绝)。
                        val returned = result.calls.distinctBy { it.id }
                        if (safety.unsafeAnswer(result.content) || returned.any {
                                !SAFE_CALL_ID.matches(it.id) || !SAFE_TOOL_NAME.matches(it.name) ||
                                    safety.hasConfig(it.id) || safety.hasConfig(it.name) || safety.unsafeArguments(it.argumentsJson)
                            }) throw UnsafeAgentOutput()
                        val proposals = returned.filter { it.name == ProfileSuggestion.ID }
                        if (canPropose && step == 1) {
                            suggestion = proposals.singleOrNull()?.let {
                                ProfileSuggestion.parse(it.argumentsJson, task.userText)
                            }?.takeUnless { it.isAlreadyIn(profile) }
                        }
                        // Keep proposal arguments out of stored history and future model requests.
                        val calls = returned.filterNot { it.name == ProfileSuggestion.ID }
                        if (calls.isEmpty() && proposals.isNotEmpty()) {
                            // A proposal alone is not an answer; use the next normal step without that schema.
                            continue
                        }
                        val intermediate = safety.redact(result.content)
                        persistAssistant(sessionId, step, intermediate, calls)
                        fullMessages += EngineMessage(
                            EngineClient.ROLE_ASSISTANT, intermediate, toolCalls = calls
                        )
                        unresolvedCalls.addAll(calls)
                        for ((callIndex, call) in calls.withIndex()) {
                            if (!currentCoroutineContext().isActive) throw CancellationException("engine stopped")
                            val tc = ToolCall(toolCallId = call.id, toolId = call.name, argsJson = call.argumentsJson)
                            _events.emit(AgentEvent.StepStarted(tc, step))
                            val permitted = call.name in exposedToolIds && registry.get(call.name) != null
                            if (permitted && call.name == ToolIds.KB_SEARCH) knowledgeSearchUsed = true
                            val toolStartedAt = clock()
                            val received = if (permitted) {
                                registry.execute(tc, taint, sessionId)
                            } else {
                                ToolResult.error(
                                    "此工具未注册或不在本轮允许范围内，任务已停止",
                                    ToolErrorCodes.DISABLED,
                                )
                            }
                            val flagged = safety.unsafeAnswer(received.raw) || safety.hasConfig(received.raw) ||
                                received.citations.any { safety.hasConfig(it.url) || safety.unsafeAnswer(it.title) || safety.unsafeAnswer(it.snippet) }
                            val toolResult = when {
                                flagged -> ToolResult.error("资料包含疑似不安全内容，已拦截", com.agentkeyboard.tools.ToolErrorCodes.DISABLED)
                                received.ok -> received.copy(
                                    wrapped = ToolResultWrapper.wrap(received.raw),
                                    citations = safety.citations(received.citations),
                                )
                                received.raw == TOOL_USER_DENIED -> received
                                else -> ToolResult.error(received.raw, received.errorCode ?: com.agentkeyboard.tools.ToolErrorCodes.INTERNAL, received.retryable)
                            }
                            if (toolResult.ok) {
                                citations += toolResult.citations
                                if (call.name == ToolIds.KB_SEARCH) {
                                    knowledgeCitationCount += toolResult.citations.size
                                }
                            }
                            if (!toolResult.ok) hadToolFailure = true
                            persistToolResult(sessionId, call, toolResult, flagged, step)
                            traceSteps += AgentTraceStep(
                                step = step, kind = call.name, elapsedMs = clock() - toolStartedAt,
                                ok = toolResult.ok, errorCode = toolResult.errorCode,
                            )
                            unresolvedCalls.remove(call)
                            fullMessages += EngineMessage(
                                EngineClient.ROLE_TOOL,
                                toolResult.wrapped,
                                toolCallId = call.id,
                                toolName = call.name,
                            )
                            _events.emit(AgentEvent.StepFinished(tc, toolResult, step, flagged))

                            val denied = toolResult.raw == TOOL_USER_DENIED
                            if (!permitted || denied || flagged) {
                                incompleteReason = when {
                                    flagged -> "资料包含疑似不安全内容，已停止本轮请求"
                                    denied -> "你拒绝了本次出网请求，任务未完成"
                                    else -> "模型请求了未注册或本轮未开放的工具，任务已停止"
                                }
                                calls.drop(callIndex + 1).forEach { pending ->
                                    val skipped = ToolResult.error("前序步骤已停止，未执行")
                                    persistToolResult(sessionId, pending, skipped, flagged = false, step)
                                    unresolvedCalls.remove(pending)
                                }
                                break
                            }
                        }
                        if (incompleteReason != null) break
                    }
                }
            }
            if (incompleteReason == null && finalText == null) {
                incompleteReason = "已达到本轮 $maxSteps 步上限，任务未完成"
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
            val uniqueCitations = citations.distinctBy { "${it.url}\u0001${it.title}" }
            if (incompleteReason != null) {
                persistRunNote(sessionId, incompleteReason, step)
                sessionDao.updateStatus(sessionId, SessionStatus.INCOMPLETE)
                _events.emit(
                    AgentEvent.Incomplete(
                        content = finalText.orEmpty(),
                        citations = uniqueCitations,
                        reason = incompleteReason,
                        sessionId = sessionId,
                        summary = AgentRunSummary(traceSteps.toList(), runUsage),
                    ),
                )
            } else {
                sessionDao.updateStatus(sessionId, SessionStatus.DONE)
                _events.emit(
                    AgentEvent.Finished(
                        finalText.orEmpty(), uniqueCitations, sessionId, suggestion,
                        summary = AgentRunSummary(traceSteps.toList(), runUsage),
                    ),
                )
            }
        } catch (_: LlmCallDeniedException) {
            withContext(NonCancellable) {
                currentUserMessageId?.let { messageDao.deleteById(it) }
                persistedSessionId?.let { sessionDao.updateStatus(it, SessionStatus.STOPPED) }
            }
            _events.emit(AgentEvent.Failed(AgentError("LLM_DENIED", ""), persistedSessionId ?: -1))
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
                _events.emit(AgentEvent.Stopped(persistedSessionId ?: task.sessionId ?: -1))
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
            val error = if (e is UnsafeAgentOutput) AgentError("UNSAFE_OUTPUT", "") else toAgentError(e)
            _events.emit(AgentEvent.Failed(error, persistedSessionId ?: -1))
        }
    }

    /** 测试可见:历史重建(含孤儿过滤) */
    internal suspend fun loadHistoryForTest(sessionId: Long): List<EngineMessage> =
        loadHistoryInternal(messageDao.bySession(sessionId))

    private fun loadHistoryInternal(rows: List<AgentMessageEntity>, safety: AgentContentSafety? = null): List<EngineMessage> = run {
        fun blocked(text: String): Boolean = safety?.let { it.hasConfig(text) || it.unsafeAnswer(text) } == true
        // 只恢复完整的 assistant tool-call 批次及其 tool 回复，避免半截历史被 API 拒绝。
        val answeredCallIds = rows.mapNotNull { m ->
            if (m.role != MessageRoles.TOOL) return@mapNotNull null
            if (blocked(m.content)) return@mapNotNull null
            m.toolJson?.let { raw ->
                runCatching {
                    val meta = JSONObject(raw)
                    meta.optString("toolCallId").takeUnless { blocked(meta.optString("name")) || blocked(it) }
                }.getOrNull()
            }
        }.filter { it.isNotEmpty() }.toSet()
        val callsByMessageId = rows.filter { it.role == MessageRoles.ASSISTANT }.associate { message ->
            message.id to parseToolCalls(message.toolJson)
        }
        val usableAssistantIds = rows.filter { it.role == MessageRoles.ASSISTANT && !blocked(it.content) }
            .mapTo(mutableSetOf()) { it.id }
        val validCallBatches = callsByMessageId.filter { (messageId, calls) ->
            messageId in usableAssistantIds && calls.isNotEmpty() &&
                calls.all {
                    it.id in answeredCallIds && !blocked(it.id) && !blocked(it.name) &&
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
                    if (blocked(m.content)) return@mapNotNull null
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
                    if (callId !in validCallIds || blocked(m.content)) null
                    else EngineMessage(
                        EngineClient.ROLE_TOOL, ToolResultWrapper.wrap(m.content),
                        toolCallId = callId, toolName = meta?.optString("name"),
                    )
                }
                MessageRoles.SYSTEM -> if (safety == null) EngineMessage(EngineClient.ROLE_SYSTEM, m.content) else null
                else -> if (blocked(m.content)) null else EngineMessage(EngineClient.ROLE_USER, m.content)
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

    private suspend fun callWithRetry(
        cfg: LlmConfig,
        messages: List<EngineMessage>,
        sessionId: Long,
        tools: List<com.agentkeyboard.tools.ToolSchema>,
        stream: com.agentkeyboard.ai.StreamingHandle,
        includesProfile: Boolean = false,
    ): ToolCallResult {
        val model = cfg.model?.trim().takeUnless { it.isNullOrEmpty() }
            ?: com.agentkeyboard.data.ModelConfig.pickModel(cfg.baseUrl)
        var attempt = 0
        while (true) {
            if (!gate.ensureLlmDisclosed(sessionId, includesProfile, cfg.baseUrl)) throw LlmCallDeniedException()
            try {
                // 流式放开:草稿先上屏供阅读,但草稿卡不可插入;完整回答须再通过内容检查才转为可交付结果。
                // 检查不通过 / 即将转入工具调用时发 ContentReset 撤回草稿。
                return llmCall(cfg.baseUrl, cfg.apiKey, model, messages, tools, stream) { accumulated ->
                    // 挂起式 emit(非 tryEmit):onDelta 在 IO 线程且首帧常早于面板收集器就绪,
                    // tryEmit 在无订阅者时会丢弃 first 增量,导致逐字效果从第二段才开始
                    // 已被新任务取代(本任务流被 cancel):残留回调不得冒到新任务的界面上
                    if (stream.cancelRequested) return@llmCall
                    _events.emit(AgentEvent.ContentDelta(accumulated))
                }
            } catch (e: CancellationException) {
                _events.tryEmit(AgentEvent.ContentReset)
                throw e
            } catch (e: Exception) {
                // 重试会从头重发,已显示的半截文字必须撤回,否则会与重试结果拼接成错误内容
                _events.tryEmit(AgentEvent.ContentReset)
                if (ErrorClassifier.isRetryable(e) && attempt < MAX_TIMEOUT_RETRIES) {
                    retryDelay(backoffMs(attempt))
                    attempt++
                    continue
                }
                throw e
            }
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

    private fun toolJson(call: EngineToolCall, result: ToolResult, flagged: Boolean): String =
        JSONObject()
            .put("toolCallId", call.id)
            .put("name", call.name)
            .put("arguments", call.argumentsJson)
            .put("ok", result.ok)
            .put("flagged", flagged)
            .toString()

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

    /** 错误分类(沿用 ErrorClassifier);P3-8:文案统一由 UI 按 kind 取 strings 资源,engine 不再硬编码 */
    private fun toAgentError(e: Exception): AgentError = if (e is CancellationException) {
        AgentError("STOPPED", "")
    } else {
        val kind = ErrorClassifier.classify(
            statusCode = ErrorClassifier.extractStatusCode(e),
            connectivityFailure = ErrorClassifier.isConnectivityFailure(e),
            timedOut = ErrorClassifier.isTimedOut(e),
        )
        AgentError(kind = kind.name, message = "")
    }
}

private class UnsafeAgentOutput : Exception()
private val SAFE_CALL_ID = Regex("[A-Za-z0-9_-]{1,256}")
private val SAFE_TOOL_NAME = Regex("[A-Za-z0-9_-]{1,64}")
