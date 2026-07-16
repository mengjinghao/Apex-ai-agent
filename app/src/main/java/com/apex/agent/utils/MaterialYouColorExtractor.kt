package com.apex.utils

import android.graphics.Color
import androidx.compose.ui.graphics.Color as ComposeColor

/**
 * Material You 色彩提取�?
 * 根据源颜色生成完整的 Material You 色彩方案
 */
object MaterialYouColorExtractor {

    /**
     * Material You 色彩方案
     */
    data class MaterialYouColors(
        val primary: Int,
        val onPrimary: Int,
        val primaryContainer: Int,
        val onPrimaryContainer: Int,
        val secondary: Int,
        val onSecondary: Int,
        val secondaryContainer: Int,
        val onSecondaryContainer: Int,
        val tertiary: Int,
        val onTertiary: Int,
        val tertiaryContainer: Int,
        val onTertiaryContainer: Int,
        val error: Int,
        val onError: Int,
        val errorContainer: Int,
        val onErrorContainer: Int,
        val surface: Int,
        val onSurface: Int,
        val surfaceVariant: Int,
        val onSurfaceVariant: Int,
        val surfaceContainer: Int,
        val surfaceContainerLow: Int,
        val surfaceContainerHigh: Int,
        val surfaceContainerHighest: Int,
        val background: Int,
        val onBackground: Int,
        val outline: Int,
        val outlineVariant: Int,
        val inverseSurface: Int,
        val inverseOnSurface: Int,
        val inversePrimary: Int,
        val isDark: Boolean = false
    )

    /**
     * 从源颜色生成 Material You 色彩方案（亮色模式）
     */
    fun extractLightColors(sourceColor: Int): MaterialYouColors {
        val hsl = rgbToHsl(sourceColor)
        val h = hsl[0]
        val s = hsl[1]
        val l = hsl[2]

        return MaterialYouColors(
            primary = hslToRgb(h, s.coerceAtLeast(0.4f), 0.4f),
            onPrimary = hslToRgb(h, 0f, if (l < 0.5f) 1f else 0f),
            primaryContainer = hslToRgb(h, s.coerceAtLeast(0.3f), 0.9f),
            onPrimaryContainer = hslToRgb(h, s.coerceAtLeast(0.4f), 0.1f),
            secondary = hslToRgb((h + 60f) % 360f, s.coerceAtLeast(0.3f), 0.45f),
            onSecondary = hslToRgb((h + 60f) % 360f, 0f, if (l < 0.5f) 1f else 0f),
            secondaryContainer = hslToRgb((h + 60f) % 360f, s.coerceAtLeast(0.25f), 0.9f),
            onSecondaryContainer = hslToRgb((h + 60f) % 360f, s.coerceAtLeast(0.35f), 0.1f),
            tertiary = hslToRgb((h + 120f) % 360f, s.coerceAtLeast(0.35f), 0.45f),
            onTertiary = hslToRgb((h + 120f) % 360f, 0f, if (l < 0.5f) 1f else 0f),
            tertiaryContainer = hslToRgb((h + 120f) % 360f, s.coerceAtLeast(0.3f), 0.9f),
            onTertiaryContainer = hslToRgb((h + 120f) % 360f, s.coerceAtLeast(0.4f), 0.1f),
            error = hslToRgb(0f, 0.7f, 0.4f),
            onError = 0xFFFFFFFF.toInt(),
            errorContainer = hslToRgb(0f, 0.5f, 0.9f),
            onErrorContainer = hslToRgb(0f, 0.7f, 0.15f),
            surface = 0xFFFFFBFE.toInt(),
            onSurface = 0xFF1C1B1F.toInt(),
            surfaceVariant = 0xFFE7E0EC.toInt(),
            onSurfaceVariant = 0xFF49454F.toInt(),
            surfaceContainer = 0xFFF3EDF7.toInt(),
            surfaceContainerLow = 0xFFF7F2FA.toInt(),
            surfaceContainerHigh = 0xFFECE6F0.toInt(),
            surfaceContainerHighest = 0xFFE6E0E9.toInt(),
            background = 0xFFFFFBFE.toInt(),
            onBackground = 0xFF1C1B1F.toInt(),
            outline = 0xFF79747E.toInt(),
            outlineVariant = 0xFFCAC4D0.toInt(),
            inverseSurface = 0xFF313033.toInt(),
            inverseOnSurface = 0xFFF4EFF4.toInt(),
            inversePrimary = 0xFFA8C7FA.toInt(),
            isDark = false
        )
    }

