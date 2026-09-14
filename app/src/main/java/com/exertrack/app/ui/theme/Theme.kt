package com.exertrack.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp

/** Phase colours used by the active screen and the notification-free parts of the UI. */
object ExColors {
    val background = Color(0xFF0B1220)
    val surface = Color(0xFF16213A)
    val surfaceHigh = Color(0xFF1F2D4D)
    val go = Color(0xFF4ADE80)        // exercise running / primary
    val rest = Color(0xFF60A5FA)      // rest countdown
    val paused = Color(0xFFFBBF24)    // paused
    val waiting = Color(0xFFC084FC)   // waiting for a tap / lead-in
    val done = Color(0xFF94A3B8)      // completed steps
    val danger = Color(0xFFF87171)
    val text = Color(0xFFF8FAFC)
    val textDim = Color(0xFF94A3B8)
}

private val DarkScheme = darkColorScheme(
    primary = ExColors.go,
    onPrimary = Color(0xFF052E16),
    secondary = ExColors.rest,
    onSecondary = Color(0xFF0B1220),
    tertiary = ExColors.waiting,
    background = ExColors.background,
    onBackground = ExColors.text,
    surface = ExColors.surface,
    onSurface = ExColors.text,
    surfaceVariant = ExColors.surfaceHigh,
    onSurfaceVariant = ExColors.textDim,
    error = ExColors.danger,
    onError = Color(0xFF0B1220),
    outline = Color(0xFF334155),
)

private val ExTypography = Typography(
    displayLarge = TextStyle(fontSize = 96.sp, fontWeight = FontWeight.Bold, letterSpacing = (-2).sp),
    displayMedium = TextStyle(fontSize = 64.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp),
    headlineLarge = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.SemiBold),
    headlineMedium = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 18.sp),
    bodyMedium = TextStyle(fontSize = 16.sp),
    labelLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
)

@Composable
fun ExertrackTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkScheme, typography = ExTypography, content = content)
}
