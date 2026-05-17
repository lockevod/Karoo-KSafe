package com.enderthor.kSafe.extension.util

import com.enderthor.kSafe.data.RideWellnessRecord
import com.enderthor.kSafe.data.WellnessHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadinessDecisionTest {

    /** A round, "now"-looking wall-clock timestamp the tests anchor against. */
    private val NOW = 1_700_000_000_000L
    private val HOUR_MS = 3_600_000L

    private fun record(
        ageHours: Long,
        maxDriftPct: Float = 0f,
        criticalFires: Int = 0,
        sustainedFires: Int = 0,
        decouplingFires: Int = 0,
        cumMsCriticalAbove: Long = 0L,
    ) = RideWellnessRecord(
        endedAtMs = NOW - ageHours * HOUR_MS,
        maxHrBpm = 175,
        cumMsCriticalAbove = cumMsCriticalAbove,
        cumMsSustainedAbove = 0L,
        maxDriftPct = maxDriftPct,
        criticalFires = criticalFires,
        sustainedFires = sustainedFires,
        decouplingFires = decouplingFires,
    )

    @Test
    fun `empty history returns null`() {
        assertNull(decideReadiness(WellnessHistory(emptyList()), NOW))
    }

    @Test
    fun `recent ride with drift over 10 percent returns TAKE_IT_EASY`() {
        val h = WellnessHistory(listOf(record(ageHours = 12, maxDriftPct = 11.5f)))
        val advice = decideReadiness(h, NOW)
        assertNotNull(advice)
        assertEquals(ReadinessLevel.TAKE_IT_EASY, advice!!.level)
        assertTrue("reason mentions drift", advice.reasons.first().contains("drift"))
    }

    @Test
    fun `recent ride with two wellness fires returns CAUTION`() {
        val h = WellnessHistory(listOf(record(ageHours = 8, sustainedFires = 1, decouplingFires = 1)))
        val advice = decideReadiness(h, NOW)
        assertEquals(ReadinessLevel.CAUTION, advice?.level)
        assertTrue(advice!!.reasons.first().contains("2 wellness alerts"))
    }

    @Test
    fun `recent ride with 10 plus minutes above critical returns CAUTION`() {
        val h = WellnessHistory(listOf(record(
            ageHours = 6,
            cumMsCriticalAbove = 12L * 60_000L, // 12 minutes
        )))
        val advice = decideReadiness(h, NOW)
        assertEquals(ReadinessLevel.CAUTION, advice?.level)
        assertTrue(advice!!.reasons.first().contains("above critical"))
    }

    @Test
    fun `three rides within last 72 hours triggers CAUTION even without other warnings`() {
        val h = WellnessHistory(listOf(
            record(ageHours = 30),  // newest — also within 72 h, all clean
            record(ageHours = 50),
            record(ageHours = 70),
        ))
        val advice = decideReadiness(h, NOW)
        assertEquals(ReadinessLevel.CAUTION, advice?.level)
        assertTrue(advice!!.reasons.first().contains("3 rides"))
    }

    @Test
    fun `rule 4 fires when newest ride is recent AND a 72h streak exists with mild signals`() {
        // The previous test made rules 1-3 unreachable by setting the newest ride to 30 h
        // (outside the 24 h recency window). This case proves rule 4 still fires when a
        // RECENT ride exists with sub-threshold drift / fires (rules 1-3 evaluate but
        // none match), and the 72 h streak is the only remaining trigger.
        val h = WellnessHistory(listOf(
            record(ageHours = 12, maxDriftPct = 3f, sustainedFires = 1),  // recent but mild
            record(ageHours = 36, maxDriftPct = 4f),
            record(ageHours = 65, maxDriftPct = 2f),
        ))
        val advice = decideReadiness(h, NOW)
        // Rule 1: drift < 10 → no. Rule 2: 1 fire < 2 → no. Rule 3: 0 ms critical → no.
        // Rule 4: 3 rides within 72 h → yes.
        assertEquals(ReadinessLevel.CAUTION, advice?.level)
        assertTrue(advice!!.reasons.first().contains("3 rides"))
    }

    @Test
    fun `most recent ride older than 24 hours and no streak returns null`() {
        // Newest ride 30 h ago — past the recent-24h window, and only one ride in 72 h.
        val h = WellnessHistory(listOf(record(ageHours = 30, maxDriftPct = 15f)))
        // Even though drift is alarming, it's stale (older than 24 h).
        assertNull(decideReadiness(h, NOW))
    }

    @Test
    fun `recent ride with no warnings returns null (silent RECOVERED)`() {
        val h = WellnessHistory(listOf(record(ageHours = 12, maxDriftPct = 5f, sustainedFires = 1)))
        // 5 % drift is below 10 %; 1 fire is below the >= 2 threshold; 0 ms critical is below 10 min.
        assertNull(decideReadiness(h, NOW))
    }

    @Test
    fun `drift threshold at exact 10 percent fires TAKE_IT_EASY (inclusive)`() {
        val h = WellnessHistory(listOf(record(ageHours = 10, maxDriftPct = 10f)))
        val advice = decideReadiness(h, NOW)
        assertEquals(ReadinessLevel.TAKE_IT_EASY, advice?.level)
    }

    @Test
    fun `drift wins over fires when both apply (TAKE_IT_EASY ranks above CAUTION)`() {
        val h = WellnessHistory(listOf(record(
            ageHours = 5,
            maxDriftPct = 12f,
            sustainedFires = 3,
        )))
        // Both rule 1 (TAKE_IT_EASY) and rule 2 (CAUTION) match; rule order picks
        // TAKE_IT_EASY first — desired because drift is a more serious signal.
        val advice = decideReadiness(h, NOW)
        assertEquals(ReadinessLevel.TAKE_IT_EASY, advice?.level)
    }
}
