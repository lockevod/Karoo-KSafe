package com.enderthor.kSafe.extension.managers

import com.enderthor.kSafe.data.WellnessHistory
import java.util.Locale

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
 * One readiness recommendation — `level` plus the human-readable [reasons] strings that
 * are joined into the InRideAlert detail. Reasons come pre-formatted; the surface code
 * (KSafeExtension) just joins them with " · ".
 */
data class ReadinessAdvice(
    val level: ReadinessLevel,
    val reasons: List<String>,
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
    val ridesWithin72h = history.records.count { nowMs - it.endedAtMs <= 72L * 3_600_000L }

    val recent = ageMs in 0..24L * 3_600_000L

    return when {
        recent && newest.maxDriftPct >= 10f -> ReadinessAdvice(
            ReadinessLevel.TAKE_IT_EASY,
            listOf(String.format(Locale.US, "Cardiac drift %.0f%% on the last ride", newest.maxDriftPct)),
        )
        recent && newest.totalFires >= 2 -> ReadinessAdvice(
            ReadinessLevel.CAUTION,
            listOf("${newest.totalFires} wellness alerts on the last ride"),
        )
        recent && minutesAbove(newest.cumMsCriticalAbove) >= 10 -> ReadinessAdvice(
            ReadinessLevel.CAUTION,
            listOf("${minutesAbove(newest.cumMsCriticalAbove)} min above critical HR yesterday"),
        )
        ridesWithin72h >= 3 -> ReadinessAdvice(
            ReadinessLevel.CAUTION,
            listOf("$ridesWithin72h rides in the last 72 h"),
        )
        else -> null
    }
}

private fun minutesAbove(ms: Long): Int = (ms / 60_000L).toInt()
