package com.agentkeyboard.agent

import com.agentkeyboard.tools.Citation
import com.agentkeyboard.tools.InjectionScanner
import com.agentkeyboard.data.db.AgentMessageEntity
import com.agentkeyboard.data.db.MessageRoles
import com.agentkeyboard.tools.CitationJson
import com.agentkeyboard.ui.formatAnswerText
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONArray
import org.json.JSONObject

/** 需要保护的配置值,按敏感程度分两类(口径见 [AgentContentSafety]) */
class ProtectedConfig(
    /** 模型与服务商名称、服务地址、资料库名称/ID:不是秘密 */
    val identifiers: Collection<String> = emptyList(),
    /** API Key、令牌 */
    val secrets: Collection<String> = emptyList(),
)

/**
 * 内容安全的判定口径,分三类,不要混用:
 * - **凭据**([secrets] 里的 Key/令牌原文,以及长得像凭据的字符串):出现在哪里都按泄露处理——
 *   不进模型、不落盘、不外发。这是客观事实,没有「像不像」可争。
 * - **标识**([identifiers]:模型与服务商名称、服务地址、资料库名称/ID):不是秘密。用户自己说过的话里
 *   出现很正常,资料里出现更正常(资料库按主题命名时,篇篇都会提到它)。只有**模型说出来的、且用户
 *   没提过**的标识才在回答与引用里遮盖;外发参数里带上它才拦。
 * - **指令样文字**:只对不可信来源(工具结果、MCP 工具描述)判定,且规则命中只是触发复核。
 *   用户自己的输入、模型自己的回答不判——那里出现多半是在讨论、翻译或改写,判了只会误伤。
 */
