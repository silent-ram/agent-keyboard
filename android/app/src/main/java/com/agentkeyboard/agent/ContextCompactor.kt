package com.agentkeyboard.agent

import com.agentkeyboard.ai.EngineMessage
import com.agentkeyboard.tools.ToolResultWrapper

/**
 * 多轮上下文折叠(FR-B10 / 12-对话面板设计 §6,纯函数):
 * - 预算 = contextWindow × 50%(缺省 8k → 4k tokens);
 * - 仅被折叠的旧轮次中工具结果压缩为摘要行(保留 toolCallId 与 assistant.toolCalls 配对,协议必需);
 *   摘要取包裹内正文并重新包裹,分隔符始终成对(提示注入边界不被截断破坏);
 * - 首条用户输入压缩为 ≤200 字摘要;仍超预算自最旧整轮丢弃并附系统注记;
 * - 丢弃旧轮后仍超预算(当前轮多步工具结果累积):折叠当前轮较早步骤的工具结果,最新一批保留全文;
 * - token 估算:中文 1 字 ≈1 token,英文 4 字符 ≈1 token(保守),assistant 的工具调用参数一并计入。
 */
object ContextCompactor {

    const val FIRST_USER_SUMMARY_LIMIT = 200
    const val TOOL_SUMMARY_LIMIT = 80
    /** 当前轮较早步骤的工具结果是本轮证据,比旧轮保留更长摘要 */
    const val CURRENT_TURN_TOOL_SUMMARY_LIMIT = 400
    const val FOLD_BUDGET_RATIO = 2 // 除数:contextWindow / 2 = 50%
    const val FOLDED_PREFIX = "[工具结果已折叠]"
    const val OMITTED_MARK = "…(中间内容已省略)"