    /**
     * 从源颜色生成 Material You 色彩方案（暗色模式）
     */
    fun extractDarkColors(sourceColor: Int): MaterialYouColors {
        val hsl = rgbToHsl(sourceColor)
        val h = hsl[0]
        val s = hsl[1]
        val l = hsl[2]

        return MaterialYouColors(
            primary = hslToRgb(h, s.coerceAtLeast(0.3f), 0.8f),
            onPrimary = hslToRgb(h, s.coerceAtLeast(0.4f), 0.2f),
            primaryContainer = hslToRgb(h, s.coerceAtLeast(0.35f), 0.3f),
            onPrimaryContainer = hslToRgb(h, s.coerceAtLeast(0.3f), 0.9f),
            secondary = hslToRgb((h + 60f) % 360f, s.coerceAtLeast(0.25f), 0.75f),
            onSecondary = hslToRgb((h + 60f) % 360f, s.coerceAtLeast(0.3f), 0.2f),
            secondaryContainer = hslToRgb((h + 60f) % 360f, s.coerceAtLeast(0.3f), 0.3f),
            onSecondaryContainer = hslToRgb((h + 60f) % 360f, s.coerceAtLeast(0.25f), 0.9f),
            tertiary = hslToRgb((h + 120f) % 360f, s.coerceAtLeast(0.3f), 0.75f),
            onTertiary = hslToRgb((h + 120f) % 360f, s.coerceAtLeast(0.35f), 0.2f),
            tertiaryContainer = hslToRgb((h + 120f) % 360f, s.coerceAtLeast(0.35f), 0.3f),
            onTertiaryContainer = hslToRgb((h + 120f) % 360f, s.coerceAtLeast(0.3f), 0.9f),
            error = hslToRgb(0f, 0.8f, 0.8f),
            onError = hslToRgb(0f, 0.7f, 0.2f),
            errorContainer = hslToRgb(0f, 0.7f, 0.35f),
            onErrorContainer = hslToRgb(0f, 0.6f, 0.9f),
            surface = 0xFF1C1B1F.toInt(),
            onSurface = 0xFFE6E1E5.toInt(),
            surfaceVariant = 0xFF49454F.toInt(),
            onSurfaceVariant = 0xFFCAC4D0.toInt(),
            surfaceContainer = 0xFF2E2C33.toInt(),
            surfaceContainerLow = 0xFF262529.toInt(),
            surfaceContainerHigh = 0xFF36343B.toInt(),
            surfaceContainerHighest = 0xFF47464E.toInt(),
            background = 0xFF1C1B1F.toInt(),
            onBackground = 0xFFE6E1E5.toInt(),
            outline = 0xFF938F99.toInt(),
            outlineVariant = 0xFF49454F.toInt(),
            inverseSurface = 0xFFE6E1E5.toInt(),
            inverseOnSurface = 0xFF313033.toInt(),
            inversePrimary = hslToRgb(h, s.coerceAtLeast(0.4f), 0.4f),
            isDark = true
        )
    }

    /**
     * 获取默认�?Material You 色彩方案（亮色模式）
     */
    fun getDefaultLightColors(): MaterialYouColors {
        return MaterialYouColors(
            primary = 0xFF1A73E8.toInt(),
            onPrimary = 0xFFFFFFFF.toInt(),
            primaryContainer = 0xFFD3E3FD.toInt(),
            onPrimaryContainer = 0xFF001D36.toInt(),
            secondary = 0xFF4285F4.toInt(),
            onSecondary = 0xFFFFFFFF.toInt(),
            secondaryContainer = 0xFFD9E2FF.toInt(),
            onSecondaryContainer = 0xFF001A42.toInt(),
            tertiary = 0xFF0F9D58.toInt(),
            onTertiary = 0xFFFFFFFF.toInt(),
            tertiaryContainer = 0xFFD5F9E3.toInt(),
            onTertiaryContainer = 0xFF002113.toInt(),
            error = 0xFFB00020.toInt(),
            onError = 0xFFFFFFFF.toInt(),
            errorContainer = 0xFFF9DEDC.toInt(),
            onErrorContainer = 0xFF410E0B.toInt(),
            surface = 0xFFFFFBFE.toInt(),
            onSurface = 0xFF1C1B1F.toInt(),
            surfaceVariant = 0xFFE7E0EC.toInt(),
            onSurfaceVariant = 0xFF49454F.toInt(),
            surfaceContainer = 0xFFF3EDF7.toInt(),
            surfaceContainerLow = 0xFFF7F2FA.toInt(),
            surfaceContainerHigh = 0xFFECE6F0.toInt(),
            surfaceContainerHighest = 0xFFE6E0E9.toInt(),
            background = 0xFFFFFBFE.toInt(),
            onBackground = 0xFF1C1B1F.toInt(),
            outline = 0xFF79747E.toInt(),
            outlineVariant = 0xFFCAC4D0.toInt(),
            inverseSurface = 0xFF313033.toInt(),
            inverseOnSurface = 0xFFF4EFF4.toInt(),
            inversePrimary = 0xFFA8C7FA.toInt(),
            isDark = false
        )
    }

