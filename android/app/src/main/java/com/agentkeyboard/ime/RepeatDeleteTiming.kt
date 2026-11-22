package com.agentkeyboard.ime

/**
 * M3-C(audit#6)长按退格连删时序(纯逻辑,JVM 可测):
 * 按下即删 1 次;按住 [HOLD_TRIGGER_MS] 后进入连删,间隔从 [INITIAL_INTERVAL_MS]
 * 每次缩短 [ACCEL_STEP_MS] 线性加速,到 [MIN_INTERVAL_MS] 封底;抬起即停。
 *
 * [deletesAfter] 为 mock clock 语义:给定按下经过的毫秒数返回累计删除次数,
 * 单测无需真实 delay 即可断言加速曲线。
 */
object RepeatDeleteTiming {
    /** 长按触发连删的按住时长 */
    const val HOLD_TRIGGER_MS = 400L
    /** 连删首个间隔(初始慢) */
    const val INITIAL_INTERVAL_MS = 150L
    /** 加速下限 */
    const val MIN_INTERVAL_MS = 30L
    /** 每次连删后间隔缩短量(线性加速) */
    const val ACCEL_STEP_MS = 12L

    /** 第 n 次连删后的等待间隔:n=0 → 150ms,随 n 递减至 30ms */
    fun intervalAt(n: Int): Long =
        (INITIAL_INTERVAL_MS - n.toLong() * ACCEL_STEP_MS).coerceAtLeast(MIN_INTERVAL_MS)

    /** 按下 [elapsedMs] 毫秒时的累计删除次数(t=0 删第 1 次;400ms 后按加速间隔连删) */
    fun deletesAfter(elapsedMs: Long): Int {
        if (elapsedMs < 0) return 0
        var count = 1
        var t = HOLD_TRIGGER_MS
        var n = 0
        while (t <= elapsedMs) {
            count++
            t += intervalAt(n)
            n++
        }
        return count
    }
}