    /** 句末/段落边界字符:压缩优先在这些位置断开,避免把一句话切成两半 */
    private val SENTENCE_BREAKS = charArrayOf('。', '！', '？', '；', '!', '?', ';', '.', '\n')

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
        // 码点区间:CJK 统一汉字 / 扩展 A / CJK 标点 / 全角字符
        c.code in 0x4E00..0x9FFF || c.code in 0x3400..0x4DBF || c.code in 0x3000..0x303F ||
            c.code in 0xFF00..0xFFEF

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
        val dropped = dropOldest(folded, budgetTokens, currentTurnStart)
        if (totalTokens(dropped) <= budgetTokens) return dropped
        // 当前轮从不丢弃,丢弃后其位置固定在表尾,据此换算新起点
        val currentLen = messages.size - currentTurnStart
        val newStart = (dropped.size - currentLen).coerceIn(0, dropped.size)
        return foldEarlierCurrentTurnTools(dropped, newStart)
    }

    /** 旧轮折叠:tool → 摘要行;第一条 user → ≤200 字;其余保留 */
    internal fun foldOldToolResults(messages: List<EngineMessage>, currentTurnStart: Int): List<EngineMessage> {
        var firstUserSeen = false
        return messages.mapIndexedNotNull { i, m ->
            when {
                i >= currentTurnStart -> m
                m.role == "tool" -> foldTool(m, TOOL_SUMMARY_LIMIT)
                m.role == "user" && !firstUserSeen -> {
                    firstUserSeen = true
                    if (m.content.length > FIRST_USER_SUMMARY_LIMIT) {
                        // 首条提问只取开头(任务意图在前),但仍在句子边界切断
                        m.copy(content = clipAtBoundary(m.content, FIRST_USER_SUMMARY_LIMIT) + "…(已精简)")
                    } else m
                }
                else -> m
            }
        }
    }

    /**
     * 当前轮内:最后一个带 toolCalls 的 assistant 之前的工具结果折叠为摘要(较长),
     * 其后的最新一批工具结果保留全文——模型下一步最依赖的是刚拿到的结果。
     */
    internal fun foldEarlierCurrentTurnTools(messages: List<EngineMessage>, currentTurnStart: Int): List<EngineMessage> {
        val lastBatch = (messages.lastIndex downTo currentTurnStart).firstOrNull {
            messages[it].role == "assistant" && !messages[it].toolCalls.isNullOrEmpty()
        } ?: return messages
        return messages.mapIndexed { i, m ->
            if (i in currentTurnStart until lastBatch && m.role == "tool") {
                foldTool(m, CURRENT_TURN_TOOL_SUMMARY_LIMIT)
            } else {
                m
            }
        }
    }

    /** 摘要取包裹内正文再重新包裹:旧实现直接 take 整段,开标记占去大半额度且闭标记被截掉 */
    private fun foldTool(m: EngineMessage, limit: Int): EngineMessage {
        if (m.content.startsWith(FOLDED_PREFIX)) return m
        val inner = unwrap(m.content).trim()
        val summary = semanticClip(inner, limit)
        val label = m.toolName ?: m.toolCallId.orEmpty()
        return m.copy(content = "$FOLDED_PREFIX $label:" + ToolResultWrapper.wrap(summary))
    }

    /**
     * 语义化压缩:取开头一段 + 结尾一段,中间标注省略。
     * 工具结果的关键结论常在末尾(结论、错误码、要点),只留开头会丢掉最有用的部分。
     * 输出总长度不超过 [limit]:预算里先扣掉标注本身,否则"压缩"反而会超预算。
     */
    internal fun semanticClip(text: String, limit: Int): String {
        if (text.length <= limit) return text
        val budget = (limit - OMITTED_MARK.length - 2).coerceAtLeast(limit / 2)
        if (budget <= 0) return text.take(limit)
        val headLimit = (budget * 2) / 3
        val tailLimit = budget - headLimit
        val head = clipAtBoundary(text, headLimit)
        val tail = clipAtBoundaryFromEnd(text, tailLimit)
        return head + "\n" + OMITTED_MARK + "\n" + tail
    }

    /** 从 0 开始按边界截断:优先段落,其次句末标点 */
    internal fun clipAtBoundary(text: String, limit: Int): String {
        if (limit <= 0) return ""
        if (text.length <= limit) return text
        val window = text.take(limit)
        val cut = maxOf(window.lastIndexOf("\n\n"), window.lastIndexOfAny(SENTENCE_BREAKS))
        // 边界太靠前(不足一半)时硬截断,避免只留下一句残句
        return if (cut > limit / 2) window.take(cut + 1).trimEnd() else window.trimEnd()
    }

    /** 从末尾按边界截断,保留结尾结论 */
    internal fun clipAtBoundaryFromEnd(text: String, limit: Int): String {
        if (limit <= 0) return ""
        if (text.length <= limit) return text
        val window = text.substring(text.length - limit)
        // 段落分隔是两个字符,单换行算一个:分别找,取更靠前的起点并跳过完整分隔符
        val paragraph = window.indexOf("\n\n").takeIf { it >= 0 }
        val sentence = SENTENCE_BREAKS
            .filter { it != '\n' }
            .map { c -> window.indexOf(c) }
            .filter { it >= 0 }
            .minOrNull()
        val singleBreak = window.indexOf('\n').takeIf { it >= 0 }
        val cut = minOf(
            paragraph ?: Int.MAX_VALUE,
            sentence ?: Int.MAX_VALUE,
            singleBreak ?: Int.MAX_VALUE,
        )
        if (cut == Int.MAX_VALUE || cut >= limit / 2) return window.trimStart()
        // 跳到分隔符之后:段落分隔占两位
        val skip = if (paragraph != null && cut == paragraph) 2 else 1
        return window.substring((cut + skip).coerceAtMost(window.length)).trimStart()
    }

    private fun unwrap(content: String): String {
        val t = content.trim()
        return if (t.startsWith(ToolResultWrapper.OPEN) && t.endsWith(ToolResultWrapper.CLOSE)) {
            t.removePrefix(ToolResultWrapper.OPEN).removeSuffix(ToolResultWrapper.CLOSE)
        } else {
            t
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

    /** 工具调用的名称与参数同样会发给模型,必须计入,否则多工具轮次会被低估 */
    private fun tokenOf(m: EngineMessage): Int =
        estimateTokens(m.content) + estimateTokens(m.toolName.orEmpty()) +
            (m.toolCalls?.sumOf { estimateTokens(it.name) + estimateTokens(it.argumentsJson) } ?: 0)

    internal fun totalTokens(messages: List<EngineMessage>): Int = messages.sumOf { tokenOf(it) }
}
