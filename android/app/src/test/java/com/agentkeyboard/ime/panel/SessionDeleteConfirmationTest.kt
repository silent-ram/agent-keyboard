package com.agentkeyboard.ime.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionDeleteConfirmationTest {
    @Test
    fun `cancel clears the pending deletion`() {
        val confirmation = SessionDeleteConfirmation()
        confirmation.request(21L)
        assertEquals(21L, confirmation.pendingId)

        confirmation.cancel()
        assertNull(confirmation.pendingId)
        assertNull(confirmation.confirm(21L))
    }

    @Test
    fun `confirm consumes only the selected session once`() {
        val confirmation = SessionDeleteConfirmation()
        confirmation.request(21L)
        confirmation.request(22L)

        assertNull(confirmation.confirm(21L))
        assertEquals(22L, confirmation.confirm(22L))
        assertNull(confirmation.confirm(22L))
    }
}
