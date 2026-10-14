package com.lucifer.mobile.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Lucifer Color Palette
private val LuciferRed = Color(0xFFFF0000)
private val LuciferDarkRed = Color(0xFFCC0000)
private val LuciferGold = Color(0xFFFFD700)
private val LuciferBlack = Color(0xFF0D0D0D)
private val LuciferObsidian = Color(0xFF1A1A1A)
private val LuciferSurface = Color(0xFF1A1A1A)
private val LuciferSurfaceVariant = Color(0xFF2A2A2A)
private val LuciferOnSurface = Color(0xFFFFFFFF)
private val LuciferOnSurfaceVariant = Color(0xFFCCCCCC)
private val LuciferOutline = Color(0xFF333333)
private val LuciferError = Color(0xFFFF4444)

private val DarkColorScheme = darkColorScheme(
    primary = LuciferRed,
    primaryContainer = LuciferDarkRed,
    secondary = LuciferGold,
    secondaryContainer = Color(0xFFB8860B),
    tertiary = Color(0xFFFF4444),
    tertiaryContainer = Color(0xFF8B0000),
    surface = LuciferSurface,
    surfaceContainer = LuciferSurfaceVariant,
    surfaceContainerHigh = Color(0xFF252525),
    surfaceContainerHighest = Color(0xFF303030),
    onSurface = LuciferOnSurface,
    onSurfaceVariant = LuciferOnSurfaceVariant,
    outline = LuciferOutline,
    outlineVariant = Color(0xFF444444),
    error = LuciferError,
    errorContainer = Color(0xFF440000),
    onError = Color.White,
    onErrorContainer = Color.White,
    scrim = Color.Black,
    shadow = Color.Black,
    inverseSurface = LuciferBlack,
    inverseOnSurface = LuciferOnSurface,
    inversePrimary = LuciferGold
)

private val LightColorScheme = lightColorScheme(
    primary = LuciferRed,
    primaryContainer = Color(0xFFFFCCCC),
    secondary = LuciferGold,
    secondaryContainer = Color(0xFFFFF8CC),
    tertiary = Color(0xFFFF4444),
    tertiaryContainer = Color(0xFFFFCCCC),
    surface = Color.White,
    surfaceContainer = Color(0xFFF5F5F5),
    surfaceContainerHigh = Color(0xFFEEEEEE),
    surfaceContainerHighest = Color(0xFFE0E0E0),
    onSurface = Color.Black,
    onSurfaceVariant = Color(0xFF333333),
    outline = Color(0xFF999999),
    outlineVariant = Color(0xFFCCCCCC),
    error = LuciferError,
    errorContainer = Color(0xFFFFCCCC),
    onError = Color.White,
    onErrorContainer = Color.Black,
    scrim = Color.Black,
    shadow = Color.Black,
    inverseSurface = LuciferBlack,
    inverseOnSurface = LuciferOnSurface,
    inversePrimary = LuciferRed
)

@Composable
fun LuciferTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = Shapes,
        content = content
    )
}

// Lucifer Typography
val Typography = androidx.compose.material3.Typography(
    displayLarge = androidx.compose.ui.text.TextStyle(
        fontFamily = androidx.compose.ui.text.font.FontFamily.Default,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = -0.25.sp
    ),
    displayMedium = androidx.compose.ui.text.TextStyle(
        fontFamily = androidx.compose.ui.text.font.FontFamily.Default,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
        fontSize = 45.sp,
        lineHeight = 52.sp
    ),
    displaySmall = androidx.compose.ui.text.TextStyle(
        fontFamily = androidx.compose.ui.text.font.FontFamily.Default,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
        fontSize = 36.sp,
        lineHeight = 44.sp
    ),
    headlineLarge = androidx.compose.ui.text.TextStyle(
        fontFamily = androidx.compose.ui.text.font.FontFamily.Default,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 40.sp
    ),
    headlineMedium = androidx.compose.ui.text.TextStyle(
        fontFamily = androidx.compose.ui.text.font.FontFamily.Default,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 36.sp
    ),
    headlineSmall = androidx.compose.ui.text.TextStyle(
        fontFamily = androidx.compose.ui.text.font.FontFamily.Default,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 32.sp
    ),
    titleLarge = androidx.compose.ui.text.TextStyle(
        fontFamily = androidx.compose.ui.text.font.FontFamily.Default,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp
    ),
    titleMedium = androidx.compose.ui.text.TextStyle(
        fontFamily = androidx.compose.ui.text.font.FontFamily.Default,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp
    ),
    titleSmall = androidx.compose.ui.text.TextStyle(
        fontFamily = androidx.compose.ui.text.font.FontFamily.Default,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    bodyLarge = androidx.compose.ui.text.TextStyle(
        fontFamily = androidx.compose.ui.text.font.FontFamily.Default,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    ),
    bodyMedium = androidx.compose.ui.text.TextStyle(
        fontFamily = androidx.compose.ui.text.font.FontFamily.Default,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp
    ),
    bodySmall = androidx.compose.ui.text.TextStyle(
        fontFamily = androidx.compose.ui.text.font.FontFamily.Default,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp
    ),
    labelLarge = androidx.compose.ui.text.TextStyle(
        fontFamily = androidx.compose.ui.text.font.FontFamily.Default,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    labelMedium = androidx.compose.ui.text.TextStyle(
        fontFamily = androidx.compose.ui.text.font.FontFamily.Default,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    ),
    labelSmall = androidx.compose.ui.text.TextStyle(
        fontFamily = androidx.compose.ui.text.font.FontFamily.Default,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )
)

// Lucifer Shapes
val Shapes = androidx.compose.material3.Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)
)

// Lucifer Colors object for easy access
object LuciferColors {
    val Red = Color(0xFFFF0000)
    val DarkRed = Color(0xFFCC0000)
    val Gold = Color(0xFFFFD700)
    val Black = Color(0xFF0D0D0D)
    val Obsidian = Color(0xFF1A1A1A)
    val Surface = Color(0xFF1A1A1A)
    val SurfaceVariant = Color(0xFF2A2A2A)
    val OnSurface = Color(0xFFFFFFFF)
    val OnSurfaceVariant = Color(0xFFCCCCCC)
    val Outline = Color(0xFF333333)
    val Error = Color(0xFFFF4444)
    val GoldLight = Color(0xFFFFEB3B)
    val GoldDark = Color(0xFFB8860B)
}