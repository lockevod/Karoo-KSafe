package com.enderthor.kSafe.extension.crash

import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Handler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.ArgumentMatchers.isNull
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify

/**
 * Lifecycle tests for [SensorReader]. We exercise register/unregister and idempotency
 * against a mocked [SensorManager].
 *
 * Limitation: SensorEvent has a package-private constructor that the stub android.jar
 * shipped with the testDebugUnitTest classpath replaces with a "Stub!" throw, and
 * mockito-inline cannot synthesize a SensorEvent that survives access to its public
 * `sensor` / `values` fields cleanly enough to drive the SensorReader through real
 * accel ticks. So the **race-fix payoff** (stop() unregistering BEFORE clearing the
 * buffers — item 11 in the reliability diagnostic) is verified by code inspection of
 * [SensorReader.stop] rather than by a feed-and-observe assertion in this suite.
 */
class SensorReaderTest {

    private fun accelSensor(): Sensor =
        mock(Sensor::class.java).also { `when`(it.type).thenReturn(Sensor.TYPE_ACCELEROMETER) }

    private fun gyroSensor(): Sensor =
        mock(Sensor::class.java).also { `when`(it.type).thenReturn(Sensor.TYPE_GYROSCOPE) }

    /** Mockito returns `false` from boolean methods by default. Post-B11
     *  `SensorReader.start` checks `registerListener`'s return value and
     *  early-returns on `false` (a real-world signal that the OS rejected
     *  the registration). The lifecycle tests want the happy path, so stub
     *  the boolean overload to `true`. */
    private fun stubRegisterListenerOk(sm: SensorManager) {
        `when`(sm.registerListener(
            any<SensorReader>(), any<Sensor>(),
            any<Int>(), any<Int>(), isNull<Handler>()
        )).thenReturn(true)
    }

    private fun newReader(sensorManager: SensorManager): SensorReader = SensorReader(
        sensorManager = sensorManager,
        clock = { 1_000L },
        onSample = { /* not exercised in lifecycle tests */ },
    )

