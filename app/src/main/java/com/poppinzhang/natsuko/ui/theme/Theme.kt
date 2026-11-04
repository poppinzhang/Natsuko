package com.poppinzhang.natsuko.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import com.poppinzhang.natsuko.model.ThemeMode

private val LightColors = lightColorScheme(
    primary = Color(0xFF75546F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF8D8F0),
    onPrimaryContainer = Color(0xFF2C1329),
    secondary = Color(0xFF725B2C),
    secondaryContainer = Color(0xFFFBE3A5),
    background = Color(0xFFFFF9F6),
    surface = Color(0xFFFFF9F6),
    surfaceVariant = Color(0xFFF2E9ED),
    outline = Color(0xFF81747B),
    error = Color(0xFFBA1A1A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFE5B9DB),
    onPrimary = Color(0xFF43263F),
    primaryContainer = Color(0xFF5B3D56),
    secondary = Color(0xFFE0C47B),
    background = Color(0xFF191217),
    surface = Color(0xFF191217),
    surfaceVariant = Color(0xFF4E444A),
)

@Composable
fun NatsukoTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (themeMode.usesDarkTheme(isSystemInDarkTheme())) DarkColors else LightColors,
        content = content,
    )
}
