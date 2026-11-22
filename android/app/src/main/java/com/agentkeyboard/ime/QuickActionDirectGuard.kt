package com.agentkeyboard.ime

import com.agentkeyboard.agent.permission.LogSanitizer
import com.agentkeyboard.agent.permission.TARGET_MODEL_SERVICE
import com.agentkeyboard.agent.permission.TOOL_LLM_CALL
import com.agentkeyboard.agent.permission.UserContentSource
import com.agentkeyboard.data.db.ActivityLogEntity
import com.agentkeyboard.data.db.LogOutcomes
import com.agentkeyboard.data.db.UserContentFlags

/**
 * P0(评审修正单,权限红线):直出路径的 llm_call 首次明示守卫。
 *
 * 背景:直出(runQuickComplete → LlmClient.complete)不走 AgentRuntime,而
 * ensureLlmDisclosed 的全工程唯一调用点在 AgentRuntime.run(面板路径)——用户从不开面板
 * 直接点「✨帮写」时,输入框上下文明文发送至模型服务,无确认、无活动日志。
 *
 * 判定(11-权限设计 §4.1,llm_call 不做派生判定):
 * - 已明示(permission_state.llm_call = ALWAYS_ALLOW)→ 落一条 llm_call 活动日志后放行直出;
 * - 未明示 → 转投面板路径(宿主 startQuickAction:确认条在面板可见呈现,复用现有 resolver
 *   流程;用户同意后走面板单轮,不再直出——本次之后 llmDisclosed=true,后续单击直出放行)。
 *
 * 从服务抽成独立类以便 JVM 单测(mock gate 注入 [llmDisclosed],日志注入 [logLlmCall])。
 */
class QuickActionDirectGuard(
    /** llm_call 是否已明示(服务侧接线 agentKit.gate::llmDisclosed;单测注入 fake gate) */
    private val llmDisclosed: suspend () -> Boolean,
    /** llm_call 活动日志落库(服务侧接线 activityLogDao::insert;单测注入记录器) */
    private val logLlmCall: suspend (ActivityLogEntity) -> Unit,
    /** 时间戳来源(单测可固定) */
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * 判定并分派:已明示 → 先落 llm_call 活动日志(内容即将发送,与 gate「判定即记」同口径)
     * 再执行 [direct](宿主 = quickRunner.onButton 直出);未明示 → 执行 [toPanel](宿主 =
     * startQuickAction 转投面板路径),[direct] 与日志均不发生(直出绝不绕过首次明示)。
     *
     * @return 是否走了直出(true = 已明示放行;false = 已转投面板路径)
     */
    suspend fun dispatch(
        action: QuickAction,
        source: String,
        direct: () -> Unit,
        toPanel: () -> Unit,
    ): Boolean {
        if (!llmDisclosed()) {
            toPanel()
            return false
        }
        logLlmCall(llmCallLog(action, source, clock()))
        direct()
        return true
    }

    /**
     * 直出 llm_call 活动日志实体(与 fc 探测同口径):
     * - outcome=AUTO_ALLOW——到达此处必已过明示判定(首次同意走面板路径,记 CONFIRMED);
     * - containsUserContent=CONTEXT——直出源文本来自输入框上下文(选区/光标前上文);
     * - preview 走 LogSanitizer(截 80 字 + 手机号/邮箱/证件号打码),不落原文。
     */
    fun llmCallLog(action: QuickAction, source: String, ts: Long): ActivityLogEntity = ActivityLogEntity(
        ts = ts,
        toolId = TOOL_LLM_CALL,
        outcome = LogOutcomes.AUTO_ALLOW,
        containsUserContent = UserContentFlags.CONTEXT,
        target = TARGET_MODEL_SERVICE,
        summary = "快捷动作直出 · ${action.tag}",
        preview = LogSanitizer.sanitizePreview(source, UserContentSource.CONTEXT),
        sessionId = null,
    )
}
