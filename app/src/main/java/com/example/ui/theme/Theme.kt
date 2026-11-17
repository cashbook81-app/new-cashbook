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
    tertiaryContainer = Color(0xFFD1FAE5),
    onTertiaryContainer = Color(0xFF065F46),
    background = White,
    onBackground = NavyText,
    surface = White,
    onSurface = NavyText,
    surfaceVariant = SoftSurfaceVariant,
    onSurfaceVariant = MutedNavyText,
    surfaceContainerLowest = White,
    surfaceContainerLow = Color(0xFFF8FCFF),
    surfaceContainer = SoftSurfaceVariant,
    surfaceContainerHigh = Color(0xFFEAF5FC),
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
