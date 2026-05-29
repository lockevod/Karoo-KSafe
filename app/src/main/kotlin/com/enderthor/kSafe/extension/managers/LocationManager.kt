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
            // K6 — retry-on-throw wrapper. Without this, an upstream exception
            // (RemoteException during a Karoo Companion rebind, IllegalStateException
            // from a service-binder hiccup, NoSuchElementException on an empty flow,
            // etc.) would terminate the collector coroutine permanently. The connect
            // callback only re-runs start() on a connected=true transition — a
            // companion-side throw with no disconnect callback never re-fires it,
            // so currentFix() would return a stale GpsFix for the rest of the ride
            // and any webhook geo-fence would compute distances against obsolete
            // coordinates. Emergency dispatch isn't affected (getFreshLocationLink
            // opens its own one-shot consumer with H5's broad catch), but webhook
            // geo-fence and the rider's last-known-position calibration log entries
            // would silently degrade.
            while (true) {
                try {
                    // `.sample(LOCATION_SAMPLE_MS)` caps the downstream wake-rate at
                    // one per interval regardless of how fast the upstream GPS stream
                    // emits (~1 Hz). With sample() the coroutine wakes ~30 times per
                    // hour instead of 3600 — same end state, ~99 % fewer resumptions.
                    karooSystem.streamLocation()
                        .sample(LOCATION_SAMPLE_MS)
                        .collect { event ->
                            // H8 — drop non-finite coordinates from the SDK.
                            if (!event.lat.isFinite() || !event.lng.isFinite()) {
                                Timber.w("Location sample dropped — non-finite coords (lat=${event.lat}, lng=${event.lng})")
                                return@collect
                            }
                            lastFix = GpsFix(event.lat, event.lng, System.currentTimeMillis())
                            if (BuildConfig.DEBUG) Timber.d("Location sampled: ${event.lat}, ${event.lng}")
                        }
                    // collect() returned cleanly (upstream completed) — extremely rare
                    // for an infinite SDK flow but possible after a Companion teardown
                    // that doesn't fire the connected=false callback. Re-subscribe.
                    Timber.w("Location stream completed unexpectedly — re-subscribing in 5 s")
                    kotlinx.coroutines.delay(5_000L)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Location collector threw — re-subscribing in 5 s")
                    kotlinx.coroutines.delay(5_000L)
                }
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
     * Tries to obtain a fresh GPS fix within [timeoutMs] milliseconds, mirroring the
     * cache-then-fresh-then-fallback logic of [getFreshLocationLink] but returning
     * the raw [GpsFix] for callers that need the coordinates (not a Maps link) —
     * specifically the webhook geo-fence distance check.
     *
     * **Why webhooks need this.** The persistent collector started by [start] applies
     * `.sample(LOCATION_SAMPLE_MS = 2 min)` so [currentFix] can be up to two minutes
     * stale. A rider who has just arrived at the webhook target spot would tap the
     * field, the geo-fence would compute distance against the position two minutes
     * earlier (~200-400 m back along the route), and the request would be blocked
     * — even though the rider IS at the target. The blocked-then-eventually-works
     * pattern observed in v1.2 and earlier 2.0 builds matched exactly this: 2-3 taps
     * spaced out until the next `.sample()` window let a fresh emission through.
     *
     * **Cost.** Webhook taps are rider-initiated and infrequent (a handful per
     * ride). The 5 s [REUSE_CACHED_FRESH_MS] window absorbs rapid retries so a
     * double-tap doesn't pay for two IPC round-trips.
     */
    suspend fun getFreshFix(timeoutMs: Long = 3_000L): GpsFix? {
        val now = System.currentTimeMillis()
        val cached = lastFix
        if (cached != null && cached.sampleTimeMs > 0L && now - cached.sampleTimeMs < REUSE_CACHED_FRESH_MS) {
            if (BuildConfig.DEBUG) Timber.d("getFreshFix: reusing cached (${(now - cached.sampleTimeMs) / 1000}s old)")
            return cached
        }
        return try {
            val event = withTimeout(timeoutMs) {
                karooSystem.streamLocation().first()
            }
            if (!event.lat.isFinite() || !event.lng.isFinite()) {
                Timber.w("getFreshFix: SDK returned non-finite coords (lat=${event.lat}, lng=${event.lng}); falling back to cache")
                return cached.freshEnoughForFallback()
            }
            val fresh = GpsFix(event.lat, event.lng, System.currentTimeMillis())
            lastFix = fresh
            if (BuildConfig.DEBUG) Timber.d("getFreshFix: fresh fix obtained: ${event.lat}, ${event.lng}")
            fresh
        } catch (_: TimeoutCancellationException) {
            Timber.w("getFreshFix: timed out after ${timeoutMs}ms, falling back to cached fix (${if (cached == null) "none" else "${(now - cached.sampleTimeMs) / 1000}s old"})")
            cached.freshEnoughForFallback()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Same broaden-the-catch rationale as getFreshLocationLink — the SDK
            // can throw RemoteException / IllegalStateException during a Companion
            // rebind, NoSuchElementException on an empty flow, etc. Fall back to
            // the cached value rather than propagating; the caller (webhook
            // dispatcher) treats a null fix as "no GPS" and surfaces it to the
            // rider, which is the right end-state for a real GPS outage but the
            // wrong one for a transient binder hiccup that the cached fix can
            // serve through.
            Timber.w(e, "getFreshFix: threw — falling back to cached fix")
            cached.freshEnoughForFallback()
        }
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
            // H8 — drop non-finite GPS coordinates from the SDK so a corrupted
            // event doesn't propagate NaN into the cached fix and downstream
            // distanceMeters calls (which would return NaN, and NaN > radius is
            // always false, bypassing the geo-fence). Fall back to cached on
            // non-finite coordinates the same way we fall back on timeout.
            if (!event.lat.isFinite() || !event.lng.isFinite()) {
                Timber.w("Fresh location returned non-finite coords (lat=${event.lat}, lng=${event.lng}); falling back to cache")
                return getLocationLink()
            }
            // Update cache with the fresh fix
            lastFix = GpsFix(event.lat, event.lng, System.currentTimeMillis())
            if (BuildConfig.DEBUG) Timber.d("Fresh location obtained: ${event.lat}, ${event.lng}")
            "https://maps.google.com/?q=${event.lat},${event.lng}"
        } catch (_: TimeoutCancellationException) {
            Timber.w("Fresh location timed out after ${timeoutMs}ms, falling back to cached location")
            getLocationLink()
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Don't catch — let structured concurrency cancel propagate.
            throw e
        } catch (e: Exception) {
            // H5 — broaden beyond TimeoutCancellationException. The Karoo SDK's
            // streamLocation().first() can throw RemoteException / IllegalStateException
            // during a transient Karoo Companion rebind, NoSuchElementException if the
            // flow completes empty, or any provider-side glitch we can't enumerate.
            // Fall back to the cached fix rather than propagating — the caller is
            // emergency-message construction and must always return a usable string.
            Timber.w(e, "Fresh location threw — falling back to cached location")
            getLocationLink()
        }
    }

    companion object {
        /** How often the persistent collector stores a new GPS fix. 2 min = ~30 updates/h
         *  instead of ~3600 — the wake-rate reduction motivates this whole class. */
        private const val LOCATION_SAMPLE_MS = 2 * 60_000L

        /** Reuse the cached fix without opening a new consumer if it's at most this old.
         *  Deliberately small (5 s, much less than [LOCATION_SAMPLE_MS]) so that
         *  [getFreshLocationLink] / [getFreshFix] almost always open a one-shot
         *  consumer — the message / geo-fence check contains a near-real-time fix,
         *  not a sample that could be up to 2 min stale. The cache is just a
         *  thundering-herd protection: rapid back-to-back emergencies firing
         *  within a few seconds, or a rider deliberately double-tapping a webhook
         *  field after a transient "no GPS" error — both fit inside 5 s. */
        private const val REUSE_CACHED_FRESH_MS = 5_000L

        /** Upper bound on the age of a CACHED fix returned by the [getFreshFix] fallback
         *  paths (timeout / non-finite / throw). The geo-fence must not be evaluated against
         *  an ancient position: a fix minutes old can place the rider hundreds of metres off,
         *  wrongly firing (or blocking) a geo-fenced webhook. Beyond this age the fallback
         *  returns null, which the caller treats as "no GPS fix yet" (blocks + tells the
         *  rider) — the correct end-state when location can't be verified. One sample
         *  interval + slack: tight enough to reject a real outage, loose enough to serve a
         *  transient binder hiccup with the most recent persistent-collector sample. */
        private const val MAX_FALLBACK_FIX_AGE_MS = LOCATION_SAMPLE_MS + 30_000L
    }

    /** The cached fix, but only if recent enough to trust for the geo-fence (see
     *  [MAX_FALLBACK_FIX_AGE_MS]); otherwise null so the caller surfaces "no GPS". */
    private fun GpsFix?.freshEnoughForFallback(): GpsFix? =
        this?.takeIf { it.sampleTimeMs > 0L &&
            System.currentTimeMillis() - it.sampleTimeMs <= MAX_FALLBACK_FIX_AGE_MS }
}
