package com.enderthor.kSafe.extension.managers

import com.enderthor.kSafe.BuildConfig
import com.enderthor.kSafe.extension.streamLocation
import io.hammerhead.karooext.KarooSystemService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import timber.log.Timber

/**
 * Tracks the device's GPS position.
 *
 * We subscribe continuously to the Karoo location stream (GPS is always active on the
 * device so there is no hardware cost in subscribing), but we only store a new position
 * every [LOCATION_SAMPLE_MS] to avoid waking the coroutine 3600 times per hour.
 *
 * The stored position is at most [LOCATION_SAMPLE_MS] old when an alert is sent,
 * which is accurate enough for emergency use — if the rider has crashed they are
 * likely stationary.
 */
class LocationManager(
    private val karooSystem: KarooSystemService,
    private val scope: CoroutineScope
) {
    companion object {
        /** How often to store a new GPS fix. 2 min = ~30 updates/hour instead of ~3600. */
        private const val LOCATION_SAMPLE_MS = 2 * 60_000L
    }

    private var lastLat: Double = 0.0
    private var lastLng: Double = 0.0
    private var lastSampleTime: Long = 0L
    private var locationJob: Job? = null

    fun start() {
        locationJob = scope.launch {
            karooSystem.streamLocation().collect { event ->
                val now = System.currentTimeMillis()
                // Accept the very first fix immediately (lastSampleTime == 0),
                // then throttle to once every LOCATION_SAMPLE_MS.
                if (now - lastSampleTime >= LOCATION_SAMPLE_MS) {
                    lastSampleTime = now
                    lastLat = event.lat
                    lastLng = event.lng
                    if (BuildConfig.DEBUG) Timber.d("Location sampled: $lastLat, $lastLng")
                }
            }
        }
    }

    fun stop() {
        locationJob?.cancel()
    }

    /** Returns the cached Google Maps link, or null if no fix has been stored yet. */
    fun getLocationLink(): String? {
        if (lastLat == 0.0 && lastLng == 0.0) return null
        return "https://maps.google.com/?q=$lastLat,$lastLng"
    }

    /**
     * Tries to obtain a fresh GPS fix within [timeoutMs] milliseconds.
     * If the fix arrives in time, the cache is updated and the fresh link is returned.
     * If the timeout expires, the cached link (possibly null) is returned instead.
     *
     * Call this when an alert is about to be sent so the location is as accurate as possible.
     */
    suspend fun getFreshLocationLink(timeoutMs: Long = 5_000L): String? {
        return try {
            val event = withTimeout(timeoutMs) {
                karooSystem.streamLocation().first()
            }
            // Update cache with the fresh fix
            lastLat = event.lat
            lastLng = event.lng
            lastSampleTime = System.currentTimeMillis()
            if (BuildConfig.DEBUG) Timber.d("Fresh location obtained: $lastLat, $lastLng")
            "https://maps.google.com/?q=${event.lat},${event.lng}"
        } catch (_: TimeoutCancellationException) {
            Timber.w("Fresh location timed out after ${timeoutMs}ms, falling back to cached location")
            getLocationLink()
        }
    }
}
