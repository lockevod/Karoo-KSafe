package com.enderthor.kSafe.extension

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.ActiveRideProfile
import io.hammerhead.karooext.models.HttpResponseState
import io.hammerhead.karooext.models.OnHttpResponse
import io.hammerhead.karooext.models.OnLocationChanged
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.RideProfile
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UserProfile
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import com.enderthor.kSafe.extension.util.Clock
import com.enderthor.kSafe.extension.util.SystemClock
import kotlin.random.Random
import timber.log.Timber

/**
 * Tolerant Json instance used everywhere we decode user-saved config from DataStore.
 *  - [ignoreUnknownKeys] = true  → fields removed from the schema (e.g. legacy
 *    `senderConfigs` nested inside [KSafeConfig], or enum values dropped from the API)
 *    are silently skipped instead of throwing a `SerializationException` that would
 *    wipe the whole config back to defaults.
 *  - [coerceInputValues] = true  → if a JSON value doesn't match the current type
 *    (e.g. an enum value that was removed without a migration substitution, or a `null`
 *    where a non-nullable property is expected), the constructor default is used for
 *    just that field rather than aborting the whole decode. This is the load-side
 *    safety net for data preservation across version upgrades — without it a single
 *    stale enum value buried deep in the JSON could erase every other field the rider
 *    spent time setting up.
 *  - [isLenient] = true          → tolerate minor JSON syntax quirks; cheap insurance.
 */
val jsonWithUnknownKeys = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    isLenient = true
}

/** Json instance for exporting config files.
 *  - [encodeDefaults] = true  → ALL fields appear in the output, even those with default values,
 *    so the exported file serves as a complete, self-documented template.
 *  - [prettyPrint] = true     → human-readable formatting for easy manual editing.
 */
val jsonForExport = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = true
}

/**
 * Json instance for **writing** persisted state to DataStore (config blob, sender configs,
 * emergency state, wellness history). Deliberately *not* configured with `encodeDefaults`:
 *  - DataStore blobs are read by [jsonWithUnknownKeys] which fills missing fields with their
 *    Kotlin data-class defaults, so omitting defaults from the stored JSON saves bytes
 *    (a fresh install writes a config tens of bytes long instead of multiple KB).
 *  - It also means a forward-compat change to a default value automatically applies to
 *    every rider whose stored JSON predates the change — they don't see the *old* default
 *    frozen into their persisted blob. Future default changes that should NOT propagate
 *    silently must go through a CONFIG_VERSION bump + a migration branch.
 *
 * Distinct from the unnamed `Json` instance the standard library exposes: this one is
 * the project's *named* contract for "writes to disk".
 */
val jsonForStorage = Json {
    // No options — equivalent to default Json. Existing as a named alias so call sites
    // make the "this is the storage write path" intent explicit.
}

/**
 * How hard to fight to keep a stream alive.
 *
 * Declared explicitly by the caller rather than derived from the data type id. Provenance is
 * not criticality: the SDK accepts both `TYPE_EXT::<ext>::<id>` and the bare `<ext>::<id>`
 * alias, so a prefix test misses one form — and a future inter-extension SAFETY stream would
 * be silently demoted to the slow schedule. [CRITICAL] is the default; only a caller that
 * knows its stream is optional asks for [OPTIONAL].
 */
enum class StreamPolicy {
    /**
     * Anything a detector reads. Recovers within [CRITICAL_CAP_MS].
     *
     * The cap is set by crash detection, not by responsiveness: `CrashStateMachine`'s
     * MONITORING speed gate deliberately does NOT bypass on GPS staleness, so while SPEED is
     * dead `lastSpeedKmh` stays frozen — and if that frozen value is under the rider's
     * minimum, every impact is rejected for the whole outage.
     */
    CRITICAL,

    /**
     * Third-party convenience data (the Headwind weather streams). Its absence degrades an
     * estimate; it never blinds a detector. Backs off hard so a producer that is genuinely
     * gone is not hammered for the whole ride.
     */
    OPTIONAL,
}

/**
 * Why a Karoo stream stopped. Extends [Exception] — deliberately NOT
 * [kotlinx.coroutines.CancellationException], which structured concurrency would swallow
 * silently, reproducing the very bug this exists to fix.
 */
sealed class KarooStreamEnded(message: String) : Exception(message) {
    /** The producer reported a failure. */
    class Error(val reason: String) : KarooStreamEnded("stream error: $reason")

