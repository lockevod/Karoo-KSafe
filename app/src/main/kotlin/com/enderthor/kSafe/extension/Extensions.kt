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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

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

fun KarooSystemService.streamDataFlow(dataTypeId: String): Flow<StreamState> = callbackFlow {
    val listenerId = addConsumer<OnStreamState>(
        params = OnStreamState.StartStreaming(dataTypeId),
        onEvent = { trySend(it.state) }
    )
    awaitClose { removeConsumer(listenerId) }
}


fun KarooSystemService.streamRide(): Flow<RideState> = callbackFlow {
    val listenerId = addConsumer<RideState>(onEvent = { trySend(it) })
    awaitClose { removeConsumer(listenerId) }
}

fun KarooSystemService.streamLocation(): Flow<OnLocationChanged> = callbackFlow {
    val listenerId = addConsumer<OnLocationChanged>(onEvent = { trySend(it) })
    awaitClose { removeConsumer(listenerId) }
}

fun KarooSystemService.streamUserProfile(): Flow<UserProfile> = callbackFlow {
    val listenerId = addConsumer<UserProfile>(onEvent = { trySend(it) })
    awaitClose { removeConsumer(listenerId) }
}

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
    val listenerId = addConsumer<ActiveRideProfile>(onEvent = { trySend(it.profile) })
    awaitClose { removeConsumer(listenerId) }
}

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

