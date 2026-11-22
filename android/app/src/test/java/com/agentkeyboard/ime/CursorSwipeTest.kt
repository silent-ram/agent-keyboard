package com.agentkeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CursorSwipeTest {

    @Test
    fun `小于阈值不移动光标`() {
        val accumulator = CursorSwipeAccumulator(thresholdPx = 10f)

        assertNull(accumulator.add(-4f))
        assertNull(accumulator.add(-5f))
    }

    @Test
    fun `向左越过阈值移动一步并清零累计量`() {
        val accumulator = CursorSwipeAccumulator(thresholdPx = 10f)

        assertEquals(-1, accumulator.add(-12f))
        assertEquals(-1, accumulator.add(-10f))
    }

    @Test
    fun `向右越过阈值返回正方向`() {
        val accumulator = CursorSwipeAccumulator(thresholdPx = 10f)

        assertEquals(1, accumulator.add(12f))
    }
}
