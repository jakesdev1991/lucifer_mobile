package com.lucifer.mobile.ui.theme

/**
 * Pure geometry for the Morningstar design language.
 *
 * Kept free of Compose so the octagonal (45°-cut) panel outlines and the sigil's arc math can be
 * verified on the host. The Compose layers only turn these numbers into Paths and DrawScope calls.
 */
data class ChamferSpec(
    val topLeft: Float = 0f,
    val topRight: Float = 0f,
    val bottomRight: Float = 0f,
    val bottomLeft: Float = 0f
) {
    fun sanitized(width: Float, height: Float): ChamferSpec {
        val limit = (minOf(width, height) / 2f).let { if (it.isNaN() || it <= 0f) 0f else it }
        fun clamp(value: Float): Float =
            if (value.isNaN() || value < 0f) 0f else value.coerceAtMost(limit)
        return ChamferSpec(clamp(topLeft), clamp(topRight), clamp(bottomRight), clamp(bottomLeft))
    }

    val isSquare: Boolean
        get() = topLeft == 0f && topRight == 0f && bottomRight == 0f && bottomLeft == 0f
}

/** One vertex of an octagonal outline, in draw order. */
data class Point(val x: Float, val y: Float)

object Chamfer {

    /** Uniform cut on all four corners — polished obsidian shard. */
    fun all(cut: Float): ChamferSpec = ChamferSpec(cut, cut, cut, cut)

    /** The signature Lucifer shape: sharp top-left, cut bottom-right. */
    fun blade(cut: Float): ChamferSpec = ChamferSpec(topLeft = cut, bottomRight = cut)

    /** Card-scale cut, proportional so small and large panels read the same. */
    fun forSize(width: Float, height: Float, fraction: Float = 0.06f): ChamferSpec {
        val cut = minOf(width, height) * fraction.coerceIn(0.02f, 0.12f)
        return all(cut).sanitized(width, height)
    }

    /**
     * Clockwise outline starting at the top edge. A corner with no cut contributes a single vertex
     * instead of two, so a plain panel comes back as four points and a shard as eight — no duplicate
     * vertices, no zero-length edges, and the path always closes cleanly.
     */
    fun outline(width: Float, height: Float, spec: ChamferSpec): List<Point> {
        if (width <= 0f || height <= 0f) return emptyList()
        val c = spec.sanitized(width, height)
        val points = ArrayList<Point>(10)
        points.add(Point(c.topLeft, 0f))
        points.add(Point(width - c.topRight, 0f))
        if (c.topRight > 0f) points.add(Point(width, c.topRight))
        points.add(Point(width, height - c.bottomRight))
        if (c.bottomRight > 0f) points.add(Point(width - c.bottomRight, height))
        points.add(Point(c.bottomLeft, height))
        if (c.bottomLeft > 0f) points.add(Point(0f, height - c.bottomLeft))
        points.add(Point(0f, c.topLeft))
        return points.distinctInSequence()
    }

    /** Drops the repeated closing vertex and any coincident neighbours. */
    private fun List<Point>.distinctInSequence(): List<Point> {
        val unique = ArrayList<Point>(size)
        for (point in this) if (unique.lastOrNull() != point) unique.add(point)
        while (unique.size > 1 && unique.first() == unique.last()) unique.removeAt(unique.lastIndex)
        return unique
    }

    /** Even-odd-safe area approximation, used by the harness to prove the outline is sane. */
    fun area(width: Float, height: Float, spec: ChamferSpec): Float {
        val points = outline(width, height, spec)
        if (points.size < 3) return 0f
        var sum = 0f
        for (index in points.indices) {
            val a = points[index]
            val b = points[(index + 1) % points.size]
            sum += a.x * b.y - b.x * a.y
        }
        return kotlin.math.abs(sum) / 2f
    }
}

/**
 * The Morningstar sigil: an occult compass that doubles as a status wheel.
 *
 * Three arcs share one ring — battery, memory, and device load — each with its own sweep. The math is
 * separated so arcs never overlap numerically and a missing reading degrades to an empty arc instead
 * of throwing.
 */
object SigilMath {

    const val START_DEGREES = 135f
    const val TOTAL_DEGREES = 270f

    fun clamp01(value: Float): Float = when {
        value.isNaN() -> 0f
        value < 0f -> 0f
        value > 1f -> 1f
        else -> value
    }

    /** Battery percentage (0-100) as a 0..1 arc fraction. Unknown readings are zero. */
    fun fractionOf(value: Int, max: Int): Float =
        if (max <= 0 || value < 0) 0f else clamp01(value.toFloat() / max.toFloat())

    /** Used-memory fraction from total/available bytes. */
    fun usedFraction(totalBytes: Long, availableBytes: Long): Float =
        if (totalBytes <= 0L) 0f else clamp01((totalBytes - availableBytes).toFloat() / totalBytes.toFloat())

