package com.agentkeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardHistoryTest {

    @Test
    fun `重复条目移到最前并限制数量`() {
        val history = ClipboardHistory(maxItems = 2, maxChars = 20)

        assertTrue(history.record("first"))
        assertTrue(history.record("second"))
        assertTrue(history.record("first"))

        assertEquals(listOf("first", "second"), history.snapshot())
    }

    @Test
    fun `超长内容截断`() {
        val history = ClipboardHistory(maxItems = 3, maxChars = 4)

        assertTrue(history.record("123456"))

        assertEquals(listOf("1234"), history.snapshot())
    }

    @Test
    fun `疑似密钥或密码不进入历史`() {
        val history = ClipboardHistory()

        assertFalse(history.record("sk-" + "abcdefghijklmnop"))
        assertFalse(history.record("password=super-secret"))
        assertFalse(history.record("Authorization: Bearer abcdefghijkl"))
        assertTrue(history.snapshot().isEmpty())
    }

    @Test
    fun `清空历史`() {
        val history = ClipboardHistory()
        history.record("note")

        history.clear()

        assertTrue(history.snapshot().isEmpty())
    }
}
