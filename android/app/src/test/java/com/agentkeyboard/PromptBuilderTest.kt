package com.agentkeyboard.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 提示词组装纯函数测试 */
class PromptBuilderTest {

    @Test
    fun userMessage_plainInput_passesThrough() {
        assertEquals("你好", PromptBuilder.buildUserMessage("你好"))
    }

    @Test
    fun userMessage_trimsWhitespace() {
        assertEquals("帮我改写这句话", PromptBuilder.buildUserMessage("  帮我改写这句话\n"))
    }

    @Test
    fun userMessage_overLongInput_isTruncatedWithEllipsis() {
        val longInput = "字".repeat(PromptBuilder.MAX_INPUT_LENGTH + 100)
        val result = PromptBuilder.buildUserMessage(longInput)
        assertEquals(PromptBuilder.MAX_INPUT_LENGTH + 1, result.length)
        assertTrue(result.endsWith("…"))
    }

    @Test
    fun userMessage_atLimit_isKeptIntact() {
        val input = "a".repeat(PromptBuilder.MAX_INPUT_LENGTH)
        assertEquals(input, PromptBuilder.buildUserMessage(input))
    }
}
