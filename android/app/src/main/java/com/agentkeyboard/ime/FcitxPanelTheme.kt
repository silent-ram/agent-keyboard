package com.agentkeyboard.ime

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.agentkeyboard.ui.AppColors
import com.agentkeyboard.ui.AssistantTheme
import com.agentkeyboard.ui.Dark
import com.agentkeyboard.ui.Light
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager

internal fun panelColors(theme: Theme, keyBorder: Boolean): AppColors {
    val base = if (theme.isDark) Dark else Light
    val surface = Color(if (keyBorder) theme.backgroundColor else theme.keyboardColor).compositeOver(base.surface)
    val bar = Color(theme.barColor).compositeOver(surface)
    val key = Color(theme.keyBackgroundColor).compositeOver(surface)
    val altKey = Color(theme.altKeyBackgroundColor).compositeOver(bar)
    val accent = Color(theme.accentKeyBackgroundColor).compositeOver(bar)
    val divider = Color(theme.dividerColor).compositeOver(surface)
    return base.copy(
        surface = surface, surfaceAlt = key, systemBg = bar, inputBg = key,
        primary = accent, onPrimary = readableKeyText(Color(theme.accentKeyTextColor), listOf(accent)),
        bodyText = readableKeyText(Color(theme.candidateTextColor), listOf(surface, key)),
        mutedText = readableKeyText(Color(theme.candidateCommentColor), listOf(surface, bar, key)),
        sectionText = readableKeyText(Color(theme.candidateTextColor), listOf(bar)),
        chipBg = altKey, chipText = readableKeyText(Color(theme.altKeyTextColor), listOf(altKey)),
        lineGray = divider, chipBorder = divider, cardBorder = divider,
    )
}

private fun readableKeyText(preferred: Color, surfaces: List<Color>): Color {
    fun minimumContrast(text: Color): Float = surfaces.minOf { bg ->
        val foreground = text.compositeOver(bg).luminance()
        val background = bg.luminance()
        (maxOf(foreground, background) + 0.05f) / (minOf(foreground, background) + 0.05f)
    }
    if (minimumContrast(preferred) >= 4.5f) return preferred
    return listOf(preferred, Color.White, Color.Black).maxBy(::minimumContrast)
}

@Composable
internal fun FcitxPanelTheme(content: @Composable () -> Unit) {
    fun preferences() = Triple(
        ThemeManager.activeTheme,
        ThemeManager.prefs.keyBorder.getValue(),
        ThemeManager.prefs.keyRadius.getValue(),
    )
    var style by remember { mutableStateOf(preferences()) }
    DisposableEffect(Unit) {
        val listener = ThemeManager.OnThemeChangeListener { style = preferences() }
        ThemeManager.addOnChangedListener(listener)
        style = preferences()
        onDispose { ThemeManager.removeOnChangedListener(listener) }
    }
    val (theme, bordered, radius) = style
    val palette = remember(theme, bordered) { panelColors(theme, bordered) }
    AssistantTheme(palette = palette, keyRadius = radius.dp, content = content)
}
