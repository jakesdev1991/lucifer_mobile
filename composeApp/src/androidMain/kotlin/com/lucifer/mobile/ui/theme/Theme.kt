package com.lucifer.mobile.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// LUCIFER MOBILE — ANIME AESTHETIC COLOR SYSTEM
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

// Core Lucifer Palette — Deep obsidian, blood red, ethereal gold
private val LuciferObsidian = Color(0xFF0A0A0F)        // Deepest void
private val LuciferVoid = Color(0xFF0F0F1A)            // Night sky
private val LuciferAbyss = Color(0xFF161622)           // Card surfaces
private val LuciferEclipse = Color(0xFF1E1E2E)         // Elevated surfaces

// Blood Red Variations — The signature Lucifer red
private val LuciferCrimson = Color(0xFFFF1744)         // Primary accent — vibrant, alive
private val LuciferBlood = Color(0xFFD32F2F)           // Darker red
private val LuciferEmber = Color(0xFFFF5252)           // Glow state
private val LuciferRose = Color(0xFFFF8A80)            // Subtle highlights

// Ethereal Gold — Divine contrast
private val LuciferGold = Color(0xFFFFD600)            // Pure gold
private val LuciferAmber = Color(0xFFFFB300)           // Warm amber
private val LuciferGilded = Color(0xFFFFF176)          // Pale gilded
private val LuciferDawn = Color(0xFFFFEB3B)            // Dawn light

// Ethereal Cyan/Azure — Magic/tech accent
private val LuciferAether = Color(0xFF00E5FF)          // Mystical cyan
private val LuciferArcane = Color(0xFF18FFFF)          // Arcane glow
private val LuciferVoidBlue = Color(0xFF00B8D4)        // Deep void blue

// Semantic Colors
private val LuciferSuccess = Color(0xFF00E676)         // Emerald success
private val LuciferWarning = Color(0xFFFFD600)         // Gold warning
private val LuciferError = Color(0xFFFF1744)           // Crimson error
private val LuciferInfo = Color(0xFF00E5FF)            // Aether info

// Text Colors
private val LuciferTextPrimary = Color(0xFFFFFFFF)     // Pure white
private val LuciferTextSecondary = Color(0xFFB0BEC5)   // Muted steel
private val LuciferTextMuted = Color(0xFF78909C)       // Dimmed
private val LuciferTextOnAccent = Color(0xFF0A0A0F)    // On red/gold

// Borders & Dividers
private val LuciferBorder = Color(0xFF2C2C3E)          // Subtle borders
private val LuciferBorderGlow = Color(0x33FF1744)      // Glowing border (20% opacity)
private val LuciferBorderGold = Color(0x33FFD600)      // Gold border (20% opacity)

private val DarkColorScheme = darkColorScheme(
    primary = LuciferCrimson,
    primaryContainer = LuciferBlood,
    secondary = LuciferGold,
    secondaryContainer = LuciferAmber,
    tertiary = LuciferAether,
    tertiaryContainer = LuciferVoidBlue,
    surface = LuciferAbyss,
    surfaceContainer = LuciferEclipse,
    surfaceContainerHigh = Color(0xFF232336),
    surfaceContainerHighest = Color(0xFF2A2A3E),
    onSurface = LuciferTextPrimary,
    onSurfaceVariant = LuciferTextSecondary,
    outline = LuciferBorder,
    outlineVariant = Color(0xFF1E1E2E),
    error = LuciferError,
    errorContainer = Color(0xFF3E0D12),
    onError = LuciferTextPrimary,
    onErrorContainer = LuciferRose,
    scrim = Color.Black,
    inverseSurface = LuciferObsidian,
    inverseOnSurface = LuciferTextPrimary,
    inversePrimary = LuciferRose
)

private val LightColorScheme = lightColorScheme(
    primary = LuciferBlood,
    primaryContainer = Color(0xFFFFCDD2),
    secondary = LuciferAmber,
    secondaryContainer = Color(0xFFFFF3E0),
    tertiary = LuciferVoidBlue,
    tertiaryContainer = Color(0xFFE0F7FA),
    surface = Color(0xFFFAFAFA),
    surfaceContainer = Color(0xFFF5F5F5),
    surfaceContainerHigh = Color(0xFFEEEEEE),
    surfaceContainerHighest = Color(0xFFE0E0E0),
    onSurface = Color(0xFF0A0A0F),
    onSurfaceVariant = Color(0xFF424242),
    outline = Color(0xFFBDBDBD),
    outlineVariant = Color(0xFFE0E0E0),
    error = LuciferBlood,
    errorContainer = Color(0xFFFFCDD2),
    onError = Color.White,
    onErrorContainer = Color(0xFF3E0D12),
    scrim = Color.Black,
    inverseSurface = LuciferObsidian,
    inverseOnSurface = LuciferTextPrimary,
    inversePrimary = LuciferRose
)

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// TYPOGRAPHY — ANIME INSPIRED TYPE SCALE
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

