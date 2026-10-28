package com.lucifer.mobile.ui.effects

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lucifer.mobile.ui.theme.ShardShape
import com.lucifer.mobile.ui.theme.shard
import com.lucifer.mobile.ui.theme.EmberField
import com.lucifer.mobile.ui.theme.Morningstar
import kotlin.math.min

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// GLASS
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

/**
 * Smoky glass without a blur shader: a black base, a faint white wash that falls off downwards, and
 * a 1px inner edge that brightens toward the top. Renders identically on API 28 and 34 — real
 * backdrop blur only works on 31+, and a panel that looks different per device is not premium.
 */
fun Modifier.glassSurface(
    shape: Shape,
    accent: Color = Morningstar.CrimsonLine,
    raised: Boolean = false
): Modifier = this
    .clip(shape)
    .background(if (raised) Morningstar.GlassRaised else Morningstar.Glass)
    .background(
        Brush.verticalGradient(
            0f to Color.White.copy(alpha = if (raised) 0.075f else 0.045f),
            0.42f to Color.White.copy(alpha = 0.012f),
            1f to Color.Transparent
        )
    )
    .border(
        width = 1.dp,
        brush = Brush.verticalGradient(
            0f to accent.copy(alpha = (accent.alpha * 1.4f).coerceAtMost(1f)),
            0.55f to accent.copy(alpha = accent.alpha * 0.6f),
            1f to Color.White.copy(alpha = 0.04f)
        ),
        shape = shape
    )

/** A hairline rule with a fading tail — used instead of Material dividers. */
@Composable
fun SigilRule(
    modifier: Modifier = Modifier,
    color: Color = Morningstar.EdgeQuiet
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(
                Brush.horizontalGradient(
                    0f to Color.Transparent,
                    0.08f to color,
                    0.72f to color.copy(alpha = color.alpha * 0.35f),
                    1f to Color.Transparent
                )
            )
    )
}

/** Short crimson needle used to mark the active item. */
@Composable
fun SigilNeedle(
    modifier: Modifier = Modifier,
    color: Color = Morningstar.Crimson,
    height: Dp = 12.dp
) {
    Box(
        modifier = modifier
            .width(1.dp)
            .height(height)
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.35f to color,
                    1f to Color.Transparent
                )
            )
    )
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// THE PULSE
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

/**
 * The long-press / press state: a thin crimson outline that spreads outward and fades, like an
 * energy pulse leaving the glass. Draws outside the panel's clip, so the ring can leave the edge.
 */
fun Modifier.pressPulse(
    pressed: Boolean,
    shape: Shape,
    color: Color = Morningstar.Crimson,
    maxSpread: Float = 0.055f
): Modifier = this.pulseLayer(
    amount = if (pressed) 1f else 0f,
    shape = shape,
    color = color,
    maxSpread = maxSpread
)

/** Continuous pulse for "this is alive" states (heartbeat, listening, running). */
@Composable
fun rememberBreathingAmount(
    min: Float = 0f,
    max: Float = 1f,
    periodMillis: Int = 2_600
): Float {
    val transition = rememberInfiniteTransition(label = "breath")
    val value by transition.animateFloat(
        initialValue = min,
        targetValue = max,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = periodMillis, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathValue"
    )
    return value
}

fun Modifier.pulseLayer(
    amount: Float,
    shape: Shape,
    color: Color,
    maxSpread: Float = 0.055f
): Modifier = this.then(
    Modifier.drawWithContentPulse(amount = amount, shape = shape, color = color, maxSpread = maxSpread)
)

private fun Modifier.drawWithContentPulse(
    amount: Float,
    shape: Shape,
    color: Color,
    maxSpread: Float
): Modifier = this.drawWithContent {
    drawContent()
    if (amount <= 0.01f) return@drawWithContent
    val outline = shape.createOutline(size, layoutDirection, this)
    val growth = 1f + maxSpread * amount
    val alpha = (0.55f * (1f - amount)).coerceIn(0f, 1f)
    val pivot = center
    drawContext.canvas.save()
    drawContext.canvas.scale(growth, growth, pivot)
    drawOutline(
        outline = outline,
        color = color,
        alpha = alpha,
        style = Stroke(width = (1.1f + 1.6f * amount).dp.toPx())
    )
    drawContext.canvas.restore()
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// TEXT GLOW
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

/** Subtle bloom for the few strings that should feel lit from behind. */
fun glowShadow(color: Color = Morningstar.Crimson, blur: Float = 14f): Shadow =
    Shadow(color = color.copy(alpha = 0.55f), offset = Offset.Zero, blurRadius = blur)

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// THE FALL — vertical, heavy, deliberate
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

/** Heavy accel / long settle: motion that feels weighted rather than springy. */
val FallEasing = CubicBezierEasing(0.16f, 0.84f, 0.05f, 1f)

/**
 * Content that falls into place when it enters composition: it starts slightly above and out of
 * focus, then settles down. Wrap a screen in `key(selectedTab) { FallIn { ... } }` to give every panel
 * switch the same downward gravity.
 */
@Composable
fun FallIn(
    modifier: Modifier = Modifier,
    distance: Dp = 26.dp,
    durationMillis: Int = 460,
    delayMillis: Int = 0,
    content: @Composable BoxScope.() -> Unit
) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = durationMillis, delayMillis = delayMillis, easing = FallEasing)
        )
    }
    val value = progress.value
    Box(
        modifier = modifier.graphicsLayer {
            translationY = -(1f - value) * distance.toPx()
            alpha = value
        },
        content = content
    )
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// DISINTEGRATION — burning away into the void
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

