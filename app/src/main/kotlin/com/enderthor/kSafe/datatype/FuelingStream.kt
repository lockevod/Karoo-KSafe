package com.enderthor.kSafe.datatype

import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Shared `startStream` plumbing for the fueling fields, so other extensions (and the
 * Karoo itself) can consume their values as a numeric stream —
 * `streamDataFlow("TYPE_EXT::ksafe::<typeId>")` — the same inter-extension composition
 * pattern Headwind uses to publish wind data.
 *
 * The host only starts the stream when something subscribes to it, so this costs
 * nothing while no consumer exists. Stream-state semantics mirror the view rules:
 *
 *  - tracker not published yet / status null  → [StreamState.Searching] (view: `---`)
 *  - master switch / feature toggle off       → [StreamState.NotAvailable] (view: OFF / `---`)
 *  - no usable sensor for the estimate        → [StreamState.Searching] (view: `Pair HR/Pwr`)
 *  - otherwise                                → [StreamState.Streaming] with the field's value
 *
 * Each DataType supplies only its status→state mapping; the boot suspension,
 * collect loop and cancellation are identical across the family and live here.
 */
internal fun <T : Any, S : Any> DataTypeImpl.startFuelingStream(
    emitter: Emitter<StreamState>,
    trackerFlow: StateFlow<T?>,
    statusFlowOf: (T) -> StateFlow<S?>,
    mapState: (S) -> StreamState,
) {
    val scopeJob = Job()
    val scope = CoroutineScope(Dispatchers.Default + scopeJob)
    scope.launch {
        try {
            // Searching until the extension finishes booting and publishes the tracker
            // (one suspension on the published reference — same pattern as the views).
            emitter.onNext(StreamState.Searching)
            val tracker = trackerFlow.filterNotNull().first()
            statusFlowOf(tracker).collectLatest { status ->
                emitter.onNext(status?.let(mapState) ?: StreamState.Searching)
            }
        } catch (_: CancellationException) {
            // normal
        } catch (e: Exception) {
            Timber.e(e, "$dataTypeId stream error: ${e.message}")
        }
    }
    emitter.setCancellable {
        scope.cancel()
        scopeJob.cancel()
    }
}

/** Single-value [StreamState.Streaming] for this data type (standard numeric field shape). */
internal fun DataTypeImpl.streamingSingle(value: Int): StreamState =
    StreamState.Streaming(
        DataPoint(dataTypeId, values = mapOf(DataType.Field.SINGLE to value.toDouble())),
    )
