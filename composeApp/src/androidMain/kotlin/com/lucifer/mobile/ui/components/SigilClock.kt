package com.lucifer.mobile.ui.components

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lucifer.mobile.ui.theme.Morningstar
import com.lucifer.mobile.ui.theme.SigilMath
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/** One reading on the sigil. Fractions are 0..1; percentages are what a human reads. */
data class SigilReadings(
    val batteryFraction: Float = 0f,
    val memoryFraction: Float = 0f,
    val loadFraction: Float = 0f,
    val batteryPercent: Int = -1,
    val memoryPercent: Int = -1,
    val loadPercent: Int = -1,
    val charging: Boolean = false
) {
    fun summary(): String = buildString {
        append(if (batteryPercent >= 0) "BAT $batteryPercent" else "BAT —")
        append(" · ")
        append(if (memoryPercent >= 0) "MEM $memoryPercent" else "MEM —")
        append(" · ")
        append(if (loadPercent >= 0) "LOAD $loadPercent" else "LOAD —")
        if (charging) append(" · ⚡")
    }
}

/**
 * Local, permission-free telemetry for the sigil. Battery and memory come from the platform;
 * load is `/proc/loadavg` normalised by core count. Nothing here leaves the device and nothing here
 * is a background service — the composable polls only while it is on screen.
 */
object SigilTelemetry {

    fun read(context: Context): SigilReadings {
        val battery = readBattery(context)
        val memory = readMemory(context)
        val load = readLoad()
        return SigilReadings(
            batteryFraction = battery.first,
            memoryFraction = memory.first,
            loadFraction = load.first,
            batteryPercent = battery.second,
            memoryPercent = memory.second,
            loadPercent = load.second,
            charging = battery.third
        )
    }

    private fun readBattery(context: Context): Triple<Float, Int, Boolean> {
        return try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            if (level < 0 || scale <= 0) Triple(0f, -1, false)
            else {
                val percent = (level * 100 / scale).coerceIn(0, 100)
                Triple(SigilMath.fractionOf(percent, 100), percent, status == BatteryManager.BATTERY_STATUS_CHARGING)
            }
        } catch (_: Exception) {
            Triple(0f, -1, false)
        }
    }

    private fun readMemory(context: Context): Pair<Float, Int> {
        return try {
            val activity = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return 0f to -1
            val info = ActivityManager.MemoryInfo()
            activity.getMemoryInfo(info)
            val fraction = SigilMath.usedFraction(info.totalMem, info.availMem)
            fraction to (fraction * 100).toInt().coerceIn(0, 100)
        } catch (_: Exception) {
            0f to -1
        }
    }

    private fun readLoad(): Pair<Float, Int> {
        return try {
            val first = java.io.File("/proc/loadavg").readText().trim().split(" ").firstOrNull()
            val average = first?.toFloatOrNull() ?: return 0f to -1
            val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            val fraction = SigilMath.loadFraction(average, cores)
            fraction to (fraction * 100).toInt().coerceIn(0, 100)
        } catch (_: Exception) {
            0f to -1
        }
    }
}

/**
 * The Morningstar sigil: a mathematical clock that is also a system status wheel.
 *
 * Outer hairline compass with 24 ticks, three arcs (battery / memory / load), a slowly turning inner
 * triangle, and the time set in Cinzel. It is the one place gilding is allowed.
 */
@Composable
fun SigilClock(
    modifier: Modifier = Modifier,
    size: Dp = 156.dp,
    onClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var readings by remember { mutableStateOf(SigilReadings()) }
    var use24Hour by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        readings = SigilTelemetry.read(context)
    }
    LaunchedEffect(Unit) {
        var tick = 0
        while (true) {
            now = System.currentTimeMillis()
            if (tick % 20 == 0) readings = SigilTelemetry.read(context)
            tick++
            delay(1_000)
        }
    }

    val turn by rememberInfiniteTransition(label = "sigil-turn").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(180_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sigil-turn-value"
    )

    val calendar = remember(now) { Calendar.getInstance().apply { timeInMillis = now } }
    val hour = calendar.get(Calendar.HOUR_OF_DAY)
    val minute = calendar.get(Calendar.MINUTE)
    val dateLabel = remember(now, hour) { SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(now)).uppercase(Locale.getDefault()) }
    val isNight = hour < 6 || hour >= 20
    val auraColor = when {
        readings.batteryPercent in 0..15 && !readings.charging -> Morningstar.Crimson
        isNight -> Morningstar.Gilded
        else -> Morningstar.Crimson
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawSigil(
                    readings = readings,
                    rotation = turn,
                    aura = auraColor,
                    showTicks = true
                )
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                Text(
                    text = SigilMath.clockText(hour, minute, use24Hour).trim(),
                    style = Morningstar.numeral(30),
                    maxLines = 1
                )
                Text(
                    text = dateLabel,
                    style = Morningstar.overline(8).copy(color = Morningstar.Ash),
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
            }
        }
        Text(
            text = readings.summary(),
            style = Morningstar.caption(8).copy(color = Morningstar.Smoke, letterSpacing = 0.9.sp),
            maxLines = 1
        )
    }
}

