package com.enderthor.kSafe.extension.pacing

/** Mechanical work estimated from heart rate, for riders without fresh power: Keytel kcal/h integrated over
 *  moving seconds and read as kJ (1 kcal metabolic ≈ 1 kJ mechanical at ~24 % gross efficiency). Kept apart
 *  from the power work in [FatigueSignals]; the two are never mixed. A null rate (Keytel inputs missing) is
 *  not counted, so [seconds] / moving seconds is the estimate's coverage. */
class HrSignals {
    var kj = 0.0; private set
    var seconds = 0.0; private set
    val meanW: Double get() = if (seconds > 0) kj * 1000.0 / seconds else 0.0
    private var lastMs = -1L

    fun onSample(kcalPerHour: Double?, tMs: Long, moving: Boolean) {
        val dt = if (lastMs < 0) 0.0 else (tMs - lastMs).coerceIn(0, 5_000) / 1000.0
        lastMs = tMs
        if (!moving || dt <= 0 || kcalPerHour == null) return
        seconds += dt; kj += kcalPerHour * dt / 3600.0
    }
}
