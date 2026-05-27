package com.enderthor.kSafe.extension.util

import com.enderthor.kSafe.data.WellnessHistory

/**
 * Categorical readiness level surfaced to the rider on the first Recording transition
 * of a session.
 *
 * Deliberately coarse (3 levels) rather than a 0-100 score: KSafe's wellness data is
 * a handful of signals from one HR strap, not a multimodal recovery model. Pretending
 * to a precise score would be false confidence.
 */
enum class ReadinessLevel { RECOVERED, CAUTION, TAKE_IT_EASY }

/**
 * Structured reason for a [ReadinessAdvice]. Carries the numeric payload only —
 * rendering to a human-readable string is done at the UI boundary
 * (`KSafeExtension.fireReadinessAdvice`) via Android string resources, so this
 * decision layer stays pure and localisation-ready.
 *
 * B26: introduced when Copilot flagged that hardcoded English advice text inside
 * the pure decision function made future translation impossible and mixed
 * presentation into the algorithmic layer.
 */
sealed class ReadinessReason {
    /** Cardiac drift over 10 % on the most recent ride. [percent] is the actual drift. */
    data class CardiacDrift(val percent: Float) : ReadinessReason()
    /** ≥ 2 wellness alerts fired on the most recent ride. [count] is the total. */
    data class WellnessAlerts(val count: Int) : ReadinessReason()
    /** ≥ 10 minutes spent above the critical HR threshold on the most recent ride. */
    data class MinutesAboveCritical(val minutes: Int) : ReadinessReason()
    /** ≥ 3 rides recorded in the last 72 hours (training-load saturation signal). */
    data class RidesIn72h(val count: Int) : ReadinessReason()
}

/**
 * One readiness recommendation — `level` plus the structured [reasons] (rendered to
 * localised strings at the UI boundary).
 */
data class ReadinessAdvice(
    val level: ReadinessLevel,
    val reasons: List<ReadinessReason>,
)

/**
 * Pure decision function. Returns `null` when there is nothing to say to the rider —
 * either the history is empty (first ride after install) or none of the warning rules
 * fires AND the silence-on-RECOVERED policy applies. The surface code treats `null` as
 * "do not fire any alert".
 *
 * Inputs:
 *   - [history] — the rolling wellness ride history (max 10 records, newest first)
 *   - [nowMs]   — current wall-clock time, injected so unit tests are deterministic
 *
 * Output: null OR a [ReadinessAdvice] with the strongest applicable level.
 *
 * Rules (evaluated in order; first match wins, except RECOVERED which is the fallback):
 *
 *   1. Most-recent ride within 24 h AND `maxDriftPct >= 10 %`           → TAKE_IT_EASY
 *   2. Most-recent ride within 24 h AND `totalFires >= 2`               → CAUTION
 *   3. Most-recent ride within 24 h AND time-above-critical ≥ 10 min    → CAUTION
 *   4. ≥ 3 rides whose endedAtMs is within the last 72 h                → CAUTION
 *   5. Otherwise (or no recent rides)                                   → null  (silent)
 *
 * Edge cases:
 *   - Empty history                                  → null
 *   - Most-recent ride > 24 h ago, and rule 4 false → null  (data too stale to advise on)
 */
fun decideReadiness(history: WellnessHistory, nowMs: Long): ReadinessAdvice? {
    val newest = history.records.firstOrNull() ?: return null

    val ageMs = nowMs - newest.endedAtMs
    // Guard against negative ages (wall-clock jumped backwards, e.g. NTP correction
    // after device boot or user changing the date). Without `>= 0` the predicate
    // would count records whose endedAtMs is in the FUTURE as "within last 72h"
    // and could spuriously trigger the 3-rides-in-72h CAUTION rule.
    val ridesWithin72h = history.records.count {
        val age = nowMs - it.endedAtMs
        age in 0..72L * 3_600_000L
    }

    val recent = ageMs in 0..24L * 3_600_000L

    return when {
        recent && newest.maxDriftPct >= 10f -> ReadinessAdvice(
            ReadinessLevel.TAKE_IT_EASY,
            listOf(ReadinessReason.CardiacDrift(newest.maxDriftPct)),
        )
        recent && newest.totalFires >= 2 -> ReadinessAdvice(
            ReadinessLevel.CAUTION,
            listOf(ReadinessReason.WellnessAlerts(newest.totalFires)),
        )
        recent && minutesAbove(newest.cumMsCriticalAbove) >= 10 -> ReadinessAdvice(
            ReadinessLevel.CAUTION,
            listOf(ReadinessReason.MinutesAboveCritical(minutesAbove(newest.cumMsCriticalAbove))),
        )
        ridesWithin72h >= 3 -> ReadinessAdvice(
            ReadinessLevel.CAUTION,
            listOf(ReadinessReason.RidesIn72h(ridesWithin72h)),
        )
        else -> null
    }
}

private fun minutesAbove(ms: Long): Int = (ms / 60_000L).toInt()
