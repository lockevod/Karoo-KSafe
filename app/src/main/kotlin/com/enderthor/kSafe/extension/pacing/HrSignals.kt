package com.enderthor.kSafe.extension.pacing

/** Heart-rate signals for pacing. Mechanical work estimated from heart rate, for riders without fresh power: Keytel kcal/h integrated over
 *  moving seconds and read as kJ (1 kcal metabolic ≈ 1 kJ mechanical at ~24 % gross efficiency). Kept apart
 *  from the power work in [FatigueSignals]; the two are never mixed. A null rate (Keytel inputs missing) is
 *  not counted, so [seconds] / moving seconds is the estimate's coverage. */
class HrSignals {
    private class Sample(val atMovingS: Double, val dt: Double, val bpm: Int)
    /** Moving HR samples of the last [WINDOW_S] moving seconds, for [meanHrLastWindow]. */
    private val window = ArrayDeque<Sample>()

    var kj = 0.0; private set
    var seconds = 0.0; private set
    val meanW: Double get() = if (seconds > 0) kj * 1000.0 / seconds else 0.0
    private var lastMs = -1L

    /** [movingS] = the ride's moving seconds now (the window clock). */
    fun onSample(bpm: Int, kcalPerHour: Double?, tMs: Long, moving: Boolean, movingS: Double) {
        val dt = if (lastMs < 0) 0.0 else (tMs - lastMs).coerceIn(0, 5_000) / 1000.0
        lastMs = tMs
        if (!moving || dt <= 0) return
        window.addLast(Sample(movingS, dt, bpm))
        while (window.first().atMovingS <= movingS - WINDOW_S) window.removeFirst()
        if (kcalPerHour == null) return
        seconds += dt; kj += kcalPerHour * dt / 3600.0
    }

    /** Mean HR over the last 5 moving minutes; null unless HR covers ≥ 90 % of them (a gap is unknown, not 0). */
    fun meanHrLastWindow(movingS: Double): Double? {
        var sec = 0.0; var sum = 0.0
        for (x in window) if (x.atMovingS > movingS - WINDOW_S) { sec += x.dt; sum += x.bpm * x.dt }
        return if (sec >= MIN_WINDOW_COVERAGE * WINDOW_S) sum / sec else null
    }

    companion object {
        const val WINDOW_S = 300.0
        const val MIN_WINDOW_COVERAGE = 0.9
    }
}
