package com.agentkeyboard.data

/**
 * 提示词组装(纯函数,便于单测)。
 * system 提示词来自 strings.xml(文案统一管理),由调用方传入。
 */
object PromptBuilder {

    /** 单轮输入长度上限:超出截断,避免超长输入打爆 token */
    const val MAX_INPUT_LENGTH = 4000

    /** 组装 user 消息:去首尾空白 + 超长截断(M0 直传原文,不做模板包装) */
    fun buildUserMessage(rawInput: String): String {
        val trimmed = rawInput.trim()
        return if (trimmed.length <= MAX_INPUT_LENGTH) {
            trimmed
        } else {
            trimmed.take(MAX_INPUT_LENGTH) + "…"
        }
    }
}