    @Test
    fun `start registers accelerometer and gyroscope at SENSOR_DELAY_GAME`() {
        val sm = mock(SensorManager::class.java)
        val accel = accelSensor()
        val gyro = gyroSensor()
        `when`(sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)).thenReturn(accel)
        `when`(sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)).thenReturn(gyro)
        stubRegisterListenerOk(sm)

        val reader = newReader(sm)
        reader.start()

        verify(sm).registerListener(
            eq(reader), eq(accel),
            eq(SensorManager.SENSOR_DELAY_GAME),
            eq(SensorReader.BATCH_MAX_LATENCY_US),
            isNull<Handler>()
        )
        verify(sm).registerListener(
            eq(reader), eq(gyro),
            eq(SensorManager.SENSOR_DELAY_GAME),
            eq(SensorReader.BATCH_MAX_LATENCY_US),
            isNull<Handler>()
        )
    }

    @Test
    fun `start is idempotent - calling twice does not re-register`() {
        val sm = mock(SensorManager::class.java)
        val accel = accelSensor()
        val gyro = gyroSensor()
        `when`(sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)).thenReturn(accel)
        `when`(sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)).thenReturn(gyro)
        stubRegisterListenerOk(sm)

        val reader = newReader(sm)
        reader.start()
        reader.start()
        reader.start()

        // 2 register calls total (one accel + one gyro), regardless of how many times start() ran.
        verify(sm, times(2)).registerListener(
            any<SensorReader>(), any<Sensor>(),
            any<Int>(), any<Int>(), isNull<Handler>()
        )
    }

    @Test
    fun `start with no accelerometer present is a no-op`() {
        val sm = mock(SensorManager::class.java)
        `when`(sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)).thenReturn(null)

        val reader = newReader(sm)
        reader.start()

        verify(sm, times(0)).registerListener(
            any<SensorReader>(), any<Sensor>(),
            any<Int>(), any<Int>(), isNull<Handler>()
        )
    }

    @Test
    fun `gyroscope is optional - start succeeds with only accelerometer`() {
        val sm = mock(SensorManager::class.java)
        val accel = accelSensor()
        `when`(sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)).thenReturn(accel)
        `when`(sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)).thenReturn(null)
        stubRegisterListenerOk(sm)

        val reader = newReader(sm)
        reader.start()

        // Exactly one register call — the accelerometer.
        verify(sm, times(1)).registerListener(
            any<SensorReader>(), any<Sensor>(),
            any<Int>(), any<Int>(), isNull<Handler>()
        )
    }

    @Test
    fun `stop on a freshly started reader unregisters the listener`() {
        // Note: this test only exercises the unregister side of stop(). We cannot easily
        // fill the buffers from a unit test (SensorEvent has a package-private constructor
        // that mockito-inline cannot mock cleanly and whose stubbed android.jar variant
        // throws "Stub!"), so the buffer-clearance race fix in stop() — which clears AFTER
        // unregister — is verified by code inspection of SensorReader.stop() rather than
        // by a feed-and-observe assertion. The assertions on accelStillSinceMs and
        // lastGyroMag below are weak (they hold even without start/stop) but exist to
        // make it obvious if a future refactor accidentally initialises these fields to
        // non-zero defaults.
        val sm = mock(SensorManager::class.java)
        val accel = accelSensor()
        val gyro = gyroSensor()
        `when`(sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)).thenReturn(accel)
        `when`(sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)).thenReturn(gyro)
        stubRegisterListenerOk(sm)

        val reader = newReader(sm)
        reader.start()
        reader.stop()

        verify(sm).unregisterListener(reader)
        assertEquals(0L, reader.accelStillSinceMs)
        assertEquals(0.0, reader.lastGyroMag, 0.0)
        assertEquals(emptyList<Double>(), reader.magnitudeBufferSnapshot())
    }

    @Test
    fun `restart after stop re-registers the sensors`() {
        val sm = mock(SensorManager::class.java)
        val accel = accelSensor()
        val gyro = gyroSensor()
        `when`(sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)).thenReturn(accel)
        `when`(sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)).thenReturn(gyro)
        stubRegisterListenerOk(sm)

        val reader = newReader(sm)
        reader.start()
        reader.stop()
        reader.start()

        // 4 register calls total (2 sensors × 2 start cycles), 1 unregister so far.
        verify(sm, times(4)).registerListener(
            any<SensorReader>(), any<Sensor>(),
            any<Int>(), any<Int>(), isNull<Handler>()
        )
        verify(sm, times(1)).unregisterListener(reader)
    }

    @Test
    fun `stop without prior start is a no-op`() {
        val sm = mock(SensorManager::class.java)

        val reader = newReader(sm)
        reader.stop()

        verify(sm, times(0)).unregisterListener(reader)
    }

    // ---- inline any() helper to keep the call sites readable in Kotlin --------------
    private inline fun <reified T> any(): T = org.mockito.ArgumentMatchers.any(T::class.java)

    @Test
    fun `preImpactReference averages the recent vector window`() {
        // Drive 200 synthetic accelerometer samples of a constant upright vector
        // through the reader, 20 ms apart, then ask for the pre-impact reference.
        val reader = newReader(mock(SensorManager::class.java))
        var t = 1_000_000L
        repeat(200) {
            reader.pushAccelForTest(x = 0f, y = 0f, z = 9.81f, tsMs = t)
            t += 20L
        }
        val impactTs = t  // just after the last sample
        val ref = reader.preImpactReference(impactTs)
        assertTrue(ref.valid)
        assertEquals(9.81, ref.z, 1e-3)
        assertEquals(0.0, ref.x, 1e-3)
    }

    @Test
    fun `preImpactReference is invalid before enough samples arrive`() {
        val reader = newReader(mock(SensorManager::class.java))
        var t = 1_000_000L
        repeat(10) {
            reader.pushAccelForTest(x = 0f, y = 0f, z = 9.81f, tsMs = t)
            t += 20L
        }
        val ref = reader.preImpactReference(t)
        assertFalse(ref.valid)
    }

    /** Mutable clock so the test can place [SensorReader.invalidateVectorRing]'s floor
     *  precisely between the old and new sample groups. */
    private class MutableClock(var nowMs: Long) : com.enderthor.kSafe.extension.util.Clock {
        override fun nowMs(): Long = nowMs
    }

    @Test
    fun `invalidateVectorRing makes pre-invalidation samples ignored`() {
        // Controllable clock — invalidateVectorRing() stamps the floor from clock.nowMs().
        val clock = MutableClock(1_000L)
        val reader = SensorReader(
            sensorManager = mock(SensorManager::class.java),
            clock = clock,
            onSample = { /* unused */ },
        )

        // 200 OLD samples, 20 ms apart, ending well before the invalidation instant.
        var t = 1_000_000L
        repeat(200) {
            reader.pushAccelForTest(x = 0f, y = 0f, z = 9.81f, tsMs = t)
            t += 20L
        }
        val lastOldTs = t - 20L  // ts of the final old sample

        // Invalidate: the floor lands at clock.nowMs(), strictly after every old sample.
        clock.nowMs = lastOldTs + 1_000L
        reader.invalidateVectorRing()

        // An impact whose pre-impact window covers ONLY the old samples → invalid,
        // because all 200 entries sit below the floor and are discarded.
        // Window for impactTs = lastOldTs + 250 is [lastOldTs-2000, lastOldTs].
        val refOnlyOld = reader.preImpactReference(lastOldTs + PreImpactReference.GUARD_MS)
        assertFalse("pre-invalidation samples must be excluded", refOnlyOld.valid)

        // Push 200 NEW samples AFTER the floor; their timestamps start above the floor.
        var tNew = clock.nowMs + 20L
        repeat(200) {
            reader.pushAccelForTest(x = 0f, y = 0f, z = 5.00f, tsMs = tNew)
            tNew += 20L
        }
        val lastNewTs = tNew - 20L

        // A later impact whose window covers the new samples → valid, and the
        // reference reflects only the post-floor value (z = 5.00, not 9.81).
        val refNew = reader.preImpactReference(lastNewTs + PreImpactReference.GUARD_MS)
        assertTrue("post-invalidation samples must yield a valid reference", refNew.valid)
        assertEquals(5.00, refNew.z, 1e-3)
        assertEquals(0.0, refNew.x, 1e-3)
    }
}

