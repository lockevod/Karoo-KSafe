package com.enderthor.kSafe.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class HydrationStateSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `legacy HydFuelingState json decodes with zero sweat fields`() {
        val s = json.decodeFromString<HydFuelingState>(
            """{"cumTargetMl":500.0,"cumLoggedMl":300,"sessionStartMs":1000}""",
        )
        assertEquals(500f, s.cumTargetMl, 0f)
        assertEquals(0L, s.coveredMs)
        assertEquals(0L, s.lowConfMs)
        assertEquals(0f, s.cumSweatBaseMl, 0f)
        assertEquals(0f, s.cumSweatMl, 0f)
        assertEquals(0f, s.cumSodiumMg, 0f)
        assertEquals(0, s.overShadowLevel)
    }

    @Test
    fun `LastHydrationRide round-trips`() {
        val r = LastHydrationRide(
            rideId = 7L, endedAtMs = 99L, rideTimeMs = 3_600_000L,
            coveredMs = 3_000_000L, lowConfMs = 100_000L,
            cumSweatBaseMl = 800f, cumSweatMl = 880f, cumLoggedMl = 700, cumSodiumMg = 1200f,
            naMmolL = 36, multiplierPctAtRide = 110, dynamicMode = true, calibrated = true,
        )
        assertEquals(r, json.decodeFromString<LastHydrationRide>(json.encodeToString(r)))
    }

    @Test
    fun `sodium profile mmol values`() {
        assertEquals(25, SweatSodiumProfile.LIGHT.mmolL(36))
        assertEquals(36, SweatSodiumProfile.TYPICAL.mmolL(36))
        assertEquals(50, SweatSodiumProfile.SALTY.mmolL(36))
        assertEquals(10, SweatSodiumProfile.MEASURED.mmolL(5))
        assertEquals(90, SweatSodiumProfile.MEASURED.mmolL(120))
        assertEquals(42, SweatSodiumProfile.MEASURED.mmolL(42))
    }
}