    /**
     * 获取默认�?Material You 色彩方案（暗色模式）
     */
    fun getDefaultDarkColors(): MaterialYouColors {
        return MaterialYouColors(
            primary = 0xFFA8C7FA.toInt(),
            onPrimary = 0xFF003366.toInt(),
            primaryContainer = 0xFF004A99.toInt(),
            onPrimaryContainer = 0xFFD3E3FD.toInt(),
            secondary = 0xFFB8C5FF.toInt(),
            onSecondary = 0xFF112567.toInt(),
            secondaryContainer = 0xFF2A3E8F.toInt(),
            onSecondaryContainer = 0xFFD9E2FF.toInt(),
            tertiary = 0xFF75D7A9.toInt(),
            onTertiary = 0xFF003824.toInt(),
            tertiaryContainer = 0xFF005237.toInt(),
            onTertiaryContainer = 0xFFD5F9E3.toInt(),
            error = 0xFFF2B8B5.toInt(),
            onError = 0xFF601410.toInt(),
            errorContainer = 0xFF8C1D18.toInt(),
            onErrorContainer = 0xFFF9DEDC.toInt(),
            surface = 0xFF1C1B1F.toInt(),
            onSurface = 0xFFE6E1E5.toInt(),
            surfaceVariant = 0xFF49454F.toInt(),
            onSurfaceVariant = 0xFFCAC4D0.toInt(),
            surfaceContainer = 0xFF2E2C33.toInt(),
            surfaceContainerLow = 0xFF262529.toInt(),
            surfaceContainerHigh = 0xFF36343B.toInt(),
            surfaceContainerHighest = 0xFF47464E.toInt(),
            background = 0xFF1C1B1F.toInt(),
            onBackground = 0xFFE6E1E5.toInt(),
            outline = 0xFF938F99.toInt(),
            outlineVariant = 0xFF49454F.toInt(),
            inverseSurface = 0xFFE6E1E5.toInt(),
            inverseOnSurface = 0xFF313033.toInt(),
            inversePrimary = 0xFF1A73E8.toInt(),
            isDark = true
        )
    }

