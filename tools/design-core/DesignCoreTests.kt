// Host-side verification for the Morningstar design math.
//
// The Compose layer cannot be compiled here (no Android SDK), but the geometry behind the chamfered
// panels, the sigil arcs, and the ember field is pure Kotlin. This harness proves those numbers stay
// inside their bounds.
//
// Not part of the Android build. Run with scripts/verify_design_core.sh.
package com.lucifer.mobile.ui.theme.harness

import com.lucifer.mobile.ui.theme.Chamfer
import com.lucifer.mobile.ui.theme.ChamferSpec
import com.lucifer.mobile.ui.theme.EmberField
import com.lucifer.mobile.ui.theme.SigilMath
import kotlin.math.abs

private var passed = 0
private val failures = mutableListOf<String>()

private fun test(name: String, block: () -> Unit) {
    try {
        block()
        passed++
        println("  ok  $name")
    } catch (error: Throwable) {
        failures.add("$name: ${error.message ?: error.javaClass.simpleName}")
        println("FAIL  $name -> ${error.message ?: error.javaClass.simpleName}")
    }
}

private fun expect(condition: Boolean, message: String) {
    if (!condition) throw AssertionError(message)
}

private fun near(actual: Float, expected: Float, tolerance: Float, what: String) {
    if (abs(actual - expected) > tolerance) throw AssertionError("$what: expected ~$expected but was $actual")
}