internal class AgentContentSafety(
    identifiers: Collection<String> = emptyList(),
    secrets: Collection<String> = emptyList(),
) {
    private val identifierValues = identifiers.asSequence()
        .map(String::trim).filter { it.length >= 4 || it.length >= 2 && it.any { ch -> ch in '\u4e00'..'\u9fff' } }
        .distinct().sortedByDescending(String::length).toList()
    private val secretValues = secrets.asSequence().map(String::trim).filter { it.length >= 4 }
        .distinct().sortedByDescending(String::length).toList()

    /** 用户在本会话里自己说过的原话:其中出现的标识不算模型泄露,回答里照常显示、检索词里照常可用 */
    private val userTexts = CopyOnWriteArrayList<String>()

    /** 还需要遮盖的标识(用户没提过的);用户说话后重算 */
    @Volatile
    private var concealed = identifierValues

    fun noteUserText(text: String?) {
        if (text.isNullOrBlank()) return
        userTexts += text
        concealed = identifierValues.filterNot { value -> userTexts.any { it.contains(value, ignoreCase = true) } }
    }

    private fun mentions(text: String, values: List<String>): Boolean = values.any { text.contains(it, ignoreCase = true) }

    /** 带着凭据:配置里的 Key/令牌原文,或长得像凭据的字符串 */
    fun hasSecret(text: String): Boolean = containsCredential(text) || mentions(text, secretValues)

    /** 带着用户没提过的配置值(模型只能从别处得知):回答里要遮盖,外发参数里不能带 */
    fun hasIdentifier(text: String): Boolean = mentions(text, concealed)

    fun hasConfig(text: String): Boolean = hasSecret(text) || hasIdentifier(text)

    /** 只遮凭据:用于用户自己的话和引擎的说明 */
    fun redactSecrets(text: String): String =
        secretValues.fold(credential.replace(text, HIDDEN)) { out, value -> out.replace(value, HIDDEN, ignoreCase = true) }

    /** 遮盖凭据和用户没提过的配置值 */
    fun redact(text: String): String =
        concealed.fold(redactSecrets(text)) { out, value -> out.replace(value, HIDDEN, ignoreCase = true) }

    fun displayAnswer(text: String): String {
        val redacted = redact(text)
        val rendered = formatAnswerText(redacted).text
        return if (hasConfig(rendered)) redact(rendered) else redacted
    }

    /**
     * 模型的回答不可交付的原因(稳定标识,不含内容);null = 可交付。
     * 只看客观事实:超过长度上限,或把数据边界声明原文带了出来(说明注入已经得手)。
     */
    fun answerProblem(text: String): String? = when {
        text.length > MAX_ANSWER -> TOO_LONG
        text.contains(SystemPrompts.DATA_BOUNDARY.lineSequence().first(), ignoreCase = true) -> GUARDRAIL
        else -> null
    }

    fun unsafeAnswer(text: String): Boolean = answerProblem(text) != null

    fun renderedAnswerProblem(text: String): String? = answerProblem(text) ?: answerProblem(formatAnswerText(text).text)

    fun unsafeRenderedAnswer(text: String): Boolean = renderedAnswerProblem(text) != null

    /**
     * 工具参数不可执行的原因:null = 可执行。
     * 带着凭据,或带着用户没提过的配置值(把它们写进检索词等于发给了外部服务)。
     */
    fun argumentsProblem(raw: String): String? = argumentsProblem(raw, strict = true)

    fun unsafeArguments(raw: String): Boolean = argumentsProblem(raw) != null

    /** MCP 工具 schema 是用户自己配置的服务给出的:枚举值、示例里出现模型名等标识很正常,只看凭据和结构 */
    fun schemaProblem(raw: String): String? = argumentsProblem(raw, strict = false)

    private fun argumentsProblem(raw: String, strict: Boolean): String? {
        if (raw.length > MAX_ARGS) return TOO_LONG
        val parsed = runCatching { JSONObject(raw) }.getOrNull() ?: return FORMAT
        if (hasSecret(raw)) return SECRET
        return nodeProblem(parsed, 0, strict)
    }

    private fun nodeProblem(node: Any?, depth: Int, strict: Boolean): String? {
        if (depth > 16) return FORMAT
        return when (node) {
            is JSONObject -> node.keys().asSequence().firstNotNullOfOrNull { key ->
                nodeProblem(key, depth + 1, strict) ?: nodeProblem(node.opt(key), depth + 1, strict)
            }
            is JSONArray -> (0 until node.length()).firstNotNullOfOrNull { nodeProblem(node.opt(it), depth + 1, strict) }
            is String -> when {
                hasSecret(node) -> SECRET
                strict && hasIdentifier(node) -> CONFIG
                else -> null
            }
            else -> null
        }
    }

    /** 不可信来源里的疑似指令样文本(规则命中,待模型复核) */
    fun looksLikeInjection(text: String): Boolean = InjectionScanner.isFlagged(text)

    /**
     * 工具结果不可采用的原因:null = 可采用。
     * 资料里出现模型名、资料库名这类标识不算——它们本来就是资料的内容,不是攻击。
     */
    fun toolResultProblem(text: String): String? = when {
        text.length > MAX_ANSWER -> TOO_LONG
        hasSecret(text) -> SECRET
        looksLikeInjection(text) -> INJECTION
        else -> null
    }

    /** 引用来源只给界面显示、不进模型上下文:带凭据的丢弃,标识照常遮盖 */
    fun citations(sources: List<Citation>): List<Citation> = sources.mapNotNull { citation ->
        val carriesSecret = hasSecret(citation.url) || hasSecret(citation.title) || hasSecret(citation.snippet)
        if (carriesSecret) null else citation.copy(title = redact(citation.title), snippet = redact(citation.snippet))
    }

    fun forDisplay(rows: List<AgentMessageEntity>): List<AgentMessageEntity> {
        // 用户自己的话里出现的配置值不算泄露:同一会话里模型的回答也照常显示
        rows.filter { it.role == MessageRoles.USER }.forEach { noteUserText(it.content) }
        return rows.map { row ->
            val content = when (row.role) {
                MessageRoles.TOOL -> ""
                MessageRoles.ASSISTANT -> if (unsafeRenderedAnswer(row.content)) "回答已拦截" else displayAnswer(row.content)
                // 用户和系统行是用户自己的话、引擎的说明:只遮凭据
                else -> redactSecrets(row.content)
            }
            row.copy(content = content, citationsJson = CitationJson.encode(citations(CitationJson.decode(row.citationsJson))))
        }
    }

    companion object {
        private const val MAX_INPUT = 32_000
        private const val MAX_ANSWER = 64_000
        private const val MAX_ARGS = 16_384
        private const val HIDDEN = "[已隐藏]"
        private val credential = Regex("""(?i)\b(?:sk-[a-z0-9._-]{12,}|tvly-[a-z0-9._-]{12,}|Bearer\s+[a-z0-9._~+/=-]{12,})""")

        /** 拦截原因:进错误卡的稳定标识,不含任何内容 */
        const val TOO_LONG = "TOO_LONG"
        const val GUARDRAIL = "GUARDRAIL"
        const val SECRET = "SECRET"
        const val CONFIG = "CONFIG"
        const val INJECTION = "INJECTION"
        const val FORMAT = "FORMAT"
        const val TOOL_CALL_ID = "TOOL_CALL_ID"

        fun containsCredential(text: String): Boolean = credential.containsMatchIn(text)

        /** 输入本身格式不可用:超长,或含换行/制表以外的控制字符 */
        fun malformedInput(text: String): Boolean =
            text.length > MAX_INPUT || text.any { it.isISOControl() && it !in "\r\n\t" }

        /**
         * 会进入系统提示的外部文本(user.md、记忆)不可用:格式问题、凭据,或指令样文本——
         * 这些文本属于指令侧,不能借用户画像盖过运行时规则。
         */
        fun rejectsInput(text: String): Boolean =
            malformedInput(text) || containsCredential(text) || InjectionScanner.isFlagged(text)

        fun endpointValues(url: String): List<String> =
            listOfNotNull(url, runCatching { URI(url).host }.getOrNull())
    }
}
