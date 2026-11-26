package com.agentkeyboard.tools

import com.agentkeyboard.agent.permission.GateResult
import com.agentkeyboard.agent.permission.PermissionGate
import com.agentkeyboard.agent.permission.TaintSnapshot
import com.agentkeyboard.data.db.ActivityLogDao
import com.agentkeyboard.data.db.ActivityLogEntity
import com.agentkeyboard.data.db.LogOutcomes
import com.agentkeyboard.data.db.UserContentFlags

const val TOOL_USER_DENIED = "user_denied"

/**
 * 工具注册表 + 执行门控点(02-架构 §2.2/§2.4):
 * - schema 暴露给 LLM(常驻注册,含占位 stub 保证 schema 稳定);
 * - execute:出网工具先过 PermissionGate → runCatching 执行(失败作为 error 结果回喂,不中断);
 *   资料是否隔离、每步结果落盘由 engine 决定(AgentContentSafety);
 * - read_context/ask_user 免授权不弹窗;kb_search/web_search 默认直接放行、不弹授权询问(见 PermissionGate);
 *   insert_text 预览确认由面板层保证,工具层直接执行但记日志。
 */
class AgentToolRegistry(
    tools: List<Tool>,
    private val gate: PermissionGate,
    private val activityLogDao: ActivityLogDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val lock = Any()
    private val byId: LinkedHashMap<String, Tool> = LinkedHashMap(tools.associateBy { it.id })

    fun schemas(): List<ToolSchema> = synchronized(lock) {
        byId.values.map { ToolSchema(it.id, it.name, it.description, it.schemaJson) }
    }

    fun get(toolId: String): Tool? = synchronized(lock) { byId[toolId] }

    /**
     * 动态工具(G12 MCP 桥接):server 同步成功后整体替换该前缀下的工具集。
     * 常驻工具(web_search/kb_search)不使用前缀命名,不会被误清。
     */
    fun replaceByPrefix(prefix: String, replacements: List<Tool>) {
        synchronized(lock) {
            byId.keys.removeAll { it.startsWith(prefix) }
            replacements.forEach { byId[it.id] = it }
        }
    }

    /** 动态工具注销(server 删除/停用且无残留时)。 */
    fun unregisterByPrefix(prefix: String) {
        synchronized(lock) { byId.keys.removeAll { it.startsWith(prefix) } }
    }

    suspend fun execute(call: ToolCall, taint: TaintSnapshot, sessionId: Long?): ToolResult {
        val tool = get(call.toolId)
            ?: return ToolResult.error(
                "此工具未注册:只能使用本轮提供的工具",
                ToolErrorCodes.NOT_FOUND,
            )

        // 参数不是 JSON 对象时直接回喂 BAD_PARAMS:模型可据此修正;出网工具也不必先弹授权再失败
        if (!isJsonObjectOrBlank(call.argsJson)) {
            return ToolResult.error(
                "参数不是合法的 JSON 对象，请按工具 schema 重新生成参数",
                ToolErrorCodes.BAD_PARAMS,
            )
        }

        // 免授权工具(IME 本职/本地):不弹窗、不逐条记日志
        if (!tool.isOutbound && tool.id !in LOGGED_LOCAL_TOOLS) {
            return executeTool(tool, call)
        }

        // insert_text:预览确认由面板层保证,工具层直接执行;上屏必须记日志(FR-E6)
        if (tool.id == ToolIds.INSERT_TEXT) {
            val payloadText = tool.paramsText(call.argsJson) // 提取后的待插入文本(P2-5)
            val contains = classifyContains(payloadText, taint)
            val result = executeTool(tool, call)
            activityLogDao.insert(
                ActivityLogEntity(
                    ts = clock(), toolId = tool.id,
                    outcome = if (result.ok) LogOutcomes.CONFIRMED else LogOutcomes.FAILED,
                    containsUserContent = contains, target = tool.targetLabel,
                    summary = if (result.ok) "上屏 · 你已插入" else "上屏失败",
                    // 剪贴板来源按设计只存前 20 字(11-权限设计 §5.4),必须把来源传给脱敏器
                    preview = com.agentkeyboard.agent.permission.LogSanitizer.sanitizePreview(
                        payloadText,
                        when (contains) {
                            UserContentFlags.CLIPBOARD -> com.agentkeyboard.agent.permission.UserContentSource.CLIPBOARD
                            UserContentFlags.CONTEXT -> com.agentkeyboard.agent.permission.UserContentSource.CONTEXT
                            else -> null
                        },
                    ),
                    sessionId = sessionId,
                )
            )
            activityLogDao.cleanup(clock() - com.agentkeyboard.data.db.RetentionPolicy.LOG_TTL_MS, com.agentkeyboard.data.db.RetentionPolicy.MAX_LOG_ENTRIES)
            return result
        }

        // 出网工具:门控 → 执行 → 失败记 FAILED(派生判定与日志均喂提取后的 query/URL,P2-5)
        val gateResult = gate.gate(
            toolId = tool.id,
            paramsText = tool.paramsText(call.argsJson),
            target = tool.targetLabel,
            taint = taint,
            sessionId = sessionId,
        )
        if (gateResult is GateResult.Denied) {
            // raw 保持协议值 user_denied(运行时终态判定与界面都依赖它);回喂模型的是带建议的结构化错误
            return ToolResult.error("用户拒绝了这次请求", ToolErrorCodes.USER_DENIED)
                .copy(raw = TOOL_USER_DENIED)
        }
        val result = executeTool(tool, call)
        if (!result.ok) {
            activityLogDao.insert(
                ActivityLogEntity(
                    ts = clock(), toolId = tool.id, outcome = LogOutcomes.FAILED,
                    containsUserContent = UserContentFlags.NONE, target = "外部服务",
                    summary = "执行失败(不影响后续步骤)", preview = "", sessionId = sessionId,
                )
            )
        }
        activityLogDao.cleanup(clock() - com.agentkeyboard.data.db.RetentionPolicy.LOG_TTL_MS, com.agentkeyboard.data.db.RetentionPolicy.MAX_LOG_ENTRIES)
        return result
    }

    private suspend fun executeTool(tool: Tool, call: ToolCall): ToolResult =
        try {
            tool.execute(call.argsJson)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 取消必须传播(FR-B1"取消随时生效"):被 runCatching 吞掉会把取消误判为工具失败
            throw e
        } catch (e: IllegalArgumentException) {
            // 缺参/参数格式错(Jsons.requireString 等):把具体原因告诉模型,便于下一步修正参数
            val detail = e.message?.take(MAX_PARAM_ERROR_CHARS)?.takeIf { it.isNotBlank() } ?: "参数不合法"
            ToolResult.error("工具参数错误:$detail", ToolErrorCodes.BAD_PARAMS)
        } catch (e: Exception) {
            ToolResult.error("工具执行失败:${e.javaClass.simpleName}", ToolErrorCodes.INTERNAL)
        }

    private fun isJsonObjectOrBlank(argsJson: String): Boolean =
        argsJson.isBlank() || runCatching { org.json.JSONObject(argsJson) }.isSuccess

    private suspend fun classifyContains(paramsText: String, taint: TaintSnapshot): String =
        when (val d = com.agentkeyboard.agent.permission.ClassifyOutbound.classify(paramsText, taint)) {
            is com.agentkeyboard.agent.permission.Decision.Confirm -> when (d.source) {
                com.agentkeyboard.agent.permission.UserContentSource.CLIPBOARD -> UserContentFlags.CLIPBOARD
                com.agentkeyboard.agent.permission.UserContentSource.CONTEXT -> UserContentFlags.CONTEXT
            }
            is com.agentkeyboard.agent.permission.Decision.Allow -> UserContentFlags.NONE
        }

    companion object {
        /** 本地但必须记日志的工具(每次上屏,11-权限设计 §5.2) */
        private val LOGGED_LOCAL_TOOLS = setOf(ToolIds.INSERT_TEXT)

        /** 参数错误原因回喂模型的长度上限(防异常信息过长撑大上下文) */
        private const val MAX_PARAM_ERROR_CHARS = 200
    }
}

/** 常驻工具 ID 注册表 */
object ToolIds {
    const val WEB_SEARCH = "web_search"
    const val FETCH_PAGE = "fetch_page"
    const val READ_CONTEXT = "read_context"
    const val INSERT_TEXT = "insert_text"
    const val KB_SEARCH = "kb_search"
    const val ASK_USER = "ask_user"
}
