package com.enderthor.kSafe.extension.util

import com.enderthor.kSafe.data.RiderSex
import io.hammerhead.karooext.models.UserProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [CarbBurnEstimator].
 *
 * Each test exercises one tier of the fall-back chain (POWER → KEYTEL → SWAIN →
 * NONE) plus the CHO-fraction zone mapping. Numbers are checked against the
 * literature values cited in the module docs so a future "let's tune the
 * formula" change can't silently drift the model away from the published
 * baselines.
 */
class CarbBurnEstimatorTest {

    /** Build a UserProfile with rider-configurable defaults for tests. */
    private fun profile(
        weight: Float = 70f,
        maxHr: Int = 190,
        restingHr: Int = 50,
        ftp: Int = 250,
        hrZones: List<UserProfile.Zone> = defaultHrZones(maxHr),
        powerZones: List<UserProfile.Zone> = defaultPowerZones(ftp),
    ) = UserProfile(
        weight = weight,
        preferredUnit = UserProfile.PreferredUnit(
            distance = UserProfile.PreferredUnit.UnitType.METRIC,
            temperature = UserProfile.PreferredUnit.UnitType.METRIC,
            elevation = UserProfile.PreferredUnit.UnitType.METRIC,
            weight = UserProfile.PreferredUnit.UnitType.METRIC,
        ),
        maxHr = maxHr,
        restingHr = restingHr,
        heartRateZones = hrZones,
        ftp = ftp,
        powerZones = powerZones,
    )

    private fun defaultHrZones(maxHr: Int): List<UserProfile.Zone> {
        // Classic 5-zone HR split anchored to maxHr.
        val z1 = (maxHr * 0.50).toInt()..(maxHr * 0.60).toInt()
        val z2 = (maxHr * 0.60).toInt() + 1..(maxHr * 0.70).toInt()
        val z3 = (maxHr * 0.70).toInt() + 1..(maxHr * 0.80).toInt()
        val z4 = (maxHr * 0.80).toInt() + 1..(maxHr * 0.90).toInt()
        val z5 = (maxHr * 0.90).toInt() + 1..maxHr
        return listOf(
            UserProfile.Zone(z1.first, z1.last),
            UserProfile.Zone(z2.first, z2.last),
            UserProfile.Zone(z3.first, z3.last),
            UserProfile.Zone(z4.first, z4.last),
            UserProfile.Zone(z5.first, z5.last),
        )
    }

    private fun defaultPowerZones(ftp: Int): List<UserProfile.Zone> {
        // 7-zone Coggan model anchored on FTP.
        return listOf(
            UserProfile.Zone(0,                   (ftp * 0.55).toInt()),
            UserProfile.Zone((ftp * 0.55).toInt() + 1, (ftp * 0.75).toInt()),
            UserProfile.Zone((ftp * 0.75).toInt() + 1, (ftp * 0.90).toInt()),
            UserProfile.Zone((ftp * 0.90).toInt() + 1, (ftp * 1.05).toInt()),
            UserProfile.Zone((ftp * 1.05).toInt() + 1, (ftp * 1.20).toInt()),
            UserProfile.Zone((ftp * 1.20).toInt() + 1, (ftp * 1.50).toInt()),
            UserProfile.Zone((ftp * 1.50).toInt() + 1, ftp * 3),
        )
    }

    // ── Tier 1: power meter ───────────────────────────────────────────────────

    @Test
    fun `tier1 power produces expected kcal per hour and POWER confidence`() {
        val r = CarbBurnEstimator.estimate(
            hrBpm = null,
            powerW = 200,
            profile = profile(),
            riderAge = 40,
            riderSex = RiderSex.MALE,
        )
        assertEquals(CarbBurnEstimator.Confidence.POWER, r.confidence)
        // 200 W × 3.6 kcal/W/h = 720 kcal/h — the standard cycling formula.
        assertEquals(720.0, r.kcalPerHour, 0.5)
    }

    @Test
    fun `tier1 power at threshold gives the expected CHO fraction and gph`() {
        // 240 W ≈ 96 % FTP (FTP=250) → power zone Z4 (90-105 % FTP) → 4th of 7
        // zones → CHO fraction ≈ 0.30 + (3/6) × 0.65 = 0.625.
        // kcal/h = 240 × 3.6 = 864.
        // g/h = 864 × 0.625 / 4 = 135.
        val r = CarbBurnEstimator.estimate(
            hrBpm = 160,   // ignored — power tier wins
            powerW = 240,
            profile = profile(ftp = 250),
            riderAge = 40,
            riderSex = RiderSex.MALE,
        )
        assertEquals(CarbBurnEstimator.Confidence.POWER, r.confidence)
        assertEquals(864.0, r.kcalPerHour, 0.5)
        assertEquals(0.625, r.choFraction, 0.001)
        assertEquals(135.0, r.gph, 0.5)
    }

