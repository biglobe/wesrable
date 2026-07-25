package com.wesrable.positioning.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Blue = Color(0xFF3D7FD9)
private val LightScheme = lightColorScheme(primary = Blue, secondary = Color(0xFF00897B))
private val DarkScheme = darkColorScheme(primary = Color(0xFF90CAF9), secondary = Color(0xFF4DB6AC))

@Composable
fun DevicePositioningTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme
    MaterialTheme(colorScheme = scheme, content = content)
}
