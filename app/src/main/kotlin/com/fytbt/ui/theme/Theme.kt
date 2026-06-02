package com.fytbt.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Tuned for a 768x1024 portrait car screen — high contrast, low saturation, no thin grey-on-grey.
private val CarDark = darkColorScheme(
    primary = Color(0xFF7AB7FF),
    onPrimary = Color(0xFF002A52),
    primaryContainer = Color(0xFF004489),
    onPrimaryContainer = Color(0xFFD8E5FF),
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

private val CarTypography = Typography(
    headlineMedium = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.SemiBold, lineHeight = 34.sp),
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp),
    titleMedium = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontSize = 18.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    labelLarge = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp),
)

@Composable
fun FytBtTheme(content: @Composable () -> Unit) {
    // Always dark — driver use, light theme would be a disaster on a car screen.
    @Suppress("UNUSED_EXPRESSION") isSystemInDarkTheme()
    MaterialTheme(colorScheme = CarDark, typography = CarTypography, content = content)
}
