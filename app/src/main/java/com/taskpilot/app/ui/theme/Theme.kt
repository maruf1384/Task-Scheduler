package com.taskpilot.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Brand: deep indigo + mint. Status colours are shared by both themes.
val Success = Color(0xFF16A34A)
val Danger = Color(0xFFDC2626)
val Warn = Color(0xFFD97706)

private val LightColors = lightColorScheme(
    primary = Color(0xFF3D4ED8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E4FF),
    onPrimaryContainer = Color(0xFF0F1A63),
    secondary = Color(0xFF0E9F8E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCDF5EE),
    onSecondaryContainer = Color(0xFF00352E),
    tertiary = Color(0xFFB45309),
    tertiaryContainer = Color(0xFFFFE8CC),
    onTertiaryContainer = Color(0xFF4A2300),
    background = Color(0xFFF6F7FB),
    onBackground = Color(0xFF14161F),
    surface = Color(0xFFF6F7FB),
    onSurface = Color(0xFF14161F),
    surfaceVariant = Color(0xFFE4E6F0),
    onSurfaceVariant = Color(0xFF5B5F73),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFEFF1F8),
    surfaceContainerHigh = Color(0xFFE9EBF4),
    surfaceContainerHighest = Color(0xFFE2E5EF),
    outline = Color(0xFFB9BDCF),
    outlineVariant = Color(0xFFDADDE8),
    error = Danger,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFAAB6FF),
    onPrimary = Color(0xFF14207A),
    primaryContainer = Color(0xFF2A3696),
    onPrimaryContainer = Color(0xFFE0E4FF),
    secondary = Color(0xFF5EEAD4),
    onSecondary = Color(0xFF003731),
    secondaryContainer = Color(0xFF0B4F47),
    onSecondaryContainer = Color(0xFFCDF5EE),
    tertiary = Color(0xFFFFB870),
    tertiaryContainer = Color(0xFF5C3300),
    onTertiaryContainer = Color(0xFFFFE8CC),
    background = Color(0xFF0F1117),
    onBackground = Color(0xFFE6E7EE),
    surface = Color(0xFF0F1117),
    onSurface = Color(0xFFE6E7EE),
    surfaceVariant = Color(0xFF2A2D3A),
    onSurfaceVariant = Color(0xFFA9ADBF),
    surfaceContainerLowest = Color(0xFF0B0D12),
    surfaceContainerLow = Color(0xFF171A23),
    surfaceContainer = Color(0xFF1B1E28),
    surfaceContainerHigh = Color(0xFF222632),
    surfaceContainerHighest = Color(0xFF2B2F3C),
    outline = Color(0xFF4A4E60),
    outlineVariant = Color(0xFF30333F),
    error = Color(0xFFFF8A80),
)

private val AppTypography = Typography().let { t ->
    t.copy(
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

val Mono = TextStyle(fontSize = 12.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)

@Composable
fun TaskPilotTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = AppTypography,
        shapes = Shapes(
            small = RoundedCornerShape(10.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(22.dp),
        ),
        content = content,
    )
}