fun main() {
    println("Morningstar design core · verification harness")

    // ── chamfer outline ──────────────────────────────────────────────
    test("a zero cut is a plain rectangle with the right area") {
        val spec = Chamfer.all(0f)
        expect(spec.isSquare, "zero cut is square")
        near(Chamfer.area(100f, 50f, spec), 5_000f, 0.5f, "area")
        val points = Chamfer.outline(100f, 50f, spec)
        expect(points.size == 4, "four vertices without cuts, was ${points.size}")
        expect(points.all { it.x in 0f..100f && it.y in 0f..50f }, "inside bounds")
    }

    test("cuts shrink the area by exactly the four triangles") {
        val cut = 10f
        val spec = Chamfer.all(cut)
        near(Chamfer.area(100f, 50f, spec), 5_000f - 4f * (cut * cut / 2f), 0.5f, "octagon area")
        val points = Chamfer.outline(100f, 50f, spec)
        expect(points.size == 8, "eight vertices with cuts, was ${points.size}")
        expect(points.zipWithNext().none { (a, b) -> a == b }, "no zero-length edges")
        expect(points.first() != points.last(), "no repeated closing vertex")
    }

    test("a single-blade panel keeps only the cuts it declares") {
        val spec = Chamfer.blade(12f)
        val points = Chamfer.outline(90f, 60f, spec)
        expect(points.size == 6, "six vertices for two cuts, was ${points.size}")
        near(Chamfer.area(90f, 60f, spec), 5_400f - 2f * (12f * 12f / 2f), 0.5f, "blade area")
    }

    test("an oversized cut is clamped to half the short side") {
        val spec = ChamferSpec(500f, 500f, 500f, 500f).sanitized(120f, 40f)
        expect(spec.topLeft == 20f && spec.topRight == 20f, "clamped: $spec")
        val points = Chamfer.outline(120f, 40f, spec)
        expect(points.all { it.x in 0f..120f && it.y in 0f..40f }, "still inside the box")
    }

    test("negative and non-finite cuts never escape") {
        val spec = ChamferSpec(-40f, Float.NaN, 12f, 6f).sanitized(80f, 60f)
        expect(spec.topLeft == 0f, "negative clamped to zero")
        expect(spec.topRight == 0f, "NaN clamped to zero")
        expect(spec.bottomRight == 12f && spec.bottomLeft == 6f, "valid cuts survive")
        expect(!Chamfer.outline(80f, 60f, spec).isEmpty(), "outline still produced")
        expect(Chamfer.outline(80f, 60f, spec).all { !it.x.isNaN() && !it.y.isNaN() }, "no NaN vertices")
        expect(ChamferSpec(4f, 4f, 4f, 4f).sanitized(Float.NaN, 60f).topLeft == 0f, "NaN size")
    }

    test("proportional cuts read the same at every size") {
        val small = Chamfer.forSize(100f, 100f)
        val large = Chamfer.forSize(600f, 600f)
        near(large.topLeft / 600f, small.topLeft / 100f, 0.001f, "proportional cut")
        expect(Chamfer.forSize(2000f, 20f).topLeft <= 10f, "never exceeds half the short side")
    }

    test("degenerate sizes produce no outline instead of an exception") {
        expect(Chamfer.outline(0f, 50f, Chamfer.all(4f)).isEmpty(), "zero width")
        expect(Chamfer.outline(50f, -1f, Chamfer.all(4f)).isEmpty(), "negative height")
    }

    // ── sigil arcs ───────────────────────────────────────────────────
    test("fractions clamp instead of wrapping") {
        expect(SigilMath.clamp01(-3f) == 0f, "negative")
        expect(SigilMath.clamp01(4f) == 1f, "over one")
        expect(SigilMath.clamp01(Float.NaN) == 0f, "NaN")
        expect(SigilMath.fractionOf(50, 100) == 0.5f, "percentage")
        expect(SigilMath.fractionOf(-1, 100) == 0f, "unknown reading")
        expect(SigilMath.usedFraction(0L, 0L) == 0f, "no memory info")
        near(SigilMath.usedFraction(100L, 40L), 0.6f, 0.0001f, "used memory")
    }

    test("load is normalised per core and capped") {
        near(SigilMath.loadFraction(4f, 4), 0.8f, 0.001f, "one load per core")
        expect(SigilMath.loadFraction(64f, 4) == 1f, "saturated")
        expect(SigilMath.loadFraction(Float.NaN, 8) == 0f, "NaN load")
        expect(SigilMath.loadFraction(1f, 0) == 0f, "zero cores")
    }

    test("an arc is never longer than its ring, and a real reading is always visible") {
        expect(SigilMath.sweep(0f) == 0f, "zero stays empty")
        expect(SigilMath.sweep(0.0001f) > 0f, "a sliver is visible")
        expect(SigilMath.sweep(0.5f) <= SigilMath.TOTAL_DEGREES, "half fits")
        expect(SigilMath.sweep(4f) == SigilMath.TOTAL_DEGREES, "clamped to the ring")
    }

    test("the compass has 24 ticks with four cardinals") {
        expect(SigilMath.tickCount(15f) == 24, "tick count")
        expect((0 until 24).count { SigilMath.isCardinal(it, 15f) } == 4, "cardinal count")
        expect(SigilMath.tickCount(0f) == 0, "degenerate step")
    }

    test("the clock text is readable in both modes") {
        expect(SigilMath.clockText(0, 5, false) == "12:05 AM", "midnight 12h")
        expect(SigilMath.clockText(13, 7, false) == "1:07 PM", "afternoon 12h")
        expect(SigilMath.clockText(13, 7, true) == "13:07", "24h")
        expect(SigilMath.clockText(99, 99, true) == "23:59", "clamped")
    }

    test("the sigil turns once per period") {
        near(SigilMath.rotationDegrees(0L, 120), 0f, 0.01f, "start")
        near(SigilMath.rotationDegrees(60_000L, 120), 180f, 0.01f, "half")
        near(SigilMath.rotationDegrees(120_000L, 120), 0f, 0.01f, "wraps")
        expect(SigilMath.rotationDegrees(1_000L, 0) == 0f, "zero period")
    }

    // ── embers ───────────────────────────────────────────────────────
    test("the ember field is deterministic for a seed") {
        val a = EmberField.generate(12, seed = 42)
        val b = EmberField.generate(12, seed = 42)
        expect(a == b, "same seed, same field")
        expect(a.size == 12, "count honoured")
        expect(EmberField.generate(12, seed = 43) != a, "different seed differs")
    }

    test("embers are bounded, capped, and ordered across the width") {
        val field = EmberField.generate(500)
        expect(field.size == 160, "cap enforced, was ${field.size}")
        expect(field.all { it.startX in 0f..1f && it.startY in 0f..1f }, "positions bounded")
        expect(field.all { it.size in 0.6f..2.8f }, "sizes bounded")
        expect(field.all { it.delay in 0f..0.45f }, "delays bounded")
        expect(EmberField.generate(0).isEmpty(), "empty field")
    }

    test("embers rise and fade to nothing") {
        val ember = EmberField.generate(1).first()
        val (x1, y1) = EmberField.position(ember, 0f)
        val (x2, y2) = EmberField.position(ember, 1f)
        expect(y2 <= y1, "rises: $y1 -> $y2")
        expect(x2 != x1 || ember.drift == 0f, "drifts")
        expect(EmberField.alpha(ember, 0f) >= 0f, "alpha non-negative")
        expect(EmberField.alpha(ember, 1f) == 0f, "fully gone at the end")
        expect(EmberField.alpha(ember, Float.NaN) >= 0f, "NaN progress is safe")
    }

    println()
    if (failures.isEmpty()) {
        println("PASS · $passed checks")
    } else {
        println("FAILED · $passed passed, ${failures.size} failed")
        failures.forEach { println(" - $it") }
        kotlin.system.exitProcess(1)
    }
}
