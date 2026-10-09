package com.enderthor.kSafe.extension.util

import org.junit.Assert.assertEquals
import org.junit.Test

class OverDrinkShadowTest {

    private val min = 60_000L

    private fun level(
        rideMin: Long? = 120, coveredMin: Long = 120, sweat: Float = 1500f, logged: Int = 2100,
        conf: SweatConfidence = SweatConfidence.HIGH, integrating: Boolean = true, last: Int = 0,
    ) = overDrinkShadowLevel(rideMin?.times(min), coveredMin * min, sweat, logged, conf, integrating, last)

    @Test fun `fires level 1 at 2h sweat 1500 logged 2100`() = assertEquals(1, level())

    @Test fun `margin uses 35 percent when larger`() = assertEquals(0, level(sweat = 2000f, logged = 2650))

    @Test fun `level 2 only after another 500`() {
        assertEquals(2, level(logged = 2600, last = 1))
        assertEquals(0, level(logged = 2300, last = 1))
    }

    @Test fun `undo below a logged level never re-logs`() = assertEquals(0, level(logged = 2100, last = 2))

    @Test fun `ride under 90 min gives 0`() = assertEquals(0, level(rideMin = 80, coveredMin = 80))
    @Test fun `null ride time gives 0`() = assertEquals(0, level(rideMin = null))
    @Test fun `sweat under 1000 gives 0`() = assertEquals(0, level(sweat = 900f, logged = 2000))
    @Test fun `LOW confidence gives 0`() = assertEquals(0, level(conf = SweatConfidence.LOW))
    @Test fun `not integrating gives 0`() = assertEquals(0, level(integrating = false))
    @Test fun `coverage 0_7 gives 0`() = assertEquals(0, level(coveredMin = 84))

    @Test fun `coverage above 105 percent of ride time gives 0`() = assertEquals(0, level(coveredMin = 127))
    @Test fun `coverage at 105 percent still fires`() = assertEquals(1, level(coveredMin = 126))

    @Test fun `early big bottle logs do not fire`() =
        assertEquals(0, level(rideMin = 35, coveredMin = 35, sweat = 350f, logged = 1000))
}
