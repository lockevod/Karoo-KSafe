package com.enderthor.kSafe.extension.managers

/**
 * Lets tests replace `System.currentTimeMillis()` with a fake clock so deterministic
 * time-based assertions are possible. Production code wires in [SystemClock]; unit tests
 * pass their own implementation that advances time on demand.
 *
 * Kept minimal on purpose — only `nowMs()` is exposed. The previous in-line
 * `System.currentTimeMillis()` calls were the only thing blocking deterministic tests
 * on the time-sensitive paths (cooldown, speed-drop window, silence-check).
 */
fun interface Clock {
    fun nowMs(): Long
}

object SystemClock : Clock {
    override fun nowMs(): Long = System.currentTimeMillis()
}