val Typography = androidx.compose.material3.Typography(
    // Display — Bold, dramatic, anime title card style
    displayLarge = androidx.compose.ui.text.TextStyle(
        fontFamily = androidx.compose.ui.text.font.FontFamily.Default,
        fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold,
        fontSize = 56.sp,
        lineHeight = 64.sp,
        letterSpacing = -1.5.sp
    ),
    displayMedium = androidx.compose.ui.text.TextStyle(
        fontFamily = androidx.compose.ui.text.font.FontFamily.Default,
        fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold,
        fontSize = 44.sp,
        lineHeight = 52.sp,
        letterSpacing = -1.sp
    ),
    displaySmall = androidx.compose.ui.text.TextStyle(
        fontFamily = androidx.compose.ui.text.font.FontFamily.Default,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
        fontSize = 36.sp,
        lineHeight = 44.sp,
        letterSpacing = -0.5.sp
    ),
    
    // Headlines — Sharp, decisive
    headlineLarge = androidx.compose.ui.text.TextStyle(
        fontFamily = androidx.compose.ui.text.font.FontFamily.Default,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
        fontSize = 30.sp,
        lineHeight = 38.sp,
        letterSpacing = 0.sp
    ),
    headlineMedium = androidx.compose.ui.text.TextStyle(
        fontFamily = androidx.compose.ui.text.font.FontFamily.Default,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 34.sp,
        letterSpacing = 0.sp
    ),
    headlineSmall = androidx.compose.ui.text.TextStyle(
        fontFamily = androidx.compose.ui.text.font.FontFamily.Default,
        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 30.sp,
        letterSpacing = 0.sp
    ),
    
    // Titles — Clean, readable
    titleLarge = androidx.compose.ui.text.TextStyle(
        fontFamily = androidx.compose.ui.text.font.FontFamily.Default,
        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.1.sp
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
    
    // Body — Clean, readable for terminal/code
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
    
    // Labels — UI elements
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

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// SHAPES — SHARP, ANGULAR, ANIME MECHA AESTHETIC
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

val Shapes = androidx.compose.material3.Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(2.dp),   // Sharp
    small = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),        // Slightly rounded
    medium = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),      // Cards
    large = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),       // Modals
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)   // Sheets
)

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// LUCIFER COLORS OBJECT — EASY ACCESS
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

object LuciferColors {
    // Core
    val Obsidian = LuciferObsidian
    val Void = LuciferVoid
    val Abyss = LuciferAbyss
    val Eclipse = LuciferEclipse
    
    // Crimson Spectrum
    val Crimson = LuciferCrimson
    val Blood = LuciferBlood
    val Ember = LuciferEmber
    val Rose = LuciferRose
    
    // Gold Spectrum
    val Gold = LuciferGold
    val Amber = LuciferAmber
    val Gilded = LuciferGilded
    val Dawn = LuciferDawn
    
    // Aether Spectrum
    val Aether = LuciferAether
    val Arcane = LuciferArcane
    val VoidBlue = LuciferVoidBlue
    
    // Semantic
    val Success = LuciferSuccess
    val Warning = LuciferWarning
    val Error = LuciferError
    val Info = LuciferInfo
    
    // Text
    val TextPrimary = LuciferTextPrimary
    val TextSecondary = LuciferTextSecondary
    val TextMuted = LuciferTextMuted
    val TextOnAccent = LuciferTextOnAccent
    
    // Borders
    val Border = LuciferBorder
    val BorderGlow = LuciferBorderGlow
    val BorderGold = LuciferBorderGold
    
    // Aliases for backward compatibility
    val Black = LuciferObsidian
    val Red = LuciferCrimson
    val DarkRed = LuciferBlood
    val GoldLight = LuciferGilded
    val GoldDark = LuciferAmber
    val OnSurface = LuciferTextPrimary
    val OnSurfaceVariant = LuciferTextSecondary
    val Outline = LuciferBorder
    val Surface = LuciferAbyss
    val SurfaceVariant = LuciferEclipse
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// THEME COMPOSABLE
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

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