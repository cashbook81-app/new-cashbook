package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val CashControlColorScheme = lightColorScheme(
    primary = CyanPrimary,
    onPrimary = White,
    primaryContainer = CyanSoftTint,
    onPrimaryContainer = CyanDeep,
    secondary = CyanBright,
    onSecondary = White,
    secondaryContainer = CyanSoftTint,
    onSecondaryContainer = CyanDeep,
    tertiary = SuccessGreen,
    onTertiary = White,
    tertiaryContainer = SuccessSoftTint,
    onTertiaryContainer = Color(0xFF14532D),
    background = IceSurface,
    onBackground = NavyText,
    surface = White,
    onSurface = NavyText,
    surfaceVariant = SoftSurfaceVariant,
    onSurfaceVariant = MutedNavyText,
    surfaceContainerLowest = White,
    surfaceContainerLow = IceSurface,
    surfaceContainer = SoftSurfaceVariant,
    surfaceContainerHigh = Color(0xFFE8EEF5),
    surfaceContainerHighest = CyanSoftTint,
    outline = CyanBorder,
    outlineVariant = SubtleOutline
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = false,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = CashControlColorScheme,
        typography = Typography,
        content = content
    )
}
