package com.enderthor.kSafe.extension.util

/**
 * Lets tests replace `System.currentTimeMillis()` with a fake clock so deterministic
 * time-based assertions are possible. Production code wires in [SystemClock]; unit tests
 * pass their own implementation that advances time on demand.
 *
 * Two time domains:
 *  - [nowMs] is wall-clock time (System.currentTimeMillis equivalent). Used for log
 *    timestamps, persisted state, and anywhere the resulting value crosses a process
 *    or device boundary.
 *  - [monotonicMs] is a strictly-monotonic time source (elapsedRealtime on Android).
 *    Used for cooldown gates and duration math so an NTP step or user-driven date
 *    change cannot make `(now - thenStamp)` negative (suppressing a cooldown
 *    expiry) or wildly large (firing a cooldown expiry prematurely). The default
 *    impl delegates to [nowMs] so test fakes that only override [nowMs] keep working.
 */
fun interface Clock {
    fun nowMs(): Long
    fun monotonicMs(): Long = nowMs()
}

object SystemClock : Clock {
    override fun nowMs(): Long = System.currentTimeMillis()
    override fun monotonicMs(): Long = android.os.SystemClock.elapsedRealtime()
}
