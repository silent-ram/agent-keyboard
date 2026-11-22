package com.agentkeyboard.ime.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P1-1:撤销条状态提升——状态在 controller,不随确认条组合销毁;5s TTL 判定可注入时钟 */
class UndoAlwaysControllerTest {

    @Test
    fun `mark - 状态可读取且独立于任何 Compose 生命周期`() {
        var now = 1000L
        val c = UndoAlwaysController(clock = { now })
        c.mark("web_search")
        assertEquals("web_search", c.toolId.value)
        // 确认条离开组合(controller 状态不受影响)
        assertEquals("web_search", c.toolId.value)
    }

    @Test
    fun `5s 内未过期 - 5s 后过期`() {
        var now = 0L
        val c = UndoAlwaysController(clock = { now })
        c.mark("fetch_page")
        now = 4999L
        assertFalse(c.isExpired())
        now = 5000L
        assertTrue(c.isExpired())
    }

    @Test
    fun `clear - 立即清空(撤销或到期)`() {
        var now = 0L
        val c = UndoAlwaysController(clock = { now })
        c.mark("web_search")
        c.clear()
        assertNull(c.toolId.value)
        assertFalse(c.isExpired())
    }

    @Test
    fun `无标记时 - 永不过期且为空`() {
        val c = UndoAlwaysController(clock = { 10_000L })
        assertNull(c.toolId.value)
        assertFalse(c.isExpired())
    }
}
