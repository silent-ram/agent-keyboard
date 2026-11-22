package com.agentkeyboard.ime

import com.agentkeyboard.data.KeyboardHeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardUiStateTest {

    @Test
    fun `普通重置清除拼音组合但保留中英文偏好`() {
        val state = KeyboardUiState().apply {
            pinyinMode = true
            heightScale = KeyboardHeight.MAX_SCALE
            pinyinBuffer = "ni"
            pinyinCandidates = listOf("你")
            pinyinCandidatePage = 2
            pinyinCandidatesExpanded = true
            editorOpen = true
        }

        state.reset()

        assertTrue(state.pinyinMode)
        assertEquals(KeyboardHeight.MAX_SCALE, state.heightScale, 0.0001f)
        assertEquals("", state.pinyinBuffer)
        assertTrue(state.pinyinCandidates.isEmpty())
        assertEquals(0, state.pinyinCandidatePage)
        assertFalse(state.pinyinCandidatesExpanded)
        assertFalse(state.editorOpen)
    }

    @Test
    fun `密码框清理同时关闭拼音并清除候选`() {
        val state = KeyboardUiState().apply {
            pinyinMode = true
            pinyinBuffer = "ni"
            pinyinCandidates = listOf("你", "尼")
            pinyinCandidatePage = 1
            pinyinCandidatesExpanded = true
        }

        state.clearPinyinComposition(disablePinyin = true)

        assertFalse(state.pinyinMode)
        assertEquals("", state.pinyinBuffer)
        assertTrue(state.pinyinCandidates.isEmpty())
        assertEquals(0, state.pinyinCandidatePage)
        assertFalse(state.pinyinCandidatesExpanded)
    }
}
