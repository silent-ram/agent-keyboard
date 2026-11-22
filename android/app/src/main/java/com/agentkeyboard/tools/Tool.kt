package com.agentkeyboard.tools

/**
 * 一次工具调用(由 LLM 的 toolCalls 发起)。
 */
data class ToolCall(
    val toolCallId: String,
    val toolId: String,
    val argsJson: String,
)

/**
 * 引用来源(查证类 Finished 时汇总)。
 * M4 出处强化:[snippet] = 片段级定位首句摘要(kb 来源必填;web 来源为空)。
 */
data class Citation(val url: String, val title: String, val snippet: String = "")

/**
 * 工具错误码(G2 工具结果契约):稳定枚举,文案只做展示,模型按 code/retryable 决定
 * 重试/换词/放弃。新增错误码属于对外契约变更,只允许追加,不允许改既有值。
 */
object ToolErrorCodes {
    const val AUTH = "AUTH"                     // 鉴权失败(401/403),换 Key 前重试无意义
    const val RATE_LIMIT = "RATE_LIMIT"         // 限流(429),可退避重试
    const val TIMEOUT = "TIMEOUT"               // 超时,可重试
    const val NETWORK = "NETWORK"               // 连接失败/DNS 等,可重试
    const val EMPTY = "EMPTY"                   // 远端成功但无结果,建议换关键词而非重试
    const val BAD_RESPONSE = "BAD_RESPONSE"     // 响应结构无法解析
    const val BAD_PARAMS = "BAD_PARAMS"         // 模型给的参数不合法,应修正参数后重试
    const val NOT_CONFIGURED = "NOT_CONFIGURED" // 缺少 Key/知识库等配置,需用户介入
    const val USER_DENIED = "USER_DENIED"       // 用户拒绝授权,不得重试同一请求
    const val DISABLED = "DISABLED"             // 开关关闭或白名单未开放
    const val REMOTE_ERROR = "REMOTE_ERROR"     // 远端 5xx 或工具自身报错
    const val INTERNAL = "INTERNAL"             // 本地未分类异常
}

/** 按 HTTP 状态码映射稳定错误码(出网工具共用)。 */
fun httpErrorCode(status: Int): Pair<String, Boolean> = when {
    status == 401 || status == 403 -> ToolErrorCodes.AUTH to false
    status == 429 -> ToolErrorCodes.RATE_LIMIT to true
    status >= 500 -> ToolErrorCodes.REMOTE_ERROR to true
    else -> ToolErrorCodes.REMOTE_ERROR to false
}

/**
 * 工具执行结果。
 * - [raw]:原始输出(日志/预览用)
 * - [wrapped]:回喂模型的视图 —— 分隔符包裹(提示注入防护 FR-E4);
 *   错误结果为结构化 JSON(G2):`{"error":{"code","retryable","message"}}`
 * - [flagged]:InjectionScanner 命中警示(由 ToolRegistry 统一扫描后标记)
 * - [citations]:出网工具提取的来源;其余为空
 * - [errorCode]/[retryable]:G2 稳定错误码与可重试标记(仅 ok=false 时有值)
 */
data class ToolResult(
    val ok: Boolean,
    val raw: String,
    val wrapped: String,
    val citations: List<Citation> = emptyList(),
    val flagged: Boolean = false,
    val errorCode: String? = null,
    val retryable: Boolean = false,
) {
    companion object {
        fun ok(raw: String, wrapped: String, citations: List<Citation> = emptyList()): ToolResult =
            ToolResult(ok = true, raw = raw, wrapped = wrapped, citations = citations)

        @JvmOverloads
        fun error(
            message: String,
            code: String = ToolErrorCodes.INTERNAL,
            retryable: Boolean = false,
        ): ToolResult {
            val structured = org.json.JSONObject()
                .put(
                    "error",
                    org.json.JSONObject()
                        .put("code", code)
                        .put("retryable", retryable)
                        .put("message", message),
                )
                .toString()
            return ToolResult(
                ok = false,
                raw = message,
                wrapped = ToolResultWrapper.wrap(structured),
                errorCode = code,
                retryable = retryable,
            )
        }
    }
}

/**
 * 工具输出分隔包裹(FR-E4):声明边界,防工具结果被当作指令。
 * wrap 前中和内容中出现的分隔符字面量(P2-6):替换为全角变体,防伪造假边界提前闭合包裹。
 */
object ToolResultWrapper {
    const val OPEN = "<<<TOOL_RESULT>>>"
    const val CLOSE = "<<<END_TOOL_RESULT>>>"
    const val OPEN_NEUTRALIZED = "⟨⟨TOOL_RESULT⟩⟩(数据内标记,非真实边界)"
    const val CLOSE_NEUTRALIZED = "⟨⟨END_TOOL_RESULT⟩⟩(数据内标记,非真实边界)"

    fun wrap(content: String): String {
        val neutralized = content.replace(OPEN, OPEN_NEUTRALIZED).replace(CLOSE, CLOSE_NEUTRALIZED)
        return "$OPEN\n$neutralized\n$CLOSE"
    }
}

/**
 * 工具接口(tools/ 层实现;agent 层经 ToolRegistry 调度)。
 */
interface Tool {
    /** 唯一标识(web_search / fetch_page / read_context / insert_text / kb_search / ask_user) */
    val id: String

    /** 面向用户/日志的名称 */
    val name: String

    /** JSON Schema(手写字符串,保证与 LLM 暴露的 schema 稳定) */
    val schemaJson: String

    /** 给 LLM 的能力描述 */
    val description: String

    /** 是否出网工具(决定是否过 PermissionGate 的出网派生判定) */
    val isOutbound: Boolean

    /** 出网目标类别(activity_log.target:搜索服务/网页域名;本地工具为"本机") */
    val targetLabel: String

    suspend fun execute(argsJson: String): ToolResult

    /**
     * 出网载荷文本(P2-5):供权限派生判定与日志 preview 使用。
     * 默认返回 argsJson 原文;工具应提取实际外发的 query/URL 等字段,避免参数名/引号噪音进日志与判定。
     */
    fun paramsText(argsJson: String): String = argsJson
}

/**
 * 工具 schema 暴露(llm tools 参数)。
 */
data class ToolSchema(val id: String, val name: String, val description: String, val schemaJson: String)
