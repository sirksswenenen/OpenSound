package com.soundcloud.lite.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import com.soundcloud.lite.data.AnimSpeed
import com.soundcloud.lite.data.AppSettings
import com.soundcloud.lite.data.GlassQuality
import com.soundcloud.lite.data.ThemePreset

data class SCThemeAttrs(
    val preset: ThemePreset,
    val glassEnabled: Boolean,
    val glassDark: Boolean,
    val liquidGlass: Boolean,
    val glassBlur: Float,
    val glassTint: Float,
    val glassReflection: Float,
    val glassHighlight: Float,
    val glassChroma: Float,
    val glassRadius: Float,
    val glassRefraction: Float,
    val glassQuality: GlassQuality,
    val glassUseGpuEffects: Boolean,
    val glassTintColor: Long,
    val animSpeed: AnimSpeed,
    val gradientBackground: Boolean,
    val bg: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val primary: Color,
    val gradientTop: Color,
    val gradientBottom: Color
)

// Use a regular `compositionLocalOf` (not `staticCompositionLocalOf`) so
// slider drags - which fire many AppSettings updates per second through
// SCLiteTheme - only invalidate direct readers of the changed field
// (the GlassSurface drawBehind, the LaunchedEffect that reads via
// rememberUpdatedState, etc.) rather than tearing down and re-running
// the whole AppRoot subtree, which used to interrupt the
// blur-capture coroutine.
val LocalSCTheme = compositionLocalOf<SCThemeAttrs> {
    error("SCThemeAttrs not provided")
}

@Composable
fun SCLiteTheme(
    settings: AppSettings,
    content: @Composable () -> Unit
) {
    val preset = settings.themePreset
    val isCustom = preset == ThemePreset.Custom
    val accent = if (isCustom) Color(settings.customAccent) else Color(preset.primary)
    val gradTop = if (isCustom) Color(settings.customGradientTop) else Color(preset.gradientTop)
    val gradBot = if (isCustom) Color(settings.customGradientBottom) else Color(preset.gradientBottom)
    // For Custom preset we synthesise the surface colours from the gradient
    // bottom so the cards/sheets visually match the user-picked background.
    val bg = if (isCustom) gradBot else Color(preset.bg)
    val surface = if (isCustom) lighten(gradBot, 0.05f) else Color(preset.surface)
    val surfaceVariant = if (isCustom) lighten(gradBot, 0.10f) else Color(preset.surfaceVariant)

    val attrs = SCThemeAttrs(
        preset = preset,
        glassEnabled = settings.glassEnabled,
        glassDark = settings.glassDark,
        liquidGlass = settings.liquidGlass,
        glassBlur = settings.glassBlur,
        glassTint = settings.glassTint,
        glassReflection = settings.glassReflection,
        glassHighlight = settings.glassHighlight,
        glassChroma = settings.glassChroma,
        glassRadius = settings.glassRadius,
        glassRefraction = settings.glassRefraction,
        glassQuality = settings.glassQuality,
        glassUseGpuEffects = settings.glassUseGpuEffects,
        glassTintColor = settings.glassTintColor,
        animSpeed = settings.animationSpeed,
        gradientBackground = settings.gradientBackground,
        bg = bg,
        surface = surface,
        surfaceVariant = surfaceVariant,
        primary = accent,
        gradientTop = gradTop,
        gradientBottom = gradBot
    )

    val colors = darkColorScheme(
        primary = attrs.primary,
        onPrimary = Color.White,
        secondary = attrs.primary,
        background = attrs.bg,
        surface = attrs.surface,
        surfaceVariant = attrs.surfaceVariant,
        onBackground = Color(0xFFE6E6EC),
        onSurface = Color(0xFFE6E6EC),
        onSurfaceVariant = Color(0xFFA8A8B2),
        error = Color(0xFFFF6B6B)
    )

    CompositionLocalProvider(LocalSCTheme provides attrs) {
        MaterialTheme(
            colorScheme = colors,
            content = content
        )
    }
}

/** Lightens an opaque colour towards white by [fraction] (0..1). */
private fun lighten(c: Color, fraction: Float): Color {
    val f = fraction.coerceIn(0f, 1f)
    return Color(
        red = c.red + (1f - c.red) * f,
        green = c.green + (1f - c.green) * f,
        blue = c.blue + (1f - c.blue) * f,
        alpha = c.alpha
    )
}
