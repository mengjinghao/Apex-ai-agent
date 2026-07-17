package com.apex.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Teal40, onPrimary = Neutral99,
    primaryContainer = Teal90, onPrimaryContainer = Teal10,
    secondary = Orange40, onSecondary = Neutral99,
    secondaryContainer = Orange90, onSecondaryContainer = Orange40,
    tertiary = Mauve40, onTertiary = Neutral99,
    tertiaryContainer = Mauve80, onTertiaryContainer = Neutral10,
    error = Error40, onError = Neutral99,
    errorContainer = Error90, onErrorContainer = Error40,
    background = Neutral99, onBackground = Neutral10,
    surface = Neutral95, onSurface = Neutral10,
    surfaceVariant = Neutral90, onSurfaceVariant = Neutral20,
    outline = Neutral20, outlineVariant = Neutral90
)

private val DarkColors = darkColorScheme(
    primary = Teal80, onPrimary = Teal20,
    primaryContainer = Teal30, onPrimaryContainer = Teal90,
    secondary = Orange80, onSecondary = Neutral20,
    secondaryContainer = Orange40, onSecondaryContainer = Orange90,
    tertiary = Mauve80, onTertiary = Neutral20,
    tertiaryContainer = Mauve40, onTertiaryContainer = Mauve80,
    error = Error80, onError = Neutral20,
    errorContainer = Error40, onErrorContainer = Error90,
    background = Neutral10, onBackground = Neutral90,
    surface = Neutral20, onSurface = Neutral90,
    surfaceVariant = Neutral20, onSurfaceVariant = Neutral90,
    outline = Neutral90, outlineVariant = Neutral20
)

@Composable
fun ApexTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colorScheme, typography = ApexTypography, content = content)
}
