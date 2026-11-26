package com.agentkeyboard.agent

import com.agentkeyboard.ai.EngineMessage
import com.agentkeyboard.tools.ToolResultWrapper

/**
 * 多轮上下文折叠(FR-B10 / 12-对话面板设计 §6,纯函数):
 * - 预算 = contextWindow × 70%,其余 30% 留给工具定义、本次输出和 token 估算误差;
 * - 预算内不折叠:完整原文比摘要可靠,折叠只在超预算时发生;
 * - 超预算时逐级收紧,每级之后预算内即停:
 *   1. 旧轮的工具结果压缩为摘要行(保留 toolCallId 与 assistant.toolCalls 配对,协议必需),
 *      首条用户输入压到 ≤200 字;摘要取包裹内正文并重新包裹,分隔符始终成对
 *      (提示注入边界不被截断破坏);
 *   2. 旧轮自最旧起精简为「问句 + 回答」两段——去掉工具交换,角色保持不变,
 *      不把旧回答提升为系统消息;仍超则自最旧整轮丢弃,并附系统注记;
 *   3. 当前轮从不丢弃:当前轮较早步骤的工具结果折叠为较长摘要,最新一批保留全文。
 * - token 估算:中文 1 字 ≈1 token,英文 4 字符 ≈1 token(保守),assistant 的工具调用参数一并计入。
 */
object ContextCompactor {

    const val FIRST_USER_SUMMARY_LIMIT = 200
    const val TOOL_SUMMARY_LIMIT = 80
    /** 当前轮较早步骤的工具结果是本轮证据,比旧轮保留更长摘要 */
    const val CURRENT_TURN_TOOL_SUMMARY_LIMIT = 400
    /** 精简旧轮时问句与回答各留的长度(字符) */
    const val TRIMMED_QUESTION_LIMIT = 60
    const val TRIMMED_ANSWER_LIMIT = 160
    /** 折叠预算占上下文窗口的百分比 */
    const val FOLD_BUDGET_PERCENT = 70
    const val FOLD_BUDGET_MIN_TOKENS = 256
    const val FOLDED_PREFIX = "[工具结果已折叠]"
    const val OMITTED_MARK = "…(中间内容已省略)"
    const val TRIMMED_NOTE = "(早期对话已精简)"

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

    fun foldBudget(contextWindowTokens: Int): Int =
        (contextWindowTokens.toLong() * FOLD_BUDGET_PERCENT / 100).coerceAtLeast(FOLD_BUDGET_MIN_TOKENS.toLong()).toInt()

