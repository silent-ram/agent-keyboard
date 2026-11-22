package com.agentkeyboard.ui

import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnswerTextTest {
    @Test
    fun `processed answers hide a configured address before delivery`() {
        val safety = com.agentkeyboard.agent.AgentContentSafety(listOf("relay.example"))
        val answer = safety.displayAnswer("连接 **relay**.example")
        assertEquals("连接 [已隐藏]", formatAnswerText(answer).text)
    }

    @Test
    fun markdownBoldRendersWithoutMarkersAndKeepsLineBreaks() {
        val answer = formatAnswerText("公司愿景：\r\n\r\n**让每条轮胎具有非凡的质量。**\r\n下一行")
        assertEquals("公司愿景：\r\n\r\n让每条轮胎具有非凡的质量。\r\n下一行", answer.text)
        val bold = answer.spanStyles.single()
        assertEquals(FontWeight.Bold, bold.item.fontWeight)
        assertEquals("让每条轮胎具有非凡的质量。", answer.text.substring(bold.start, bold.end))
    }

    @Test
    fun codeEscapedMarkersAndUnmatchedMarkersRemainLiteral() {
        val raw = "`**a**`\n\n```\n**b**\n```\n\n\\*\\*literal\\*\\*\n\n**unfinished"
        val answer = formatAnswerText(raw)
        assertEquals(raw, answer.text)
        assertTrue(answer.spanStyles.isEmpty())
    }

    @Test
    fun nestedBoldAndEmojiKeepTheirSourceOffsets() {
        val answer = formatAnswerText("🙂 **外层 __内层__ 结束**")
        assertEquals("🙂 外层 内层 结束", answer.text)
        assertEquals(setOf("外层 内层 结束", "内层"), answer.spanStyles.map {
            answer.text.substring(it.start, it.end)
        }.toSet())
    }
}
