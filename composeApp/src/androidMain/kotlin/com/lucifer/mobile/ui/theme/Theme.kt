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
private val LuciferObsidian = Color(0xFF000000)        // OLED true black
private val LuciferVoid = Color(0xFF070607)            // Lifted black
private val LuciferAbyss = Color(0xFF0F0E10)           // Glass base
private val LuciferEclipse = Color(0xFF121114)         // Raised glass

// Blood Red Variations — The signature Lucifer red
private val LuciferCrimson = Color(0xFFD32F2F)         // Crimson — the only living accent
private val LuciferBlood = Color(0xFF7A0F14)           // Blood
private val LuciferEmber = Color(0xFFFF4A24)           // Ember
private val LuciferRose = Color(0xFFE8B4B0)            // Faint rose

// Ethereal Gold — Divine contrast
private val LuciferGold = Color(0xFFC9A227)            // Gilded (sigil only)
private val LuciferAmber = Color(0xFFD9A441)           // Amber
private val LuciferGilded = Color(0xFFE7D9A8)          // Pale gilded
private val LuciferDawn = Color(0xFF9A7B1C)            // Deep gilt

// Ethereal Cyan/Azure — Magic/tech accent
private val LuciferAether = Color(0xFF7FA6C9)          // Cool ash-blue
private val LuciferArcane = Color(0xFF9FC1D9)          // Pale ash-blue
private val LuciferVoidBlue = Color(0xFF4E6B80)        // Deep slate

// Semantic Colors
private val LuciferSuccess = Color(0xFF3FB27F)         // Muted jade
private val LuciferWarning = Color(0xFFD9A441)         // Amber warning
private val LuciferError = Color(0xFFD32F2F)           // Crimson error
private val LuciferInfo = Color(0xFF7FA6C9)            // Cool info

// Text Colors
private val LuciferTextPrimary = Color(0xFFEDEAE6)     // Bone
private val LuciferTextSecondary = Color(0xFF8A8480)   // Ash
private val LuciferTextMuted = Color(0xFF55504D)       // Smoke
private val LuciferTextOnAccent = Color(0xFF0B0A0B)    // On crimson

// Borders & Dividers
private val LuciferBorder = Color(0x1FFFFFFF)          // Hairline
private val LuciferBorderGlow = Color(0x33D32F2F)      // Crimson edge (20%)
private val LuciferBorderGold = Color(0x2EC9A227)      // Gilded edge (18%)

private val DarkColorScheme = darkColorScheme(
    primary = LuciferCrimson,
    primaryContainer = LuciferBlood,
    secondary = LuciferGold,
    secondaryContainer = LuciferAmber,
    tertiary = LuciferAether,
    tertiaryContainer = LuciferVoidBlue,
    surface = LuciferAbyss,
    surfaceContainer = LuciferEclipse,
    surfaceContainerHigh = Color(0xFF151417),
    surfaceContainerHighest = Color(0xFF1A181C),
    onSurface = LuciferTextPrimary,
    onSurfaceVariant = LuciferTextSecondary,
    outline = LuciferBorder,
    outlineVariant = Color(0xFF151417),
    error = LuciferError,
    errorContainer = Color(0xFF2A0709),
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
// TYPOGRAPHY — CINZEL FOR NAMES, INTER FOR EVERYTHING READABLE
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

/** Kept as a name so existing imports keep working; the scale lives in [Morningstar]. */
val Typography: androidx.compose.material3.Typography = MorningstarTypography

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// SHAPES — SHARP, ANGULAR, ANIME MECHA AESTHETIC
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

// Every Material surface inherits the chamfer, so a Card, a dialog, and a chip all read as the
// same obsidian material without per-call-site shapes.
val Shapes = androidx.compose.material3.Shapes(
    extraSmall = ShardShape(3.dp),
    small = ShardShape(5.dp),
    medium = ShardShape(9.dp),
    large = ShardShape(13.dp),
    extraLarge = ShardShape(17.dp)
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
        typography = MorningstarTypography,
        shapes = Shapes,
        content = content
    )
}