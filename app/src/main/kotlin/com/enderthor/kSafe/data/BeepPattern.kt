package com.enderthor.kSafe.data

import io.hammerhead.karooext.models.PlayBeepPattern

/**
 * Rider-selectable beep patterns for informational in-ride alerts (carbs, hydration).
 *
 * The Karoo SDK has no built-in sound catalogue — every "different sound" is just a
 * different sequence of synthesised tones via [PlayBeepPattern]. This enum names a
 * handful of presets so the rider can pick one in Settings; each value maps to a
 * fixed [PlayBeepPattern] (or null for OFF).
 *
 * **Why this is informational-only:** emergency / crash / medical beeps stay on the
 * urgent default in [EmergencyManager] because they need to grab attention regardless
 * of rider preference. Letting the rider mute a crash beep would defeat the safety
 * pipeline. Fueling alerts are different: they're hint-level, and a silent on-screen
 * overlay is a perfectly reasonable choice for some riders.
 *
 * Order matters — UI lists preserve declaration order in the dropdown.
 */
enum class BeepPattern {
    /** No sound — visual InRideAlert only. */
    OFF,
    /** One long tone (880 Hz × 800 ms) — the historical default. */
    SINGLE_LONG,
    /** Two short pips (880 Hz × 150 ms, gap 100 ms, again). Subtle but audible. */
    DOUBLE_SHORT,
    /** Three rising tones (660 → 880 → 1100 Hz × 200 ms each). Pleasant chime. */
    RISING_TRIPLE,
    /** Urgent pulse (880-pause-880-pause-1100). Louder presence — same shape as the
     *  emergency-countdown urgency pattern but the rider can opt in for fueling too. */
    URGENT_PULSE;

    /**
     * @return the concrete [PlayBeepPattern] to dispatch, or null if this preset is OFF.
     */
    fun toPlayBeepPattern(): PlayBeepPattern? = when (this) {
        OFF -> null
        SINGLE_LONG -> SINGLE_LONG_PATTERN
        DOUBLE_SHORT -> DOUBLE_SHORT_PATTERN
        RISING_TRIPLE -> RISING_TRIPLE_PATTERN
        URGENT_PULSE -> URGENT_PULSE_PATTERN
    }

    companion object {
        // Cached pattern instances — PlayBeepPattern allocates a list of Tones, so caching
        // avoids re-allocating on every alert dispatch. The objects are immutable.
        private val SINGLE_LONG_PATTERN = PlayBeepPattern(listOf(
            PlayBeepPattern.Tone(frequency = 880, durationMs = 800),
        ))
        private val DOUBLE_SHORT_PATTERN = PlayBeepPattern(listOf(
            PlayBeepPattern.Tone(frequency = 880, durationMs = 150),
            PlayBeepPattern.Tone(frequency = null, durationMs = 100),
            PlayBeepPattern.Tone(frequency = 880, durationMs = 150),
        ))
        private val RISING_TRIPLE_PATTERN = PlayBeepPattern(listOf(
            PlayBeepPattern.Tone(frequency = 660, durationMs = 200),
            PlayBeepPattern.Tone(frequency = 880, durationMs = 200),
            PlayBeepPattern.Tone(frequency = 1100, durationMs = 200),
        ))
        private val URGENT_PULSE_PATTERN = PlayBeepPattern(listOf(
            PlayBeepPattern.Tone(frequency = 880, durationMs = 200),
            PlayBeepPattern.Tone(frequency = null, durationMs = 100),
            PlayBeepPattern.Tone(frequency = 880, durationMs = 200),
            PlayBeepPattern.Tone(frequency = null, durationMs = 100),
            PlayBeepPattern.Tone(frequency = 1100, durationMs = 500),
        ))
    }
}
