package com.orbit.ai.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.orbit.ai.settings.ThemeMode

val OrbitPurple = Color(0xFF8B5CF6)
val OrbitCyan = Color(0xFF22D3EE)
val OrbitRed = Color(0xFFF87171)

/** Extra colours that Material's scheme has no slot for. */
@Immutable
data class OrbitColors(
    val charcoal: Color,
    val glowPurple: Color,
    val glowCyan: Color,
    val softText: Color,
    val success: Color
)

val LocalOrbitColors = staticCompositionLocalOf {
    OrbitColors(Color(0xFF12121A), OrbitPurple, OrbitCyan, Color(0xFFA1A1B5), Color(0xFF4ADE80))
}

private val DarkScheme = darkColorScheme(
    primary = OrbitPurple,
    secondary = OrbitCyan,
    tertiary = Color(0xFFC4B5FD),
    background = Color(0xFF07070B),       // deep black
    surface = Color(0xFF12121A),          // dark charcoal
    surfaceVariant = Color(0xFF1B1B26),
    onPrimary = Color.White,
    onSecondary = Color(0xFF00232B),
    onBackground = Color(0xFFF5F5FA),
    onSurface = Color(0xFFF5F5FA),
    onSurfaceVariant = Color(0xFFA1A1B5),
    outline = Color(0xFF3A3A4D),
    error = OrbitRed
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF6D3FE0),
    secondary = Color(0xFF0891B2),
    tertiary = Color(0xFF7C3AED),
    background = Color(0xFFF6F5FB),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEDEBF7),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = Color(0xFF14141C),
    onSurface = Color(0xFF14141C),
    onSurfaceVariant = Color(0xFF5B5B70),
    outline = Color(0xFFC9C7DA),
    error = Color(0xFFDC2626)
)

private val OrbitTypography = Typography(
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Light, fontSize = 30.sp, letterSpacing = 4.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 22.sp, letterSpacing = 0.5.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 16.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 21.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 1.sp)
)

/** Every colour animates for 450 ms, so switching Dark/Light/System fades instead of snapping. */
@Composable
private fun animated(scheme: ColorScheme): ColorScheme {
    @Composable fun c(color: Color) = animateColorAsState(color, tween(450), label = "theme").value
    return scheme.copy(
        primary = c(scheme.primary), secondary = c(scheme.secondary), tertiary = c(scheme.tertiary),
        background = c(scheme.background), surface = c(scheme.surface), surfaceVariant = c(scheme.surfaceVariant),
        onPrimary = c(scheme.onPrimary), onSecondary = c(scheme.onSecondary),
        onBackground = c(scheme.onBackground), onSurface = c(scheme.onSurface),
        onSurfaceVariant = c(scheme.onSurfaceVariant), outline = c(scheme.outline), error = c(scheme.error)
    )
}

@Composable
fun OrbitTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val scheme = animated(if (dark) DarkScheme else LightScheme)
    val extra = OrbitColors(
        charcoal = scheme.surface,
        glowPurple = scheme.primary,
        glowCyan = scheme.secondary,
        softText = scheme.onSurfaceVariant,
        success = if (dark) Color(0xFF4ADE80) else Color(0xFF16A34A)
    )
    androidx.compose.runtime.CompositionLocalProvider(LocalOrbitColors provides extra) {
        MaterialTheme(colorScheme = scheme, typography = OrbitTypography, content = content)
    }
}
