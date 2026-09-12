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

    // ── Delayed-stop gap (pre-impact orientation revision) ───────────────────
    /**
     * Gap (ms) between the impact and first stillness above which the stop is
     * treated as "delayed" — the rider kept riding after the impact, so the
     * event is false-positive-prone and the long [silenceDurationUprightMs]
     * window is required regardless of orientation. A real crash stops within
     * 1–4 s of the impact, comfortably below this. See the design spec.
     */
    val delayedStopGapMs: Long = 8_000L,

    // ── Orientation-aware silence (pre-impact orientation revision) ──────────
    /**
     * Silence duration to require when the bike is detected as still upright —
     * i.e. the silence-window gravity vector is within
     * [uprightAngleThresholdDegrees] of the pre-impact orientation reference
     * captured in the ~2 s before the impact. Designed to eliminate the
     * bump+brake+stop false positive: a rider who stops upright at a light
     * will almost always shift weight, tilt the bike to put a foot down, or
     * interact with the device within this window — any motion >
     * [silenceDeviationMax] resets the silence clock.
     *
     * Residual FP coverage (R6-G, 2026-06-03): a rider who instead stands
     * perfectly motionless and upright for the full window slips through the
     * "they'll move" assumption (session `effa0e`). That case is now vetoed —
     * see [nonGapUprightVetoMaxGyroRadS]. This does NOT weaken real-crash
     * coverage: an incapacitated rider cannot keep a laterally-unstable bike
     * balanced within the veto cones — 15° [promptVetoUprightAngleDeg] for the
     * prompt-stop regime (R6-G) and 25° [gapVetoUprightAngleDeg] for the gap
     * regime (R6-F) — so it topples on-side (≥ cone → confirms) or tumbles (high
     * gyro → confirms). Only the balanced-conscious upright stand (low gyro, ≈0°
     * tilt) is suppressed.
     */
    val silenceDurationUprightMs: Long = 20_000L,
    /**
     * Angle (degrees) between the silence-window gravity vector and the
     * pre-impact orientation reference below which the bike is classified as
     * "still upright" and the longer [silenceDurationUprightMs] applies.
     * > this → on-side or tilted significantly → keep the standard
     * [silenceDurationMs].
     */
    val uprightAngleThresholdDegrees: Double = 45.0,
    /**
     * Angle (degrees) below which the GAP-regime confirm is VETOED (R6-F).
     *
     * Deliberately MUCH tighter than [uprightAngleThresholdDegrees] (45°): that
     * one is a *timing* threshold (how long to wait before confirming — both
     * sides still confirm), whereas this one is a *fire / don't-fire* threshold
     * that SUPPRESSES an SOS. A false negative (missing a real crash) is far
     * worse than a false positive, so the veto only engages when the bike is
     * almost identical to its pre-impact orientation.
     *
     * Widened from 15° to 25° after field evidence (session 2ab57f): a settled
     * bike at ~18.6° from upright produced a false positive with the old 15°
     * cone. Domain rationale: a real crash always tips the bike well past 25°;
     * vetoing up to 25° at rest has negligible FN risk. A bike merely
     * tilted/knocked to 25–45° is left to confirm: that posture is consistent
     * with a real crash and must NOT be vetoed.
     *
     * Only consulted in the gap regime (`firstSilenceGapMs > delayedStopGapMs`);
     * the prompt-stop regime uses the stricter [promptVetoUprightAngleDeg].
     */
    val gapVetoUprightAngleDeg: Double = 25.0,
    /**
     * PROMPT-regime (prompt-stop) upright veto cone. Kept at the original tight 15° while the
     * GAP-regime cone is 25°: a prompt stop (rider stopped quickly post-impact) is more
     * crash-like than a gap stop (rider rode on = conscious), so it gets the stricter cone.
     * The widening to 25° has field evidence only in the GAP regime (session 2ab57f).
     */
    val promptVetoUprightAngleDeg: Double = 15.0,
    /**
     * Peak gyroscope magnitude (rad/s, measured from the impact through the
     * silence window) below which the **non-gap (prompt-stop) upright veto**
     * (R6-G) may engage. The gap-regime veto (R6-F) ignores this — there the
     * >8 s impact→stillness gap already proves the rider kept riding, so an
     * upright stop is benign regardless of rotation.
     *
     * R6-G extends the upright veto to the prompt-stop regime (gap ≤
     * [delayedStopGapMs]) to kill the bump→brake→stand-still-upright FP
     * (2026-06-03 session `effa0e`, peak gyro ≈ 1.7 rad/s). The prompt stop is
     * the more crash-like regime, so the veto here demands an EXTRA proof of
     * benignity: no violent rotation. A real over-the-bars / endo that happens
     * to leave the bike wheels-down (≈ upright) spikes the gyro well above this
     * (the 2026-06-03 on-side crash `27baa0` hit 9.65 rad/s) and is therefore
     * NOT vetoed. A toppled-on-side crash is already excluded by the upright
     * veto cone (15° [promptVetoUprightAngleDeg] in this prompt-stop regime).
     * Set below the tumble range and above the
     * benign-stop range; a false negative is far worse than a false positive,
     * so keep it low (veto only when rotation was clearly minimal).
     *
     * Only consulted in the prompt-stop regime; the gap regime is unaffected.
     */
    val nonGapUprightVetoMaxGyroRadS: Double = 3.0,
    /**
     * Angle (degrees) from the pre-impact reference above which the bike is
     * considered "decisively on the ground" — used to gate the speed-rise
     * relaxation in SILENCE_CHECK. Stricter than [uprightAngleThresholdDegrees]
     * (45°) so that bikes merely tilted (leaned against something, partial
     * fall, rider walking the bike) do not get the speed gate relaxed. Real
     * crashes that leave the bike flat are at 80–95°; a value of 60° captures
     * these while excluding partial leans.
     */
    val onSideRelaxationAngleDeg: Double = 60.0,
    /**
     * Speed (km/h) ceiling above which the IMPACT-phase on-side speed-rise
     * relaxation does NOT fire (GPS-stale bypasses this ceiling — when GPS is
     * unreliable the rolling-bike speed reading isn't trustworthy and the
     * orientation evidence is allowed to carry the decision alone).
     *
     * **Why this exists** (FP-2 from the 2026-05-25 ride log, elapsed 14141.2 s):
     * the relaxation's stated purpose is "the bike has escaped the downed rider
     * and is rolling" — a scenario that decelerates within seconds. Without a
     * speed ceiling, a marginal SMOOTH-source impact at high sustained speed
     * on a rough descent (rider leaning forward — pre-impact reference was
     * upright at the moment the impact crossed the smoothed gate, but the
     * 2.2 s IMPACT phase accumulated ≥25 samples at 75° vs that reference
     * because of sustained forward lean) can engage the relaxation. The
     * LEGACY 4.5 s window then collapses with CR2 on-side bump preservation
     * absorbing all the descent bumps. CRASH_OK fires while the rider is
     * still riding at 36 km/h.
     *
     * Empirically from the same ride: ALL real falls (4 events) had IMPACT
     * speeds < 25 km/h (8.9 / 6.7 / 5.8 / 15.0). The FP sat at 34.7 km/h
     * sustained — a 10 km/h gap of clean separation between real-crash speeds
     * and false-alarm-prone speeds. 25 km/h is the chosen ceiling.
     *
     * **Why this doesn't cause FN**:
     *   - A real fall at < 25 km/h IMPACT: relaxation still available.
     *   - A real fall at ≥ 25 km/h where the bike stops promptly: `speedDropOk`
     *     opens the gate via the legitimate speed-drop path (no relaxation
     *     needed — the rider is on the ground next to a stationary bike).
     *   - A real fall at ≥ 25 km/h with GPS stale (rider unconscious in
     *     thick cover): bypass clause re-enables the relaxation.
     *   - A real fall at ≥ 25 km/h with GPS fresh and the bike rolling at
     *     ≥ 25 km/h alongside the downed rider: bike escapes very quickly,
     *     speed drops below 25 within seconds → relaxation re-enables. The
     *     residual gap is "bike rolling alongside rider at ≥ 25 km/h for
     *     the entire impactWindowMs (20 s)" — physically implausible.
     *   - The SpeedDropMonitor watchdog (independent backstop) catches
     *     long-duration speed=0 windows regardless of this gate.
     */
    val onSideRelaxationMaxSpeedKmh: Double = 25.0,

    /**
     * Moving-vigilance trust gate. An on-side confirm's angle is only trustworthy when the
     * settled silence orientation was sampled AT REST: a bike at rest reads ‖a‖ ≈ GRAVITY
     * (9.81). Field FPs read far below it (8.49, 1.78 m/s²) because the angle was sampled
     * mid-motion. Below this floor, the confirm is diverted into a speed-gated window instead
     * of firing. Default ≈ 0.90 × GRAVITY.
     */
    val onSideTrustMinAccel: Double = 8.83,

    /** Moving-vigilance window: how long sustained riding speed must hold to silent-clear. */
    val movingVigilanceWindowMs: Long = 4_000L,

    /**
     * Moving-vigilance "clearly still riding" speed floor (km/h). Speed must stay at/above this
     * on every sample to silent-clear — a breach escalates on the sample that sees it.
     *
     * Freshness is NOT judged per sample: since 2.2.3 the staleness verdict is taken once, at
     * window end, so a speed feed that goes quiet and recovers inside the window now clears
     * where it used to escalate. That is a deliberately spent false-negative budget, not a
     * fail-safe property — the old "no FN" wording here was wrong. Two guards bound it: a
     * confirm whose speed is ALREADY stale never enters the window at all (it escalates
     * immediately), and a window that reaches its end with stale speed escalates. See
     * [MovingVigilance] and `CrashDetectionManager.driveMovingVigilance`.
     */
    val movingVigilanceSpeedKmh: Double = 8.0,

    /**
     * Moving-vigilance speed-freshness recency. The CLEAR path requires the speed VALUE to have
     * changed within this window — shorter than [movingVigilanceWindowMs]. A GPS frozen at a
     * non-zero value after a crash reads "not stale" for up to GPS_STALE_MS (10 s) but its value
     * stops changing; this catches that (escalates) before the 4 s vigilance window can clear.
     */
    val movingVigilanceSpeedFreshMs: Long = 3_000L,
)
