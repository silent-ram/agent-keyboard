package com.agentkeyboard.agent.permission

import com.agentkeyboard.agent.AgentEvent
import com.agentkeyboard.agent.ConfirmationKind
import com.agentkeyboard.agent.ConfirmationRequest
import com.agentkeyboard.agent.ConfirmationResolver
import com.agentkeyboard.data.db.ActivityLogDao
import com.agentkeyboard.data.db.ActivityLogEntity
import com.agentkeyboard.data.db.LogOutcomes
import com.agentkeyboard.data.db.PermissionStateDao
import com.agentkeyboard.data.db.PermissionStateEntity
import com.agentkeyboard.data.db.PermissionStates
import com.agentkeyboard.data.db.UserContentFlags
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 门控结果:Allowed(含 outcome 与用户内容标注) / Denied(回喂 user_denied) */
sealed class GateResult {
    data class Allowed(val outcome: String, val containsUserContent: String) : GateResult()
    data object Denied : GateResult()
}

/** llm_call 首次明示的授权对象标识 */
const val TOOL_LLM_CALL = "llm_call"
private const val LLM_PROFILE_DISCLOSURE = "llm_call_profile"
private const val LLM_ENDPOINT_DISCLOSURE = "llm_call_endpoint"
private const val LLM_ENDPOINT_PROFILE_DISCLOSURE = "llm_call_endpoint_profile"
const val TARGET_MODEL_SERVICE = "模型服务"
const val TARGET_LOCAL = "本机"

/**
 * 默认可直接使用的出网工具:资料检索与联网搜索不弹授权询问,也不读取历史授权记录;
 * 只在命中派生底线(请求参数带着用户原文)时当场确认。
 */
private val DEFAULT_ALLOWED_TOOLS = setOf("kb_search", "web_search")

/**
 * 权限门控(11-权限设计 §3/§4/§5):
 * - llm_call:首次明示一次(必须同意),同意后记忆 ALWAYS_ALLOW;不做派生判定(§4.1);
 * - 出网工具:kb_search / web_search 默认可用([DEFAULT_ALLOWED_TOOLS]),不弹授权询问;
 *   其余(fetch_page / MCP 工具)UNASKED → 首次确认条;ASK_EVERY_TIME → 精简确认条;
 *   ALWAYS_ALLOW → 只做派生判定;
 * - 派生底线不可豁免:任何放行路径上,请求参数含用户原文 → 派生底线确认条(仅 允许一次/拒绝,不可记忆);
 * - 拒绝 → Denied,由调用方回喂 ToolResult.error("user_denied");
 * - 每次门控结果写 activity_log，记录操作类别和结果。
 * - 任务级"本次允许":同任务内同工具不重复弹(边界表 #3),派生命中除外(每次判定)。
 */