    /**
     * [currentTurnStart]:当前轮第一条消息(通常是本轮 user 消息)的索引;
     * 小于该索引的为"旧轮"。单轮任务传 1(system + user)。
     */
    fun compact(messages: List<EngineMessage>, budgetTokens: Int, currentTurnStart: Int): List<EngineMessage> {
        if (messages.isEmpty()) return messages
        val turnStart = currentTurnStart.coerceIn(0, messages.size)
        // 预算内原样发送
        if (totalTokens(messages) <= budgetTokens) return messages
        val folded = foldOldToolResults(messages, turnStart)
        if (totalTokens(folded) <= budgetTokens) return folded
        val trimmed = dropOldest(folded, budgetTokens, turnStart)
        if (totalTokens(trimmed) <= budgetTokens) return trimmed
        // 当前轮从不丢弃,丢弃后其位置固定在表尾,据此换算新起点
        val currentLen = messages.size - turnStart
        val newStart = (trimmed.size - currentLen).coerceIn(0, trimmed.size)
        return foldEarlierCurrentTurnTools(trimmed, newStart)
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
        val folded = m.copy(content = "$FOLDED_PREFIX $label:" + ToolResultWrapper.wrap(summary))
        // 短结果加前缀和包裹反而更长:这种折叠只增不减,原样保留
        return if (estimateTokens(folded.content) < estimateTokens(m.content)) folded else m
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
     * 旧轮收紧:自最旧起先把旧轮精简为「问句 + 回答」,仍超预算再自最旧整轮丢弃,直到预算内。
     * 一组 = user 起到下一个 user 之前的所有 assistant/tool 消息,整组同进退保证
     * assistant.toolCalls 与 tool 结果成对,不产生孤儿(孤儿 tool 消息或无响应的 tool_calls
     * 会导致 OpenAI 兼容 API 400)。精简保留原角色:旧回答不因此获得系统消息的地位。
     * 保留/丢弃判定基于原始索引区间,不用 data class equals(内容相同的两条消息会被误判受保护)。
     */
    internal fun dropOldest(messages: List<EngineMessage>, budgetTokens: Int, currentTurnStart: Int): List<EngineMessage> {
        val groups = oldTurnGroups(messages, currentTurnStart)
        if (groups.isEmpty()) return messages

        val trimmedForms = groups.associate { g -> g.first() to trimmedTurn(messages, g) }
        val fullTokens = groups.map { g -> g.sumOf { tokenOf(messages[it]) } }
        /** 每组的形态:0 原文 / 1 精简 / 2 丢弃 */
        val form = IntArray(groups.size)
        val owner = IntArray(messages.size) { -1 }
        groups.forEachIndexed { gi, g -> g.forEach { owner[it] = gi } }

        fun keptTokens(): Int {
            // 只要有旧轮被动过就会带一条注记,先计入
            var t = estimateTokens(TRIMMED_NOTE)
            // 不属于任何旧轮的消息:system 与当前轮,原样保留
            for (i in 0 until currentTurnStart) if (owner[i] < 0) t += tokenOf(messages[i])
            for (i in currentTurnStart until messages.size) t += tokenOf(messages[i])
            // 精简形态是整组合并后的消息,按组计一次
            for (gi in groups.indices) {
                when (form[gi]) {
                    0 -> t += fullTokens[gi]
                    1 -> t += tokenOf(trimmedForms.getValue(groups[gi].first()) ?: emptyList())
                }
            }
            return t
        }

        // 第一遍:能精简的旧轮自最旧起精简;没有回答可留的旧轮只能丢弃
        for (gi in groups.indices) {
            if (keptTokens() <= budgetTokens) break
            form[gi] = if (trimmedForms.containsKey(groups[gi].first())) 1 else 2
        }
        // 第二遍:仍超预算,自最旧整轮丢弃
        for (gi in groups.indices) {
            if (keptTokens() <= budgetTokens) break
            form[gi] = 2
        }
        if (form.all { it == 0 }) return messages

        val out = ArrayList<EngineMessage>(messages.size)
        out += messages[0]
        out += EngineMessage(role = "system", content = TRIMMED_NOTE)
        var i = 1
        while (i < messages.size) {
            val gi = owner[i]
            if (gi < 0) {
                out += messages[i]
                i++
                continue
            }
            when (form[gi]) {
                0 -> groups[gi].forEach { out += messages[it] }
                1 -> out += trimmedForms.getValue(i).orEmpty()
            }
            i = groups[gi].last() + 1
        }
        return out
    }

    /** 旧轮分组:[1, currentTurnStart) 内按 user 起点切分,每组是连续的索引区间 */
    private fun oldTurnGroups(messages: List<EngineMessage>, currentTurnStart: Int): List<IntRange> {
        // 轮边界:每条 user 索引 + 当前轮起点 + 表尾
        val boundaries = sortedSetOf(1, currentTurnStart, messages.size)
        for (idx in 1 until messages.size) {
            if (messages[idx].role == "user") boundaries.add(idx)
        }
        val b = boundaries.toList()
        return (0 until b.size - 1).mapNotNull { j ->
            val s = b[j]
            val e = b[j + 1]
            if (s >= 1 && s < currentTurnStart && s < e) s until e else null
        }
    }

    /**
     * 旧轮的精简形态:问句截到 [TRIMMED_QUESTION_LIMIT],最后一段有字的回答截到 [TRIMMED_ANSWER_LIMIT];
     * 工具调用与工具结果全部去掉。没有回答可留时返回 null(这种旧轮只能整组丢弃)。
     */
    private fun trimmedTurn(messages: List<EngineMessage>, group: IntRange): List<EngineMessage>? {
        val answer = group.lastOrNull {
            messages[it].role == "assistant" && messages[it].content.isNotBlank()
        } ?: return null
        return buildList {
            group.firstOrNull { messages[it].role == "user" }?.let { q ->
                add(messages[q].copy(content = shortened(messages[q].content, TRIMMED_QUESTION_LIMIT, ::clipAtBoundary)))
            }
            add(
                messages[answer].copy(
                    content = shortened(messages[answer].content, TRIMMED_ANSWER_LIMIT, ::semanticClip),
                    toolCalls = null,
                    toolCallId = null,
                    toolName = null,
                ),
            )
        }
    }

    private fun shortened(text: String, limit: Int, clip: (String, Int) -> String): String =
        if (text.length <= limit) text else clip(text, limit).ifBlank { text.take(limit) } + "…(已精简)"

    /** 工具调用的名称与参数同样会发给模型,必须计入,否则多工具轮次会被低估 */
    private fun tokenOf(m: EngineMessage): Int =
        estimateTokens(m.content) + estimateTokens(m.toolName.orEmpty()) +
            (m.toolCalls?.sumOf { estimateTokens(it.name) + estimateTokens(it.argumentsJson) } ?: 0)

    private fun tokenOf(messages: List<EngineMessage>): Int = messages.sumOf { tokenOf(it) }

    internal fun totalTokens(messages: List<EngineMessage>): Int = messages.sumOf { tokenOf(it) }
}
