package io.github.kawincodes.stiksound.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val BrandGreen = Color(0xFF25D366)
val BrandTeal = Color(0xFF128C7E)

private val DarkColors = darkColorScheme(
    primary = BrandGreen,
    onPrimary = Color(0xFF08180E),
    primaryContainer = Color(0xFF0F3D26),
    onPrimaryContainer = Color(0xFFB7F5CF),
    secondary = Color(0xFF7FD6C8),
    onSecondary = Color(0xFF00201C),
    secondaryContainer = Color(0xFF1F3A36),
    onSecondaryContainer = Color(0xFFCFEFE9),
    background = Color(0xFF0D1117),
    onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF0D1117),
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF1F2633),
    onSurfaceVariant = Color(0xFFAAB4C0),
    surfaceContainer = Color(0xFF141820),
    surfaceContainerHigh = Color(0xFF1A1F29),
    surfaceContainerHighest = Color(0xFF222936),
    outline = Color(0xFF344054),
    outlineVariant = Color(0xFF2A3240),
    error = Color(0xFFFF8A80),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0E7A45),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC6F3D8),
    onPrimaryContainer = Color(0xFF00210F),
    secondary = BrandTeal,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCEDE8),
    onSecondaryContainer = Color(0xFF00201C),
    background = Color(0xFFF7F9F8),
    onBackground = Color(0xFF151A1E),
    surface = Color(0xFFF7F9F8),
    onSurface = Color(0xFF151A1E),
    surfaceVariant = Color(0xFFE3E8E6),
    onSurfaceVariant = Color(0xFF4A5560),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF0F3F2),
    surfaceContainerHighest = Color(0xFFE8EDEB),
    outline = Color(0xFFB8C2C0),
    outlineVariant = Color(0xFFD5DDDB),
)

@Composable
fun StikSoundTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
