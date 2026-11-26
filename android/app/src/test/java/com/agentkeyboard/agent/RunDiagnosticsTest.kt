package com.agentkeyboard.agent

import com.agentkeyboard.ai.TokenUsage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 运行诊断:调试信息只由分类、状态码与计数组成,不读异常原文与对话内容 */
class RunDiagnosticsTest {

    @After
    fun clear() = RunDiagnostics.clear()

    @Test
    fun `describe - 只有分类 状态码 错误码与计数 不读 message`() {
        val error = AgentError("RATE_LIMIT", "RAW-EXCEPTION-TEXT 用户的提问原文", httpStatus = 429, providerCode = "rate_limit_exceeded")
        val summary = AgentRunSummary(
            steps = listOf(
                AgentTraceStep(1, "llm", 900),
                AgentTraceStep(1, "retry", 300, ok = false),
                AgentTraceStep(2, "web_search", 200),
                AgentTraceStep(2, AgentTraceStep.KIND_CONTEXT, 10, ok = false),
                AgentTraceStep(2, AgentTraceStep.KIND_REJECTED, 0, ok = false),
                AgentTraceStep(2, AgentTraceStep.KIND_JUDGE, 1500),
            ),
            usage = TokenUsage(1200, 300, 1500),
            stopReason = StopReason.TIME_BUDGET,
            firstTokenMs = 2_050,
            totalMs = 12_400,
        )
        val text = RunDiagnostics.describe(error, summary)
        assertTrue(text, text.startsWith("RATE_LIMIT · HTTP 429 · rate_limit_exceeded"))
        assertTrue(text, text.contains("首 token 2.1s"))
        assertTrue(text, text.contains("总计 12s"))
        assertTrue(text, text.contains("模型 1 次"))
        assertTrue(text, text.contains("工具 1 次")) // 未执行的调用、上下文收紧与模型复核都不算工具调用
        assertTrue(text, text.contains("安检 1 次"))
        assertTrue(text, text.contains("重试 1 次"))
        assertTrue(text, text.contains("收紧上下文 1 次"))
        assertTrue(text, text.contains("token 1200+300"))
        assertTrue(text, text.contains("时间预算用尽"))
        assertFalse("异常原文不进调试信息", text.contains("RAW-EXCEPTION-TEXT"))
        assertFalse("对话内容不进调试信息", text.contains("用户的提问原文"))
    }

    @Test
    fun `describe - 没有错误与用量时只给耗时`() {
        val text = RunDiagnostics.describe(null, AgentRunSummary(firstTokenMs = 300, totalMs = 999))
        assertEquals("首 token 0.3s · 总计 1.0s", text)
        assertEquals("", RunDiagnostics.describe(null, AgentRunSummary()))
    }

    @Test
    fun `line - 时间 任务类型 结果 调试信息`() {
        val record = RunRecord(
            at = 0L,
            taskType = TaskType.KNOWLEDGE_ASK,
            outcome = "INCOMPLETE",
            error = null,
            summary = AgentRunSummary(partial = true, stopReason = StopReason.STEP_LIMIT, totalMs = 3_000),
        )
        val line = RunDiagnostics.line(record)
        assertTrue(line, line.contains("KNOWLEDGE_ASK · INCOMPLETE(部分)"))
        assertTrue(line, line.contains("总计 3.0s"))
        assertTrue(line, line.contains("步数用尽"))
    }

    @Test
    fun `record - 只保留最近 20 条 新的在前`() {
        repeat(25) { i ->
            RunDiagnostics.record(RunRecord(i.toLong(), TaskType.ASK, "FINISHED", error = null, summary = AgentRunSummary()))
        }
        val recent = RunDiagnostics.recent.value
        assertEquals(RunDiagnostics.MAX_RECORDS, recent.size)
        assertEquals(24L, recent.first().at)
        assertEquals(5L, recent.last().at)
    }
}
