package com.enderthor.kSafe.extension.managers

import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Handler
import org.junit.Assert.assertEquals
import org.junit.Test
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
}
