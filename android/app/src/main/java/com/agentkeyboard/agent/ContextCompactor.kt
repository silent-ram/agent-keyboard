package com.agentkeyboard.agent

import com.agentkeyboard.ai.EngineMessage

/**
 * 多轮上下文折叠(FR-B10 / 12-对话面板设计 §6,纯函数):
 * - 预算 = contextWindow × 50%(缺省 8k → 4k tokens);
 * - 仅被折叠的旧轮次中工具结果压缩为摘要行(保留 toolCallId 与 assistant.toolCalls 配对,协议必需);
 * - 当前轮全文;首条用户输入压缩为 ≤200 字摘要;仍超预算自最旧整轮丢弃并附系统注记;
 * - token 估算:中文 1 字 ≈1 token,英文 4 字符 ≈1 token(保守)。
 */
object ContextCompactor {

    const val FIRST_USER_SUMMARY_LIMIT = 200
    const val TOOL_SUMMARY_LIMIT = 80
    const val FOLD_BUDGET_RATIO = 2 // 除数:contextWindow / 2 = 50%

    /** 中文 1 字 1 token;其余按 4 字符 1 token */
    fun estimateTokens(text: String): Int {
        var cjk = 0
        var other = 0
        for (c in text) {
            if (isCjk(c)) cjk++ else other++
        }
        return cjk + (other + 3) / 4
    }

    private fun isCjk(c: Char): Boolean =
        (c in '\u4E00'..'\u9FFF') || (c in '\u3400'..'\u4DBF') || (c in '\u3000'..'\u303F') ||
            (c in '\uFF00'..'\uFFEF')

    fun foldBudget(contextWindowTokens: Int): Int = (contextWindowTokens / FOLD_BUDGET_RATIO).coerceAtLeast(256)

    /**
     * [currentTurnStart]:当前轮第一条消息(通常是本轮 user 消息)的索引;
     * 小于该索引的为"旧轮"。单轮任务传 1(system + user)。
     */
    fun compact(messages: List<EngineMessage>, budgetTokens: Int, currentTurnStart: Int): List<EngineMessage> {
        if (messages.isEmpty()) return messages
        val folded = foldOldToolResults(messages, currentTurnStart)
        if (totalTokens(folded) <= budgetTokens) return folded
        // 仍超:自最旧(保 system)整轮丢弃,直到预算内
        return dropOldest(folded, budgetTokens, currentTurnStart)
    }

    /** 旧轮折叠:tool → 摘要行;第一条 user → ≤200 字;其余保留 */
    internal fun foldOldToolResults(messages: List<EngineMessage>, currentTurnStart: Int): List<EngineMessage> {
        var firstUserSeen = false
        return messages.mapIndexedNotNull { i, m ->
            when {
                i >= currentTurnStart -> m
                m.role == "tool" -> m.copy(
                    content = "[工具结果已折叠] ${m.toolName ?: m.toolCallId.orEmpty()}:${m.content.take(TOOL_SUMMARY_LIMIT)}"
                )
                m.role == "user" && !firstUserSeen -> {
                    firstUserSeen = true
                    if (m.content.length > FIRST_USER_SUMMARY_LIMIT) {
                        m.copy(content = m.content.take(FIRST_USER_SUMMARY_LIMIT) + "…(已精简)")
                    } else m
                }
                else -> m
            }
        }
    }

    /**
     * 自最旧(保留 system 与当前轮)按"轮"为整体单位丢弃:一组 = user 起到下一个 user 之前的所有
     * assistant/tool 消息。整组同丢保证 assistant.toolCalls 与 tool 结果成对,不产生孤儿
     * (孤儿 tool 消息/无响应的 tool_calls 会导致 OpenAI 兼容 API 400)。
     * 保留/丢弃判定基于原始索引区间,不用 data class equals(P1-1 附带:内容相同的两条消息会被误判受保护)。
     */
    internal fun dropOldest(messages: List<EngineMessage>, budgetTokens: Int, currentTurnStart: Int): List<EngineMessage> {
        // 轮边界:每条 user 索引 + 当前轮起点 + 表尾
        val boundaries = sortedSetOf(1, currentTurnStart, messages.size)
        for (idx in 1 until messages.size) {
            if (messages[idx].role == "user") boundaries.add(idx)
        }
        val b = boundaries.toList()
        val oldGroups = mutableListOf<IntRange>()
        for (j in 0 until b.size - 1) {
            val s = b[j]
            val e = b[j + 1]
            if (s >= 1 && s < currentTurnStart && s < e) oldGroups.add(s until e)
        }
        if (oldGroups.isEmpty()) return messages

        val keep = BooleanArray(messages.size) { true }
        var droppedAny = false
        fun keptTokens(): Int {
            var t = 0
            for (i in messages.indices) if (keep[i]) t += tokenOf(messages[i])
            return t
        }
        for (g in oldGroups) {
            if (keptTokens() <= budgetTokens) break
            for (i in g) keep[i] = false
            droppedAny = true
        }
        if (!droppedAny) return messages
        val out = messages.filterIndexed { i, _ -> keep[i] }.toMutableList()
        out.add(1, EngineMessage(role = "system", content = "(早期对话已精简)"))
        return out
    }

    private fun tokenOf(m: EngineMessage): Int = estimateTokens(m.content) + estimateTokens(m.toolName.orEmpty())

    internal fun totalTokens(messages: List<EngineMessage>): Int =
        messages.sumOf { estimateTokens(it.content) + estimateTokens(it.toolName.orEmpty()) }
}