class PermissionGate(
    private val permissionStateDao: PermissionStateDao,
    private val activityLogDao: ActivityLogDao,
    private val resolver: ConfirmationResolver,
    private val emitConfirmation: suspend (AgentEvent.ConfirmationRequired) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    /** 时长测量用单调时钟(系统时间被校正会回拨) */
    private val monotonicClock: () -> Long = System::nanoTime,
    private val llmDisclosureTitle: String = "",
    private val llmDisclosureBody: String = "",
) {

    /**
     * 任务级"本次允许"缓存(一次 agent 会话内有效)。
     * M3 欠账清零:访问全部 synchronized——beginTask(引擎循环)与 reset/setAskEveryTime
     * (面板撤销条/设置页写协程)分属不同协程,MutableSet 非并发安全,并发 add/remove/clear
     * 会抛 ConcurrentModificationException。
     */
    private val taskAllowed = mutableSetOf<String>()

    /**
     * 当前待决确认请求(单槽,P1-2):ConfirmationRequired 事件之外的可恢复通道——
     * replay=0 的 SharedFlow 在面板未 collect 时会丢事件,面板挂起恢复/重进面板时读此 StateFlow
     * 即可重建确认条。resolver 返回(用户已决定)后清空。
     */
    private val _pendingConfirmation = MutableStateFlow<ConfirmationRequest?>(null)
    val pendingConfirmation: StateFlow<ConfirmationRequest?> get() = _pendingConfirmation

    /**
     * 本任务内等待用户决定(确认条/明示条)的累计时长。确认条按设计不超时,用户考虑多久都不该
     * 吃掉运行时的时间预算,AgentRuntime 用 [userWaitMs] 把这段从预算里扣除。
     */
    private val userWaitNanos = AtomicLong()

    /** 本任务内等待用户决定的累计毫秒数 */
    val userWaitMs: Long get() = userWaitNanos.get() / 1_000_000

    suspend fun beginTask() {
        synchronized(taskAllowed) { taskAllowed.clear() }
        userWaitNanos.set(0)
    }

    /** 「总是允许」记忆(UI 点「总是允许」按钮 / 设置页改写时调用) */
    suspend fun setAlwaysAllow(toolId: String) {
        permissionStateDao.upsert(PermissionStateEntity(toolId, PermissionStates.ALWAYS_ALLOW, clock()))
    }

    /** 设置页改写为「总是询问」(11 号设计 §7 两态分段控件) */
    suspend fun setAskEveryTime(toolId: String) {
        permissionStateDao.upsert(PermissionStateEntity(toolId, PermissionStates.ASK_EVERY_TIME, clock()))
        synchronized(taskAllowed) { taskAllowed.remove(toolId) }
    }

    /** 设置页重置授权 → UNASKED,重新走首次询问 */
    suspend fun reset(toolId: String) {
        permissionStateDao.upsert(PermissionStateEntity(toolId, PermissionStates.UNASKED, clock()))
        if (toolId == TOOL_LLM_CALL) {
            permissionStateDao.upsert(PermissionStateEntity(LLM_PROFILE_DISCLOSURE, PermissionStates.UNASKED, clock()))
            permissionStateDao.upsert(PermissionStateEntity(LLM_ENDPOINT_DISCLOSURE, PermissionStates.UNASKED, clock()))
            permissionStateDao.upsert(PermissionStateEntity(LLM_ENDPOINT_PROFILE_DISCLOSURE, PermissionStates.UNASKED, clock()))
        }
        synchronized(taskAllowed) { taskAllowed.remove(toolId) }
    }

    /**
     * P0(评审修正单):llm_call 是否已明示(只读查询,不弹确认、不落日志)。
     * 直出路径(QuickActionRunner → runQuickComplete → LlmClient.complete)不走 AgentRuntime,
     * 在单击直出前据此判定:已明示 → 直出放行;未明示 → 转投面板路径(首次明示确认条
     * 在面板可见呈现)。写路径由 ensureLlmDisclosed 处理(§4.1,不做派生判定)。
     */
    suspend fun llmDisclosed(): Boolean =
        permissionStateDao.byTool(TOOL_LLM_CALL)?.state == PermissionStates.ALWAYS_ALLOW

    /** 每次模型请求前明示状态并记活动;首次同意后逐次记录自动放行。 */
    suspend fun ensureLlmDisclosed(sessionId: Long?, includesProfile: Boolean = false, endpoint: String? = null): Boolean {
        val endpointFingerprint = endpoint?.let(::endpointFingerprint)
        val modelConsentId = if (endpoint == null) TOOL_LLM_CALL else LLM_ENDPOINT_DISCLOSURE
        val profileConsentId = if (endpoint == null) LLM_PROFILE_DISCLOSURE else LLM_ENDPOINT_PROFILE_DISCLOSURE
        val consent = endpointFingerprint ?: PermissionStates.ALWAYS_ALLOW
        val disclosed = permissionStateDao.byTool(modelConsentId)?.state == consent
        val profileDisclosed = !includesProfile ||
            permissionStateDao.byTool(profileConsentId)?.state == consent
        if (disclosed && profileDisclosed) {
            log(
                TOOL_LLM_CALL,
                LogOutcomes.AUTO_ALLOW,
                UserContentFlags.NONE,
                if (includesProfile) "AI 模型请求 · 含用户说明或记忆" else "AI 模型请求",
                sessionId,
            )
            return true
        }
        val allowed = confirm(
            TOOL_LLM_CALL, ConfirmationKind.LLM_DISCLOSURE, reason = null, canRemember = false,
        )
        if (allowed) {
            permissionStateDao.upsert(PermissionStateEntity(modelConsentId, consent, clock()))
            if (includesProfile) {
                permissionStateDao.upsert(PermissionStateEntity(profileConsentId, consent, clock()))
            }
            log(TOOL_LLM_CALL, LogOutcomes.CONFIRMED, UserContentFlags.NONE, "AI 模型调用 · 首次已同意", sessionId)
            return true
        }
        log(TOOL_LLM_CALL, LogOutcomes.DENIED, UserContentFlags.NONE, "AI 模型调用 · 暂不使用", sessionId)
        return false
    }

    private fun endpointFingerprint(endpoint: String): String =
        MessageDigest.getInstance("SHA-256").digest(endpoint.trim().trimEnd('/').encodeToByteArray())
            .joinToString("") { "%02x".format(it) }

    /**
     * 这次工具调用是否可能弹确认条。只读判定,不落日志、不改状态:
     * 运行时据此决定调用能否与同批次的其它调用并发——会弹确认条的调用必须单独按序执行,
     * 否则用户拒绝其中一个时,其余请求已经发出。
     */
    suspend fun mayPrompt(toolId: String, paramsText: String, taint: TaintSnapshot): Boolean {
        // 派生底线逐次判定、不可豁免:载荷含用户原文就一定当场确认
        if (ClassifyOutbound.classify(paramsText, taint) is Decision.Confirm) return true
        if (toolId in DEFAULT_ALLOWED_TOOLS) return false
        if (synchronized(taskAllowed) { toolId in taskAllowed }) return false
        return when (permissionStateDao.byTool(toolId)?.state) {
            PermissionStates.ALWAYS_ALLOW -> false
            else -> true
        }
    }

    /**
     * 出网工具执行前判定。
     * [paramsText]:本次出网载荷文本(query/URL);[target]:目标类别(搜索服务/网页)。
     */
    suspend fun gate(
        toolId: String,
        paramsText: String,
        @Suppress("UNUSED_PARAMETER") target: String,
        taint: TaintSnapshot,
        sessionId: Long?,
    ): GateResult {
        // 资料检索与联网搜索默认可用:不弹授权询问,也不读取历史授权记录;派生底线照常生效
        val state = if (toolId in DEFAULT_ALLOWED_TOOLS) PermissionStates.ALWAYS_ALLOW
            else permissionStateDao.byTool(toolId)?.state ?: PermissionStates.UNASKED
        // 任务级"本次允许":同任务同工具不重复弹「授权询问」,
        // 但派生底线(§4)逐次判定不豁免——载荷含用户原文仍必须当场确认(P0-1 修正)
        val taskCached = synchronized(taskAllowed) { toolId in taskAllowed }
        if (taskCached) {
            return when (val d = ClassifyOutbound.classify(paramsText, taint)) {
                is Decision.Allow -> {
                    log(toolId, LogOutcomes.CONFIRMED, UserContentFlags.NONE, "已确认,本次任务内执行", sessionId)
                    GateResult.Allowed(LogOutcomes.CONFIRMED, UserContentFlags.NONE)
                }
                is Decision.Confirm -> {
                    val allowed = confirm(
                        toolId, ConfirmationKind.DERIVED_BOTTOM_LINE, reason = d.reason, canRemember = false,
                    )
                    val contains = flagOf(d.source)
                    if (allowed) {
                        log(toolId, LogOutcomes.ALLOWED_ONCE, contains, "含原文,当场确认后执行", sessionId)
                        GateResult.Allowed(LogOutcomes.ALLOWED_ONCE, contains)
                    } else {
                        log(toolId, LogOutcomes.DENIED, contains, "含原文,你已拒绝", sessionId)
                        GateResult.Denied
                    }
                }
            }
        }

        when (state) {
            PermissionStates.ALWAYS_ALLOW -> {
                // 派生判定(仅出网工具;含原文 → 底线确认,不可豁免)
                return when (val d = ClassifyOutbound.classify(paramsText, taint)) {
                    is Decision.Allow -> {
                        log(toolId, LogOutcomes.AUTO_ALLOW, UserContentFlags.NONE, "自动执行", sessionId)
                        GateResult.Allowed(LogOutcomes.AUTO_ALLOW, UserContentFlags.NONE)
                    }
                    is Decision.Confirm -> {
                        val allowed = confirm(
                            toolId, ConfirmationKind.DERIVED_BOTTOM_LINE, reason = d.reason, canRemember = false,
                        )
                        val contains = flagOf(d.source)
                        if (allowed) {
                            // 「允许一次」仅本次生效,不改持久状态(底线不可记忆)
                            log(toolId, LogOutcomes.ALLOWED_ONCE, contains, "含原文,当场确认后执行", sessionId)
                            GateResult.Allowed(LogOutcomes.ALLOWED_ONCE, contains)
                        } else {
                            log(toolId, LogOutcomes.DENIED, contains, "含原文,你已拒绝", sessionId)
                            GateResult.Denied
                        }
                    }
                }
            }
            else -> {
                // UNASKED → 首次条;ASK_EVERY_TIME → 精简条
                val derived = ClassifyOutbound.classify(paramsText, taint) as? Decision.Confirm
                val kind = when {
                    derived != null -> ConfirmationKind.DERIVED_BOTTOM_LINE
                    state == PermissionStates.UNASKED -> ConfirmationKind.FIRST_TIME
                    else -> ConfirmationKind.EVERY_TIME
                }
                val allowed = confirm(
                    toolId, kind, reason = derived?.reason, canRemember = derived == null,
                )
                if (derived != null) {
                    val outcome = if (allowed) LogOutcomes.ALLOWED_ONCE else LogOutcomes.DENIED
                    val contains = flagOf(derived.source)
                    log(toolId, outcome, contains, "含原文，已单独询问", sessionId)
                    return if (allowed) GateResult.Allowed(outcome, contains) else GateResult.Denied
                }
                // 「总是允许」在 resolver 挂起期间可能已落盘:不降级,仅 UNASKED 态落 ASK_EVERY_TIME
                val current = permissionStateDao.byTool(toolId)?.state
                return when {
                    allowed -> {
                        if (current == null || current == PermissionStates.UNASKED) {
                            permissionStateDao.upsert(PermissionStateEntity(toolId, PermissionStates.ASK_EVERY_TIME, clock()))
                        }
                        synchronized(taskAllowed) { taskAllowed.add(toolId) }
                        val contains = containsFlag(paramsText, taint)
                        log(toolId, LogOutcomes.CONFIRMED, contains, "你已确认", sessionId)
                        GateResult.Allowed(LogOutcomes.CONFIRMED, contains)
                    }
                    else -> {
                        if (current == null || current == PermissionStates.UNASKED) {
                            // 拒绝也落 ASK_EVERY_TIME(下次弹精简条,不再重复首次说明)
                            permissionStateDao.upsert(PermissionStateEntity(toolId, PermissionStates.ASK_EVERY_TIME, clock()))
                        }
                        log(toolId, LogOutcomes.DENIED, UserContentFlags.NONE, "你已拒绝", sessionId)
                        GateResult.Denied
                    }
                }
            }
        }
    }

    private suspend fun containsFlag(
        paramsText: String,
        taint: TaintSnapshot,
    ): String =
        when (val d = ClassifyOutbound.classify(paramsText, taint)) {
            is Decision.Confirm -> flagOf(d.source)
            is Decision.Allow -> UserContentFlags.NONE
        }

    private fun flagOf(source: UserContentSource): String = when (source) {
        UserContentSource.CONTEXT -> UserContentFlags.CONTEXT
        UserContentSource.CLIPBOARD -> UserContentFlags.CLIPBOARD
    }

    private suspend fun log(
        toolId: String,
        outcome: String,
        contains: String,
        summary: String,
        sessionId: Long?,
    ) {
        activityLogDao.insert(
            ActivityLogEntity(
                ts = clock(),
                toolId = toolId,
                outcome = outcome,
                containsUserContent = contains,
                target = publicTarget(toolId),
                summary = summary,
                preview = "",
                sessionId = sessionId,
            )
        )
        activityLogDao.cleanup(clock() - com.agentkeyboard.data.db.RetentionPolicy.LOG_TTL_MS, com.agentkeyboard.data.db.RetentionPolicy.MAX_LOG_ENTRIES)
    }

    /** 组装确认请求(文案对应 11 号设计 §6.2 三形态),发 ConfirmationRequired 后挂起等待用户决定 */
    private suspend fun confirm(
        toolId: String,
        kind: ConfirmationKind,
        reason: String?,
        canRemember: Boolean,
    ): Boolean {
        val destination = publicTarget(toolId)
        val title = when (kind) {
            ConfirmationKind.FIRST_TIME, ConfirmationKind.EVERY_TIME -> destination
            ConfirmationKind.DERIVED_BOTTOM_LINE -> "含你的原文,需当场确认"
            ConfirmationKind.LLM_DISCLOSURE -> llmDisclosureTitle
        }
        val body = when (kind) {
            ConfirmationKind.FIRST_TIME, ConfirmationKind.EVERY_TIME -> "本次操作会将请求内容发送到外部服务。"
            ConfirmationKind.DERIVED_BOTTOM_LINE -> "本次外部请求${reason ?: "包含你的原文"}，需要单独确认。"
            ConfirmationKind.LLM_DISCLOSURE -> llmDisclosureBody
        }
        val request = ConfirmationRequest(
            toolId = toolId,
            kind = kind,
            title = title,
            body = body,
            paramsPreview = "",
            targetLabel = destination,
            reason = reason,
            canRemember = canRemember,
        )
        // 单槽互斥:并发确认必须排队,否则后者覆盖前者、前者 finally 又清掉后者的确认条。
        // 先置位再发事件:面板收到事件时一定能读到槽;emit 被取消/抛异常也由 finally 清槽。
        val waitStarted = monotonicClock()
        confirmMutex.lock()
        try {
            _pendingConfirmation.value = request // P1-2:单槽可恢复,面板未收到事件也能重建确认条
            try {
                emitConfirmation(AgentEvent.ConfirmationRequired(request))
                return resolver.resolve(request)
            } finally {
                _pendingConfirmation.value = null // 已决定(允许/拒绝/取消)即清空
            }
        } finally {
            userWaitNanos.addAndGet(monotonicClock() - waitStarted)
            confirmMutex.unlock()
        }
    }

    /** 确认条单槽互斥锁(concurrency #4) */
    private val confirmMutex = kotlinx.coroutines.sync.Mutex()
}

private fun publicTarget(toolId: String): String = when (toolId) {
    TOOL_LLM_CALL -> TARGET_MODEL_SERVICE
    "kb_search" -> "资料检索"
    "web_search" -> "联网搜索"
    "fetch_page" -> "读取网页"
    else -> "外部工具"
}
