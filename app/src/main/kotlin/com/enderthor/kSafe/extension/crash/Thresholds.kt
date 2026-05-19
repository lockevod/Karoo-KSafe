package com.enderthor.kSafe.extension.crash

/**
 * All numeric tunables that the state machine reads. Lifted out of [CrashDetectionManager]
 * so unit tests can construct a known set without touching DataStore.
 *
 * Defaults match the **MEDIUM** sensitivity preset documented in
 * `docs/crash-detection-algorithm.md`. Production code in
 * [CrashDetectionManager.updateConfig] builds an instance from the active `KSafeConfig`.
 *
 * Source notes (each value):
 *  - `smoothedImpactThreshold = 45.0` — MEDIUM smoothed bar (doc rev 3 + production code).
 *  - `peakImpactThreshold = 50.0` — MEDIUM peak bar, `smoothedThr + 5` principle (doc rev 3).
 *  - `silenceDeviationMax = 4.0` — `SILENCE_DEVIATION_MAX` (doc + production code).
 *  - `silenceDurationMs = 4_500` — `SILENCE_DURATION_MS` (doc + production code).
 *  - `gpsStaleSilenceDeviationMax = 1.5` — `GPS_STALE_DEVIATION_MAX` (doc + production code).
 *  - `gpsStaleSilenceDurationMs = 8_000` — `GPS_STALE_SILENCE_DURATION_MS` (doc + production code).
 *  - `gyroMovingMax = 2.0` — `GYRO_MOVING_MAX` used as IMPACT → SILENCE_CHECK gate (doc + production).
 *  - `impactWindowMs = 20_000` — MEDIUM impact window (doc + production code).
 *  - `minSpeedForCrashKmh = 10` — MEDIUM `minSpeedForCrashKmh` default (doc + KSafeConfig default).
 *  - `crashConfirmSpeedKmh = 5` — MEDIUM `crashConfirmSpeedKmh` default (doc + KSafeConfig default).
 *  - `minTimeSinceImpactMs = 500` — hardcoded in doc + production code.
 *  - `coldStartGuardMs = 8_000` — `COLD_START_GUARD_MS` (doc + production code).
 *  - `gpsStaleThresholdMs = 10_000` — `GPS_STALE_MS` (doc + production code).
 *  - `cadenceQuietThresholdRpm = 20.0` — doc Revision 4 C5: "cadence > 20 RPM" gate.
 *  - `cadenceStaleThresholdMs = 10_000` — picked to mirror `gpsStaleThresholdMs`; the doc
 *     does not pin this value but treats stale cadence as absent.
 */
data class Thresholds(
    // ── MONITORING → IMPACT spike thresholds ─────────────────────────────────
    val smoothedImpactThreshold: Double = 45.0,
    val peakImpactThreshold: Double = 50.0,

    // ── IMPACT → SILENCE_CHECK entry (GPS-fresh thresholds) ──────────────────
    val silenceDeviationMax: Double = 4.0,
    val silenceDurationMs: Long = 4_500L,
    val gyroMovingMax: Double = 2.0,

    // ── GPS-stale fallback thresholds (hardened) ─────────────────────────────
    val gpsStaleSilenceDeviationMax: Double = 1.5,
    val gpsStaleSilenceDurationMs: Long = 8_000L,

    // ── Timing windows ───────────────────────────────────────────────────────
    val impactWindowMs: Long = 20_000L,
    val minTimeSinceImpactMs: Long = 500L,

    // ── Speed / GPS gates ────────────────────────────────────────────────────
    val minSpeedForCrashKmh: Int = 10,
    val crashConfirmSpeedKmh: Int = 5,
    val coldStartGuardMs: Long = 8_000L,
    val gpsStaleThresholdMs: Long = 10_000L,

    // ── Cadence gate (revision 4 C5) ─────────────────────────────────────────
    val cadenceQuietThresholdRpm: Double = 20.0,
    val cadenceStaleThresholdMs: Long = 10_000L,

    // ── Orientation-aware silence (revision 5) ───────────────────────────────
    /**
     * Silence duration to require when the bike is detected as still upright
     * (gravity vector within [uprightAngleThresholdDegrees] of the learned
     * baseline). Designed to eliminate the bump+brake+stop false positive: a
     * rider who stops upright at a light will almost always shift weight,
     * tilt the bike to put a foot down, or interact with the device within
     * this window — any motion > [silenceDeviationMax] resets the silence
     * clock. An unconscious rider with the bike pinned upright still
     * confirms, just delayed by ~15s vs the on-side path.
     */
    val silenceDurationUprightMs: Long = 20_000L,
    /**
     * Angle (degrees) between the current gravity vector and the learned
     * baseline below which the bike is classified as "still upright" and
     * the longer [silenceDurationUprightMs] applies. > this → on-side or
     * tilted significantly → keep the standard [silenceDurationMs].
     */
    val uprightAngleThresholdDegrees: Double = 45.0,
    /**
     * Minimum number of qualifying samples (cruising conditions) the state
     * machine must accumulate before the baseline is considered ready. At
     * ~50 Hz this is ~30 seconds of cruising. Until then the orientation
     * gate is bypassed and the legacy silence threshold applies (no
     * detection regression on a freshly started ride).
     */
    val baselineMinSamples: Int = 1_500,
    /**
     * Minimum speed (km/h) for a sample to qualify as a baseline-learning
     * candidate. Below this the rider is stopped or rolling slowly; the
     * bike may be leaning (foot down) and the captured gravity vector
     * would corrupt the upright reference.
     */
    val baselineCruisingMinSpeedKmh: Int = 15,
)
