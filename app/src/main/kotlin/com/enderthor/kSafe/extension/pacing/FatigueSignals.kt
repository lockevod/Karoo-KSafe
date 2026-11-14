package com.enderthor.kSafe.extension.pacing

/** Pw:HR decoupling on matched-intensity 5-min windows + moving work. Independent of WellnessMonitor
 *  (which is off by default, uses instantaneous HR/W and holds stale values). Unknown → null, never 0. */
class FatigueSignals(private val ftpW: Int, private val weightKg: Double) {
    var kjPerKg = 0.0; private set
    var movingSeconds = 0.0; private set
    var kjPerKgAtFirstDrift5: Double? = null; private set
    var driftStableSinceMs: Long? = null; private set
    val meanMovingPowerW: Double get() = if (movingSeconds > 0) kjPerKg * weightKg * 1000.0 / movingSeconds else 0.0

    private var lastMs = -1L
    private var winSec = 0.0; private var winP = 0.0; private var winHr = 0.0; private var winHrSec = 0.0
    private var baseline: Double? = null
    private var lastDrift: Double? = null; private var lastDriftAtMs = 0L

    fun onSample(powerW: Int, hrBpm: Int?, tMs: Long, moving: Boolean) {
        val dt = if (lastMs < 0) 0.0 else ((tMs - lastMs).coerceIn(0, 5_000)) / 1000.0
        lastMs = tMs
        if (!moving || dt <= 0) return
        movingSeconds += dt
        if (weightKg > 0) kjPerKg += powerW * dt / 1000.0 / weightKg
        winSec += dt; winP += powerW * dt
        if (hrBpm != null && hrBpm > 0) { winHr += hrBpm * dt; winHrSec += dt }
        if (winSec >= WINDOW_S) closeWindow(tMs)
    }

    private fun closeWindow(tMs: Long) {
        val meanP = winP / winSec
        // Mean HR over the seconds that had HR only; a window with too little HR is discarded, not read as 0.
        val hrComplete = winHrSec >= HR_COMPLETE_FRACTION * winSec
        val meanHr = if (hrComplete) winHr / winHrSec else 0.0
        winSec = 0.0; winP = 0.0; winHr = 0.0; winHrSec = 0.0
        // "Stable" means consecutive fresh windows: any window without a drift value breaks the run.
        if (ftpW <= 0 || !hrComplete || meanHr <= 0 || meanP < LOW * ftpW || meanP > HIGH * ftpW) { driftStableSinceMs = null; return }
        val pwhr = meanP / meanHr
        val b = baseline
        if (b == null) { if (movingSeconds >= BASELINE_AFTER_S) baseline = pwhr; driftStableSinceMs = null; return }
        val d = (b - pwhr) / b * 100.0
        if (lastDrift == null || tMs - lastDriftAtMs > MAX_AGE_MS) driftStableSinceMs = null
        lastDrift = d; lastDriftAtMs = tMs
        if (d > 5.0 && kjPerKgAtFirstDrift5 == null) kjPerKgAtFirstDrift5 = kjPerKg
        driftStableSinceMs = if (d < 5.0) (driftStableSinceMs ?: tMs) else null
    }

    fun driftPct(nowMs: Long): Double? =
        lastDrift?.takeIf { nowMs - lastDriftAtMs <= MAX_AGE_MS }

    companion object {
        const val WINDOW_S = 300.0
        const val BASELINE_AFTER_S = 900.0
        const val MAX_AGE_MS = 360_000L
        const val LOW = 0.60
        const val HIGH = 0.80
        const val HR_COMPLETE_FRACTION = 0.9
    }
}
