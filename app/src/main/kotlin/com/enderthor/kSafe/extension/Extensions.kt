package com.enderthor.kSafe.extension

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.HttpResponseState
import io.hammerhead.karooext.models.OnHttpResponse
import io.hammerhead.karooext.models.OnLocationChanged
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UserProfile
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

val jsonWithUnknownKeys = Json { ignoreUnknownKeys = true }

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

fun KarooSystemService.streamDataFlow(dataTypeId: String): Flow<StreamState> = callbackFlow {
    val listenerId = addConsumer<OnStreamState>(
        params = OnStreamState.StartStreaming(dataTypeId),
        onEvent = { trySend(it.state) }
    )
    awaitClose { removeConsumer(listenerId) }
}

fun KarooSystemService.streamDataMonitorFlow(dataTypeId: String): Flow<StreamState> =
    streamDataFlow(dataTypeId)

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
