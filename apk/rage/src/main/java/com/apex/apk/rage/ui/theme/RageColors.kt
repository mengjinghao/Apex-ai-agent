package com.apex.apk.rage.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/** 狂暴模式专属配色 — 暗色为主，橙红色调突出"狂暴"感。 */
object RageColors {
    // 亮色
    val LightPrimary = Color(0xFFB71C1C)
    val LightOnPrimary = Color.White
    val LightPrimaryContainer = Color(0xFFFFCDD2)
    val LightOnPrimaryContainer = Color(0xFF410002)
    val LightSecondary = Color(0xFFE65100)
    val LightSecondaryContainer = Color(0xFFFFE0B2)
    val LightTertiary = Color(0xFF6A1B9A)
    val LightBackground = Color(0xFFFAFAFA)
    val LightSurface = Color(0xFFFFF8F6)
    val LightSurfaceVariant = Color(0xFFFFCCBC)

    // 暗色
    val DarkPrimary = Color(0xFFFF5252)
    val DarkOnPrimary = Color(0xFF690000)
    val DarkPrimaryContainer = Color(0xFF93000A)
    val DarkOnPrimaryContainer = Color(0xFFFFDAD6)
    val DarkSecondary = Color(0xFFFFAB40)
    val DarkSecondaryContainer = Color(0xFF5D4037)
    val DarkTertiary = Color(0xFFCE93D8)
    val DarkBackground = Color(0xFF141118)
    val DarkSurface = Color(0xFF1A1520)
    val DarkSurfaceVariant = Color(0xFF2A2230)

    // 执行状态色
    val Thinking = Color(0xFF7C4DFF)   // 思考中 - 紫
    val Executing = Color(0xFFFFAB40)  // 执行中 - 橙
    val Success = Color(0xFF69F0AE)    // 成功 - 绿
    val Failed = Color(0xFFFF5252)     // 失败 - 红
    val Paused = Color(0xFF40C4FF)     // 暂停 - 蓝
}

val RageLightColors = lightColorScheme(
    primary = RageColors.LightPrimary,
    onPrimary = RageColors.LightOnPrimary,
    primaryContainer = RageColors.LightPrimaryContainer,
    onPrimaryContainer = RageColors.LightOnPrimaryContainer,
    secondary = RageColors.LightSecondary,
    secondaryContainer = RageColors.LightSecondaryContainer,
    tertiary = RageColors.LightTertiary,
    background = RageColors.LightBackground,
    surface = RageColors.LightSurface,
    surfaceVariant = RageColors.LightSurfaceVariant
)

val RageDarkColors = darkColorScheme(
    primary = RageColors.DarkPrimary,
    onPrimary = RageColors.DarkOnPrimary,
    primaryContainer = RageColors.DarkPrimaryContainer,
    onPrimaryContainer = RageColors.DarkOnPrimaryContainer,
    secondary = RageColors.DarkSecondary,
    secondaryContainer = RageColors.DarkSecondaryContainer,
    tertiary = RageColors.DarkTertiary,
    background = RageColors.DarkBackground,
    surface = RageColors.DarkSurface,
    surfaceVariant = RageColors.DarkSurfaceVariant
)
