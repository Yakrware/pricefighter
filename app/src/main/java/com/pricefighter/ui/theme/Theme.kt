package com.pricefighter.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Green = Color(0xFF0B5D3B)
private val GreenLight = Color(0xFF5FD49A)

private val LightColors = lightColorScheme(
    primary = Green,
    secondary = Color(0xFF3F6B55),
    tertiary = Color(0xFF8A5A2B),
)

private val DarkColors = darkColorScheme(
    primary = GreenLight,
    secondary = Color(0xFFA8D7BF),
    tertiary = Color(0xFFE7B98A),
)

@Composable
fun PriceFighterTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Material You dynamic color is available on this app's min SDK (API 36).
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && darkTheme -> dynamicDarkColorScheme(context)
        dynamicColor -> dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content,
    )
}
