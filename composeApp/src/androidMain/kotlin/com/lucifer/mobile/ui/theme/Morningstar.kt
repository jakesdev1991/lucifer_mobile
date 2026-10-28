package com.lucifer.mobile.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lucifer.mobile.R

/**
 * THE MORNINGSTAR AESTHETIC
 *
 * OLED true black, smoky glass, one crimson accent used only where something is alive or
 * actionable. Nothing glows unless it means something: crimson = interactive or alert, gilded =
 * the sigil, bone = text.
 *
 * Two families carry the whole language: Cinzel (regal, high-contrast serif) for names, numerals,
 * and section marks; Inter (thin geometric sans) for everything a person has to read.
 */
object Morningstar {

    // ── surface: the void ────────────────────────────────────────────

    /** True #000000. The launcher canvas is genuinely black, not near-black. */
    val Void = Color(0xFF000000)

    /** Slightly lifted black used behind glass so the panel edge is readable. */
    val Obsidian = Color(0xFF070607)

    /** Smoky glass base, layered under a subtle white gradient. */
    val Glass = Color(0x990F0E10)

    /** Elevated glass for the focused or selected element. */
    val GlassRaised = Color(0xB3121114)

    /** Recessed slot: terminal output, text fields. */
    val Well = Color(0xCC050505)

    // ── accent: the fall ─────────────────────────────────────────────

    val Crimson = Color(0xFFD32F2F)
    val CrimsonDim = Color(0x66D32F2F)
    val CrimsonLine = Color(0x33D32F2F)
    val Ember = Color(0xFFFF4A24)
    val Blood = Color(0xFF7A0F14)

    /** Used only for the sigil ring and the active tab needle. */
    val Gilded = Color(0xFFC9A227)
    val GildedDim = Color(0x40C9A227)

    // ── text ─────────────────────────────────────────────────────────

    val Bone = Color(0xFFEDEAE6)
    val Ash = Color(0xFF8A8480)
    val Smoke = Color(0xFF55504D)
    val Whisper = Color(0xFF35312F)

    // ── glass edges ──────────────────────────────────────────────────

    /** 1px inner edge: crimson at low alpha, brightening toward the top. */
    val Edge = Color(0x33D32F2F)
    val EdgeQuiet = Color(0x1FFFFFFF)

    // ── semantics ────────────────────────────────────────────────────

    val Success = Color(0xFF3FB27F)
    val SuccessDim = Color(0x333FB27F)
    val Warning = Color(0xFFD9A441)
    val WarningDim = Color(0x33D9A441)
    val Error = Crimson
    val Info = Color(0xFF7FA6C9)
    val InfoDim = Color(0x337FA6C9)

    // ── typography ───────────────────────────────────────────────────

    /** Regal serif — names, numerals, section marks. Never body copy. */
    val Display: FontFamily = FontFamily(
        Font(R.font.cinzel_regular, FontWeight.Normal),
        Font(R.font.cinzel_semibold, FontWeight.SemiBold)
    )

    /** Thin geometric sans — everything readable. */
    val Body: FontFamily = FontFamily(
        Font(R.font.inter_light, FontWeight.Light),
        Font(R.font.inter_regular, FontWeight.Normal)
    )

    /** Instrument readouts and the terminal: a mono that was drawn for thin, geometric text. */
    val MonoTail: FontFamily = FontFamily(
        Font(R.font.jetbrains_mono_light, FontWeight.Light),
        Font(R.font.jetbrains_mono_regular, FontWeight.Normal)
    )

    /** Wordmark / screen name: Cinzel, wide tracking, small. */
    fun title(size: Int = 16): TextStyle = TextStyle(
        fontFamily = Display,
        fontWeight = FontWeight.SemiBold,
        fontSize = size.sp,
        letterSpacing = (size * 0.14f).sp,
        color = Bone
    )

    /** Section mark above a block: Cinzel caps, very wide tracking, ash. */
    fun overline(size: Int = 9): TextStyle = TextStyle(
        fontFamily = Display,
        fontWeight = FontWeight.Normal,
        fontSize = size.sp,
        letterSpacing = (size * 0.26f).sp,
        color = Ash
    )

    /** Body copy: Inter, slightly loose leading for dark backgrounds. */
    fun body(size: Int = 13): TextStyle = TextStyle(
        fontFamily = Body,
        fontWeight = FontWeight.Normal,
        fontSize = size.sp,
        lineHeight = (size * 1.5f).sp,
        letterSpacing = (size * 0.012f).sp,
        color = Bone
    )

    /** Secondary copy and metadata. */
    fun caption(size: Int = 11): TextStyle = TextStyle(
        fontFamily = Body,
        fontWeight = FontWeight.Light,
        fontSize = size.sp,
        lineHeight = (size * 1.45f).sp,
        letterSpacing = (size * 0.02f).sp,
        color = Ash
    )

    /** Button and chip labels. */
    fun label(size: Int = 10): TextStyle = TextStyle(
        fontFamily = Body,
        fontWeight = FontWeight.Normal,
        fontSize = size.sp,
        letterSpacing = (size * 0.12f).sp,
        color = Bone
    )

    /** Clock and big numerals. */
    fun numeral(size: Int = 40): TextStyle = TextStyle(
        fontFamily = Display,
        fontWeight = FontWeight.Normal,
        fontSize = size.sp,
        letterSpacing = (size * 0.03f).sp,
        color = Bone,
        textAlign = TextAlign.Center
    )

    /** Terminal output stays monospaced; everything else is Morningstar. */
    fun mono(size: Int = 11): TextStyle = TextStyle(
        fontFamily = MonoTail,
        fontSize = size.sp,
        lineHeight = (size * 1.35f).sp,
        color = Bone
    )

    // ── rhythm ───────────────────────────────────────────────────────

    object Space {
        val hair = 1.dp
        val xs = 4.dp
        val sm = 8.dp
        val md = 14.dp
        val lg = 22.dp
        val xl = 34.dp
    }

    /** The cut used on the corner of every glass shard. */
    val Cut = 14.dp
}

/**
 * Material3 typography rewritten in the Morningstar voice, so Material components (buttons, text
 * fields, dialogs) inherit the language even where a call site does not name a family.
 */
val MorningstarTypography: Typography = Typography(
    displayLarge = Morningstar.numeral(54),
    displayMedium = Morningstar.numeral(44),
    displaySmall = Morningstar.numeral(34),
    headlineLarge = Morningstar.title(26),
    headlineMedium = Morningstar.title(22),
    headlineSmall = Morningstar.title(18),
    titleLarge = Morningstar.title(17),
    titleMedium = Morningstar.title(14),
    titleSmall = Morningstar.title(12),
    bodyLarge = Morningstar.body(15),
    bodyMedium = Morningstar.body(13),
    bodySmall = Morningstar.body(12),
    labelLarge = Morningstar.label(12),
    labelMedium = Morningstar.label(10),
    labelSmall = Morningstar.label(9)
)
