package com.enderthor.kSafe.extension

import com.enderthor.kSafe.data.KSafeConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards [KSafeExtension.recordingStreamNeeds] — the gate that decides which Recording-only
 * Karoo streams get a subscription.
 *
 * The failure this protects against is silent and one-directional: a stream wrongly gated OFF
 * starves its consumer for the whole ride (no HR reaching the medical detector = no FLATLINE /
 * COLLAPSE detection) with nothing in the logs to say so. Wrongly gated ON only costs battery.
 * So every enabled feature must be asserted to pull the streams it reads.
 */
class RecordingStreamNeedsTest {

    /** All features off, master on — the explicit baseline the cases below toggle from. */
    private fun bare() = KSafeConfig(
        isActive = true,
        medicalEpisodeEnabled = false,
        wellnessEnabled = false,
        carbsTrackerEnabled = false,
        hrCaloriesEnabled = false,
        hydrationTrackerEnabled = false,
    )

    @Test
    fun `master switch off needs nothing`() {
        // Every consumer is stopped while the master switch is off, including the ones
        // whose own flags are still true — so no stream is worth a subscription.
        val needs = KSafeExtension.recordingStreamNeeds(
            bare().copy(isActive = false, medicalEpisodeEnabled = true, hydrationTrackerEnabled = true)
        )
        assertFalse("no stream is needed with the master switch off", needs.any)
    }

    @Test
    fun `crash-only rider needs no recording stream`() {
        // The whole point of the gate: safety-only riders were paying for six subscriptions
        // feeding trackers that had already returned from start().
        assertFalse(KSafeExtension.recordingStreamNeeds(bare()).any)
    }

    @Test
    fun `medical episode needs HR and power but not profile or ambient`() {
        // Default install. medicalDetector reads updateHr + updatePower only; it is absent
        // from the userProfile and temperature fan-outs.
        val needs = KSafeExtension.recordingStreamNeeds(bare().copy(medicalEpisodeEnabled = true))
        assertTrue("medical detector reads HR", needs.heartRate)
        assertTrue("medical FLATLINE cross-check reads power", needs.power)
        assertFalse("medical detector never reads the rider profile", needs.userProfile)
        assertFalse("medical detector never reads ambient temperature", needs.ambient)
    }

    @Test
    fun `hydration is the only consumer of ambient temperature`() {
        assertTrue(KSafeExtension.recordingStreamNeeds(bare().copy(hydrationTrackerEnabled = true)).ambient)
        for (other in listOf(
            bare().copy(medicalEpisodeEnabled = true),
            bare().copy(wellnessEnabled = true),
            bare().copy(carbsTrackerEnabled = true),
            bare().copy(hrCaloriesEnabled = true),
        )) {
            assertFalse(
                "only the hydration tracker consumes TEMPERATURE / Headwind",
                KSafeExtension.recordingStreamNeeds(other).ambient,
            )
        }
    }

    @Test
    fun `either fueling half pulls the same streams`() {
        // CarbsTracker.fuelMonitorEnabled() = carbsTrackerEnabled || hrCaloriesEnabled.
        // Gating on carbs alone would starve an HR-calories-only rider.
        assertEquals(
            KSafeExtension.recordingStreamNeeds(bare().copy(carbsTrackerEnabled = true)),
            KSafeExtension.recordingStreamNeeds(bare().copy(hrCaloriesEnabled = true)),
        )
        val needs = KSafeExtension.recordingStreamNeeds(bare().copy(hrCaloriesEnabled = true))
        assertTrue(needs.power)
        assertTrue(needs.userProfile)
        assertTrue(needs.heartRate)
    }

    @Test
    fun `wellness needs power profile and HR`() {
        val needs = KSafeExtension.recordingStreamNeeds(bare().copy(wellnessEnabled = true))
        assertTrue("cardiac decoupling reads power", needs.power)
        assertTrue("percent-of-max-HR mode reads the rider profile", needs.userProfile)
        assertTrue(needs.heartRate)
    }

    @Test
    fun `every feature on needs every stream`() {
        val needs = KSafeExtension.recordingStreamNeeds(
            bare().copy(
                medicalEpisodeEnabled = true,
                wellnessEnabled = true,
                carbsTrackerEnabled = true,
                hrCaloriesEnabled = true,
                hydrationTrackerEnabled = true,
            )
        )
        assertEquals(KSafeExtension.RecordingStreamNeeds(true, true, true, true), needs)
    }

    @Test
    fun `shipped defaults need exactly HR and power`() {
        // Pinned to a concrete tuple, not recomputed from the same disjunction the
        // implementation uses — otherwise the assertion can only catch a changed default,
        // never a wrong gate. As shipped: medical episode ON, everything else OFF, so the
        // default install wants HR + POWER and must NOT subscribe to the rider profile or
        // the ambient-temperature streams. If a default flips, this fails and the reviewer
        // has to decide deliberately what the new baseline subscription set should be.
        val needs = KSafeExtension.recordingStreamNeeds(KSafeConfig().copy(isActive = true))
        assertEquals(
            KSafeExtension.RecordingStreamNeeds(
                power = true, userProfile = false, heartRate = true, ambient = false,
            ),
            needs,
        )
    }

    @Test
    fun `ambient always implies every other stream`() {
        // The invariant KSafeExtension.stopRecordingCollectors' `hasHeadwindTemp = false`
        // depends on: because the only ambient consumer (hydration) also reads power,
        // profile and HR, the sole tuple with ambient=true is (T,T,T,T). A rebuild only
        // happens when the tuple CHANGES, so the Headwind collectors can never be torn
        // down and immediately recreated — the onboard-temperature fallback cannot race
        // the Headwind source. Exhaustive over all 32 flag combinations, so a future
        // second ambient consumer that breaks the invariant fails here rather than
        // shipping as a subtle temperature glitch.
        var seenAmbient = 0
        for (mask in 0 until 32) {
            val c = bare().copy(
                medicalEpisodeEnabled   = mask and 1 != 0,
                wellnessEnabled         = mask and 2 != 0,
                carbsTrackerEnabled     = mask and 4 != 0,
                hrCaloriesEnabled       = mask and 8 != 0,
                hydrationTrackerEnabled = mask and 16 != 0,
            )
            val needs = KSafeExtension.recordingStreamNeeds(c)
            if (!needs.ambient) continue
            seenAmbient++
            assertTrue("ambient without power (mask=$mask)", needs.power)
            assertTrue("ambient without userProfile (mask=$mask)", needs.userProfile)
            assertTrue("ambient without heartRate (mask=$mask)", needs.heartRate)
        }
        assertEquals("half the combinations enable hydration", 16, seenAmbient)
    }
}
