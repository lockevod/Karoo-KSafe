package com.enderthor.kSafe.extension.crash

/**
 * Speed-gated verification for an on-side confirm whose orientation was read mid-motion.
 * FN-safe by construction: silent-clears ONLY on sustained, fresh riding speed; ANY doubt
 * (speed below the floor, stale GPS, window not yet elapsed-and-clean) never clears — the
 * caller escalates to the normal cancellable countdown. The accelerometer is deliberately
 * NOT consulted here (it is the noisy signal that caused the FP).
 */
class MovingVigilance(private val thresholds: Thresholds) {

    enum class Outcome { PENDING, CLEAR, ESCALATE }

    private var armedAtMs: Long = NOT_ARMED

    val isArmed: Boolean get() = armedAtMs != NOT_ARMED

    fun arm(nowMs: Long) { armedAtMs = nowMs }

    fun reset() { armedAtMs = NOT_ARMED }

    /**
     * Drive once per sensor sample while armed. [speedFresh] must be `!isGpsStale(now)`.
     * Returns [Outcome.CLEAR]/[Outcome.ESCALATE] exactly once (self-resets), else PENDING.
     */
    fun onTick(nowMs: Long, speedKmh: Double, speedFresh: Boolean): Outcome {
        if (!isArmed) return Outcome.PENDING
        if (!speedFresh || speedKmh < thresholds.movingVigilanceSpeedKmh) {
            reset(); return Outcome.ESCALATE
        }
        if (nowMs - armedAtMs >= thresholds.movingVigilanceWindowMs) {
            reset(); return Outcome.CLEAR
        }
        return Outcome.PENDING
    }

    private companion object { const val NOT_ARMED = -1L }
}
