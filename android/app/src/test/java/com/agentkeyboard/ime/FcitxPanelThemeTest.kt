package com.agentkeyboard.ime

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FcitxPanelThemeTest {
    @Test
    fun `panel uses active keyboard surfaces and accent keys`() {
        for (theme in listOf(ThemePreset.PixelLight, ThemePreset.PixelDark)) {
            for (bordered in listOf(false, true)) {
                val palette = panelColors(theme, bordered)
                val background = Color(if (bordered) theme.backgroundColor else theme.keyboardColor)
                assertEquals(theme.isDark, palette.isDark)
                assertEquals(background, palette.surface)
                assertEquals(Color(theme.barColor).compositeOver(background), palette.systemBg)
                assertEquals(Color(theme.keyBackgroundColor).compositeOver(background), palette.inputBg)
                assertEquals(Color(theme.accentKeyBackgroundColor).compositeOver(palette.systemBg), palette.primary)
            }
        }
    }

    @Test
    fun `an unreadable custom accent label gets a contrasting foreground`() {
        val theme = ThemePreset.PixelLight.copy(accentKeyTextColor = ThemePreset.PixelLight.accentKeyBackgroundColor)
        val palette = panelColors(theme, keyBorder = false)
        val foreground = palette.onPrimary.compositeOver(palette.primary).luminance()
        val background = palette.primary.luminance()
        val ratio = (maxOf(foreground, background) + 0.05f) / (minOf(foreground, background) + 0.05f)
        assertTrue("Accent contrast: $ratio", ratio >= 4.5f)
    }
}
