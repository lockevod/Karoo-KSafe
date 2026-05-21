package com.enderthor.kSafe.extension.managers

import com.enderthor.kSafe.BuildConfig
import com.enderthor.kSafe.extension.streamLocation
import io.hammerhead.karooext.KarooSystemService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import timber.log.Timber

/** An immutable GPS fix — latitude, longitude and the wall-clock time it was sampled. */
data class GpsFix(val lat: Double, val lng: Double, val sampleTimeMs: Long)

/**
 * Tracks the device's GPS position.
 *
 * The persistent collector started by [start] uses `.sample(LOCATION_SAMPLE_MS)` to cap
 * the coroutine wake-rate at one per 2 minutes (vs. ~3600/h before).
 *
 * The accuracy contract for emergency dispatch is owned by [getFreshLocationLink], not
 * by the cached value:
 *  - If the cache is fresh enough ([REUSE_CACHED_FRESH_MS]), reuse it — covers a
 *    thundering-herd of emergency messages fired within seconds of each other (rare).
 *  - Otherwise OPEN A FRESH ONE-SHOT CONSUMER and grab the next SDK emission. Cost
 *    ~1-5 s for the fresh fix, but the message contains the rider's actual current
 *    location, not a 2-min-old sample.
 *
 * Net effect: ~99 % wake-rate reduction in steady-state operation, with emergency-time
 * accuracy unchanged — every alert still gets a near-real-time GPS fix.
 */
class LocationManager(
    private val karooSystem: KarooSystemService,
    private val scope: CoroutineScope
) {
    // (Constants in the companion object at the bottom of the file.)

    // @Volatile: the fix is written from the location-stream coroutine on Default and
    // read from any dispatcher when an emergency builds the {location} link. Storing
    // lat+lng+timestamp as a single immutable [GpsFix] behind ONE volatile reference
    // means a reader always gets a self-consistent triple — it can never pair a
    // latitude from one sample with a longitude from the next. Reference writes are
    // atomic; volatile supplies the cross-thread visibility.
    @Volatile private var lastFix: GpsFix? = null
    private var locationJob: Job? = null

    /** The most recent stored GPS fix as a single atomic snapshot, or null if none yet. */
    fun currentFix(): GpsFix? = lastFix

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    fun start() {
        // Defensive cancel of any prior job — Karoo system reconnects can re-fire
        // the connect callback while a previous collector is still alive. Without
        // this an orphaned collector would leak forever (its reference is
        // overwritten by the new `locationJob =` below so stop() can't reach it).
        locationJob?.cancel()
        locationJob = scope.launch {
            // `.sample(LOCATION_SAMPLE_MS)` caps the downstream wake-rate at one per
            // interval regardless of how fast the upstream GPS stream emits (~1 Hz).
            // Previous code used a per-emission `if` check, which still woke the
            // collector 3600 times/h just to discard most. With sample() the coroutine
            // wakes ~30 times per hour (once per 2 min) — same end state, ~99 % fewer
            // resumptions.
            //
            // Trade-off: the FIRST emission is delayed up to LOCATION_SAMPLE_MS after
            // the first GPS fix. `getFreshLocationLink` already has a one-shot
            // `streamLocation().first()` fallback that covers the pre-cached-fix
            // window, so an emergency in the first 2 min still produces a real
            // location — it just opens an ad-hoc consumer instead of using the cache.
            karooSystem.streamLocation()
                .sample(LOCATION_SAMPLE_MS)
                .collect { event ->
                    lastFix = GpsFix(event.lat, event.lng, System.currentTimeMillis())
                    if (BuildConfig.DEBUG) Timber.d("Location sampled: ${event.lat}, ${event.lng}")
                }
        }
    }

    fun stop() {
        locationJob?.cancel()
    }

    /** Returns the cached Google Maps link, or null if no fix has been stored yet. */
    fun getLocationLink(): String? {
        val fix = lastFix ?: return null
        if (fix.lat == 0.0 && fix.lng == 0.0) return null
        return "https://maps.google.com/?q=${fix.lat},${fix.lng}"
    }

    /**
     * Tries to obtain a fresh GPS fix within [timeoutMs] milliseconds.
     * If the fix arrives in time, the cache is updated and the fresh link is returned.
     * If the timeout expires, the cached link (possibly null) is returned instead.
     *
     * Call this when an alert is about to be sent so the location is as accurate as possible.
     *
     * **Reuses the cached fix without opening a new consumer if the last sample is less
     * than [REUSE_CACHED_FRESH_MS] old.** The persistent stream started by [start] already
     * keeps the cache near-realtime, so opening a fresh consumer just to read what we
     * already have wastes a Karoo IPC round-trip and slows the emergency dispatch.
     */
    suspend fun getFreshLocationLink(timeoutMs: Long = 5_000L): String? {
        val now = System.currentTimeMillis()
        val cached = lastFix
        if (cached != null && cached.sampleTimeMs > 0L && now - cached.sampleTimeMs < REUSE_CACHED_FRESH_MS) {
            if (BuildConfig.DEBUG) Timber.d("Reusing cached location (${(now - cached.sampleTimeMs) / 1000}s old)")
            return getLocationLink()
        }
        return try {
            val event = withTimeout(timeoutMs) {
                karooSystem.streamLocation().first()
            }
            // Update cache with the fresh fix
            lastFix = GpsFix(event.lat, event.lng, System.currentTimeMillis())
            if (BuildConfig.DEBUG) Timber.d("Fresh location obtained: ${event.lat}, ${event.lng}")
            "https://maps.google.com/?q=${event.lat},${event.lng}"
        } catch (_: TimeoutCancellationException) {
            Timber.w("Fresh location timed out after ${timeoutMs}ms, falling back to cached location")
            getLocationLink()
        }
    }

    companion object {
        /** How often the persistent collector stores a new GPS fix. 2 min = ~30 updates/h
         *  instead of ~3600 — the wake-rate reduction motivates this whole class. */
        private const val LOCATION_SAMPLE_MS = 2 * 60_000L

        /** Reuse the cached fix without opening a new consumer if it's at most this old.
         *  Deliberately small (10 s, much less than [LOCATION_SAMPLE_MS]) so that
         *  [getFreshLocationLink] almost always opens a one-shot consumer for the
         *  EMERGENCY path — the message contains a near-real-time fix, not a sample
         *  that could be up to 2 min stale. The cache is just a thundering-herd
         *  protection for the rare case of multiple alerts firing within seconds. */
        private const val REUSE_CACHED_FRESH_MS = 10_000L
    }
}
