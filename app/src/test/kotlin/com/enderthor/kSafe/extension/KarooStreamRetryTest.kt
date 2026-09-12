package com.enderthor.kSafe.extension

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import timber.log.Timber

/**
 * Guards [retryKarooStream] — the operator that keeps a Karoo SDK stream alive after the
 * producer ends it.
 *
 * Why this matters: the SDK removes the consumer inside both its `onError` and `onComplete`
 * wrappers, so before this operator existed a terminated stream left the `callbackFlow`
 * suspended forever. A dead HEART_RATE stream silently disabled medical detection; a dead
 * SPEED stream froze `lastSpeedKmh`, and `CrashStateMachine.handleMonitoring`'s speed gate
 * deliberately does NOT bypass on staleness — so every impact was rejected for the whole
 * outage. The failure is invisible at runtime, which is exactly why it needs tests.
 *
 * All timing assertions use `runTest`'s virtual clock, so they are deterministic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KarooStreamRetryTest {

    /** Upstream that emits [emitCount] values then dies the way the SDK kills a stream. */
    private fun dyingFlow(
        emitCount: Int = 0,
        aliveMs: Long = 0L,
        cause: () -> Throwable = { KarooStreamEnded.Complete() },
        onSubscribe: () -> Unit = {},
    ): Flow<Int> = flow {
        onSubscribe()
        repeat(emitCount) { emit(it) }
        if (aliveMs > 0) kotlinx.coroutines.delay(aliveMs)
        throw cause()
    }

    @Test
    fun `an Error re-subscribes`() = runTest {
        var subscriptions = 0
        val f = dyingFlow(cause = { KarooStreamEnded.Error("binder died") }) { subscriptions++ }
            .retryKarooStream("TEST", StreamPolicy.CRITICAL, clock = { currentTime })
        // take(0) would not subscribe; collect in the background and let time pass.
        val job = launch { f.collect { } }
        advanceTimeBy(20_000)
        job.cancel()
        assertTrue("an Error must re-subscribe, got $subscriptions", subscriptions > 1)
    }

    @Test
    fun `a Complete re-subscribes`() = runTest {
        var subscriptions = 0
        val f = dyingFlow(cause = { KarooStreamEnded.Complete() }) { subscriptions++ }
            .retryKarooStream("TEST", StreamPolicy.CRITICAL, clock = { currentTime })
        val job = launch { f.collect { } }
        advanceTimeBy(20_000)
        job.cancel()
        assertTrue("a Complete must re-subscribe, got $subscriptions", subscriptions > 1)
    }

    @Test
    fun `values survive the re-subscription`() = runTest {
        // The whole point: a consumer keeps receiving data across a producer death.
        val f = dyingFlow(emitCount = 2).retryKarooStream("TEST", StreamPolicy.CRITICAL, clock = { currentTime })
        val got = f.take(5).toList()
        assertEquals(listOf(0, 1, 0, 1, 0), got)
    }

    @Test
    fun `CRITICAL backoff climbs to its cap and never exceeds it`() = runTest {
        val starts = mutableListOf<Long>()
        val f = dyingFlow { starts += currentTime }
            .retryKarooStream("TEST", StreamPolicy.CRITICAL, clock = { currentTime })
        val job = launch { f.collect { } }
        advanceTimeBy(120_000)
        job.cancel()

        val gaps = starts.zipWithNext { a, b -> b - a }
        assertTrue("expected several retries, got ${starts.size}", gaps.size >= 8)
        // Jitter is +/-25%, so assert bounds rather than exact values.
        assertTrue("first gap ${gaps[0]} outside 1s +/-25%", gaps[0] in 750..1250)
        assertTrue("second gap ${gaps[1]} outside 2s +/-25%", gaps[1] in 1500..2500)
        assertTrue("third gap ${gaps[2]} outside 4s +/-25%", gaps[2] in 3000..5000)
        // The cap is what protects crash detection's speed gate — it is a hard ceiling,
        // jitter included.
        gaps.drop(3).forEach {
            assertTrue("gap $it exceeded the CRITICAL 5s cap", it <= 5_000)
            assertTrue("gap $it below the jitter floor of the cap", it >= 3_750)
        }
    }

    @Test
    fun `an OPTIONAL Error climbs the slow schedule and caps at five minutes`() = runTest {
        val starts = mutableListOf<Long>()
        val f = dyingFlow(cause = { KarooStreamEnded.Error("headwind died") }) { starts += currentTime }
            .retryKarooStream("TEST", StreamPolicy.OPTIONAL, clock = { currentTime })
        val job = launch { f.collect { } }
        advanceTimeBy(3_600_000)
        job.cancel()

        val gaps = starts.zipWithNext { a, b -> b - a }
        assertTrue("expected several retries, got ${starts.size}", gaps.size >= 6)
        assertTrue("first gap ${gaps[0]} outside 30s +/-25%", gaps[0] in 22_500..37_500)
        assertTrue("second gap ${gaps[1]} outside 60s +/-25%", gaps[1] in 45_000..75_000)
        gaps.forEach { assertTrue("gap $it exceeded the OPTIONAL 300s cap", it <= 300_000) }
    }

    @Test
    fun `an OPTIONAL Complete follows the slow schedule, not a jump to the cap`() = runTest {
        // A third-party producer ending its stream is a legitimate end-of-life, so the SLOW
        // schedule is the right regime — but it starts at 30s for exactly that reason, and
        // jumping straight to the 300s cap would make a Headwind restart 20s later wait five
        // minutes to be noticed, for no gain. Retrying at all is still right: after
        // onComplete the SDK drops the listener, and a reconnect only re-registers listeners
        // still present, so the restart would otherwise go unseen until the next ride.
        val starts = mutableListOf<Long>()
        val f = dyingFlow(cause = { KarooStreamEnded.Complete() }) { starts += currentTime }
            .retryKarooStream("TEST", StreamPolicy.OPTIONAL, clock = { currentTime })
        val job = launch { f.collect { } }
        advanceTimeBy(1_800_000)
        job.cancel()

        val gaps = starts.zipWithNext { a, b -> b - a }
        assertTrue("expected retries, got ${starts.size}", gaps.size >= 4)
        assertTrue("first gap ${gaps[0]} outside 30s +/-25%", gaps[0] in 22_500..37_500)
        assertTrue("second gap ${gaps[1]} outside 60s +/-25%", gaps[1] in 45_000..75_000)
        assertTrue("third gap ${gaps[2]} outside 120s +/-25%", gaps[2] in 90_000..150_000)
        assertTrue("fourth gap ${gaps[3]} outside 240s +/-25%", gaps[3] in 180_000..300_000)
        gaps.forEach { assertTrue("gap $it exceeded the OPTIONAL 300s cap", it <= 300_000) }
    }

    @Test
    fun `a CRITICAL Complete still climbs from the fast step`() = runTest {
        // The end-of-life reasoning applies only to optional third-party producers. A native
        // stream completing is not normal, and crash detection cannot wait 5 minutes for it.
        val starts = mutableListOf<Long>()
        val f = dyingFlow(cause = { KarooStreamEnded.Complete() }) { starts += currentTime }
            .retryKarooStream("TEST", StreamPolicy.CRITICAL, clock = { currentTime })
        val job = launch { f.collect { } }
        advanceTimeBy(30_000)
        job.cancel()

        val gaps = starts.zipWithNext { a, b -> b - a }
        assertTrue("first gap ${gaps[0]} outside 1s +/-25%", gaps[0] in 750..1250)
        gaps.forEach { assertTrue("gap $it exceeded the CRITICAL 5s cap", it <= 5_000) }
    }

    @Test
    fun `two collectors of the SAME flow do not share backoff state`() = runTest {
        // Must collect the SAME Flow object twice. The earlier version of this test built a
        // second, separate operator instance — which would start at 1s even if the counter
        // had been hoisted out of the flow builder into the operator's closure, i.e. it could
        // not fail for the reason it claimed.
        val starts = mutableListOf<Long>()
        val shared = dyingFlow { starts += currentTime }
            .retryKarooStream("TEST", StreamPolicy.CRITICAL, clock = { currentTime })

        val a = launch { shared.collect { } }
        advanceTimeBy(60_000)            // drive collector A well past its cap
        val mark = starts.size
        val markedAt = currentTime

        val b = launch { shared.collect { } }   // fresh collection of the SAME instance
        advanceTimeBy(1_400)
        a.cancel(); b.cancel()

        // B must subscribe immediately, then retry at ~1s — not inherit A's capped schedule.
        val bStarts = starts.drop(mark).filter { it >= markedAt }
        assertTrue("collector B never subscribed", bStarts.isNotEmpty())
        val bSecond = bStarts.getOrNull(1)
        assertTrue(
            "B's first retry must be at the 1s step, not A's cap — starts=$bStarts",
            bSecond != null && bSecond - bStarts[0] <= 1_250,
        )
    }

    @Test
    fun `thirty seconds of stability resets the backoff without any emission`() = runTest {
        // Emission is NOT evidence of health: StreamState.Searching / NotAvailable / Idle
        // are valid emissions carrying no data, and location can be healthily silent.
        // Only how long the subscription survived may reset the schedule.
        val starts = mutableListOf<Long>()
        var round = 0
        val f = flow<Int> {
            starts += currentTime
            round++
            // Rounds 1-3 die instantly (climbing the backoff), round 4 lives past the
            // stability threshold with zero emissions, round 5+ die instantly again.
            if (round == 4) kotlinx.coroutines.delay(31_000)
            throw KarooStreamEnded.Complete()
        }.retryKarooStream("TEST", StreamPolicy.CRITICAL, clock = { currentTime })

        val job = launch { f.collect { } }
        advanceTimeBy(120_000)
        job.cancel()

        val gaps = starts.zipWithNext { a, b -> b - a }
        // gaps[3] spans the 31s-long round plus the delay after it. The delay AFTER the
        // stable round must be back at the 1s step, not the 5s cap.
        val afterStable = gaps[3] - 31_000
        assertTrue("backoff did not reset after a stable subscription: ${afterStable}ms", afterStable <= 1_250)
    }

    @Test
    fun `a short-lived subscription that emitted does not reset the backoff`() = runTest {
        // The mirror of the test above: emitting quickly and then dying is the signature of
        // a flapping producer, and must keep climbing the backoff.
        val starts = mutableListOf<Long>()
        val f = dyingFlow(emitCount = 3) { starts += currentTime }
            .retryKarooStream("TEST", StreamPolicy.CRITICAL, clock = { currentTime })
        val job = launch { f.collect { } }
        advanceTimeBy(30_000)
        job.cancel()

        val gaps = starts.zipWithNext { a, b -> b - a }
        assertTrue("expected the backoff to climb, gaps=$gaps", gaps.size >= 4)
        assertTrue("emitting must not reset the backoff", gaps[3] >= 3_750)
    }

    @Test
    fun `cancellation is not retried`() = runTest {
        var subscriptions = 0
        val f = flow<Int> {
            subscriptions++
            throw CancellationException("structured cancellation")
        }.retryKarooStream("TEST", StreamPolicy.CRITICAL, clock = { currentTime })

        val result = withTimeoutOrNull(10_000) { runCatching { f.collect { } } }
        advanceTimeBy(20_000)
        assertEquals("cancellation must not re-subscribe", 1, subscriptions)
        assertNull("collect must not hang after cancellation", result?.getOrNull())
    }

    @Test
    fun `an incident is closed by the first accepted value, not by resubscribing`() = runTest {
        // Recovery is confirmed by DATA, never by a successful re-registration: the SDK
        // guarantees replay only for RideState and UserProfile, so for everything else a
        // resubscribe proves nothing. The operator must therefore keep treating itself as
        // "down" until a value actually arrives.
        var round = 0
        val downRounds = 3
        val f = flow {
            round++
            if (round <= downRounds) throw KarooStreamEnded.Error("still down")
            emit(round)
            kotlinx.coroutines.delay(60_000)
            throw KarooStreamEnded.Complete()
        }.retryKarooStream("TEST", StreamPolicy.CRITICAL, clock = { currentTime })

        val first = withTimeoutOrNull(120_000) { f.first() }
        assertEquals("must deliver a value once the producer comes back", 4, first)
        assertEquals("expected exactly $downRounds failed attempts", downRounds + 1, round)
    }

    @Test
    fun `a callbackFlow closed with KarooStreamEnded resubscribes and tears down each consumer`() = runTest {
        // Every other test drives a plain flow { throw }. This is the shape that actually runs
        // on device: the SDK's onError/onComplete call close(cause) on a callbackFlow whose
        // producer lives on a Binder thread. It pins three things that would otherwise only
        // fail in production — that the close surfaces as a caught KarooStreamEnded, that
        // awaitClose (i.e. removeConsumer) runs for every subscription so consumers cannot
        // leak across thousands of retries, and that take()'s AbortFlowException still
        // traverses ChannelFlow's coroutineScope instead of being swallowed.
        var opened = 0
        var closed = 0
        val f = callbackFlow {
            opened++
            trySend(opened)
            close(KarooStreamEnded.Error("binder died"))
            awaitClose { closed++ }
        }.retryKarooStream("TEST", StreamPolicy.CRITICAL, clock = { currentTime })

        assertEquals(listOf(1, 2, 3), f.take(3).toList())
        assertEquals("every subscription must be torn down", 3, closed)
    }

    @Test
    fun `the recovery WARN is actually emitted`() = runTest {
        // Spec 3.6 makes this line the ONLY field instrument that will tell us whether this
        // failure mode happens on real hardware, so assert the log itself, not just that a
        // value arrived. Release keeps WARN, so what is asserted here is what ships.
        val logs = mutableListOf<String>()
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                logs += message
            }
        }
        Timber.plant(tree)
        try {
            var round = 0
            val f = flow {
                round++
                if (round <= 2) throw KarooStreamEnded.Error("still down")
                emit(round)
                kotlinx.coroutines.delay(60_000)
                throw KarooStreamEnded.Complete()
            }.retryKarooStream("SPEED", StreamPolicy.CRITICAL, clock = { currentTime })

            assertEquals(3, withTimeoutOrNull(60_000) { f.first() })
        } finally {
            Timber.uproot(tree)
        }

        assertTrue("no interruption WARN: $logs", logs.any { it.contains("ended") && it.contains("SPEED") })
        assertTrue("no recovery WARN: $logs", logs.any { it.contains("recovered after") })
    }

    @Test
    fun `jitter actually varies the delay`() = runTest {
        // The bounds assertions elsewhere would all pass with jitter(base) = base. This one
        // fails if the spread is ever removed — which matters because at the cap, clamping
        // the drawn value instead of the range collapses half the distribution onto one
        // millisecond and re-aligns every collector a common failure knocked down together.
        val starts = mutableListOf<Long>()
        val f = dyingFlow { starts += currentTime }
            .retryKarooStream("TEST", StreamPolicy.CRITICAL, clock = { currentTime })
        val job = launch { f.collect { } }
        advanceTimeBy(120_000)
        job.cancel()

        // Steady-state gaps only: once the backoff is at the cap, every gap is a fresh draw.
        val capped = starts.zipWithNext { a, b -> b - a }.drop(4)
        assertTrue("expected many capped retries, got ${capped.size}", capped.size >= 10)
        assertTrue("delays are identical — jitter is not being applied: $capped",
            capped.distinct().size > 1)
        assertTrue("jitter never reached below the cap: $capped", capped.any { it < 5_000 })
    }

    @Test
    fun `a downstream exception is not absorbed`() = runTest {
        // retryKarooStream only owns the SDK's termination. An exception thrown by the
        // consumer must propagate, which is why LocationManager keeps its outer loop.
        val f = flow { emit(1) }
            .retryKarooStream("TEST", StreamPolicy.CRITICAL, clock = { currentTime })
            .map { error("downstream boom") }

        val thrown = runCatching { f.first() }.exceptionOrNull()
        assertTrue("expected the downstream failure, got $thrown", thrown is IllegalStateException)
    }

    @Test
    fun `a non-Karoo upstream exception is not absorbed either`() = runTest {
        // Only KarooStreamEnded means "the SDK ended this stream". Anything else is a real
        // bug and must surface rather than being retried into an invisible loop.
        var subscriptions = 0
        val f = flow<Int> {
            subscriptions++
            throw IllegalStateException("genuine bug")
        }.retryKarooStream("TEST", StreamPolicy.CRITICAL, clock = { currentTime })

        val thrown = runCatching { f.collect { } }.exceptionOrNull()
        assertTrue("expected the original exception, got $thrown", thrown is IllegalStateException)
        assertEquals("a non-Karoo failure must not re-subscribe", 1, subscriptions)
    }
}
