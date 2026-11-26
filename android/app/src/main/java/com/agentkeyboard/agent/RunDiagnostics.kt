package com.agentkeyboard.agent

import com.agentkeyboard.agent.permission.LogSanitizer
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 一次运行的诊断记录:只有分类、计数与耗时,不含问题、回答、工具参数或请求内容。
 * 只留在进程内存里,不落盘。
 */
data class RunRecord(
    /** 结束时刻(墙上时钟) */
    val at: Long,
    val taskType: TaskType,
    /** FINISHED / INCOMPLETE / FAILED / STOPPED */
    val outcome: String,
    val error: AgentError?,
    val summary: AgentRunSummary,
)

/**
 * 运行诊断:最近若干次运行的摘要,供 Agent 配置页的诊断区块查看。
 * 输入法和 App 在同一进程,两边的运行都记在这里;每条都是终态事件里已有的信息。
 */
object RunDiagnostics {

    const val MAX_RECORDS = 20

    private val _recent = MutableStateFlow<List<RunRecord>>(emptyList())
    val recent: StateFlow<List<RunRecord>> get() = _recent

    fun record(record: RunRecord) {
        _recent.value = (listOf(record) + _recent.value).take(MAX_RECORDS)
    }

    fun clear() {
        _recent.value = emptyList()
    }

    /**
     * 错误卡与诊断页共用的调试信息:错误分类、状态码、错误码与各项计数。
     * 只由标识符和数字组成,可以直接进界面、剪贴板和日志。
     */
    fun describe(error: AgentError?, summary: AgentRunSummary): String {
        val parts = mutableListOf<String>()
        error?.let {
            parts += it.kind
            it.httpStatus?.let { status -> parts += "HTTP $status" }
            it.providerCode?.let { code -> parts += code }
        }
        summary.firstTokenMs?.let { parts += "首 token ${seconds(it)}" }
        if (summary.totalMs > 0) parts += "总计 ${seconds(summary.totalMs)}"
        if (summary.llmCalls > 0) parts += "模型 ${summary.llmCalls} 次"
        if (summary.toolCalls > 0) parts += "工具 ${summary.toolCalls} 次"
        if (summary.judgeCalls > 0) parts += "安检 ${summary.judgeCalls} 次"
        if (summary.retries > 0) parts += "重试 ${summary.retries} 次"
        if (summary.contextShrinks > 0) parts += "收紧上下文 ${summary.contextShrinks} 次"
        if (!summary.usage.isEmpty) parts += "token ${summary.promptTokens}+${summary.completionTokens}"
        summary.stopReason?.let { parts += stopLabel(it) }
        return LogSanitizer.mask(parts.joinToString(" · "))
    }

    /** 诊断页的一行 */
    fun line(record: RunRecord): String {
        val time = java.text.SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(java.util.Date(record.at))
        val outcome = record.outcome + if (record.summary.partial) "(部分)" else ""
        return "$time · ${record.taskType.name} · $outcome · ${describe(record.error, record.summary)}"
    }

    fun stopLabel(reason: StopReason): String = when (reason) {
        StopReason.STEP_LIMIT -> "步数用尽"
        StopReason.TIME_BUDGET -> "时间预算用尽"
        StopReason.TOKEN_BUDGET -> "token 预算用尽"
        StopReason.MODEL_LENGTH -> "达到输出长度上限"
        StopReason.FILTERED -> "被内容过滤中断"
    }

    private fun seconds(ms: Long): String =
        if (ms in 1..9_999) String.format(Locale.US, "%.1fs", ms / 1000.0) else "${ms / 1000}s"
}