    @Test
    fun `tier1 power below first zone uses lowest CHO fraction`() {
        val r = CarbBurnEstimator.estimate(
            hrBpm = null,
            powerW = 50,                  // well below 55 % FTP → Z1
            profile = profile(ftp = 250),
            riderAge = 40,
            riderSex = RiderSex.MALE,
        )
        assertEquals(CarbBurnEstimator.Confidence.POWER, r.confidence)
        assertEquals(CarbBurnEstimator.CHO_MIN_FRACTION, r.choFraction, 0.001)
        // g/h = 50 × 3.6 × 0.30 / 4 = 13.5
        assertEquals(13.5, r.gph, 0.5)
    }

    // ── Tier 2: Keytel 2005 HR-based ──────────────────────────────────────────

    @Test
    fun `tier2 keytel male formula matches published regression`() {
        // No power; HR-only path. Keytel male at HR=150, W=70, A=40:
        //   kJ/min = -55.0969 + 0.6309·150 + 0.1988·70 + 0.2017·40
        //          = -55.0969 + 94.635 + 13.916 + 8.068 = 61.5221
        //   kcal/h = 61.5221 × 60 / 4.184 = 882.16
        val r = CarbBurnEstimator.estimate(
            hrBpm = 150,
            powerW = null,
            profile = profile(weight = 70f),
            riderAge = 40,
            riderSex = RiderSex.MALE,
        )
        assertEquals(CarbBurnEstimator.Confidence.KEYTEL, r.confidence)
        assertEquals(882.16, r.kcalPerHour, 0.5)
    }

    @Test
    fun `tier2 keytel female formula matches published regression`() {
        // Female at HR=150, W=60, A=35:
        //   kJ/min = -20.4022 + 0.4472·150 - 0.1263·60 + 0.0740·35
        //          = -20.4022 + 67.08 - 7.578 + 2.59 = 41.6898
        //   kcal/h = 41.6898 × 60 / 4.184 = 597.84
        val r = CarbBurnEstimator.estimate(
            hrBpm = 150,
            powerW = null,
            profile = profile(weight = 60f),
            riderAge = 35,
            riderSex = RiderSex.FEMALE,
        )
        assertEquals(CarbBurnEstimator.Confidence.KEYTEL, r.confidence)
        assertEquals(597.84, r.kcalPerHour, 0.5)
    }

    @Test
    fun `tier2 falls through to tier3 when age missing`() {
        // Age=0 (rider hasn't filled Settings) → Keytel unavailable → fall back to Swain.
        val r = CarbBurnEstimator.estimate(
            hrBpm = 150,
            powerW = null,
            profile = profile(),
            riderAge = 0,
            riderSex = RiderSex.MALE,
        )
        assertEquals(CarbBurnEstimator.Confidence.SWAIN, r.confidence)
    }

    @Test
    fun `tier2 falls through to tier3 when sex not set`() {
        val r = CarbBurnEstimator.estimate(
            hrBpm = 150,
            powerW = null,
            profile = profile(),
            riderAge = 40,
            riderSex = RiderSex.NOT_SET,
        )
        assertEquals(CarbBurnEstimator.Confidence.SWAIN, r.confidence)
    }

    // ── Tier 3: Swain HRR → METs ──────────────────────────────────────────────

    @Test
    fun `tier3 swain HRR formula matches expected METs and kcal per hour`() {
        // HR=150, maxHr=190, restHr=50, weight=70:
        //   HRR = (150-50)/(190-50) = 0.7143
        //   METs = 6 × 0.7143 + 1 = 5.286
        //   kcal/h = 5.286 × 70 = 370
        val r = CarbBurnEstimator.estimate(
            hrBpm = 150,
            powerW = null,
            profile = profile(weight = 70f, maxHr = 190, restingHr = 50),
            riderAge = 0,
            riderSex = RiderSex.NOT_SET,
        )
        assertEquals(CarbBurnEstimator.Confidence.SWAIN, r.confidence)
        assertEquals(370.0, r.kcalPerHour, 0.5)
    }

    @Test
    fun `tier3 missing maxHr or restingHr returns NONE`() {
        val noMaxHr = profile().copy(maxHr = 0)
        val r = CarbBurnEstimator.estimate(
            hrBpm = 150, powerW = null, profile = noMaxHr,
            riderAge = 0, riderSex = RiderSex.NOT_SET,
        )
        assertEquals(CarbBurnEstimator.Confidence.NONE, r.confidence)
        assertEquals(0.0, r.gph, 0.0)
    }

    // ── Tier 4: no usable inputs ──────────────────────────────────────────────

    @Test
    fun `tier4 returns NONE when nothing useful is available`() {
        val r = CarbBurnEstimator.estimate(
            hrBpm = null, powerW = null, profile = null,
            riderAge = 40, riderSex = RiderSex.MALE,
        )
        assertEquals(CarbBurnEstimator.Confidence.NONE, r.confidence)
        assertEquals(0.0, r.gph, 0.0)
    }