/**
 * Direct wrap-around coverage for [Vec3RingBuffer].
 *
 * The lifecycle tests above exercise [Vec3RingBuffer] only indirectly (via
 * [SensorReader.preImpactReference]). These tests construct the ring directly and
 * verify the boundary conditions that matter for correctness:
 *  - not-yet-full: snapshot returns exactly what was added, in order.
 *  - over-full: snapshot returns exactly [capacity] entries (the most-recent ones),
 *    in insertion order — oldest-surviving entry first.
 *  - clear: empties the ring so subsequent adds start fresh.
 */
class Vec3RingBufferWrapAroundTest {

    @Test
    fun `Vec3RingBuffer wraps and keeps the most recent entries in insertion order`() {
        val capacity = 4
        val buf = Vec3RingBuffer(capacity)

        // --- not-yet-full case: add 2 entries into a capacity-4 buffer ---
        buf.add(0.0, 0.0, 0.0, tsMs = 0L)
        buf.add(1.0, 1.0, 1.0, tsMs = 1L)

        val partial = buf.snapshot()
        assertEquals("not-yet-full: expected 2 entries", 2, partial.size)
        assertEquals(TimedVec3(0.0, 0.0, 0.0, 0L), partial[0])
        assertEquals(TimedVec3(1.0, 1.0, 1.0, 1L), partial[1])

        // --- wrap-around case: add 4 more (6 total) into the same capacity-4 buffer ---
        // Entries 0 and 1 were already in the buffer; entries 2..5 push 0 and 1 out.
        buf.add(2.0, 2.0, 2.0, tsMs = 2L)
        buf.add(3.0, 3.0, 3.0, tsMs = 3L)
        buf.add(4.0, 4.0, 4.0, tsMs = 4L)
        buf.add(5.0, 5.0, 5.0, tsMs = 5L)

        // Now 6 adds into capacity-4 → only the last 4 survive: entries 2, 3, 4, 5.
        val full = buf.snapshot()
        assertEquals("wrap-around: expected capacity entries", capacity, full.size)

        // Oldest surviving entry first — insertion order is preserved.
        assertEquals(TimedVec3(2.0, 2.0, 2.0, 2L), full[0])
        assertEquals(TimedVec3(3.0, 3.0, 3.0, 3L), full[1])
        assertEquals(TimedVec3(4.0, 4.0, 4.0, 4L), full[2])
        assertEquals(TimedVec3(5.0, 5.0, 5.0, 5L), full[3])

        // --- clear() empties the buffer ---
        buf.clear()
        assertEquals("after clear: expected empty snapshot", emptyList<TimedVec3>(), buf.snapshot())

        // A subsequent add works as if the buffer is brand-new.
        buf.add(9.0, 9.0, 9.0, tsMs = 99L)
        val afterClear = buf.snapshot()
        assertEquals(1, afterClear.size)
        assertEquals(TimedVec3(9.0, 9.0, 9.0, 99L), afterClear[0])
    }
}

/**
 * Tests for the O(1) running-sum optimization of [DoubleRingBuffer].
 *
 * Each test compares the optimized `stdDev()` / `runningSum` against a
 * fresh O(N) recomputation. If the incremental state ever drifts from the
 * truth, these tests will catch it.
 */
class DoubleRingBufferIncrementalSumTest {

    private fun expectedSum(values: DoubleArray): Double = values.sum()
    private fun expectedMean(values: DoubleArray): Double =
        if (values.isEmpty()) 0.0 else expectedSum(values) / values.size
    private fun expectedStdDev(values: DoubleArray): Double {
        if (values.size < 2) return 0.0
        val m = expectedMean(values)
        var sumSq = 0.0
        values.forEach { v -> val d = v - m; sumSq += d * d }
        return kotlin.math.sqrt(sumSq / values.size)
    }

    @org.junit.Test
    fun `empty buffer returns zero stdDev and zero sums`() {
        val b = DoubleRingBuffer(8)
        org.junit.Assert.assertEquals(0.0, b.runningSum, 0.0)
        org.junit.Assert.assertEquals(0.0, b.runningSumSq, 0.0)
        org.junit.Assert.assertEquals(0.0, b.stdDev(), 0.0)
    }

