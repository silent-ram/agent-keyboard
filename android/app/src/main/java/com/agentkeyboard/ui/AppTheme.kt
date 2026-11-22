package com.agentkeyboard.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * M3-C(audit#7)应用配色:light/dark 双套,`isSystemInDarkTheme()` 跟随系统切换。
 *
 * 各组合根(MainActivity / KeyboardScreen / PanelScreen)先调 [AppTheme.FollowSystemDark]
 * 再渲染子树;颜色经各文件的 top-level getter(`get() = AppTheme.colors.x`)读取,
 * 组合期间读值 → 系统切换时自动重组。
 */
data class AppColors(
    val isDark: Boolean,
    /** 页面/面板/弹层底色 */
    val surface: Color,
    /** 次级卡片底(文档行、提示条) */
    val surfaceAlt: Color,
    /** 灰底说明块 / 运行条 / 系统卡 */
    val systemBg: Color,
    val primary: Color,
    val onPrimary: Color,
    val primaryPressed: Color,
    val bodyText: Color,
    val mutedText: Color,
    val sectionText: Color,
    val errorText: Color,
    val errorBg: Color,
    val errorBorder: Color,
    val okGreen: Color,
    /** M3-C 评审 P2:面板「■ 停止」按钮容器底(slate;dark 提亮保可辨) */
    val stopBg: Color,
    /** 恢复/成功提示条底 */
    val okBg: Color,
    val lineGray: Color,
    val chipBorder: Color,
    /** 蓝色浅底(chip、选中项、默认模型卡) */
    val chipBg: Color,
    val chipText: Color,
    val cardBorder: Color,
    val inputBg: Color,
    /** 键盘功能键激活底 */
    val activeToggle: Color,
    val kbBg: Color,
    val keyBg: Color,
    val fnKeyBg: Color,
    val pressedKeyBg: Color,
    val keyText: Color,
    val bannerBg: Color,
    val bannerText: Color,
    val warnBg: Color,
    val warnBorder: Color,
    val warnText: Color,
    val shieldBg: Color,
    val shieldBorder: Color,
    val shieldText: Color,
)

val Light = AppColors(
    isDark = false,
    surface = Color(0xFFFFFFFF),
    surfaceAlt = Color(0xFFF5F7F6),
    systemBg = Color(0xFFEDF0EF),
    primary = Color(0xFF146654),
    onPrimary = Color(0xFFFFFFFF),
    primaryPressed = Color(0xFF104F43),
    bodyText = Color(0xFF192320),
    mutedText = Color(0xFF53615C),
    sectionText = Color(0xFF374640),
    errorText = Color(0xFFB91C1C),
    errorBg = Color(0xFFFEF2F2),
    errorBorder = Color(0xFFFECACA),
    okGreen = Color(0xFF16A34A),
    stopBg = Color(0xFFAD3833),
    okBg = Color(0xFFDCFCE7),
    lineGray = Color(0xFFDBE2DE),
    chipBorder = Color(0xFFCBD6D0),
    chipBg = Color(0xFFE0F0EA),
    chipText = Color(0xFF145848),
    cardBorder = Color(0xFFACCCC0),
    inputBg = Color(0xFFF1F4F2),
    activeToggle = Color(0xFFBFDBFE),
    kbBg = Color(0xFFECEFF3),
    keyBg = Color(0xFFFFFFFF),
    fnKeyBg = Color(0xFFDDE3EA),
    pressedKeyBg = Color(0xFFC9D2DD),
    keyText = Color(0xFF1F2937),
    bannerBg = Color(0xFFE5E7EB),
    bannerText = Color(0xFF6B7280),
    warnBg = Color(0xFFFFFBEB),
    warnBorder = Color(0xFFFDE68A),
    warnText = Color(0xFF92400E),
    shieldBg = Color(0xFFFFF7ED),
    shieldBorder = Color(0xFFFED7AA),
    shieldText = Color(0xFF9A3412),
)

val Dark = AppColors(
    isDark = true,
    surface = Color(0xFF151B19),
    surfaceAlt = Color(0xFF1C2421),
    systemBg = Color(0xFF252D2A),
    primary = Color(0xFF8AD3BE),
    onPrimary = Color(0xFF14362C),
    primaryPressed = Color(0xFFA8E2D0),
    bodyText = Color(0xFFEDF3EF),
    mutedText = Color(0xFFA8B6AF),
    sectionText = Color(0xFFCCD9D2),
    errorText = Color(0xFFF87171),
    errorBg = Color(0xFF2C1518),
    errorBorder = Color(0xFF7F1D1D),
    okGreen = Color(0xFF4ADE80),
    stopBg = Color(0xFFF8ABA3),
    okBg = Color(0xFF14301F),
    lineGray = Color(0xFF35443E),
    chipBorder = Color(0xFF465A50),
    chipBg = Color(0xFF263F35),
    chipText = Color(0xFFA2DFCD),
    cardBorder = Color(0xFF456D5C),
    inputBg = Color(0xFF232D28),
    activeToggle = Color(0xFF1E3A8A),
    kbBg = Color(0xFF171E29),
    keyBg = Color(0xFF2A3441),
    fnKeyBg = Color(0xFF212B38),
    pressedKeyBg = Color(0xFF3D4A5C),
    keyText = Color(0xFFE5E7EB),
    bannerBg = Color(0xFF212B38),
    bannerText = Color(0xFF9CA3AF),
    warnBg = Color(0xFF33270D),
    warnBorder = Color(0xFF854D0E),
    warnText = Color(0xFFFBBF24),
    shieldBg = Color(0xFF372416),
    shieldBorder = Color(0xFF9A6425),
    shieldText = Color(0xFFFDBA74),
)

object AppTheme {
    private val darkFlag = mutableStateOf(false)
    internal val LocalColors = staticCompositionLocalOf<AppColors?> { null }

    val isDark: Boolean get() = darkFlag.value

    val colors: AppColors get() = if (darkFlag.value) Dark else Light

    val componentColors: AppColors
        @Composable get() = LocalColors.current ?: colors

    /** 各组合根调用一次:跟随系统深浅色 */
    @Composable
    fun FollowSystemDark() {
        darkFlag.value = isSystemInDarkTheme()
    }
}
