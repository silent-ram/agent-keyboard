package com.agentkeyboard.agent.permission

/**
 * 污染快照:本次任务中已读到的用户原文(11-权限设计 §4.2)。
 */
data class TaintSnapshot(
    val contextText: String? = null,
    val clipboardText: String? = null,
    val profileEntries: List<String> = emptyList(),
) {
    companion object {
        val EMPTY = TaintSnapshot(null, null)
    }
}

/** 用户内容来源(activity_log.contains_user_content) */
enum class UserContentSource { CONTEXT, CLIPBOARD }

/** 判定结果:Allow = LLM 改写产物,放行;Confirm = 命中底线,必须当场确认 */
sealed class Decision {
    data object Allow : Decision()
    data class Confirm(
        val reason: String,
        val preview: String,
        val source: UserContentSource,
    ) : Decision()
}

/**
 * 出网派生内容判定(11-权限设计 §4,纯本地字符串运算,可单测):
 * - 规则 A:原文任一 ≥10 字连续片段(滑窗步长 4)逐字命中请求参数 → 确认
 * - 规则 B:≤120 字短剪贴板与请求参数 2-gram Jaccard ≥0.6 → 确认
 * - LLM 改写产物不算派生,放行
 * 适用范围:仅 web_search / fetch_page / MCP 远端工具;llm_call 不做派生判定(§4.1)。
 */
object ClassifyOutbound {

    const val CHUNK_SIZE = 10
    const val CHUNK_STEP = 4
    const val SHORT_CLIPBOARD_MAX = 120
    const val JACCARD_THRESHOLD = 0.6

    fun classify(paramsText: String, snap: TaintSnapshot): Decision {
        val text = normalize(paramsText)
        for (entry in snap.profileEntries.flatMap { it.lines() }) {
            val value = normalize(entry)
            if (value.isNotEmpty() && (value in text || slidingChunks(value).any { it in text })) {
                return Decision.Confirm("包含你的记忆或用户说明原文", entry, UserContentSource.CONTEXT)
            }
        }
        // 规则 A:逐字命中(剪贴板优先于上下文,与伪代码顺序一致)
        for (src in listOfNotNull(
            snap.clipboardText?.let { Tainted(it, UserContentSource.CLIPBOARD, "剪贴板") },
            snap.contextText?.let { Tainted(it, UserContentSource.CONTEXT, "输入框") },
        )) {
            val n = normalize(src.text)
            if (slidingChunks(n).any { it in text }) {
                return Decision.Confirm(
                    reason = "包含你${src.label}中的原文",
                    preview = src.text,
                    source = src.kind,
                )
            }
        }
        // 规则 B:短剪贴板高相似
        val cb = snap.clipboardText?.let(::normalize)
        if (cb != null && cb.length in 1..SHORT_CLIPBOARD_MAX && bigramJaccard(cb, text) >= JACCARD_THRESHOLD) {
            return Decision.Confirm(
                reason = "与剪贴板内容高度相似",
                preview = snap.clipboardText ?: "",
                source = UserContentSource.CLIPBOARD,
            )
        }
        return Decision.Allow
    }

    private data class Tainted(val text: String, val kind: UserContentSource, val label: String)

    /** 归一化:去空白、全角→半角、英文小写 */
    internal fun normalize(s: String): String = buildString(s.length) {
        for (c in s) {
            when {
                c.isWhitespace() -> {}
                c in '\uFF01'..'\uFF5E' -> append((c.code - 0xFEE0).toChar())
                c == '\u3000' -> {}
                else -> append(c)
            }
        }
    }.lowercase()

    /** ≥10 字滑窗片段,步长 4,末尾补收口片段防跨片段绕过 */
    internal fun slidingChunks(s: String, size: Int = CHUNK_SIZE, step: Int = CHUNK_STEP): List<String> {
        if (s.length < size) return emptyList()
        val chunks = mutableListOf<String>()
        var i = 0
        while (i + size <= s.length) {
            chunks.add(s.substring(i, i + size))
            i += step
        }
        val last = s.substring(s.length - size)
        if (chunks.last() != last) chunks.add(last)
        return chunks
    }

    /** 字符 2-gram Jaccard 相似度 */
    internal fun bigramJaccard(a: String, b: String): Double {
        if (a.length < 2 || b.length < 2) return if (a == b) 1.0 else 0.0
        val ga = a.windowed(2).toSet()
        val gb = b.windowed(2).toSet()
        val union = ga + gb
        if (union.isEmpty()) return 0.0
        return ga.intersect(gb).size.toDouble() / union.size
    }
}
