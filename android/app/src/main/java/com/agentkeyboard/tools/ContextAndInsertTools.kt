package com.agentkeyboard.tools

/**
 * 输入框上下文快照(engine 启动时经 ImeContextProvider 缓存,TaintSnapshot 同源)。
 */
data class ImeContextSnapshot(
    val beforeText: String,
    val afterText: String,
    val clipboardText: String? = null,
)

/**
 * read_context:读输入框上下文快照(IME 本职,免授权不记日志,02-架构 §2.4)。
 * 输出 ≤ context_before_chars(默认 200)。
 */
class ReadContextTool(
    private val snapshotProvider: suspend () -> ImeContextSnapshot?,
    private val contextBeforeChars: Int = 200,
) : Tool {

    override val id = "read_context"
    override val name = "读取输入框上下文"
    override val isOutbound = false
    override val targetLabel = "本机"
    override val description =
        "Read the text around the cursor in the current input box (before/after context). " +
            "Call this first when the user's request refers to what they are typing."

    override val schemaJson = """
        {"type":"object","properties":{
          "after":{"type":"integer","description":"How many characters after the cursor to read (optional)"}
        }}
    """.trimIndent()

    override suspend fun execute(argsJson: String): ToolResult {
        val snap = snapshotProvider()
            ?: return ToolResult.error("当前没有可读取的输入框上下文")
        val before = snap.beforeText.take(contextBeforeChars)
        val after = snap.afterText.take(contextBeforeChars)
        if (before.isBlank() && after.isBlank()) {
            return ToolResult.error("输入框上下文为空")
        }
        val text = "光标前:$before\n光标后:$after"
        return ToolResult.ok(raw = text, wrapped = ToolResultWrapper.wrap(text))
    }
}

/**
 * insert_text:上屏(回调 IME commitText)。
 * 权限:一律预览确认——由面板层保证(11-权限设计 §2),工具层直接执行。
 */
fun interface InsertSink {
    /** true = 上屏成功;false = 编辑器不支持(调用方退回复制) */
    suspend fun commit(text: String): Boolean
}

class InsertTextTool(private val sink: InsertSink) : Tool {

    override val id = "insert_text"
    override val name = "上屏插入"
    override val isOutbound = false
    override val targetLabel = "本机"
    override val description =
        "Insert the final answer text into the user's input box. " +
            "Only call this after the user has confirmed the preview."

    override val schemaJson = """
        {"type":"object","properties":{
          "text":{"type":"string","description":"The text to insert"}
        },"required":["text"]}
    """.trimIndent()

    override suspend fun execute(argsJson: String): ToolResult {
        val text = try {
            Jsons.requireString(argsJson, "text")
        } catch (e: IllegalArgumentException) {
            return ToolResult.error("insert_text 参数无效:${e.message}")
        }
        val ok = runCatching { sink.commit(text) }.getOrDefault(false)
        return if (ok) {
            val msg = "已插入 ${text.length} 字"
            ToolResult.ok(raw = msg, wrapped = ToolResultWrapper.wrap(msg))
        } else {
            ToolResult.error("插入失败:编辑器不接受文本")
        }
    }

    /** 供日志摘要:待插入文本(P2-5,覆写接口默认方法) */
    override fun paramsText(argsJson: String): String = Jsons.optionalString(argsJson, "text") ?: argsJson
}

/** ask_user 占位 stub(M1 后续批次接面板提问卡) */
class AskUserStubTool : Tool {
    override val id = "ask_user"
    override val name = "向用户提问"
    override val isOutbound = false
    override val targetLabel = "本机"
    override val description = "Ask the user a clarifying question. Not available yet."
    override val schemaJson = """
        {"type":"object","properties":{
          "question":{"type":"string"}
        },"required":["question"]}
    """.trimIndent()

    override suspend fun execute(argsJson: String): ToolResult =
        ToolResult.error("ask_user 尚未开放:M1 后续批次接入面板提问")
}