/**
 * Ember burst used when something is erased (cleanup, ledger clear, conversation purge). The content
 * itself fades while seeded particles rise and die against pure black.
 *
 * [trigger] increments to fire; [onFinished] runs once the burn completes.
 */
@Composable
fun Disintegration(
    modifier: Modifier = Modifier,
    trigger: Int,
    onFinished: (() -> Unit)? = null,
    embers: Int = 48
) {
    val progress = remember { Animatable(1f) }
    val field = remember(embers) { EmberField.generate(embers) }
    LaunchedEffect(trigger) {
        if (trigger <= 0) return@LaunchedEffect
        progress.snapTo(0f)
        progress.animateTo(1f, animationSpec = tween(durationMillis = 900, easing = FallEasing))
        onFinished?.invoke()
    }
    val value = progress.value
    if (value >= 1f) return
    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        for (ember in field) {
            val (x, y) = EmberField.position(ember, value)
            val alpha = EmberField.alpha(ember, value)
            if (alpha <= 0.01f) continue
            val radius = (ember.size * 1.4f).dp.toPx() * (1f - value * 0.35f)
            drawCircle(
                color = if (ember.size > 1.8f) Morningstar.Ember else Morningstar.Crimson,
                radius = radius.coerceAtLeast(0.6f),
                center = Offset(x * w, (1f - y) * h),
                alpha = alpha * 0.85f
            )
        }
        // A single fading hairline where the mass used to be.
        drawLine(
            color = Morningstar.Crimson.copy(alpha = (1f - value) * 0.25f),
            start = Offset(0f, h * 0.62f),
            end = Offset(w, h * 0.62f),
            strokeWidth = 1.dp.toPx()
        )
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// BACKDROP
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

/**
 * The canvas: true black, a soft vignette, the faintest compass rings, and drifting dust. Nothing
 * here is a wallpaper — it is the absence of one.
 */
@Composable
fun SigilBackdrop(
    modifier: Modifier = Modifier,
    dust: Boolean = true,
    ringAlpha: Float = 0.05f
) {
    val drift = rememberBreathingAmount(periodMillis = 24_000)
    Canvas(modifier = modifier.fillMaxSize()) {
        drawRect(color = Morningstar.Void)
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(Color.Transparent, Morningstar.Void.copy(alpha = 0.92f)),
                center = Offset(size.width * 0.5f, size.height * 0.38f),
                radius = size.maxDimension * 0.78f
            )
        )
        val center = Offset(size.width * 0.5f, size.height * 0.42f)
        val base = min(size.width, size.height) * 0.36f
        for (index in 0 until 3) {
            drawCircle(
                color = Morningstar.Crimson.copy(alpha = ringAlpha * (0.5f + 0.5f * index / 2f)),
                radius = base * (1f + index * 0.34f),
                center = center,
                style = Stroke(width = 1f)
            )
        }
        drawCircle(
            color = Morningstar.Gilded.copy(alpha = ringAlpha * 0.6f),
            radius = base * 1.72f,
            center = center,
            style = Stroke(width = 1f)
        )
        if (dust) {
            val embers = EmberField.generate(26, seed = 0xD057)
            for (ember in embers) {
                val x = (ember.startX + ember.drift * drift) * size.width
                val y = (1f - ember.startY - ember.rise * 0.12f * drift) * size.height
                drawCircle(
                    color = Color.White.copy(alpha = 0.05f + 0.05f * drift),
                    radius = ember.size * 0.5f,
                    center = Offset(x, y)
                )
            }
        }
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// THE SHARD — the one card every screen uses
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

/**
 * A glass shard with an optional crimson pulse on press. Sections are separated by a gilded needle
 * and a Cinzel mark, never by a heavy header bar.
 */
@Composable
fun GlassShard(
    modifier: Modifier = Modifier,
    cut: Dp = Morningstar.Cut,
    raised: Boolean = false,
    accent: Color = Morningstar.CrimsonLine,
    contentPadding: Dp = Morningstar.Space.md,
    onClick: (() -> Unit)? = null,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = remember(cut) { ShardShape(cut) }
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val pulseModifier = if (onClick != null) {
        Modifier.pressPulse(pressed = pressed, shape = shape, color = Morningstar.Crimson)
    } else {
        Modifier
    }
    Column(
        modifier = modifier
            .then(pulseModifier)
            .glassSurface(shape = shape, accent = accent, raised = raised || pressed)
            .then(
                if (onClick != null) {
                    Modifier.clickable(interactionSource = source, indication = null, onClick = onClick)
                } else {
                    Modifier
                }
            )
            .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(Morningstar.Space.sm),
        content = content
    )
}
