package com.lucifer.mobile.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lucifer.mobile.ui.theme.LuciferColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

@Composable
fun LuciferIcon(
    modifier: Modifier = Modifier,
    tint: Color = LuciferColors.Crimson,
    animated: Boolean = true,
    size: androidx.compose.ui.unit.Dp = 64.dp
) {
    val animatedRotation by animateFloatAsState(
        targetValue = if (animated) 360f else 0f,
        animationSpec = tween(20000, delayMillis = 0, easing = LinearEasing)
    )
    val animatedGlow by animateFloatAsState(
        targetValue = if (animated) 1f else 0f,
        animationSpec = tween(1500, delayMillis = 0, easing = LinearEasing)
    )

    Box(modifier = modifier.size(size)) {
        Canvas(modifier = Modifier.size(size)) {
            drawLuciferSigil(tint, size)
        }
        if (animated) {
            Canvas(modifier = Modifier.size(size)) {
                drawParticles(animatedRotation, size)
                drawGlow(animatedGlow, tint, size)
            }
        }
    }
}

private fun DrawScope.drawLuciferSigil(
    tint: Color,
    size: androidx.compose.ui.unit.Dp
) {
    val canvasWidth: Float = size.toPx()
    val center = Offset(canvasWidth / 2f, canvasWidth / 2f)
    val radius = (min(canvasWidth, canvasWidth) / 2f * 0.85f)

    drawCircle(
        color = tint.copy(alpha = 0.3f),
        center = center,
        radius = radius,
        style = Stroke(width = 2.dp.toPx())
    )
    drawCircle(
        color = tint.copy(alpha = 0.15f),
        center = center,
        radius = radius * 0.6f,
        style = Fill
    )

    // Seven-pointed star — the Lucifer sigil
    val path = Path()
    for (i in 0 until 7) {
        val angle = (i * 360f / 7f - 90f) * PI / 180f
        val x = (center.x + radius * cos(angle)).toFloat()
        val y = (center.y + radius * sin(angle)).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    drawPath(
        path = path,
        color = tint,
        style = Stroke(width = 2.5.dp.toPx())
    )
}

private fun DrawScope.drawGlow(
    glow: Float,
    tint: Color,
    size: androidx.compose.ui.unit.Dp
) {
    val canvasWidth: Float = size.toPx()
    val center = Offset(canvasWidth / 2f, canvasWidth / 2f)
    val radius = (canvasWidth / 2f * 0.85f)
    val glowRadius = radius + 8.dp.toPx() + glow * 4.dp.toPx()
    drawCircle(
        color = tint.copy(alpha = (0.15f + glow * 0.1f)),
        center = center,
        radius = glowRadius,
        style = Stroke(width = (4f + glow * 3f).dp.toPx())
    )
}

private fun DrawScope.drawParticles(
    rotation: Float,
    size: androidx.compose.ui.unit.Dp
) {
    val canvasWidth: Float = size.toPx()
    val center = Offset(canvasWidth / 2f, canvasWidth / 2f)
    val radius = (canvasWidth / 2f * 0.85f)

    // Gold particles
    for (i in 0 until 6) {
        val particleAngle = (rotation + i * 60f) * PI / 180f
        val particleRadius = (radius + 12.dp.toPx() + (sin(rotation * PI / 180f * 3f + i) * 4.dp.toPx())).toFloat()
        val particleX = (center.x + particleRadius * cos(particleAngle)).toFloat()
        val particleY = (center.y + particleRadius * sin(particleAngle)).toFloat()
        val particleSize = (1.5f + sin(rotation * PI / 180f * 3f + i) * 0.5f).dp.toPx()
        drawCircle(
            center = Offset(particleX, particleY),
            radius = particleSize,
            color = LuciferColors.Gold,
            style = Fill
        )
    }

    // Aether particles (cyan)
    for (i in 0 until 4) {
        val particleAngle = (rotation * 1.5f + i * 90f + 45f) * PI / 180f
        val particleRadius = (radius + 20.dp.toPx() + (cos(rotation * PI / 180f * 2f + i * 2) * 6.dp.toPx())).toFloat()
        val particleX = (center.x + particleRadius * cos(particleAngle)).toFloat()
        val particleY = (center.y + particleRadius * sin(particleAngle)).toFloat()
        val particleSize = (1f + cos(rotation * PI / 180f * 4f + i) * 0.3f).dp.toPx()
        drawCircle(
            center = Offset(particleX, particleY),
            radius = particleSize,
            color = LuciferColors.Aether,
            style = Fill
        )
    }
}

@Composable
fun LuciferIconSmall(
    modifier: Modifier = Modifier,
    tint: Color = LuciferColors.Crimson,
    animated: Boolean = false
) {
    LuciferIcon(
        modifier = modifier.size(24.dp),
        tint = tint,
        animated = animated,
        size = 24.dp
    )
}

@Composable
fun HeartbeatIndicator(
    modifier: Modifier = Modifier,
    isActive: Boolean,
    phase: String
) {
    val pulse by animateFloatAsState(
        targetValue = if (isActive) 1f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
    )
    Box(modifier = modifier.size(12.dp)) {
        Canvas(modifier = Modifier.size(12.dp)) {
            val center = Offset(6.dp.toPx(), 6.dp.toPx())
            val currentRadius = 4.dp.toPx() + pulse * 3.dp.toPx()
            drawCircle(
                center = center,
                radius = currentRadius,
                color = if (isActive) LuciferColors.Crimson else LuciferColors.Gold.copy(alpha = 0.3f),
                style = Fill
            )
        }
        Text(
            text = phase,
            fontSize = 8.sp,
            color = if (isActive) LuciferColors.Crimson else LuciferColors.Gold,
            modifier = Modifier.padding(top = 16.dp)
        )
    }
}
