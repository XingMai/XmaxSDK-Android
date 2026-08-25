package com.xmax.xlab.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val XLabColors = darkColorScheme(
    primary = Color(0xFF8EF0C8),
    onPrimary = Color(0xFF003829),
    secondary = Color(0xFF90CAF9),
    background = Color(0xFF070A0E),
    onBackground = Color(0xFFE8EDF5),
    surface = Color(0xFF0E141C),
    onSurface = Color(0xFFE8EDF5),
    surfaceVariant = Color(0xFF151D27),
    onSurfaceVariant = Color(0xFF9AA7B7),
    outline = Color(0xFF2A3645),
)

@Composable
public fun XLabTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = XLabColors,
        content = content,
    )
}

