package com.enderthor.kSafe.extension.managers

/**
 * Identifies which path inside [CrashDetectionManager] confirmed a crash.
 *
 * Currently used by the unified `confirmCrash(source)` gate to distinguish the
 * accelerometer-based main pipeline from the parallel speed-drop monitor in the
 * calibration log and (later) in the emergency message metadata.
 */
enum class CrashSource {
    /** State machine MONITORING → IMPACT → SILENCE_CHECK → CRASH_CONFIRMED. */
    IMPACT_CONFIRMED,

    /** Speed has been zero with the accelerometer stable for the configured window. */
    SPEED_DROP,
}
