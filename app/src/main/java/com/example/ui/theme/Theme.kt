package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val PrintStudioColorScheme = darkColorScheme(
    primary = LimeAccent,
    onPrimary = SlateBg,
    secondary = SkyAccent,
    onSecondary = SlateBg,
    background = SlateBg,
    onBackground = TextPrimary,
    surface = SlateCard,
    onSurface = TextPrimary,
    surfaceVariant = SlateCard,
    onSurfaceVariant = TextSecondary,
    outline = SlateBorder
)

@Composable
fun MyApplicationTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = PrintStudioColorScheme,
        typography = Typography,
        content = content
    )
}
