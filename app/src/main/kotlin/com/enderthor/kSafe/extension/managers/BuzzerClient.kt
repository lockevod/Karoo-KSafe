package com.enderthor.kSafe.extension.managers

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import timber.log.Timber

/**
 * Private-API client for the Karoo's physical buzzer.
 *
 * Binds [io.hammerhead.hal/.HIDLTranslationService] (exported, no permission required
 * on Karoo OS 1.527+) and invokes the AIDL `beep(List<BeeperCommand>)` transaction
 * directly via manual [Parcel] marshalling — no .aidl files needed.
 *
 * **This bypasses the Karoo's mute / AudioAlertSettings entirely.** Use only for
 * emergency-class events where being heard matters more than respecting the rider's
 * silence preference (crash detection ALERTING). Never for ride-start, check-in
 * confirmations, or other non-critical UI feedback.
 *
 * Caveats:
 *  - Private API. Hammerhead can break this in any OTA by adding `android:permission`
 *    on the service, an enforceCallingPermission in onTransact, or by renaming
 *    components. Every call is wrapped in try/catch; a SecurityException or
 *    RemoteException is logged and swallowed so the rest of KSafe continues working.
 *  - The transaction ID (14) and AIDL descriptor are pinned from the decompiled
 *    HAL APK (io.hammerhead.hal package, /vendor/priv-app/hh_hidl_aidl_nrf_translation_client).
 *    If a future OTA shuffles AIDL methods, the transaction ID changes and beep()
 *    becomes a no-op (or hits a different method); the catch keeps things safe.
 */
class BuzzerClient(private val context: Context) {

    @Volatile private var binder: IBinder? = null
    @Volatile private var bound: Boolean = false

