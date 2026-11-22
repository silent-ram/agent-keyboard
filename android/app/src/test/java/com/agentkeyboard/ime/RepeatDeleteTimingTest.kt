package com.agentkeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M3-C(audit#6)长按退格连删时序(mock clock 语义,纯函数):按下即删一次;
 * 按住 400ms 后连删;间隔 150ms 起线性加速到 30ms 封底。
 */
class RepeatDeleteTimingTest {

    // 1. 间隔序列:150 起每次 -12,到 30 封底
    @Test
    fun `intervalAt - 150起线性加速到30封底`() {
        assertEquals(150L, RepeatDeleteTiming.intervalAt(0))
        assertEquals(138L, RepeatDeleteTiming.intervalAt(1))
        assertEquals(126L, RepeatDeleteTiming.intervalAt(2))
        assertEquals(30L, RepeatDeleteTiming.intervalAt(10))
        assertEquals(30L, RepeatDeleteTiming.intervalAt(100)) // 封底不变
    }

    // 2. 单击(未到 400ms)只删一次
    @Test
    fun `deletesAfter - 短按只删一次`() {
        assertEquals(1, RepeatDeleteTiming.deletesAfter(0))
        assertEquals(1, RepeatDeleteTiming.deletesAfter(399))
    }

    // 3. 长按曲线:400ms 触发第 2 删,之后按加速间隔累加
    // 删除时刻表:#1=0,#2=400,#3=550(+150),#4=688(+138),#5=814(+126),#6=928(+114)…
    @Test
    fun `deletesAfter - 长按加速曲线`() {
        assertEquals(2, RepeatDeleteTiming.deletesAfter(400)) // 触发连删第 1 次
        assertEquals(3, RepeatDeleteTiming.deletesAfter(550)) // 150ms 后第 2 次
        assertEquals(3, RepeatDeleteTiming.deletesAfter(687))
        assertEquals(4, RepeatDeleteTiming.deletesAfter(688))
        assertEquals(5, RepeatDeleteTiming.deletesAfter(814))
        assertEquals(6, RepeatDeleteTiming.deletesAfter(1000)) // 1 秒 6 删(含按下首删)
    }

    // 4. 30ms 封底后匀速
    // 删除时刻表:#1=0,#2=400,#3=550,#4=688,#5=814,#6=928,#7=1030,#8=1120,#9=1198,
    // #10=1264,#11=1318,#12=1360,#13=1390,此后每 30ms 一删(#14=1420…)
    @Test
    fun `deletesAfter - 封底后匀速30ms`() {
        assertEquals(13, RepeatDeleteTiming.deletesAfter(1390))
        assertEquals(13, RepeatDeleteTiming.deletesAfter(1402)) // 下一删在 1420
        assertEquals(14, RepeatDeleteTiming.deletesAfter(1420))
        assertEquals(14, RepeatDeleteTiming.deletesAfter(1432)) // 下一删在 1450
        assertEquals(15, RepeatDeleteTiming.deletesAfter(1450))
        assertEquals(33, RepeatDeleteTiming.deletesAfter(2000)) // 2 秒 33 删
    }

    // 5. 非法输入
    @Test
    fun `deletesAfter - 负数为 0`() {
        assertEquals(0, RepeatDeleteTiming.deletesAfter(-1))
    }
}
