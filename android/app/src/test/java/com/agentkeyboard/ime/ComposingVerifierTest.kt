package com.agentkeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** FR-D2 流式上屏退回逻辑(纯函数) */
class ComposingVerifierTest {

    @Test
    fun `已写入内容仍在光标前 - OK`() {
        assertEquals(ComposingVerifier.Verdict.OK, ComposingVerifier.verify("你好", "前缀文本你好"))
        assertEquals(ComposingVerifier.Verdict.OK, ComposingVerifier.verify("", "任意"))
    }

    @Test
    fun `光标前文本变化 - 用户打字 - USER_EDITED`() {
        assertEquals(
            ComposingVerifier.Verdict.USER_EDITED,
            ComposingVerifier.verify("你好", "前缀文本你好呀"),
        )
        assertEquals(
            ComposingVerifier.Verdict.USER_EDITED,
            ComposingVerifier.verify("你好", "完全不同"),
        )
    }

    @Test
    fun `无法查询光标前文本 - NOT_SUPPORTED`() {
        assertEquals(ComposingVerifier.Verdict.NOT_SUPPORTED, ComposingVerifier.verify("你好", null))
    }

    @Test
    fun `setComposingText 后 - 编辑器保留 composing 判定`() {
        assertTrue(ComposingVerifier.keepsComposing("你好", "前缀你好"))
        assertFalse(ComposingVerifier.keepsComposing("你好", null))
        assertFalse(ComposingVerifier.keepsComposing("你好", "不匹配"))
    }
}
