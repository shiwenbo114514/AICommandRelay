package com.aicmd.relay.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF34D399),
    onPrimary = Color(0xFF052E21),
    primaryContainer = Color(0xFF0F3D2E),
    onPrimaryContainer = Color(0xFFA7F3D0),
    secondary = Color(0xFF60A5FA),
    onSecondary = Color(0xFF0B2447),
    secondaryContainer = Color(0xFF12325C),
    onSecondaryContainer = Color(0xFFBFDBFE),
    tertiary = Color(0xFFF59E0B),
    background = Color(0xFF0B0F14),
    onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF11161D),
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF1A2230),
    onSurfaceVariant = Color(0xFF8B98A9),
    outline = Color(0xFF2C3948),
    outlineVariant = Color(0xFF232E3C),
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A),
)

@Composable
fun AICmdTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        content = content,
    )
}
