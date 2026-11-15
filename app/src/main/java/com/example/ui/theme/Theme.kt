package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val CyberDarkColorScheme = darkColorScheme(
    primary = CyberCyan,
    onPrimary = CyberObsidian,
    primaryContainer = CyberCyanContainer,
    onPrimaryContainer = CyberCyan,
    secondary = CyberEmerald,
    onSecondary = CyberObsidian,
    secondaryContainer = CyberEmeraldContainer,
    onSecondaryContainer = CyberEmerald,
    tertiary = CyberAmber,
    onTertiary = CyberObsidian,
    tertiaryContainer = CyberAmberContainer,
    onTertiaryContainer = CyberAmber,
    error = CyberCrimson,
    onError = Color.White,
    errorContainer = CyberCrimsonContainer,
    onErrorContainer = CyberCrimson,
    background = CyberObsidian,
    onBackground = CyberTextPrimary,
    surface = CyberSlate,
    onSurface = CyberTextPrimary,
    surfaceVariant = CyberSurfaceElevated,
    onSurfaceVariant = CyberTextSecondary,
    surfaceContainerHigh = CyberSurfaceHigh,
    outline = CyberOutline,
)

private val CyberLightColorScheme = lightColorScheme(
    primary = CyberLightPrimary,
    onPrimary = Color.White,
    secondary = CyberLightSecondary,
    onSecondary = Color.White,
    background = CyberLightBg,
    onBackground = CyberObsidian,
    surface = CyberLightSurface,
    onSurface = CyberObsidian,
    error = CyberCrimson,
    onError = Color.White,
)

val CyberShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun CyberLinkTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) CyberDarkColorScheme else CyberLightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = CyberTypography,
        shapes = CyberShapes,
        content = content,
    )
}
