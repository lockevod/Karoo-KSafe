package com.enderthor.kSafe.extension.util

import com.enderthor.kSafe.data.LastHydrationRide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HydrationCalibrationTest {

    private val h = 3_600_000L
    private val now = 100 * h

    private fun ride(
        rideTime: Long = 2 * h, covered: Long = 2 * h, endedAt: Long = now - h, calibrated: Boolean = false,
        base: Float = 1500f, sweat: Float = 1500f, logged: Int = 1200, sodium: Float = 1000f, na: Int = 36,
    ) = LastHydrationRide(
        rideId = 42, endedAtMs = endedAt, rideTimeMs = rideTime, coveredMs = covered, lowConfMs = 0,
        cumSweatBaseMl = base, cumSweatMl = sweat, cumLoggedMl = logged, cumSodiumMg = sodium,
        naMmolL = na, multiplierPctAtRide = 100, dynamicMode = true, calibrated = calibrated,
    )

    private fun input(
        pre: Double = 70.0, post: Double = 69.0, drink: Int = 600, food: Int = 0, urinated: Boolean = false,
    ) = CalibrationInput(pre, post, drink, food, urinated, now)

    private fun accepted(r: LastHydrationRide?, i: CalibrationInput, ex: List<Float> = emptyList()) =
        calibrate(r, 42, i, ex) as CalibrationResult.Accepted

    private fun rejection(r: LastHydrationRide?, i: CalibrationInput, id: Long = 42) =
        (calibrate(r, id, i, emptyList()) as CalibrationResult.Rejected).reason

    @Test fun `ride blocker reports why a record can never calibrate`() {
        assertNull(rideCalibrationBlocker(ride()))
        assertEquals(CalibrationRejection.NO_RIDE_TIME, rideCalibrationBlocker(ride(rideTime = 0)))
        assertEquals(CalibrationRejection.TOO_SHORT, rideCalibrationBlocker(ride(rideTime = h / 2, covered = h / 2)))
        assertEquals(CalibrationRejection.LOW_COVERAGE, rideCalibrationBlocker(ride(covered = h)))
    }

    @Test fun `accepted calibration computes measured and ratio`() {
        val a = accepted(ride(), input())
        assertEquals(1520, a.measuredMl)
        assertEquals(1.013f, a.ratio, 0.001f)
        assertEquals(101, a.newMultiplierPct)
    }

    @Test fun `correction applies to body mass only`() {
        assertEquals(2460, accepted(ride(), input(post = 69.5, drink = 2000)).measuredMl)
    }

    @Test fun `weight gain with big intake still calibrates`() {
        assertEquals(1224, accepted(ride(), input(post = 70.3, drink = 1500)).measuredMl)
    }

    @Test fun `NO_RIDE`() = assertEquals(CalibrationRejection.NO_RIDE, rejection(null, input()))
    @Test fun `RIDE_CHANGED`() = assertEquals(CalibrationRejection.RIDE_CHANGED, rejection(ride(), input(), id = 43))
    @Test fun `ALREADY_CALIBRATED`() =
        assertEquals(CalibrationRejection.ALREADY_CALIBRATED, rejection(ride(calibrated = true), input()))
    @Test fun `TOO_OLD`() =
        assertEquals(CalibrationRejection.TOO_OLD, rejection(ride(endedAt = now - 6 * h - 1), input()))
    @Test fun `exactly six hours is accepted`() { accepted(ride(endedAt = now - 6 * h), input()) }
    @Test fun `URINATED`() = assertEquals(CalibrationRejection.URINATED, rejection(ride(), input(urinated = true)))
    @Test fun `NO_RIDE_TIME`() =
        assertEquals(CalibrationRejection.NO_RIDE_TIME, rejection(ride(rideTime = 0, covered = 0), input()))
    @Test fun `TOO_SHORT`() =
        assertEquals(CalibrationRejection.TOO_SHORT, rejection(ride(rideTime = h / 2, covered = h / 2), input()))
    @Test fun `LOW_COVERAGE`() =
        assertEquals(CalibrationRejection.LOW_COVERAGE, rejection(ride(rideTime = 2 * h, covered = h), input()))
    @Test fun `coverage above ride time is LOW_COVERAGE`() =
        assertEquals(CalibrationRejection.LOW_COVERAGE, rejection(ride(rideTime = 70 * h / 60, covered = 3 * h), input()))
    @Test fun `coverage exactly 105 percent of ride time is ok`() {
        assertTrue(coverageOk(2 * h * 105 / 100, 2 * h))
        assertFalse(coverageOk(2 * h * 105 / 100 + 1, 2 * h))
    }
    @Test fun `IMPLAUSIBLE_WEIGHT`() =
        assertEquals(CalibrationRejection.IMPLAUSIBLE_WEIGHT, rejection(ride(), input(pre = 20.0, post = 19.0)))
    @Test fun `TOO_LITTLE_SWEAT`() =
        assertEquals(CalibrationRejection.TOO_LITTLE_SWEAT, rejection(ride(), input(post = 69.9, drink = 0)))
    @Test fun `RATIO_OUT_OF_RANGE`() =
        assertEquals(CalibrationRejection.RATIO_OUT_OF_RANGE, rejection(ride(base = 10_000f), input()))
    @Test fun `zero base is RATIO_OUT_OF_RANGE`() =
        assertEquals(CalibrationRejection.RATIO_OUT_OF_RANGE, rejection(ride(base = 0f), input()))

    @Test fun `one hour gap makes coverage too low`() =
        assertEquals(CalibrationRejection.LOW_COVERAGE, rejection(ride(rideTime = 3 * h, covered = 2 * h), input()))

    @Test fun `ratios keep last three and average`() {
        // measured 1520 on base 1900 -> ratio 0.8
        val a = accepted(ride(base = 1900f), input(), listOf(1.2f, 1.4f, 1.0f))
        assertEquals(listOf(1.4f, 1.0f, 0.8f), a.newRatios)
        assertEquals(107, a.newMultiplierPct)
    }

    @Test fun `multiplier clamps`() {
        assertEquals(200, multiplierFromRatios(listOf(3.0f)))
        assertEquals(50, multiplierFromRatios(listOf(0.3f)))
        assertEquals(100, multiplierFromRatios(emptyList()))
    }

    private fun sodiumRide(rideTime: Long = 3 * h, covered: Long = 3 * h, logged: Int = 2400, na: Int = 36) =
        ride(rideTime = rideTime, covered = covered, sweat = 3000f, logged = logged, sodium = 2482.92f, na = na)

    @Test fun `sodium advice typical 3h ride`() {
        val s = sodiumAdvice(sodiumRide())
        assertEquals(2483, s.mgLost); assertEquals(828, s.mgPerHour); assertEquals(517, s.bottleMgPerL)
        assertFalse(s.recommended); assertFalse(s.partial); assertFalse(s.fluidLimited)
    }

    @Test fun `sodium advice salty high replacement is recommended`() {
        val s = sodiumAdvice(sodiumRide(logged = 2700, na = 50))
        assertTrue(s.recommended); assertEquals(639, s.bottleMgPerL)
    }

    @Test fun `short ride has no bottle`() {
        val s = sodiumAdvice(sodiumRide(rideTime = 90 * 60_000L, covered = 90 * 60_000L))
        assertNull(s.bottleMgPerL); assertFalse(s.recommended)
    }

    @Test fun `low replacement is fluid limited`() {
        val s = sodiumAdvice(sodiumRide(logged = 900))
        assertNull(s.bottleMgPerL); assertTrue(s.fluidLimited)
    }

    @Test fun `partial coverage suppresses bottle and marks partial`() {
        val s = sodiumAdvice(sodiumRide(covered = 2 * h))
        assertTrue(s.partial); assertNull(s.bottleMgPerL); assertFalse(s.fluidLimited)
    }
}