    /** The producer ended the stream normally. For a third-party extension this is a
     *  legitimate end-of-life, which is why [StreamPolicy.OPTIONAL] backs off harder.
     *
     *  A `class`, not a `data object`: as a singleton it would be the SAME Throwable instance
     *  for every stream death in the process, and coroutine machinery can `addSuppressed`
     *  onto a thrown exception — an unbounded, non-thread-safe accumulation on an object that
     *  never dies. */
    class Complete : KarooStreamEnded("stream completed")

    // Every instance is thrown on the Binder callback thread and immediately caught by
    // retryKarooStream; the stack trace is never read and filling it in on every stream
    // death is pure cost.
    override fun fillInStackTrace(): Throwable = this
}

private const val CRITICAL_CAP_MS = 5_000L
private const val OPTIONAL_CAP_MS = 300_000L
private const val CRITICAL_FIRST_MS = 1_000L
private const val OPTIONAL_FIRST_MS = 30_000L

/** A subscription that survived this long is treated as healthy, so the next death starts a
 *  fresh backoff burst. Deliberately measured in TIME ALONE: `StreamState.Searching`,
 *  `NotAvailable` and `Idle` are valid emissions carrying no health information, and a
 *  location or optional stream can be healthily silent for minutes. */
private const val STABLE_SUBSCRIPTION_MS = 30_000L

/** Backoff jitter, applied inside the cap so the ceiling is never exceeded. Independent
 *  collectors are killed together by a common producer or service failure; without jitter
 *  their retries stay aligned and hit the same Binder service in bursts. */
private const val JITTER = 0.25

/** Interval for the "still down" summary line. Time-based, not every-Nth-attempt: at the
 *  5 s critical cap every-5th would be ~48 lines/hour/stream, while at the 300 s optional cap
 *  it would be one line every 25 minutes. */
private const val DOWN_SUMMARY_INTERVAL_MS = 600_000L

/**
 * Keeps a Karoo SDK stream alive across producer termination.
 *
 * The SDK's `addConsumer` calls `removeConsumer` inside BOTH its `onError` and `onComplete`
 * wrappers, so once a producer ends a stream the consumer is gone for good. Without this the
 * wrapping `callbackFlow` simply stayed suspended: the collector coroutine remained alive and
 * its parent Job still reported `isActive`, so nothing anywhere noticed or rebuilt. Detectors
 * went blind in silence for the rest of the ride.
 *
 * Only [KarooStreamEnded] is caught. Cancellation propagates, and so does any other
 * exception — a genuine bug must surface rather than be retried into an invisible loop.
 * Exceptions thrown DOWNSTREAM are not seen here at all (this operator sits inside the
 * wrapper), which is why `LocationManager` keeps its own outer retry loop.
 *
 * All mutable state lives inside the `flow { }` builder, so it belongs to each collection.
 * `Flow.retryWhen` cannot express this: its `attempt` counter only resets on successful
 * completion, which never happens for an infinite stream, so the backoff would ratchet to the
 * cap over a long ride and stay there.
 */
