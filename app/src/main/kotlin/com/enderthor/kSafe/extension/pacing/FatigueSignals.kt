package com.enderthor.kSafe.extension.pacing

/** Pw:HR decoupling on matched-intensity 5-min windows + moving work. Independent of WellnessMonitor
 *  (which is off by default, uses instantaneous HR/W and holds stale values). Unknown → null, never 0.
 *  Work is kept in ABSOLUTE kJ so a mid-ride weight edit rescales kJ/kg instead of losing the ride. */
class FatigueSignals(private var ftpW: Int, weightKg: Double) {
    /** Settable mid-ride: kJ/kg is derived at read time. */
    var weightKg: Double = weightKg
    var kj = 0.0; private set
    var movingSeconds = 0.0; private set
    var driftStableSinceMs: Long? = null; private set
    private var kjAtFirstDrift5: Double? = null
    /** 0 when the weight is unknown; loggers print `na` for that case. */
    val kjPerKg: Double get() = if (weightKg > 0) kj / weightKg else 0.0
    val kjPerKgAtFirstDrift5: Double? get() = kjAtFirstDrift5?.takeIf { weightKg > 0 }?.div(weightKg)
    val meanMovingPowerW: Double get() = if (movingSeconds > 0) kj * 1000.0 / movingSeconds else 0.0

    private var lastMs = -1L
    private var winSec = 0.0; private var winP = 0.0; private var winHr = 0.0; private var winHrSec = 0.0
    private var baseline: Double? = null
    private var lastDrift: Double? = null; private var lastDriftAtMs = 0L

    fun onSample(powerW: Int, hrBpm: Int?, tMs: Long, moving: Boolean) {
        val dt = if (lastMs < 0) 0.0 else ((tMs - lastMs).coerceIn(0, 5_000)) / 1000.0
        lastMs = tMs
        if (!moving || dt <= 0) return
        movingSeconds += dt
        kj += powerW * dt / 1000.0
        winSec += dt; winP += powerW * dt
        if (hrBpm != null && hrBpm > 0) { winHr += hrBpm * dt; winHrSec += dt }
        if (winSec >= WINDOW_S) closeWindow(tMs)
    }

    /** New FTP = new matched-intensity band: drop the baseline and drift, keep work and moving time. */
    fun onFtpChanged(ftp: Int) {
        ftpW = ftp
        baseline = null; lastDrift = null; driftStableSinceMs = null
        winSec = 0.0; winP = 0.0; winHr = 0.0; winHrSec = 0.0
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
        if (d > 5.0 && kjAtFirstDrift5 == null) kjAtFirstDrift5 = kj
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
