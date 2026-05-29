package com.enderthor.kSafe.extension.managers

import android.content.Context
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import java.io.File
import java.lang.reflect.Field

/**
 * Tests for the two bug fixes on [CalibrationLogger] that are otherwise
 * verified only by inspection:
 *
 *  - `restartFlushJob` lost-cancellation race fix: after [CalibrationLogger.disable]
 *    has run, [CalibrationLogger.restartFlushJob] must NOT re-launch the flush
 *    coroutine. The fix at the top of `restartFlushJob` adds an
 *    `if (!isEnabled) return` guard so a stale health-check restart cannot revive a
 *    logger the rider just turned off.
 *
 *  - `disableAsync` off-Main flush: [CalibrationLogger.disableAsync] sets state +
 *    appends the LOG_END marker synchronously on the caller's thread, then
 *    dispatches the flush itself to [kotlinx.coroutines.Dispatchers.IO] so a Main-
 *    dispatcher caller is not blocked by the up-to-500-row CSV append. We verify:
 *      • isEnabled flips to false *before* the returned Job completes.
 *      • LOG_END lands in the buffer immediately (not on the IO hop).
 *      • The returned Job completes and the LOG_END row is on disk afterwards.
 *
 * Test infrastructure shape (kept minimal — see header note in the task brief):
 *  - [Context] is a Mockito mock returning a [TemporaryFolder]-backed dir from
 *    `getExternalFilesDir(null)`.
 *  - [ConfigurationManager] is mocked; the suspend `getOrCreateInstallId()` is
 *    stubbed via the standard `runBlocking { when(...) }` idiom so the
 *    `installId by lazy` initializer (which `runBlocking`s on Dispatchers.IO at
 *    first access from `enable()`'s LOGGER_START row) returns a known value.
 *  - [TestScope] hosts the flush coroutine; we drive it through
 *    [advanceTimeBy] / [advanceUntilIdle] for deterministic ticking.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CalibrationLoggerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var externalDir: File

    companion object {
        /**
         * The Android stub jar shipped on the testDebugUnitTest classpath leaves
         * `android.os.Build.MODEL` as a final-static null field. The
         * [CalibrationLogger] DEVICE_LABEL lazy initializer reads `Build.MODEL.trim()`
         * and KotlinNullPointerException's on the null target.
         *
         * `testOptions.unitTests.isReturnDefaultValues` only makes stub *methods*
         * return defaults, not fields. We patch the field via reflection here so
         * `enable()` can complete. Production is never affected — real devices
         * always have a non-null MODEL.
         */
        @JvmStatic
        @BeforeClass
        fun ensureBuildModelNotNull() {
            // JDK 17 has stricter rules on writing `static final` String fields. We use
            // sun.misc.Unsafe.staticFieldBase / staticFieldOffset which still works for
            // final statics across the JDK 8 .. 21 range without requiring
            // --add-opens on the command line. Wrapped in try/catch: if the JDK
            // ever closes this off we fall through and the test fails with a clear NPE
            // at enable() that points to this hook.
            try {
                val field: Field = android.os.Build::class.java.getField("MODEL")
                val unsafeClass = Class.forName("sun.misc.Unsafe")
                val theUnsafe: Field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
                val unsafe = theUnsafe.get(null)
                val base = unsafeClass.getMethod("staticFieldBase", Field::class.java).invoke(unsafe, field)
                val offset = unsafeClass.getMethod("staticFieldOffset", Field::class.java).invoke(unsafe, field) as Long
                unsafeClass.getMethod("putObject", Any::class.java, java.lang.Long.TYPE, Any::class.java)
                    .invoke(unsafe, base, offset, "test-device")
            } catch (_: Throwable) {
                // Best-effort. If MODEL ends up null the tests will NPE in enable() with
                // a stack pointing here so the failure mode is obvious.
            }
        }
    }

    @Before
    fun setUp() {
        externalDir = tempFolder.newFolder("external-files")
    }

    @After
    fun tearDown() {
        // TemporaryFolder cleans up automatically; nothing to do.
    }

    private fun mockContext(): Context {
        val ctx = mock(Context::class.java)
        `when`(ctx.getExternalFilesDir(null)).thenReturn(externalDir)
        return ctx
    }

    private fun mockConfigManager(installId: String = "abc123"): ConfigurationManager {
        val cm = mock(ConfigurationManager::class.java)
        // Stub the suspend fun. Mockito 5's inline mock-maker handles final classes and
        // suspend functions transparently — the suspend Continuation parameter is hidden
        // by the `runBlocking { when(...) }` pattern.
        runBlocking { `when`(cm.getOrCreateInstallId()).thenReturn(installId) }
        return cm
    }

    /**
     * Builds a logger with a [TestScope] so we can advance virtual time. The flush
     * coroutine is launched on this scope; under TestDispatcher, [Dispatchers.IO]
     * inside [kotlinx.coroutines.launch(Dispatchers.IO)] is replaced by the test
     * dispatcher so [advanceTimeBy] / [advanceUntilIdle] reach it.
     */
    private fun TestScope.newLogger(): CalibrationLogger = CalibrationLogger(
        context = mockContext(),
        scope = this,
        configurationManager = mockConfigManager(),
    )

    // ── T2.1: lost-cancellation race in restartFlushJob ──────────────────────

    /**
     * Pins the top-guard in `restartFlushJob`. Without this guard, a health-check
     * tick arriving just after the rider disabled the logger would re-launch the
     * flush coroutine — a stale logger continuing to write after the user said
     * "off".
     *
     * Reverting the `if (!isEnabled) return` guard at the top of `restartFlushJob`
     * (and also the second `if (!isEnabled) return` after `flushJob?.cancel()` —
     * both belong to the same fix) makes this test fail because the call after
     * `disable()` re-launches a flush coroutine that then attempts to write a
     * post-disable flush.
     */
    @Test
    fun `restartFlushJob after disable is a no-op (lost-cancellation race fix)`() = runTest {
        val logger = newLogger()
        logger.enable()
        advanceUntilIdle()  // let enable()'s IO launch run (file create + LOG_START)
        assertTrue("logger should be enabled after enable()", logger.isEnabled)

        logger.disable()  // flushes synchronously; isEnabled = false; flushJob cancelled

        // restartFlushJob is normally driven by KSafeExtension's health-check loop.
        // A stale tick arriving right after disable() must NOT revive the logger.
        logger.restartFlushJob()

        assertFalse(
            "isEnabled must remain false after disable(); restartFlushJob must not flip it.",
            logger.isEnabled,
        )

        // Advance well past two flush intervals: a revived flush loop would tick at
        // FLUSH_INTERVAL_MS (60s). We log an entry first; if the loop is alive it would
        // flush this to disk on the next tick. If the loop was correctly NOT relaunched,
        // the entry stays in the in-memory buffer and is never written.
        logger.addEntry(CalibrationLogger.Event.PERIODIC, "post_disable_marker")
        val sizeBefore = currentDiskLineCount(logger)
        advanceTimeBy(2 * CalibrationLogger.FLUSH_INTERVAL_MS + 1_000L)
        advanceUntilIdle()
        val sizeAfter = currentDiskLineCount(logger)

        assertEquals(
            "Disk line count must NOT grow after disable()+restartFlushJob() — the " +
                "lost-cancellation race fix is what stops a stale health-check restart " +
                "from reviving the flush coroutine.",
            sizeBefore,
            sizeAfter,
        )
    }

    private fun currentDiskLineCount(@Suppress("UNUSED_PARAMETER") logger: CalibrationLogger): Int {
        val file = File(externalDir, CalibrationLogger.FILE_NAME)
        if (!file.exists()) return 0
        return file.bufferedReader().use { it.lineSequence().count() }
    }

    // ── T2.2: disableAsync off-Main flush ────────────────────────────────────

    /**
     * Pins the contract: `disableAsync()` returns a Job and the LOG_END marker /
     * `isEnabled = false` are applied SYNCHRONOUSLY before the returned Job
     * actually flushes. The actual flush hops to a different dispatcher so a Main-
     * thread caller is not blocked by disk I/O.
     *
     * Reverting `disableAsync` to call `disable()` directly (which flushes
     * synchronously on the caller's thread) makes this test fail because we would
     * see the LOG_END row on disk before [advanceUntilIdle] runs the dispatched
     * launch — the contract distinguishing async from sync would be gone.
     */
    @Test
    fun `disableAsync flips isEnabled and adds LOG_END synchronously but defers the flush`() = runTest {
        val logger = newLogger()
        logger.enable()
        advanceUntilIdle()

        // Add a row that must end up on disk via the deferred flush.
        logger.addEntry(CalibrationLogger.Event.PERIODIC, "pre_disable_marker")
        val diskLinesBeforeDisable = currentDiskLineCount(logger)

        val job: Job = logger.disableAsync()
        // Synchronous post-conditions: state + LOG_END row before any dispatcher work.
        assertFalse(
            "disableAsync must set isEnabled=false synchronously on the caller's thread.",
            logger.isEnabled,
        )
        // Buffer should hold the LOG_END row plus the pre_disable marker (the flush
        // coroutine hasn't been awaited yet, so the in-memory buffer is still
        // populated). getContent() lists buffer contents.
        assertTrue(
            "LOG_END marker must land in the buffer SYNCHRONOUSLY before the IO hop. " +
                "If disableAsync only added it inside the launched coroutine, this " +
                "would be empty until advanceUntilIdle runs the launch.",
            logger.getContent().contains(CalibrationLogger.Event.LOG_END.tag),
        )
        // Disk has NOT received the deferred flush yet — the Job has not run.
        assertEquals(
            "The flush must be deferred (IO-dispatched); disk content must not have " +
                "advanced yet at the moment disableAsync returns.",
            diskLinesBeforeDisable,
            currentDiskLineCount(logger),
        )

        // disableAsync launches on Dispatchers.IO — under runTest that's the REAL IO
        // dispatcher, not the test scheduler, so advanceUntilIdle won't drain it.
        // join() the returned Job directly to block this test coroutine until the
        // deferred flush has completed. This is what the production caller relies on
        // when it observes the Job (e.g. structured-concurrency cleanup).
        job.join()
        assertTrue("disableAsync's returned Job must complete after join", job.isCompleted)

        val finalDisk = File(externalDir, CalibrationLogger.FILE_NAME).readText()
        assertTrue(
            "After the deferred flush completes, LOG_END must be on disk.",
            finalDisk.contains(CalibrationLogger.Event.LOG_END.tag),
        )
        assertTrue(
            "After the deferred flush completes, the pre-disable marker must be on disk.",
            finalDisk.contains("pre_disable_marker"),
        )
    }
}
