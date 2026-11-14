package com.enderthor.kSafe.extension.pacing

enum class AlertSource(val priority: Int) { EMERGENCY(0), MEDICAL(1), W_PRIME(2), PACING(3), FUEL_HINT(4), FUEL_DEFICIT(5), FUEL_TIME(6) }
data class AlertCandidate(val source: AlertSource, val key: String, val severity: Double)
data class ArbiterVerdict(val fire: Boolean, val suppressedBy: String?)

/** Cross-source arbitration (spec §3.8). Drop, never queue. Lower priority number = more important. */
class AlertArbiter {
    private val lastBySource = HashMap<AlertSource, Long>()
    private val lastByKey = HashMap<String, Pair<Long, Double>>()
    private var lastPacing: Triple<Long, String, Double>? = null

    fun evaluate(c: AlertCandidate, nowMs: Long, emergencyActive: Boolean): ArbiterVerdict {
        if (emergencyActive && c.source != AlertSource.EMERGENCY) return ArbiterVerdict(false, "emergency")
        // Pacing / W′ advice keeps GAP_MS after ANY alert the rider saw; other sources only after a more important one.
        val pacing = c.source == AlertSource.PACING || c.source == AlertSource.W_PRIME
        for ((src, at) in lastBySource) if ((pacing || (src != c.source && src.priority <= c.source.priority)) && nowMs - at < GAP_MS)
            return ArbiterVerdict(false, "gap:${src.name}")
        if (pacing) {
            lastPacing?.let { (at, key, sev) ->
                val harderLaterBlock = block(key) != block(c.key) && c.severity > sev
                if (nowMs - at < PACING_RATE_MS && !harderLaterBlock) return ArbiterVerdict(false, "pacing_rate")
            }
        }
        lastByKey[c.key]?.let { (at, sev) ->
            if (nowMs - at < REPEAT_MS && c.severity < sev + WORSE_DELTA) return ArbiterVerdict(false, "repeat")
        }
        return ArbiterVerdict(true, null)
    }

    fun recordFired(source: AlertSource, key: String, severity: Double, nowMs: Long) {
        lastBySource[source] = nowMs; lastByKey[key] = nowMs to severity
        if (source == AlertSource.PACING || source == AlertSource.W_PRIME) lastPacing = Triple(nowMs, key, severity)
    }

    /** An alert fired by another subsystem (carbs, hydration, medical, wellness). */
    fun recordExternal(source: AlertSource, nowMs: Long) { lastBySource[source] = nowMs }

    private fun block(key: String) = key.substringAfter('#', "")

    companion object {
        const val GAP_MS = 3 * 60_000L
        const val PACING_RATE_MS = 10 * 60_000L
        const val REPEAT_MS = 30 * 60_000L
        const val WORSE_DELTA = 0.1
    }
}
