package com.agentkeyboard.ime

import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EditorActionResolverTest {
    @Test
    fun `明确 action 优先交给宿主`() {
        assertEquals(
            EditorInfo.IME_ACTION_SEND,
            EditorActionResolver.actionOrNull(EditorInfo.IME_ACTION_SEND),
        )
    }

    @Test
    fun `none 与 unspecified 回退为回车事件`() {
        assertNull(EditorActionResolver.actionOrNull(EditorInfo.IME_ACTION_NONE))
        assertNull(EditorActionResolver.actionOrNull(EditorInfo.IME_ACTION_UNSPECIFIED))
    }
}