internal fun <T> Flow<T>.retryKarooStream(
    label: String,
    policy: StreamPolicy,
    // Injected so JVM tests can drive the stability window off runTest's virtual clock;
    // android.os.SystemClock is unmockable there and would make every timing assertion
    // depend on wall time. Production keeps the monotonic elapsedRealtime source.
    clock: Clock = SystemClock,
    /**
     * Invoked on the collector's context each time the subscription is lost, BEFORE the
     * retry delay. Lets a caller whose data has no natural expiry react to the stream
     * ending without the exception escaping (the operator absorbs [KarooStreamEnded] by
     * design, and re-throwing it would defeat the whole point).
     *
     * The Headwind ambient collectors use it: a freshness window alone cannot be both long
     * enough to survive Headwind's own multi-minute publish cadence and short enough to
     * notice a dead stream within a ride. The window handles "alive but silent"; this
     * handles "gone", immediately.
     */
    onInterrupted: () -> Unit = {},
): Flow<T> = flow {
    val firstMs = if (policy == StreamPolicy.CRITICAL) CRITICAL_FIRST_MS else OPTIONAL_FIRST_MS
    val capMs = if (policy == StreamPolicy.CRITICAL) CRITICAL_CAP_MS else OPTIONAL_CAP_MS

    var consecutiveFailures = 0
    var downSinceMs: Long? = null
    var attemptsWhileDown = 0
    var lastSummaryMs = 0L
    var lastCauseKey: String? = null

    while (true) {
        val startedMs = clock.monotonicMs()
        val ended: KarooStreamEnded = try {
            collect { value ->
                // First value accepted after an outage: this — not the resubscription — is
                // what proves recovery. A resubscribe only proves the attempt, and for
                // streams whose collectors apply distinctUntilChanged an identical replayed
                // value would be swallowed before anyone saw it.
                val incidentStart = downSinceMs
                if (incidentStart != null) {
                    Timber.w(
                        "Karoo stream '$label' recovered after " +
                            "${clock.monotonicMs() - incidentStart}ms and $attemptsWhileDown attempt(s)"
                    )
                    downSinceMs = null
                    attemptsWhileDown = 0
                    lastCauseKey = null
                }
                emit(value)
            }
            // A Karoo stream flow never completes on its own; if one does, treat it exactly
            // like an SDK-side completion rather than silently ending the collection.
            KarooStreamEnded.Complete()
        } catch (e: KarooStreamEnded) {
            e
        }
        // NOTE: CancellationException and every non-KarooStreamEnded throwable are
        // deliberately NOT caught above, so they propagate out of this loop untouched.

        onInterrupted()

        val aliveMs = clock.monotonicMs() - startedMs
        if (aliveMs >= STABLE_SUBSCRIPTION_MS) {
            // The subscription was healthy for a while; this is a fresh incident.
            consecutiveFailures = 0
        }

        val nowMs = clock.monotonicMs()
        if (downSinceMs == null) {
            downSinceMs = nowMs
            lastSummaryMs = nowMs
            attemptsWhileDown = 0
        }
        attemptsWhileDown++

        // The schedule is chosen by policy alone. An OPTIONAL producer completing is a
        // legitimate end-of-life rather than a fault, and the slow schedule ALREADY starts at
        // 30 s for that reason — jumping straight to the 300 s cap would make a Headwind
        // restart 20 s later wait five minutes to be noticed, for no gain.
        val step = run {
            val exp = firstMs shl consecutiveFailures.coerceAtMost(16)
            if (exp <= 0L) capMs else exp.coerceAtMost(capMs)
        }
        val delayMs = jitter(step, capMs)
        consecutiveFailures++

        val causeKey = ended.message ?: ended::class.java.name
        val firstOfIncident = attemptsWhileDown == 1
        val causeChanged = causeKey != lastCauseKey
        val summaryDue = nowMs - lastSummaryMs >= DOWN_SUMMARY_INTERVAL_MS
        if (firstOfIncident || causeChanged || summaryDue) {
            if (summaryDue) lastSummaryMs = nowMs
            // WARN, not DEBUG: release strips v/d/i, and these lines are the only evidence
            // that will tell us whether this failure mode happens in the field at all.
            Timber.w(
                "Karoo stream '$label' ended (${ended.message}) — resubscribing in ${delayMs}ms " +
                    // "no data for" rather than "down": an incident is closed by a VALUE, not by a
                    // successful resubscribe, so this span can include time when the stream was
                    // subscribed but silent. For a sensor stream that is the failure, not a gap in
                    // the measurement.
                    "[attempt $attemptsWhileDown, no data for ${nowMs - (downSinceMs ?: nowMs)}ms, policy=$policy]"
            )
        }
        lastCauseKey = causeKey

        delay(delayMs)
    }
}

/**
 * Spreads [baseMs] by +/-[JITTER], bounded by [capMs].
 *
 * The bound is applied to the RANGE, not to the drawn value. Clamping the result instead
 * (`jitter(step).coerceAtMost(cap)`) silently destroys the jitter exactly where it matters:
 * once the backoff reaches the cap — the steady state of every persistent outage — the whole
 * upper half of the distribution collapses onto the cap, so half of all retries fire on the
 * same millisecond and the collectors a common failure knocked down together re-align. That
 * is precisely the stampede the jitter exists to prevent.
 */
private fun jitter(baseMs: Long, capMs: Long): Long {
    val lo = (baseMs * (1 - JITTER)).toLong().coerceAtLeast(1L)
    val hi = (baseMs * (1 + JITTER)).toLong().coerceAtMost(capMs)
    return if (hi <= lo) lo else Random.nextLong(lo, hi + 1)
}

fun KarooSystemService.streamDataFlow(
    dataTypeId: String,
    policy: StreamPolicy = StreamPolicy.CRITICAL,
    onInterrupted: () -> Unit = {},
): Flow<StreamState> = callbackFlow {
    val listenerId = addConsumer<OnStreamState>(
        params = OnStreamState.StartStreaming(dataTypeId),
        onError = { close(KarooStreamEnded.Error(it)) },
        onComplete = { close(KarooStreamEnded.Complete()) },
        onEvent = { trySend(it.state) },
    )
    awaitClose { removeConsumer(listenerId) }
}.retryKarooStream(dataTypeId, policy, onInterrupted = onInterrupted)