    /** Load average divided by core count, expressed on a log-ish scale so 1.0 per core is 80%. */
    fun loadFraction(loadAverage: Float, cores: Int): Float {
        if (cores <= 0 || loadAverage.isNaN() || loadAverage < 0f) return 0f
        val perCore = loadAverage / cores.toFloat()
        return clamp01(perCore / 1.25f)
    }

    /** Sweep in degrees for one arc, with a minimum visible sliver when the value is non-zero. */
    fun sweep(fraction: Float, minimumVisibleDegrees: Float = 1.5f): Float {
        val f = clamp01(fraction)
        if (f <= 0f) return 0f
        return maxOf(minimumVisibleDegrees, f * TOTAL_DEGREES).coerceAtMost(TOTAL_DEGREES)
    }

    fun startAngleFor(index: Int, rings: Int): Float {
        if (rings <= 0) return START_DEGREES
        val ring = index.coerceIn(0, rings - 1)
        return START_DEGREES + ring * 0f
    }

    /** Tick marks around the compass: every 15°, cardinal ticks longer. */
    fun tickCount(stepDegrees: Float = 15f): Int {
        if (stepDegrees <= 0f) return 0
        return (360f / stepDegrees).toInt().coerceAtMost(72)
    }

    fun isCardinal(tickIndex: Int, stepDegrees: Float = 15f): Boolean {
        if (stepDegrees <= 0f) return false
        return (tickIndex * stepDegrees).toInt() % 90 == 0
    }

    /** Slow rotation of the inner triangle; one revolution per [periodSeconds]. */
    fun rotationDegrees(elapsedMillis: Long, periodSeconds: Int = 120): Float {
        if (periodSeconds <= 0) return 0f
        val period = periodSeconds * 1_000f
        return (elapsedMillis % period.toLong()) / period * 360f
    }

    fun clockText(hour: Int, minute: Int, use24Hour: Boolean): String {
        val h = if (use24Hour) hour.coerceIn(0, 23) else {
            val normalized = hour.coerceIn(0, 23) % 12
            if (normalized == 0) 12 else normalized
        }
        val suffix = if (use24Hour) "" else if (hour.coerceIn(0, 23) < 12) " AM" else " PM"
        val padded = if (use24Hour) h.toString().padStart(2, '0') else h.toString()
        return "$padded:${minute.coerceIn(0, 59).toString().padStart(2, '0')}$suffix"
    }
}

/**
 * Deterministic ember particles for the disintegration effect. A seeded generator keeps the burn
 * looking identical on every launch and lets the harness assert the bounds.
 */
data class Ember(
    val startX: Float,
    val startY: Float,
    val drift: Float,
    val rise: Float,
    val size: Float,
    val delay: Float,
    val life: Float
)

object EmberField {

    fun generate(count: Int, seed: Long = 0x5EED_1FEL, spread: Float = 1f): List<Ember> {
        if (count <= 0) return emptyList()
        var state = if (seed == 0L) 1L else seed
        fun next(): Float {
            state = (state * 6364136223846793005L + 1442695040888963407L)
            val bits = ((state ushr 33).toInt() and 0x7FFFFFFF)
            return bits.toFloat() / Int.MAX_VALUE.toFloat()
        }
        val capped = count.coerceAtMost(160)
        return (0 until capped).map { index ->
            val column = (index + 0.5f) / capped
            Ember(
                startX = (column + (next() - 0.5f) * 0.04f).let { SigilMath.clamp01(it) * spread },
                startY = SigilMath.clamp01(0.25f + next() * 0.7f),
                drift = (next() - 0.5f) * 0.18f,
                rise = 0.35f + next() * 0.75f,
                size = 0.6f + next() * 2.2f,
                delay = next() * 0.45f,
                life = 0.45f + next() * 0.55f
            )
        }
    }

    /** Position of an ember at [progress] 0..1, after its own delay. */
    fun position(ember: Ember, progress: Float): Pair<Float, Float> {
        val local = ((SigilMath.clamp01(progress) - ember.delay) / (1f - ember.delay).coerceAtLeast(0.01f))
            .let(::clampLocal)
        val eased = local * local
        return Pair(
            ember.startX + ember.drift * eased,
            ember.startY - ember.rise * eased
        )
    }

    fun alpha(ember: Ember, progress: Float): Float {
        val local = ((SigilMath.clamp01(progress) - ember.delay) / (1f - ember.delay).coerceAtLeast(0.01f))
            .let(::clampLocal)
        return if (local >= ember.life) 0f else (1f - local / ember.life).coerceIn(0f, 1f)
    }

    private fun clampLocal(value: Float): Float = when {
        value.isNaN() -> 0f
        value < 0f -> 0f
        value > 1f -> 1f
        else -> value
    }
}
