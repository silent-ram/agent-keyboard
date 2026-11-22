package com.agentkeyboard.data

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardHeightTest {

    @Test
    fun `normalize 将任意输入收敛为最近的三档`() {
        assertEquals(KeyboardHeight.MIN_SCALE, KeyboardHeight.normalize(0.7f), 0.0001f)
        assertEquals(KeyboardHeight.DEFAULT_SCALE, KeyboardHeight.normalize(0.96f), 0.0001f)
        assertEquals(KeyboardHeight.DEFAULT_SCALE, KeyboardHeight.normalize(1.07f), 0.0001f)
        assertEquals(KeyboardHeight.MAX_SCALE, KeyboardHeight.normalize(1.3f), 0.0001f)
    }

    @Test
    fun `normalize 对非有限值回退默认档`() {
        assertEquals(KeyboardHeight.DEFAULT_SCALE, KeyboardHeight.normalize(Float.NaN), 0.0001f)
        assertEquals(KeyboardHeight.DEFAULT_SCALE, KeyboardHeight.normalize(Float.POSITIVE_INFINITY), 0.0001f)
        assertEquals(KeyboardHeight.DEFAULT_SCALE, KeyboardHeight.normalize(Float.NEGATIVE_INFINITY), 0.0001f)
    }
}
