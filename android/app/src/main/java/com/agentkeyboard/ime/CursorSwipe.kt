package com.agentkeyboard.ime

import kotlin.math.abs

/**
 * Accumulates horizontal space-bar movement and emits one cursor step whenever
 * the configured touch threshold is crossed.
 */
internal class CursorSwipeAccumulator(
    private val thresholdPx: Float,
) {
    init {
        require(thresholdPx > 0f)
    }

    private var accumulatedPx = 0f

    fun add(deltaPx: Float): Int? {
        accumulatedPx += deltaPx
        if (abs(accumulatedPx) < thresholdPx) return null
        val direction = if (accumulatedPx < 0f) -1 else 1
        accumulatedPx = 0f
        return direction
    }
}
