package com.agentkeyboard.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import org.fcitx.fcitx5.android.R

@Composable
internal fun AssistantTheme(
    palette: AppColors? = null,
    keyRadius: Dp = 8.dp,
    content: @Composable () -> Unit,
) {
    if (palette == null) AppTheme.FollowSystemDark()
    val colors = palette ?: AppTheme.colors
    val scheme = if (colors.isDark) darkColorScheme() else lightColorScheme()
    MaterialTheme(
        colorScheme = scheme.copy(
            primary = colors.primary,
            onPrimary = colors.onPrimary,
            primaryContainer = colors.chipBg,
            onPrimaryContainer = colors.chipText,
            secondary = colors.primary,
            onSecondary = colors.onPrimary,
            secondaryContainer = colors.chipBg,
            onSecondaryContainer = colors.chipText,
            tertiary = colors.primary,
            onTertiary = colors.onPrimary,
            tertiaryContainer = colors.chipBg,
            onTertiaryContainer = colors.chipText,
            background = colors.surface,
            onBackground = colors.bodyText,
            surface = colors.surface,
            onSurface = colors.bodyText,
            surfaceVariant = colors.surfaceAlt,
            onSurfaceVariant = colors.mutedText,
            surfaceContainerHigh = colors.surfaceAlt,
            surfaceContainer = colors.surfaceAlt,
            outline = colors.mutedText,
            outlineVariant = colors.lineGray,
            error = colors.errorText,
            errorContainer = colors.errorBg,
            onErrorContainer = colors.errorText,
        ),
        shapes = Shapes(
            extraSmall = RoundedCornerShape(4.dp),
            small = RoundedCornerShape(keyRadius),
            medium = RoundedCornerShape(8.dp),
            large = RoundedCornerShape(8.dp),
            extraLarge = RoundedCornerShape(12.dp),
        ),
    ) {
        CompositionLocalProvider(AppTheme.LocalColors provides colors, content = content)
    }
}

@Composable
internal fun AssistantIcon(
    @DrawableRes icon: Int,
    description: String? = null,
    tint: Color = AppTheme.componentColors.mutedText,
    modifier: Modifier = Modifier,
) {
    Icon(painterResource(icon), contentDescription = description, tint = tint, modifier = modifier.size(20.dp))
}

@Composable
internal fun AssistantIconButton(
    @DrawableRes icon: Int,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    emphasized: Boolean = false,
    selected: Boolean = false,
    danger: Boolean = false,
) {
    val colors = AppTheme.componentColors
    IconButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = when {
                danger -> colors.errorBg
                emphasized -> colors.primary
                selected -> colors.chipBg
                else -> Color.Transparent
            },
            contentColor = when {
                danger -> colors.errorText
                emphasized -> colors.onPrimary
                selected -> colors.chipText
                else -> colors.sectionText
            },
            disabledContainerColor = if (emphasized) colors.systemBg else Color.Transparent,
            disabledContentColor = colors.mutedText.copy(alpha = 0.6f),
        ),
    ) {
        Icon(painterResource(icon), contentDescription = description, modifier = Modifier.size(20.dp))
    }
}

@Composable
internal fun AssistantPageBar(
    title: String,
    backLabel: String? = null,
    backEnabled: Boolean = true,
    onBack: () -> Unit = {},
    actions: @Composable () -> Unit = {},
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (backLabel != null) {
            AssistantIconButton(R.drawable.ic_baseline_arrow_back_24, backLabel, onBack, enabled = backEnabled)
        }
        Box(Modifier.weight(1f).padding(start = if (backLabel == null) 8.dp else 0.dp)) {
            Text(
                title,
                color = AppTheme.componentColors.bodyText,
                fontSize = 22.sp,
                lineHeight = 28.sp,
                letterSpacing = 0.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        actions()
    }
    HorizontalDivider(color = AppTheme.componentColors.lineGray)
}
