package com.fytbt.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Light / dark / follow-the-system, chosen in Settings. */
enum class ThemeMode { LIGHT, DARK, SYSTEM }

// Tuned for a 768x1024 portrait car screen — high contrast, no thin grey-on-grey. primary/secondary
// are overridden at runtime by the chosen accent, so only the neutrals matter here.
private val CarDark = darkColorScheme(
    primary = Color(0xFF7AB7FF),
    onPrimary = Color(0xFF002A52),
    secondary = Color(0xFF9CD67E),
    onSecondary = Color(0xFF0F3900),
    tertiary = Color(0xFFFFB779),
    onTertiary = Color(0xFF4A1F00),
    background = Color(0xFF000000),
    onBackground = Color(0xFFF5F5F5),
    surface = Color(0xFF111316),
    onSurface = Color(0xFFF5F5F5),
    surfaceVariant = Color(0xFF1E2126),
    onSurfaceVariant = Color(0xFFC8C9CC),
    outline = Color(0xFF6F7479),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF370001),
)

private val CarLight = lightColorScheme(
    primary = Color(0xFF1769C7),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF3C8A22),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFFB1560A),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFF7F8FA),
    onBackground = Color(0xFF15171A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF15171A),
    surfaceVariant = Color(0xFFE6E8EC),
    onSurfaceVariant = Color(0xFF44474C),
    outline = Color(0xFF74777E),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
)

private val CarTypography = Typography(
    headlineMedium = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.SemiBold, lineHeight = 34.sp),
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp),
    titleMedium = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontSize = 18.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    labelLarge = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp),
)

private fun readableOn(bg: Color): Color = if (bg.luminance() > 0.5f) Color.Black else Color.White

/** Apply the chosen accent as the scheme's primary AND secondary, so every accent-tinted control
 *  (buttons, switches, highlights) follows the picked color. */
private fun ColorScheme.withAccent(accent: Color): ColorScheme = copy(
    primary = accent,
    onPrimary = readableOn(accent),
    secondary = accent,
    onSecondary = readableOn(accent),
)

@Composable
fun FytBtTheme(
    accent: Int,
    themeMode: ThemeMode = ThemeMode.DARK,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val scheme = (if (dark) CarDark else CarLight).withAccent(Color(accent))
    MaterialTheme(colorScheme = scheme, typography = CarTypography, content = content)
}

/**
 * Forced-dark theme for the Now Playing media screen. Its background is always the album art under a
 * dark scrim (or an accent-on-black gradient when there's no art), so it uses light text and a dark
 * surface regardless of the app's light/dark setting — exactly like every other media player.
 */
@Composable
fun NowPlayingDarkTheme(accent: Int, content: @Composable () -> Unit) {
    val scheme = CarDark.withAccent(Color(accent))
    MaterialTheme(colorScheme = scheme, typography = CarTypography) {
        // Provide a light content color too: text with no explicit color uses LocalContentColor,
        // which the outer (possibly light) Surface would otherwise set to a dark, near-invisible tone.
        CompositionLocalProvider(LocalContentColor provides scheme.onSurface, content = content)
    }
}