    /**
     * �?MaterialYouColors 转换�?Compose ColorScheme
     */
    fun toColorScheme(colors: MaterialYouColors): androidx.compose.material3.ColorScheme {
        return if (colors.isDark) {
            androidx.compose.material3.darkColorScheme(
                primary = ComposeColor(colors.primary),
                onPrimary = ComposeColor(colors.onPrimary),
                primaryContainer = ComposeColor(colors.primaryContainer),
                onPrimaryContainer = ComposeColor(colors.onPrimaryContainer),
                secondary = ComposeColor(colors.secondary),
                onSecondary = ComposeColor(colors.onSecondary),
                secondaryContainer = ComposeColor(colors.secondaryContainer),
                onSecondaryContainer = ComposeColor(colors.onSecondaryContainer),
                tertiary = ComposeColor(colors.tertiary),
                onTertiary = ComposeColor(colors.onTertiary),
                tertiaryContainer = ComposeColor(colors.tertiaryContainer),
                onTertiaryContainer = ComposeColor(colors.onTertiaryContainer),
                error = ComposeColor(colors.error),
                onError = ComposeColor(colors.onError),
                errorContainer = ComposeColor(colors.errorContainer),
                onErrorContainer = ComposeColor(colors.onErrorContainer),
                surface = ComposeColor(colors.surface),
                onSurface = ComposeColor(colors.onSurface),
                surfaceVariant = ComposeColor(colors.surfaceVariant),
                onSurfaceVariant = ComposeColor(colors.onSurfaceVariant),
                surfaceContainer = ComposeColor(colors.surfaceContainer),
                surfaceContainerLow = ComposeColor(colors.surfaceContainerLow),
                surfaceContainerHigh = ComposeColor(colors.surfaceContainerHigh),
                surfaceContainerHighest = ComposeColor(colors.surfaceContainerHighest),
                background = ComposeColor(colors.background),
                onBackground = ComposeColor(colors.onBackground),
                outline = ComposeColor(colors.outline),
                outlineVariant = ComposeColor(colors.outlineVariant),
                inverseSurface = ComposeColor(colors.inverseSurface),
                inverseOnSurface = ComposeColor(colors.inverseOnSurface),
                inversePrimary = ComposeColor(colors.inversePrimary)
            )
        } else {
            androidx.compose.material3.lightColorScheme(
                primary = ComposeColor(colors.primary),
                onPrimary = ComposeColor(colors.onPrimary),
                primaryContainer = ComposeColor(colors.primaryContainer),
                onPrimaryContainer = ComposeColor(colors.onPrimaryContainer),
                secondary = ComposeColor(colors.secondary),
                onSecondary = ComposeColor(colors.onSecondary),
                secondaryContainer = ComposeColor(colors.secondaryContainer),
                onSecondaryContainer = ComposeColor(colors.onSecondaryContainer),
                tertiary = ComposeColor(colors.tertiary),
                onTertiary = ComposeColor(colors.onTertiary),
                tertiaryContainer = ComposeColor(colors.tertiaryContainer),
                onTertiaryContainer = ComposeColor(colors.onTertiaryContainer),
                error = ComposeColor(colors.error),
                onError = ComposeColor(colors.onError),
                errorContainer = ComposeColor(colors.errorContainer),
                onErrorContainer = ComposeColor(colors.onErrorContainer),
                surface = ComposeColor(colors.surface),
                onSurface = ComposeColor(colors.onSurface),
                surfaceVariant = ComposeColor(colors.surfaceVariant),
                onSurfaceVariant = ComposeColor(colors.onSurfaceVariant),
                surfaceContainer = ComposeColor(colors.surfaceContainer),
                surfaceContainerLow = ComposeColor(colors.surfaceContainerLow),
                surfaceContainerHigh = ComposeColor(colors.surfaceContainerHigh),
                surfaceContainerHighest = ComposeColor(colors.surfaceContainerHighest),
                background = ComposeColor(colors.background),
                onBackground = ComposeColor(colors.onBackground),
                outline = ComposeColor(colors.outline),
                outlineVariant = ComposeColor(colors.outlineVariant),
                inverseSurface = ComposeColor(colors.inverseSurface),
                inverseOnSurface = ComposeColor(colors.inverseOnSurface),
                inversePrimary = ComposeColor(colors.inversePrimary)
            )
        }
    }

    /**
     * RGB �?HSL
     */
    private fun rgbToHsl(color: Int): FloatArray {
        val r = ((color shr 16) and 0xFF) / 255f
        val g = ((color shr 8) and 0xFF) / 255f
        val b = (color and 0xFF) / 255f

        val max = r.coerceAtLeast(g).coerceAtLeast(b)
        val min = r.coerceAtMost(g).coerceAtMost(b)
        var h = 0f
        var s = 0f
        val l = (max + min) / 2f

        if (max != min) {
            val d = max - min
            s = if (l > 0.5f) d / (2 - max - min) else d / (max + min)
            h = when (max) {
                r -> ((g - b) / d + (if (g < b) 6f else 0f)) / 6f
                g -> ((b - r) / d + 2f) / 6f
                b -> ((r - g) / d + 4f) / 6f
                else -> 0f
            }
        }

        return floatArrayOf(h * 360f, s, l)
    }

    /**
     * HSL �?RGB
     */
    private fun hslToRgb(h: Float, s: Float, l: Float): Int {
        val r: Float
        val g: Float
        val b: Float

        if (s == 0f) {
            r = l
            g = l
            b = l
        } else {
            val hue = h / 360f
            val q = if (l < 0.5f) l * (1 + s) else l + s - l * s
            val p = 2 * l - q
            r = hueToRgb(p, q, hue + 1f / 3f)
            g = hueToRgb(p, q, hue)
            b = hueToRgb(p, q, hue - 1f / 3f)
        }

        return Color.rgb(
            (r * 255).toInt().coerceIn(0, 255),
            (g * 255).toInt().coerceIn(0, 255),
            (b * 255).toInt().coerceIn(0, 255)
        )
    }

    private fun hueToRgb(p: Float, q: Float, t: Float): Float {
        var hue = t
        if (hue < 0f) hue += 1f
        if (hue > 1f) hue -= 1f
        return when {
            hue < 1f / 6f -> p + (q - p) * 6f * hue
            hue < 1f / 2f -> q
            hue < 2f / 3f -> p + (q - p) * (2f / 3f - hue) * 6f
            else -> p
        }
    }
}