    @Test
    fun `zero or negative power falls through to the HR tier`() {
        val r = CarbBurnEstimator.estimate(
            hrBpm = 150,
            powerW = 0,             // freewheeling — power=0 is a valid reading we should not use
            profile = profile(),
            riderAge = 40,
            riderSex = RiderSex.MALE,
        )
        assertEquals(CarbBurnEstimator.Confidence.KEYTEL, r.confidence)
    }

    @Test
    fun `zero power classifies the CHO zone from HR, not power Z1`() {
        // Regression: a 0 W reading (freewheeling, or a power meter latched at 0 after a
        // dropout) makes the kcal tier fall through to HR — the ZONE must follow the SAME
        // signal. Pre-fix the zone was classified from power=0 → power Z1 → CHO pinned to
        // the minimum, while kcal reflected an elevated HR, systematically under-counting
        // carbs (worst with a dead meter stuck at 0 W on a climb).
        val r = CarbBurnEstimator.estimate(
            hrBpm = 150,            // maxHr 190 → HR Z3 (134..152), index 2 of 5
            powerW = 0,             // must NOT pin the zone to power Z1
            profile = profile(),
            riderAge = 40,
            riderSex = RiderSex.MALE,
        )
        assertEquals(ZoneSource.HR, r.zoneSnapshot.source)
        // Z3 of 5 → ratio 2/4 = 0.5 → 0.30 + 0.5×0.65 = 0.625 (NOT CHO_MIN_FRACTION).
        assertEquals(0.625, r.choFraction, 0.001)
        assertTrue("CHO must not be pinned to the Z1 minimum",
            r.choFraction > CarbBurnEstimator.CHO_MIN_FRACTION)
    }

    // ── CHO fraction zone mapping ─────────────────────────────────────────────

    @Test
    fun `cho fraction is min at zone 0 and max at last zone for any zone count`() {
        // 5-zone HR profile
        val z1Hr5 = ZoneClassifier.Zone(ZoneSource.HR, 0, 5)
        assertEquals(CarbBurnEstimator.CHO_MIN_FRACTION,
            CarbBurnEstimator.choFractionForZone(z1Hr5), 1e-6)
        val z5Hr5 = ZoneClassifier.Zone(ZoneSource.HR, 4, 5)
        assertEquals(CarbBurnEstimator.CHO_MAX_FRACTION,
            CarbBurnEstimator.choFractionForZone(z5Hr5), 1e-6)
        // 7-zone power profile
        val z1Pw7 = ZoneClassifier.Zone(ZoneSource.POWER, 0, 7)
        assertEquals(CarbBurnEstimator.CHO_MIN_FRACTION,
            CarbBurnEstimator.choFractionForZone(z1Pw7), 1e-6)
        val z7Pw7 = ZoneClassifier.Zone(ZoneSource.POWER, 6, 7)
        assertEquals(CarbBurnEstimator.CHO_MAX_FRACTION,
            CarbBurnEstimator.choFractionForZone(z7Pw7), 1e-6)
    }

    @Test
    fun `cho fraction interpolates linearly between zone extremes`() {
        // 5-zone profile, middle zone (index 2 of 5) → ratio = 2/4 = 0.5
        // expected = 0.30 + 0.5 × 0.65 = 0.625
        val z3Hr5 = ZoneClassifier.Zone(ZoneSource.HR, 2, 5)
        assertEquals(0.625, CarbBurnEstimator.choFractionForZone(z3Hr5), 1e-6)
    }

    @Test
    fun `cho fraction returns midpoint for NONE-source zone`() {
        // Defensive: when no zones are configured we don't pretend to know.
        val none = ZoneClassifier.Zone.NONE
        val midpoint = (CarbBurnEstimator.CHO_MIN_FRACTION + CarbBurnEstimator.CHO_MAX_FRACTION) / 2.0
        assertEquals(midpoint, CarbBurnEstimator.choFractionForZone(none), 1e-6)
    }

    // ── End-to-end sanity: deficit growth at threshold ────────────────────────

    @Test
    fun `power tier at high intensity produces a burn rate above absorption cap`() {
        // Audit-style sanity: a real rider at threshold burns more carbs per hour
        // than the gut can absorb. The estimator should reflect that (the cap is
        // applied DOWNSTREAM in CarbsTracker, not here).
        val r = CarbBurnEstimator.estimate(
            hrBpm = null,
            powerW = 300,                 // sustainable threshold for a fit recreational rider
            profile = profile(ftp = 280), // 300 W = 107 % FTP → near top zone
            riderAge = 40,
            riderSex = RiderSex.MALE,
        )
        assertTrue(
            "expected high-intensity burn > 90 g/h absorption cap so the tracker " +
                "clamps it downstream; got ${r.gph}",
            r.gph > 90.0,
        )
    }
}
