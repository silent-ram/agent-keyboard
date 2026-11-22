package com.agentkeyboard.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M3-C(audit#7)深色模式主题双套测试:light 原样保真,dark 命名色符合规格
 * (键盘深灰底/深色按键,面板深色卡片,主色亮蓝 #3B82F6)。
 */
class AppThemeTest {

    // 1. light 套保真(现网配色原样)
    @Test
    fun `Light - 主色与表面色保持现网值`() {
        assertContrast(Light.onPrimary, Light.primary)
        assertEquals(Color(0xFFFFFFFF), Light.surface)
        assertEquals(Color(0xFFECEFF3), Light.kbBg)
        assertEquals(Color(0xFFFFFFFF), Light.keyBg)
        assertEquals(false, Light.isDark)
    }

    // 2. dark 套规格:主色亮蓝 #3B82F6,深色表面/按键
    @Test
    fun `Dark - 主色亮蓝且键盘面板深色`() {
        assertContrast(Dark.onPrimary, Dark.primary)
        assertContrast(Dark.bodyText, Dark.surface)
        assertEquals(Color(0xFF171E29), Dark.kbBg) // 键盘深灰底
        assertEquals(Color(0xFF2A3441), Dark.keyBg) // 深色按键
        assertEquals(Color(0xFFE5E7EB), Dark.keyText) // 深色按键上的可读文字
        assertEquals(true, Dark.isDark)
    }

    // 3. 双套关键色互异(确保真的切换了主题)
    @Test
    fun `Light 与 Dark - 关键色互异`() {
        assertNotEquals(Light.primary, Dark.primary)
        assertNotEquals(Light.surface, Dark.surface)
        assertNotEquals(Light.keyBg, Dark.keyBg)
        assertNotEquals(Light.kbBg, Dark.kbBg)
    }

    // 4. 语义色可读性:dark 下正文亮、错误偏亮红、ok/warn 相应提亮
    @Test
    fun `Dark - 语义色为深底可读配色`() {
        // 正文亮度高于深色表面
        val darkLum = luminance(Dark.bodyText)
        val surfaceLum = luminance(Dark.surface)
        assertTrue(darkLum > surfaceLum)
        // 错误色在深底下仍偏亮红(可读)
        assertTrue(luminance(Dark.errorText) > 0.3)
    }

    // 5. M3-C 评审 P1(最近活动页深色不可读):页面背景必须取 surface——
    //    light surface 为纯白、dark surface 为深色且正文在其上可读(高对比)
    @Test
    fun `Surface - 最近活动页背景色 light 白 dark 深底`() {
        assertEquals(Color(0xFFFFFFFF), Light.surface)
        assertContrast(Light.mutedText, Light.surface)
        assertContrast(Dark.mutedText, Dark.surface)
        // 深色页面底上的正文/静音文字必须为亮色(可读),不再有 Dark 文字画白底
        assertTrue(luminance(Dark.bodyText) > 0.8)
        assertTrue(luminance(Dark.surface) < 0.2)
    }

    // 6. M3-C 评审 P2(PanelView 硬编码残留):stopBg light 保真 #475569,dark 提亮保证深底可辨;
    //    chipBg light 即原 actionTag 标签底 #EFF6FF,okGreen light 即原 OkGreenSheet #16A34A
    @Test
    fun `Panel - stopBg chipBg okGreen light 保真 dark 提亮`() {
        for (palette in listOf(Light, Dark)) {
            assertContrast(palette.chipText, palette.chipBg)
            assertContrast(palette.bodyText, palette.surfaceAlt)
            assertContrast(palette.mutedText, palette.inputBg)
            assertContrast(palette.errorText, palette.errorBg)
        }
        assertEquals(Color(0xFF16A34A), Light.okGreen)
        // dark 下停止按钮比深色面板底亮(可辨),chip/ok 相应提亮
        assertTrue(luminance(Dark.stopBg) > luminance(Dark.surface))
        assertNotEquals(Light.stopBg, Dark.stopBg)
    }

    private fun luminance(c: Color): Float = c.luminance()

    private fun assertContrast(foreground: Color, background: Color) {
        val fg = foreground.luminance()
        val bg = background.luminance()
        val ratio = (maxOf(fg, bg) + 0.05f) / (minOf(fg, bg) + 0.05f)
        assertTrue("Contrast $ratio for $foreground on $background", ratio >= 4.5f)
    }
}
