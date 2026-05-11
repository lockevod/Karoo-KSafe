package com.enderthor.kSafe.extension.managers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sanity-band tests for [estimateSweatRate]. The model is not exact (published sweat-loss
 * studies report ±15–25 % error vs lab measurement), so we assert that the output falls in
 * a plausible window for the documented scenario, not an exact number.
 */
class SweatEstimatorTest {

    private fun assertInRange(label: String, value: Double, low: Double, high: Double) {
        assertTrue("$label: expected [$low, $high], got $value", value in low..high)
    }

    @Test
    fun `moderate endurance HR temperate conditions returns ~500-650 ml per hour`() {
        // 70 kg rider, 150 bpm, 20 °C, 50 % RH. The classic "long endurance ride at z2-z3".
        // Sport-science literature reports 0.5–0.7 L/hr in cool conditions.
        val out = estimateSweatRate(SweatEstimateInputs(
            hrBpm = 150,
            weightKg = 70.0,
            ambientTempC = 20.0,
            humidityPct = 50,
        ))
        assertInRange("moderate temperate", out.mlPerHour, 450.0, 700.0)
        assertEquals(SweatConfidence.MEDIUM, out.confidence)
    }

    @Test
    fun `high intensity hot humid conditions reaches 1300-1900 ml per hour`() {
        // 70 kg, 170 bpm, 30 °C, 70 % RH — threshold work in summer.
        // Baker 2017 / Cheuvront 2014: ~1.5–2.0 L/hr in hot conditions at high intensity.
        val out = estimateSweatRate(SweatEstimateInputs(
            hrBpm = 170,
            weightKg = 70.0,
            ambientTempC = 30.0,
            humidityPct = 70,
        ))
        assertInRange("hot threshold", out.mlPerHour, 1200.0, 2000.0)
    }

    @Test
    fun `heavy rider scales up vs reference 70 kg`() {
        val light = estimateSweatRate(SweatEstimateInputs(
            hrBpm = 150, weightKg = 60.0, ambientTempC = 25.0, humidityPct = 50,
        )).mlPerHour
        val heavy = estimateSweatRate(SweatEstimateInputs(
            hrBpm = 150, weightKg = 90.0, ambientTempC = 25.0, humidityPct = 50,
        )).mlPerHour
        assertTrue("90 kg rider sweats more than 60 kg at same HR/conditions: " +
            "light=$light heavy=$heavy", heavy > light * 1.15)
    }

    @Test
    fun `cool conditions ride does not over-estimate sweat`() {
        // 12 °C is the threshold below which heat factor stays at 1.0 ×.
        val out = estimateSweatRate(SweatEstimateInputs(
            hrBpm = 130, weightKg = 70.0, ambientTempC = 12.0, humidityPct = 40,
        )).mlPerHour
        assertInRange("cool recovery", out, 300.0, 550.0)
    }

    @Test
    fun `power-based gives HIGH confidence when temp and humidity known`() {
        val out = estimateSweatRate(SweatEstimateInputs(
            powerW = 200,
            weightKg = 70.0,
            ambientTempC = 22.0,
            humidityPct = 50,
        ))
        assertEquals(SweatConfidence.HIGH, out.confidence)
        // 200 W mechanical → 800 W metabolic → ~688 kcal/hr → ~585 ml/hr base × heat factor
        assertInRange("power 200W temperate", out.mlPerHour, 500.0, 800.0)
    }

    @Test
    fun `no HR no power returns LOW confidence with non-zero default`() {
        val out = estimateSweatRate(SweatEstimateInputs(
            ambientTempC = 20.0, humidityPct = 50,
        ))
        assertEquals(SweatConfidence.LOW, out.confidence)
        // Default 350 kcal/hr → ~298 ml/hr × 1.0 heat factor
        assertInRange("default ride", out.mlPerHour, 200.0, 400.0)
    }

    @Test
    fun `heat factor is monotonic in temperature`() {
        val tempers = listOf(15.0, 20.0, 25.0, 30.0, 35.0)
        val rates = tempers.map { t ->
            estimateSweatRate(SweatEstimateInputs(
                hrBpm = 150, weightKg = 70.0, ambientTempC = t, humidityPct = 50,
            )).mlPerHour
        }
        // Strictly non-decreasing — each step up in temperature gives at least as much sweat.
        for (i in 1 until rates.size) {
            assertTrue("rate at ${tempers[i]}°C (${rates[i]}) should be >= ${tempers[i-1]}°C (${rates[i-1]})",
                rates[i] >= rates[i - 1])
        }
        // And clearly increasing across the full range.
        assertTrue("35°C rate (${rates.last()}) should exceed 15°C rate (${rates.first()}) by >50%",
            rates.last() > rates.first() * 1.5)
    }

    @Test
    fun `humidity raises sweat at the same temperature`() {
        // 25 °C is in the linear heat band — small but measurable humidity effect.
        val dry = estimateSweatRate(SweatEstimateInputs(
            hrBpm = 150, weightKg = 70.0, ambientTempC = 25.0, humidityPct = 20,
        )).mlPerHour
        val humid = estimateSweatRate(SweatEstimateInputs(
            hrBpm = 150, weightKg = 70.0, ambientTempC = 25.0, humidityPct = 80,
        )).mlPerHour
        assertTrue("80 % RH should give more sweat than 20 % RH at 25 °C: dry=$dry humid=$humid",
            humid > dry)
    }

    @Test
    fun `default fallbacks produce a plausible mid range estimate`() {
        // All inputs null — should still return a moderate, non-degenerate value.
        val out = estimateSweatRate(SweatEstimateInputs())
        assertEquals(SweatConfidence.LOW, out.confidence)
        assertInRange("defaults only", out.mlPerHour, 200.0, 400.0)
    }

    @Test
    fun `zero or negative HR is treated as missing`() {
        val out = estimateSweatRate(SweatEstimateInputs(
            hrBpm = 0, weightKg = 70.0, ambientTempC = 20.0, humidityPct = 50,
        ))
        // hrBpm = 0 means strap dropped / not paired — falls through to default kcal floor.
        assertEquals(SweatConfidence.LOW, out.confidence)
    }
}
