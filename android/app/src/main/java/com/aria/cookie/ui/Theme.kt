package com.aria.cookie.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Dough = Color(0xFFD9A066)
private val Choco = Color(0xFF6B3E1E)
private val Spotify = Color(0xFF1ED760)

val SpotifyGreen = Spotify

private val Dark = darkColorScheme(
    primary = Dough,
    onPrimary = Color(0xFF2A1A0C),
    secondary = Color(0xFFC9B8A6),
    tertiary = Spotify,
    background = Color(0xFF14110F),
    surface = Color(0xFF1C1815),
    surfaceVariant = Color(0xFF2A241F),
    onBackground = Color(0xFFF1E9E1),
    onSurface = Color(0xFFF1E9E1),
    onSurfaceVariant = Color(0xFFC9BCB0),
)

private val Light = lightColorScheme(
    primary = Choco,
    onPrimary = Color.White,
    secondary = Color(0xFF7A6552),
    tertiary = Color(0xFF168D45),
    background = Color(0xFFFBF7F2),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF1E8DE),
)

@Composable
fun CookieTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