    /** Outcome of the most recent [beep] call — used by callers (and the Test button)
     *  to detect whether Hammerhead has gated the bypass after a Karoo OTA, so they
     *  can fall back to the SDK PlayBeepPattern path. */
    @Volatile var lastResult: BeepResult = BeepResult.NEVER_CALLED
        private set

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            binder = service
            Timber.d("BuzzerClient connected to %s", name.flattenToShortString())
        }
        override fun onServiceDisconnected(name: ComponentName) {
            binder = null
            Timber.d("BuzzerClient service disconnected")
        }
        override fun onBindingDied(name: ComponentName) {
            binder = null
            Timber.w("BuzzerClient binding died")
        }
        override fun onNullBinding(name: ComponentName) {
            binder = null
            Timber.w("BuzzerClient onNullBinding — service refused to bind")
        }
    }

    /** True when we hold a live binder to the HAL service. */
    fun isReady(): Boolean = binder != null

    /**
     * Bind the HAL service. Idempotent; safe to call multiple times. Logs and
     * swallows any failure (SecurityException if a future OTA gates the service,
     * etc.) so the caller never sees an exception.
     *
     * @return a short diagnostic string describing the result — useful for the
     *   debug Test button in Settings. Successful bind reports "bind dispatched";
     *   onServiceConnected then flips [isReady] to true asynchronously.
     */
    fun connect(): String {
        if (bound) return "already bound"
        val pm = context.packageManager
        // Pre-flight: confirm we can even see the HAL package. On Android 11+,
        // package visibility (manifest <queries>) determines whether this resolves.
        try {
            pm.getPackageInfo(HAL_PACKAGE, 0)
        } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
            return "HAL package not visible — missing <queries> in manifest, or package not installed"
        }
        val intent = Intent().apply {
            component = ComponentName(HAL_PACKAGE, HAL_SERVICE)
        }
        // Verify the service component itself resolves before bindService — gives
        // a better error than the silent-false that bindService returns.
        val resolved = pm.resolveService(intent, 0)
        if (resolved == null) {
            return "HAL service does not resolve — not exported, or different name"
        }
        bound = try {
            context.applicationContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: SecurityException) {
            Timber.w(e, "BuzzerClient bind refused by SecurityException")
            return "SecurityException: ${e.message ?: "permission required"}"
        } catch (e: Exception) {
            Timber.w(e, "BuzzerClient bind failed")
            return "bind exception: ${e.javaClass.simpleName}: ${e.message}"
        }
        return if (bound) "bind dispatched (waiting for onServiceConnected)"
               else "bindService returned false — system refused"
    }

    fun disconnect() {
        if (!bound) return
        try { context.applicationContext.unbindService(connection) }
        catch (e: IllegalArgumentException) { /* not registered, ignore */ }
        catch (e: Exception) { Timber.w(e, "BuzzerClient unbind failed") }
        // B34 — clear `binder` BEFORE `bound`. A concurrent `beep()` reads `bound`
        // as the fast-path gate, then dereferences `binder`. Pre-B34 the order
        // (`bound = false` first, then `binder = null`) opened a narrow window
        // where a beep call between the two writes would observe `bound == false`
        // *AND* still see a non-null `binder` that's about to be torn down — or
        // observe `bound == false` and short-circuit cleanly. Order is reversed
        // so a beep racing with disconnect either sees `binder != null && bound ==
        // true` (transact runs against a still-live binder; service.unbind in
        // flight catches the result) OR `binder == null` (early-return).
        // `transact()` on a dead binder still throws → caught by the TRANSACT_THREW
        // branch and falls back to SDK; this swap removes the race symptom
        // ("transact threw on what looked like a live binder") rather than the
        // failure mode.
        binder = null
        bound = false
    }

    /**
     * Play a sequence of tones via the physical buzzer. Each [Tone] is
     * (frequencyHz, durationMs). frequencyHz=0 is silence (gap between tones).
     *
     * Returns true if the AIDL transaction was dispatched successfully (which is
     * NOT the same as "the buzzer actually emitted sound" — the HAL forwards to
     * native HIDL which forwards to the nRF chip, and any of those layers can
     * fail silently). Returns false if we have no binder or the transact threw.
     */
    fun beep(tones: List<Tone>): Boolean {
        if (tones.isEmpty()) return false
        val b = binder ?: run {
            Timber.d("BuzzerClient beep dropped — no binder")
            lastResult = BeepResult.BIND_NOT_READY
            return false
        }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(AIDL_DESCRIPTOR)
            // Parcel layout for writeTypedList(List<BeeperCommand>):
            //   writeInt(size); for each: writeInt(1)  // not null
            //                              writeInt(frequency)
            //                              writeInt(durationMs)
            data.writeInt(tones.size)
            for (t in tones) {
                data.writeInt(1)                  // non-null marker for writeTypedObject
                data.writeInt(t.frequencyHz)
                data.writeInt(t.durationMs)
            }
            val ok = b.transact(TRANSACTION_BEEP, data, reply, 0)
            reply.readException()
            lastResult = if (ok) BeepResult.SUCCESS else BeepResult.TRANSACT_RETURNED_FALSE
            ok
        } catch (e: SecurityException) {
            // Most likely sign of a Hammerhead OTA that added enforceCallingPermission
            // to the HAL service's onTransact. Caller should fall back to SDK beep.
            Timber.w(e, "BuzzerClient beep refused by SecurityException — bypass gated by OTA")
            lastResult = BeepResult.GATED_BY_SECURITY
            false
        } catch (e: Exception) {
            // Other failure modes: AIDL descriptor mismatch (Hammerhead renamed the
            // interface), transaction ID shift (new method inserted before beep), binder
            // died, Parcel layout mismatch (BeeperCommand fields reordered). Treat the
            // bypass as broken until the next process restart re-binds and re-tests.
            Timber.w(e, "BuzzerClient beep transact threw — bypass may be gated or shifted")
            lastResult = BeepResult.TRANSACT_THREW
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** Convenience: play a single tone. */
    fun beep(frequencyHz: Int, durationMs: Int): Boolean =
        beep(listOf(Tone(frequencyHz, durationMs)))

    data class Tone(val frequencyHz: Int, val durationMs: Int)

    /** Outcome of the most recent [beep] attempt. Persistable detector for OTA gating. */
    enum class BeepResult {
        /** [beep] has not been called yet on this client. */
        NEVER_CALLED,
        /** Transaction completed without throwing. The HAL forwarded to the nRF chip —
         *  but whether the chip actually emitted sound is not observable from here. */
        SUCCESS,
        /** No binder available — [connect] never succeeded, or [onServiceDisconnected]
         *  / [onBindingDied] cleared it. Caller falls back to SDK PlayBeepPattern. */
        BIND_NOT_READY,
        /** `binder.transact` returned false. Rare. Caller should fall back. */
        TRANSACT_RETURNED_FALSE,
        /** `binder.transact` threw [SecurityException] — Hammerhead has gated the call
         *  with `enforceCallingPermission` or a signature check. Caller MUST fall back. */
        GATED_BY_SECURITY,
        /** `binder.transact` or `readException` threw something else — descriptor /
         *  transaction-ID / Parcel layout drift from an OTA, or the binder died mid-call.
         *  Caller should fall back; the next process restart will re-bind and re-probe. */
        TRANSACT_THREW,
    }

    companion object {
        private const val HAL_PACKAGE = "io.hammerhead.hal"
        private const val HAL_SERVICE = "io.hammerhead.hal.HIDLTranslationService"
        private const val AIDL_DESCRIPTOR = "io.hammerhead.hal.service.IPhoneROMController"

        // From decompiled IPhoneROMController.Stub on Karoo OS 1.527+.
        // 1=init, 2=pollState, 3=pollIsBonded, 4=unpairPhone, 5=disconnect,
        // 6=power, 7=startAdvertising, 8=performNotificationAction,
        // 9=listenForNotificationAttributes, 10=startMessageChunk,
        // 11=sendMessageChunk, 12=messageChunkDone, 13=cancelMessageChunk,
        // 14=beep, 15=enableMediaPlayer, 16=performMediaAction.
        private const val TRANSACTION_BEEP = 14

        // ─── HAL beep patterns ──────────────────────────────────────────────
        //
        // B25-c — SDK ↔ HAL pairing map. Each HAL pattern below is invoked by
        // `EmergencyManager.playEmergencyBeep(config, sdkPattern, halPattern)`
        // alongside the SDK pattern listed; the two should convey the same
        // audible identity so a rider switching between muted (HAL) and
        // unmuted (SDK) hears the SAME lifecycle event the same way. Editing
        // one side without the other breaks audio-identity coherence and is
        // hard to spot in code review — always check the pairing table when
        // touching either side.
        //
        //   SDK (mute OFF)   ↔  HAL (mute ON, bypass enabled)
        //   ───────────────────────────────────────────────────────────────
        //   BEEP_LONG        ↔  COUNTDOWN_START     countdown initial beep
        //   BEEP_URGENT      ↔  COUNTDOWN_TICK      ≤5 s countdown ticks
        //   BEEP_LONG        ↔  EMERGENCY_PATTERN   sendAlerts final beep
        //   <descending 5T>  ↔  DELIVERY_FAILED_PATTERN   delivery-failure beep
        //
        // Frequency note: HAL patterns sit in the 2000–3000 Hz band because
        // the Karoo's hardware buzzer is a piezo transducer whose physics
        // favour those frequencies — going lower than ~1500 Hz produces a
        // weak, muffled output. The SDK pairings use lower frequencies
        // (600–880 Hz, 1100 Hz) which the system mixer routes through the
        // proper speaker. The audible "feel" therefore differs across
        // channels for the same lifecycle event, but the rhythmic shape
        // (single-tone / multi-tone-rising / multi-tone-descending) is
        // preserved so the rider's brain reads the same identity.

        /** Rising-urgency pattern (~960 ms) suitable for the ALERTING entry — only once,
         *  to signal "alerts are firing now". Two short bursts plus a longer climb so it
         *  cuts through ambient road noise even on a partly-occluded buzzer.
         *  Paired with SDK [PlayBeepPattern] `BEEP_LONG` at the sendAlerts callsite. */
        val EMERGENCY_PATTERN: List<Tone> = listOf(
            Tone(2500, 200),
            Tone(0, 80),
            Tone(2500, 200),
            Tone(0, 80),
            Tone(3000, 400),
        )

        /** Rising-urgency 3-tone burst (~520 ms) for the countdown ≤5 s ticks. Paired
         *  with SDK `BEEP_URGENT` (the multi-tone climbing SDK pattern); the rider
         *  needs the same "URGENCY rising" cue on both channels because the ≤5 s
         *  window is the LAST cancel opportunity before the alert fires. Pre-B25-b
         *  this was a single 200 ms tone — audibly identical to [COUNTDOWN_START]
         *  below, leaving the muted rider unable to distinguish "countdown began
         *  (30 s margin)" from "T-3 (act NOW)" by ear. Fits comfortably in the 1 s
         *  inter-tick gap so successive invocations don't step on each other when
         *  the buzzer is busy emitting the previous tick. */
        val COUNTDOWN_TICK: List<Tone> = listOf(
            Tone(2500, 100),
            Tone(0, 60),
            Tone(2500, 100),
            Tone(0, 60),
            Tone(3000, 200),
        )

        /** Single longer tone (~600 ms) for the countdown INITIAL beep — distinct
         *  from [COUNTDOWN_TICK] so a muted-Karoo rider can tell "the countdown
         *  has just started (full cancel window ahead)" from "we are in the final
         *  5 seconds (act NOW)" without looking at the screen. Same pitch family
         *  as the tick (2800 Hz) so the two read as related events; 3× the
         *  duration so it doesn't sound like just another tick. Paired with SDK
         *  `BEEP_LONG`. */
        val COUNTDOWN_START: List<Tone> = listOf(
            Tone(2800, 600),
        )

        /** Descending pattern (~1.2 s) for the "alert delivery failed" notification —
         *  audibly distinct from [EMERGENCY_PATTERN] (rising) so a rider on a muted
         *  Karoo can tell "alert fired" from "alert FAILED to fire" without looking
         *  at the screen. Mirrors the SDK-side `PlayBeepPattern` in
         *  `EmergencyManager.notifyDeliveryFailure` (600/500/400 Hz tones). */
        val DELIVERY_FAILED_PATTERN: List<Tone> = listOf(
            Tone(2400, 200),
            Tone(0, 80),
            Tone(2200, 200),
            Tone(0, 80),
            Tone(2000, 400),
        )

        /** A short single beep for the test button — must not be alarming. */
        val TEST_PATTERN: List<Tone> = listOf(
            Tone(2000, 150),
            Tone(0, 80),
            Tone(2500, 150),
        )
    }
}