    @org.junit.Test
    fun `single element has zero stdDev`() {
        val b = DoubleRingBuffer(8)
        b.add(9.81)
        org.junit.Assert.assertEquals(9.81, b.runningSum, 1e-9)
        org.junit.Assert.assertEquals(9.81 * 9.81, b.runningSumSq, 1e-9)
        org.junit.Assert.assertEquals(0.0, b.stdDev(), 1e-9)
    }

    @org.junit.Test
    fun `runningSum matches sum recomputation while filling`() {
        val b = DoubleRingBuffer(5)
        val xs = doubleArrayOf(1.0, 2.0, 3.0, 4.0)
        xs.forEach { b.add(it) }
        org.junit.Assert.assertEquals(expectedSum(xs), b.runningSum, 1e-9)
    }

    @org.junit.Test
    fun `runningSum stays correct across wrap-around`() {
        val b = DoubleRingBuffer(3)
        // Fill: [1,2,3]
        listOf(1.0, 2.0, 3.0).forEach { b.add(it) }
        org.junit.Assert.assertEquals(6.0, b.runningSum, 1e-9)
        // Evict 1, push 4 → [2,3,4]
        b.add(4.0)
        org.junit.Assert.assertEquals(9.0, b.runningSum, 1e-9)
        // Evict 2, push 5 → [3,4,5]
        b.add(5.0)
        org.junit.Assert.assertEquals(12.0, b.runningSum, 1e-9)
        // Evict 3, push 6 → [4,5,6]
        b.add(6.0)
        org.junit.Assert.assertEquals(15.0, b.runningSum, 1e-9)
    }

    @org.junit.Test
    fun `runningSumSq stays correct across wrap-around`() {
        val b = DoubleRingBuffer(3)
        listOf(1.0, 2.0, 3.0).forEach { b.add(it) }
        org.junit.Assert.assertEquals(1.0 + 4.0 + 9.0, b.runningSumSq, 1e-9)
        b.add(4.0)  // evict 1: 4+9+16
        org.junit.Assert.assertEquals(4.0 + 9.0 + 16.0, b.runningSumSq, 1e-9)
        b.add(5.0)  // evict 2: 9+16+25
        org.junit.Assert.assertEquals(9.0 + 16.0 + 25.0, b.runningSumSq, 1e-9)
    }

    @org.junit.Test
    fun `stdDev matches O(N) recomputation after sequence of adds`() {
        val b = DoubleRingBuffer(10)
        // Mix of values to give non-trivial variance.
        val xs = doubleArrayOf(9.80, 9.85, 9.75, 9.95, 9.70, 10.20, 9.40, 10.05, 9.90, 9.83)
        xs.forEach { b.add(it) }
        org.junit.Assert.assertEquals(expectedStdDev(xs), b.stdDev(), 1e-9)
    }

    @org.junit.Test
    fun `stdDev matches O(N) recomputation across many wrap-arounds`() {
        val b = DoubleRingBuffer(50)
        val rng = java.util.Random(42)
        val values = DoubleArray(50)
        // Push 1000 random samples; after each, the live buffer is the last 50.
        // After step k (k>=50), the buffer contains samples k-49..k. Compare.
        var idx = 0
        repeat(1000) { step ->
            val v = 9.81 + (rng.nextGaussian() * 0.5)
            b.add(v)
            // Maintain a parallel window of the last min(step+1, 50) samples for comparison.
            if (step < 50) {
                values[step] = v
            } else {
                // Shift left, append.
                System.arraycopy(values, 1, values, 0, 49)
                values[49] = v
            }
            // Compare every 50 steps (don't slow tests by comparing every step).
            if ((step + 1) % 50 == 0) {
                val truth = expectedStdDev(values.copyOfRange(0, minOf(step + 1, 50)))
                org.junit.Assert.assertEquals(
                    "stdDev drift at step=$step", truth, b.stdDev(), 1e-6
                )
            }
            idx = step
        }
    }

    @org.junit.Test
    fun `clear resets running sums`() {
        val b = DoubleRingBuffer(5)
        listOf(1.0, 2.0, 3.0).forEach { b.add(it) }
        b.clear()
        org.junit.Assert.assertEquals(0.0, b.runningSum, 0.0)
        org.junit.Assert.assertEquals(0.0, b.runningSumSq, 0.0)
        org.junit.Assert.assertEquals(0.0, b.stdDev(), 0.0)
        // And start fresh.
        b.add(7.0)
        org.junit.Assert.assertEquals(7.0, b.runningSum, 1e-9)
        org.junit.Assert.assertEquals(49.0, b.runningSumSq, 1e-9)
    }
}
