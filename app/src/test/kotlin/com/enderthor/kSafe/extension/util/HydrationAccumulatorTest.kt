package com.enderthor.kSafe.extension.util

import org.junit.Assert.assertEquals
import org.junit.Test

class HydrationAccumulatorTest {

    private fun input(
        integrating: Boolean = true, base: Double = 1000.0, conf: SweatConfidence = SweatConfidence.HIGH,
        dynamic: Boolean = true, static: Int = 750, mult: Int = 100, repl: Int = 80, na: Int = 36,
    ) = HydTickInput(1000L, integrating, base, conf, dynamic, static, mult, repl, na)

    /** Runs [seconds] one-second steps. */
    private fun run(seconds: Int, acc: HydAccum = HydAccum(), i: HydTickInput): Pair<HydAccum, Float> {
        var a = acc
        var rate = 0f
        repeat(seconds) { val r = hydrationStep(a, i); a = r.accum; rate = r.drinkRateMlPerHour }
        return a to rate
    }

    private fun near(expected: Double, actual: Float) = assertEquals(expected, actual.toDouble(), 0.5)

    @Test fun `dynamic one hour at 1000 ml_h base gives sweat 1000 target 800`() {
        val (a, _) = run(3600, i = input())
        near(1000.0, a.cumSweatBaseMl); near(1000.0, a.cumSweatMl); near(800.0, a.cumTargetMl)
    }

    @Test fun `multiplier scales sweat and target but not base`() {
        val (a, _) = run(3600, i = input(mult = 150))
        near(1000.0, a.cumSweatBaseMl); near(1500.0, a.cumSweatMl); near(1200.0, a.cumTargetMl)
    }

    @Test fun `multiplier change mid-ride leaves base untouched`() {
        val (a1, _) = run(1800, i = input(mult = 100))
        val (a2, _) = run(1800, a1, input(mult = 200))
        near(1000.0, a2.cumSweatBaseMl); near(1500.0, a2.cumSweatMl)
    }

    @Test fun `static mode target uses fixed rate while sweat still accumulates`() {
        val (a, rate) = run(3600, i = input(dynamic = false, static = 750))
        near(750.0, a.cumTargetMl); near(1000.0, a.cumSweatMl); assertEquals(750f, rate, 0.01f)
    }

    @Test fun `sodium 1000 ml at 36 mmol is 827_6 mg`() {
        val (a, _) = run(3600, i = input(na = 36))
        assertEquals(827.6, a.cumSodiumMg.toDouble(), 0.5)
    }

    @Test fun `not integrating changes nothing and drink rate is zero`() {
        val start = HydAccum(1f, 2f, 3f, 4f, 5L, 6L)
        val r = hydrationStep(start, input(integrating = false))
        assertEquals(start, r.accum); assertEquals(0f, r.drinkRateMlPerHour, 0f)
    }

    @Test fun `LOW confidence counts lowConfMs not coveredMs`() {
        val (a, _) = run(10, i = input(conf = SweatConfidence.LOW))
        assertEquals(10_000L, a.lowConfMs); assertEquals(0L, a.coveredMs)
        val (b, _) = run(10, i = input(conf = SweatConfidence.MEDIUM))
        assertEquals(10_000L, b.coveredMs); assertEquals(0L, b.lowConfMs)
    }

    @Test fun `out of range multiplier and replacement are clamped`() {
        val (hi, _) = run(3600, i = input(mult = 500, repl = 300))
        near(2000.0, hi.cumSweatMl); near(2000.0, hi.cumTargetMl)
        val (lo, _) = run(3600, i = input(mult = 10, repl = 10))
        near(500.0, lo.cumSweatMl); near(250.0, lo.cumTargetMl)
    }
}
