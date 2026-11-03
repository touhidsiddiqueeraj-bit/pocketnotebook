package com.pocketnotebook.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

// ---------------------------------------------------------------------------
// Liquid Glass palette — "Liquid" dark + "Porcelain" light
// ---------------------------------------------------------------------------

data class GlassColors(
    val fillTop: Color,
    val fillBottom: Color,
    val borderTop: Color,
    val borderBottom: Color,
    val text: Color,
    val textMuted: Color,
    val accent: Color,
    val accent2: Color,
    val accent3: Color,
    val glow: Color,
)

val DarkGlass = GlassColors(
    fillTop = Color(0x14FFFFFF),
    fillBottom = Color(0x08FFFFFF),
    borderTop = Color(0x59FFFFFF),
    borderBottom = Color(0x0FFFFFFF),
    text = Color(0xFFEAF0FF),
    textMuted = Color(0xFF8B94AD),
    accent = Color(0xFF7FE7D4),
    accent2 = Color(0xFF9BA4FF),
    accent3 = Color(0xFFFF9EC7),
    glow = Color(0x337FE7D4),
)

val LightGlass = GlassColors(
    fillTop = Color(0xB8FFFFFF),
    fillBottom = Color(0x8CFFFFFF),
    borderTop = Color(0xFFFFFFFF),
    borderBottom = Color(0x73FFFFFF),
    text = Color(0xFF131A2E),
    textMuted = Color(0xFF5A6480),
    accent = Color(0xFF0E8C7A),
    accent2 = Color(0xFF5B5ED6),
    accent3 = Color(0xFFC2527E),
    glow = Color(0x220E8C7A),
)

val LocalGlassColors = staticCompositionLocalOf { DarkGlass }

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF7FE7D4),
    onPrimary = Color(0xFF06231E),
    secondary = Color(0xFF9BA4FF),
    onSecondary = Color(0xFF101B3A),
    tertiary = Color(0xFFFF9EC7),
    background = Color(0xFF070B14),
    onBackground = Color(0xFFEAF0FF),
    surface = Color(0xFF0C1122),
    onSurface = Color(0xFFEAF0FF),
    surfaceVariant = Color(0xFF121A30),
    onSurfaceVariant = Color(0xFF8B94AD),
    error = Color(0xFFFF7A8A),
    outline = Color(0xFF2A3450),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF0E8C7A),
    onPrimary = Color.White,
    secondary = Color(0xFF5B5ED6),
    tertiary = Color(0xFFC2527E),
    background = Color(0xFFEEF2F9),
    onBackground = Color(0xFF131A2E),
    surface = Color.White,
    onSurface = Color(0xFF131A2E),
    surfaceVariant = Color(0xFFE3E9F5),
    onSurfaceVariant = Color(0xFF5A6480),
    error = Color(0xFFB3213C),
    outline = Color(0xFFC6CFDF),
)

/** Minimalist type scale: tight, airy, generous line height. */
private val PocketType = Typography().let { base ->
    base.copy(
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Medium),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.Medium),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp),
        labelMedium = base.labelMedium.copy(letterSpacing = 0.4.sp),
        labelSmall = TextStyle(
            fontWeight = FontWeight.Medium,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            letterSpacing = 0.8.sp,
        ),
    )
}

/**
 * themeMode: "system" | "dark" | "light" (from PocketPrefs).
 */
@Composable
fun PocketNotebookTheme(
    themeMode: String = "system",
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        (view.context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark
        }
    }

    CompositionLocalProvider(LocalGlassColors provides if (dark) DarkGlass else LightGlass) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            typography = PocketType,
            content = content,
        )
    }
}
