package dev.mindmax.v4.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val MindMaxDarkColorScheme = darkColorScheme(
    primary = MindMaxColors.SlatePrimary,
    onPrimary = MindMaxColors.SlateOnSurface,
    secondary = MindMaxColors.SlateSecondary,
    onSecondary = MindMaxColors.SlateOnSurface,
    tertiary = MindMaxColors.SlateTertiary,
    background = MindMaxColors.SlateBackground,
    onBackground = MindMaxColors.SlateOnSurface,
    surface = MindMaxColors.SlateSurface,
    onSurface = MindMaxColors.SlateOnSurface,
    surfaceVariant = MindMaxColors.SlateSurfaceVariant,
    onSurfaceVariant = MindMaxColors.SlateOnSurfaceMuted,
    error = MindMaxColors.SlateError,
    onError = MindMaxColors.SlateOnSurface,
)

@Composable
fun MindMaxTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = true, // dark-only by spec
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = MindMaxDarkColorScheme,
        typography = MindMaxTypography,
        content = content,
    )
}
