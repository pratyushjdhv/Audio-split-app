package com.audiosplit.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val scheme = darkColorScheme(
    primary = Color(0xFF7AD9FF),
    onPrimary = Color(0xFF00344A),
    primaryContainer = Color(0xFF004C69),
    onPrimaryContainer = Color(0xFFC4E9FF),
    secondary = Color(0xFFFFB8A8),
    background = Color(0xFF101318),
    onBackground = Color(0xFFE2E6EC),
    surface = Color(0xFF171A20),
    onSurface = Color(0xFFE2E6EC),
    surfaceVariant = Color(0xFF232830),
    onSurfaceVariant = Color(0xFFBFC6D0),
    error = Color(0xFFFFB4AB),
)

@Composable
fun AudioSplitTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
