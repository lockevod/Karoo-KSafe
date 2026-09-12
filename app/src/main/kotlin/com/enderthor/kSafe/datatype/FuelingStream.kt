package com.enderthor.kSafe.datatype

import com.enderthor.kSafe.extension.KSafeExtension
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.combine
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
 *  - no active ride (Karoo idle / post-ride)  → [StreamState.Idle] — the trackers
 *    retain their accumulators after ride end (post-ride summary, cross-toggle
 *    restore), but a consumer must NOT receive last ride's totals as live data
 *  - extension still booting (no tracker yet) → [StreamState.Searching]
 *  - ride active but status never published   → [StreamState.NotAvailable]: the
 *    rideActiveFlow flips true only AFTER the Recording branch started the trackers
 *    (whose first status publish is synchronous), so a still-null status during an
 *    active ride means the tracker never started — master switch or every fueling
 *    feature off. (Transient corner: a service rebind landing mid-autopause reports
 *    NotAvailable until the rider resumes, because trackers start on Recording.)
 *  - master switch / feature toggle off       → [StreamState.NotAvailable] (view: OFF / `---`)
 *  - no usable sensor for the estimate        → [StreamState.Searching] (view: `Pair HR/Pwr`)
 *  - otherwise                                → [StreamState.Streaming] with the field's value
 *
 * Each DataType supplies only its status→state mapping; the boot suspension,
 * ride-state gate, collect loop and cancellation are identical across the family
 * and live here.
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
            // Combined (not read at collect time) so the stream flips to Idle on the
            // ride-end transition itself — the tracker stops emitting after stop(),
            // so a status-only collect would freeze on the last mid-ride snapshot.
            combine(statusFlowOf(tracker), KSafeExtension.rideActiveFlow) { status, rideActive ->
                when {
                    !rideActive -> StreamState.Idle
                    // Ride active + never-published status = the tracker didn't start
                    // (master / all fueling features off) — see the KDoc above.
                    status == null -> StreamState.NotAvailable
                    else -> mapState(status)
                }
            }
                // The seven numeric fueling fields all derive from ONE shared snapshot
                // (CarbStatus / HydrationStatus bundles deficit, burn rate, average,
                // zone, calories, enable state). Every tracker tick republishes the whole
                // snapshot, so without this each field emitted — and paid a Binder
                // round-trip — whenever ANY sibling value moved. StreamState.Streaming
                // and DataPoint both have value equality (DataPoint carries no timestamp),
                // so this compares the number the field actually shows.
                //
                // NOTE: this is a `startStream` emitter, not the `updateView` frame dedupe
                // the tap/graphical fields do — there the host latches the last RemoteViews
                // by construction. Here we rely on the host latching the last StreamState,
                // which the SDK does not document. A field whose value is genuinely constant
                // (hydration ml between drinks) now goes minutes without an emission where
                // it used to re-send every 15 s. Verified against karoo-ext 1.1.9: no TTL or
                // staleness concept on the stream API.
                .distinctUntilChanged()
                .collectLatest { emitter.onNext(it) }
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