fun KarooSystemService.streamRide(): Flow<RideState> = callbackFlow {
    val listenerId = addConsumer<RideState>(
        onError = { close(KarooStreamEnded.Error(it)) },
        onComplete = { close(KarooStreamEnded.Complete()) },
        onEvent = { trySend(it) },
    )
    awaitClose { removeConsumer(listenerId) }
}.retryKarooStream("RIDE_STATE", StreamPolicy.CRITICAL)

fun KarooSystemService.streamLocation(): Flow<OnLocationChanged> = callbackFlow {
    val listenerId = addConsumer<OnLocationChanged>(
        onError = { close(KarooStreamEnded.Error(it)) },
        onComplete = { close(KarooStreamEnded.Complete()) },
        onEvent = { trySend(it) },
    )
    awaitClose { removeConsumer(listenerId) }
}.retryKarooStream("LOCATION", StreamPolicy.CRITICAL)

fun KarooSystemService.streamUserProfile(): Flow<UserProfile> = callbackFlow {
    val listenerId = addConsumer<UserProfile>(
        onError = { close(KarooStreamEnded.Error(it)) },
        onComplete = { close(KarooStreamEnded.Complete()) },
        onEvent = { trySend(it) },
    )
    awaitClose { removeConsumer(listenerId) }
}.retryKarooStream("USER_PROFILE", StreamPolicy.CRITICAL)

/**
 * Streams the currently active [RideProfile] — the profile the user selected on the launcher.
 * Emits immediately on subscription with the current profile, then on every profile change.
 *
 * Provides [RideProfile.routingPreference] (ROAD / GRAVEL / MTB) and
 * [RideProfile.defaultActivityType] (RIDE / MOUNTAIN_BIKE / GRAVEL / EBIKE / …).
 * Used to add ride-context to calibration logs and optionally pre-tune crash thresholds.
 *
 * @since Karoo SDK 1.1.5
 */
fun KarooSystemService.streamRideProfile(): Flow<RideProfile> = callbackFlow {
    val listenerId = addConsumer<ActiveRideProfile>(
        onError = { close(KarooStreamEnded.Error(it)) },
        onComplete = { close(KarooStreamEnded.Complete()) },
        onEvent = { trySend(it.profile) },
    )
    awaitClose { removeConsumer(listenerId) }
}.retryKarooStream("RIDE_PROFILE", StreamPolicy.CRITICAL)

/** Makes an HTTP request and returns the completed response. */
suspend fun KarooSystemService.httpRequest(
    method: String,
    url: String,
    headers: Map<String, String> = emptyMap(),
    body: ByteArray? = null,
): HttpResponseState.Complete {
    return callbackFlow {
        val listenerId = addConsumer<OnHttpResponse>(
            params = OnHttpResponse.MakeHttpRequest(method, url, headers, body),
            onEvent = { response ->
                if (response.state is HttpResponseState.Complete) {
                    trySend(response.state as HttpResponseState.Complete)
                    close()
                }
            }
        )
        awaitClose { removeConsumer(listenerId) }
    }.first()
}

/** Extracts speed in km/h from a SPEED StreamState.Streaming data point. */
fun StreamState.speedKmh(): Double? {
    if (this !is StreamState.Streaming) return null
    val raw = dataPoint.singleValue ?: return null
    return raw * 3.6 // m/s → km/h
}

/** Extracts cadence in RPM from a CADENCE StreamState.Streaming data point. */
fun StreamState.cadenceRpm(): Double? {
    if (this !is StreamState.Streaming) return null
    return dataPoint.singleValue  // already in RPM
}

/**
 * Extracts the current road grade in percent from an ELEVATION_GRADE StreamState.
 * Negative = downhill, positive = uphill. E.g. -8.0 = 8% descent.
 */
fun StreamState.gradePercent(): Double? {
    if (this !is StreamState.Streaming) return null
    return dataPoint.singleValue  // already in %
}

/**
 * Extracts heart rate in bpm from a HEART_RATE [StreamState.Streaming] data point.
 * Returns null when no rate is being streamed (no sensor paired, sensor disconnected,
 * or the SDK has not emitted yet). Mirrors [speedKmh] / [cadenceRpm] / [gradePercent].
 */
fun StreamState.heartRateBpm(): Int? {
    if (this !is StreamState.Streaming) return null
    return dataPoint.singleValue?.toInt()
}

/**
 * Extracts cycling power in watts from a POWER [StreamState.Streaming] data point.
 * Returns null when no power is being streamed (no power meter paired, sensor disconnected,
 * or the SDK has not emitted yet). Mirrors [speedKmh] / [cadenceRpm] / [gradePercent] / [heartRateBpm].
 */
fun StreamState.powerW(): Int? {
    if (this !is StreamState.Streaming) return null
    return dataPoint.singleValue?.toInt()
}

