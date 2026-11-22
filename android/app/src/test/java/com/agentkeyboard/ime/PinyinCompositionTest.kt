package com.agentkeyboard.ime

import com.agentkeyboard.ime.pinyin.PinyinDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.StringReader

class PinyinCompositionTest {

    private fun decoder(): PinyinDecoder = PinyinDecoder.load(
        StringReader("ni\t你\nnihao\t你好\n"),
        StringReader("ni\t你尼\nhao\t好号\n"),
    )

    @Test
    fun `没有拼音组合时不产生提交文本`() {
        assertNull(pendingPinyinCommit(false, "ni", KeyboardLayout.QWERTY, decoder()))
        assertNull(pendingPinyinCommit(true, "", KeyboardLayout.QWERTY, decoder()))
    }

    @Test
    fun `切换 26 键前优先提交拼音首选`() {
        assertEquals("你", pendingPinyinCommit(true, "ni", KeyboardLayout.QWERTY, decoder()))
    }

    @Test
    fun `切换九宫格前优先提交九宫格首选`() {
        assertEquals("你好", pendingPinyinCommit(true, "64426", KeyboardLayout.T9, decoder()))
    }

    @Test
    fun `无候选时原样提交组合串避免静默丢字`() {
        assertEquals("xyz", pendingPinyinCommit(true, "xyz", KeyboardLayout.QWERTY, decoder()))
        assertEquals("201", pendingPinyinCommit(true, "201", KeyboardLayout.T9, decoder()))
    }
}