private fun DrawScope.drawSigil(
    readings: SigilReadings,
    rotation: Float,
    aura: Color,
    showTicks: Boolean
) {
    val radius = size.minDimension / 2f - 1.dp.toPx()
    val center = Offset(size.width / 2f, size.height / 2f)

    // Outer compass hairline + aura.
    drawCircle(color = Morningstar.EdgeQuiet, radius = radius, center = center, style = Stroke(width = 1.dp.toPx()))
    drawCircle(
        color = aura.copy(alpha = 0.10f),
        radius = radius * 0.99f,
        center = center,
        style = Stroke(width = 2.4.dp.toPx())
    )

    if (showTicks) {
        val ticks = SigilMath.tickCount(15f)
        for (index in 0 until ticks) {
            val degrees = index * 15f - 90f
            val cardinal = SigilMath.isCardinal(index, 15f)
            val outer = radius
            val inner = radius - if (cardinal) 7.dp.toPx() else 3.5.dp.toPx()
            val radians = Math.toRadians(degrees.toDouble())
            val cosR = cos(radians).toFloat()
            val sinR = sin(radians).toFloat()
            drawLine(
                color = if (cardinal) Morningstar.Ash.copy(alpha = 0.55f) else Morningstar.Whisper,
                start = Offset(center.x + cosR * inner, center.y + sinR * inner),
                end = Offset(center.x + cosR * outer, center.y + sinR * outer),
                strokeWidth = if (cardinal) 1.1.dp.toPx() else 0.8.dp.toPx()
            )
        }
    }

    // Three status arcs, outermost first: battery, memory, load.
    val arcs = listOf(
        Triple(readings.batteryFraction, radius * 0.87f, if (readings.charging) Morningstar.Success else Morningstar.Crimson),
        Triple(readings.memoryFraction, radius * 0.78f, Morningstar.Gilded),
        Triple(readings.loadFraction, radius * 0.69f, Morningstar.Ember)
    )
    for ((fraction, arcRadius, color) in arcs) {
        drawCircle(
            color = Morningstar.Whisper.copy(alpha = 0.5f),
            radius = arcRadius,
            center = center,
            style = Stroke(width = 2.dp.toPx())
        )
        val sweep = SigilMath.sweep(fraction)
        if (sweep > 0f) {
            drawArc(
                color = color.copy(alpha = 0.9f),
                startAngle = SigilMath.START_DEGREES,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = Offset(center.x - arcRadius, center.y - arcRadius),
                size = androidx.compose.ui.geometry.Size(arcRadius * 2, arcRadius * 2),
                style = Stroke(width = 2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
            )
        }
    }

    // Inner triangle: the Morningstar mark, turning once every three minutes.
    val markRadius = radius * 0.40f
    val path = Path()
    for (index in 0 until 3) {
        val degrees = rotation - 90f + index * 120f
        val radians = Math.toRadians(degrees.toDouble())
        val x = center.x + cos(radians).toFloat() * markRadius
        val y = center.y + sin(radians).toFloat() * markRadius
        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    drawPath(path, color = Morningstar.Gilded.copy(alpha = 0.30f), style = Stroke(width = 1.dp.toPx()))
    drawCircle(
        color = Morningstar.Gilded.copy(alpha = 0.35f),
        radius = 1.6.dp.toPx(),
        center = center
    )

    // Low battery draws a single crimson wedge at the top — an alarm, not decoration.
    if (readings.batteryPercent in 0..15 && !readings.charging) {
        drawArc(
            color = Morningstar.Crimson.copy(alpha = 0.75f),
            startAngle = 250f,
            sweepAngle = 40f,
            useCenter = false,
            topLeft = Offset(center.x - radius * 0.94f, center.y - radius * 0.94f),
            size = androidx.compose.ui.geometry.Size(radius * 1.88f, radius * 1.88f),
            style = Stroke(width = 1.dp.toPx())
        )
    }
}

/** Compact horizontal ring, for strip headers where a full sigil would be too much. */
@Composable
fun SigilMeter(
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
    label: String? = null
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Canvas(modifier = Modifier.size(size)) {
            val stroke = 1.6.dp.toPx()
            val r = this.size.minDimension / 2f - stroke
            drawCircle(color = Morningstar.Whisper, radius = r, center = center, style = Stroke(width = stroke))
            val sweep = SigilMath.sweep(fraction, minimumVisibleDegrees = 0f)
            if (sweep > 0f) {
                drawArc(
                    color = color,
                    startAngle = -90f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = Offset(center.x - r, center.y - r),
                    size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
                    style = Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                )
            }
        }
        if (label != null) {
            Text(label, style = Morningstar.caption(7).copy(color = Morningstar.Smoke), maxLines = 1)
        }
    }
}
