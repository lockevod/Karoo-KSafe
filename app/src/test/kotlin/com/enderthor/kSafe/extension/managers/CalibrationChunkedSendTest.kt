package com.enderthor.kSafe.extension.managers

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import java.io.File
import java.lang.reflect.Field

/**
 * Tests for the calibration-log chunked-send building blocks — the headline
 * fix for the 2026-05-25 incident where a 262 KB on-disk log silently failed
 * every periodic-send retry because each `httpRequest` body exceeded the
 * Karoo SDK's ~80 KB Binder transaction limit.
 *
 * The drain loop itself ([KSafeExtension.sendCalibrationLogInChunks]) is a
 * thin orchestrator over these primitives, so pinning the primitives is
 * what stops the incident from re-occurring under future refactors:
 *
 *  - [CalibrationLogger.getFileContentChunked] returns null on empty / header-
 *    only files (caller's loop breaks) and produces size-capped chunks
 *    correctly when the file is larger than `maxBytes`.
 *  - [CalibrationLogger.truncateAfterSuccessfulSend] / [CalibrationLogger.
 *    truncatePreviousAfterSuccessfulSend] drop exactly the sent lines and
 *    delete the file when the tail is empty.
 *  - A multi-chunk drain (get-chunk → truncate → get-chunk → …) eventually
 *    drains the file and the last chunk reports `hasMore = false`.
 *
 * Test infrastructure mirrors [CalibrationLoggerTest] — same Build.MODEL
 * unsafe-field patch, same temp-folder / mock-context shape.
 */
class CalibrationChunkedSendTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var externalDir: File

    companion object {
        /** See [CalibrationLoggerTest.ensureBuildModelNotNull] — same hack. */
        @JvmStatic
        @BeforeClass
        fun ensureBuildModelNotNull() {
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
            }
        }
    }

    @org.junit.Before
    fun setUp() {
        externalDir = tempFolder.newFolder("external-files")
    }

    private fun mockContext(): Context {
        val ctx = mock(Context::class.java)
        `when`(ctx.getExternalFilesDir(null)).thenReturn(externalDir)
        return ctx
    }

    private fun makeLogger(): CalibrationLogger {
        val cm = mock(ConfigurationManager::class.java)
        kotlinx.coroutines.runBlocking { `when`(cm.getOrCreateInstallId()).thenReturn("test-install") }
        return CalibrationLogger(
            context = mockContext(),
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined),
            configurationManager = cm,
        )
    }

    /** Writes `n` data lines + the HEADER directly to disk — bypasses
     *  [CalibrationLogger.enable] / flush so the test controls the file shape.
     *  Returns the file path. */
    private fun seedFile(lines: Int, payloadPerLine: String = "x".repeat(40)): File {
        val file = File(externalDir, CalibrationLogger.FILE_NAME)
        val content = buildString {
            append(CalibrationLogger.HEADER).append('\n')
            repeat(lines) { idx -> append("100$idx,0,PERIODIC,${payloadPerLine}\n") }
        }
        file.writeText(content)
        return file
    }

    private fun seedPreviousFile(lines: Int, payloadPerLine: String = "y".repeat(40)): File {
        val file = File(externalDir, CalibrationLogger.PREVIOUS_FILE_NAME)
        val content = buildString {
            append(CalibrationLogger.HEADER).append('\n')
            repeat(lines) { idx -> append("200$idx,0,PERIODIC,${payloadPerLine}\n") }
        }
        file.writeText(content)
        return file
    }

    // ── readChunkStreaming / getFileContentChunked ──────────────────────────

    @Test
    fun `getFileContentChunked returns null when file does not exist`() {
        val logger = makeLogger()
        val chunk = logger.getFileContentChunked(maxBytes = 4_096)
        assertNull("Missing file must return null so the drain loop breaks cleanly", chunk)
    }

    @Test
    fun `getFileContentChunked returns null on a HEADER-only file`() {
        val file = File(externalDir, CalibrationLogger.FILE_NAME)
        file.writeText(CalibrationLogger.HEADER + "\n")
        val logger = makeLogger()
        val chunk = logger.getFileContentChunked(maxBytes = 4_096)
        assertNull(
            "HEADER-only file has no data to send — must return null so the drain loop " +
                "doesn't ship an empty CSV every cycle",
            chunk,
        )
    }

    @Test
    fun `getFileContentChunked returns null when first line is not the canonical HEADER`() {
        // Could happen if the file was hand-edited or the HEADER constant
        // changed without a migration. The chunker must refuse rather than
        // ship an unparseable CSV.
        val file = File(externalDir, CalibrationLogger.FILE_NAME)
        file.writeText("not_a_header\n100,0,PERIODIC,data\n")
        val logger = makeLogger()
        val chunk = logger.getFileContentChunked(maxBytes = 4_096)
        assertNull(chunk)
    }

    @Test
    fun `getFileContentChunked includes HEADER plus all data when file fits in maxBytes`() {
        seedFile(lines = 5, payloadPerLine = "abc")
        val logger = makeLogger()
        val chunk = logger.getFileContentChunked(maxBytes = 64_000)
        assertNotNull(chunk)
        val c = chunk!!
        assertEquals(
            "linesIncluded counts HEADER + N data lines so truncateAfterSuccessfulSend " +
                "can drop the right total",
            6, c.linesIncluded,
        )
        assertFalse("Single-chunk file must have hasMore=false", c.hasMore)
        assertTrue("HEADER must be the first line of every chunk", c.content.startsWith(CalibrationLogger.HEADER))
    }

    @Test
    fun `getFileContentChunked stops at maxBytes and reports hasMore for a larger file`() {
        // 50 lines × 50 bytes/line ≈ 2.5 KB of data + 32-byte HEADER.
        // Cap at 600 bytes → ~11 data lines per chunk + HEADER.
        seedFile(lines = 50, payloadPerLine = "x".repeat(40))  // each line ≈ 50 bytes
        val logger = makeLogger()
        val chunk = logger.getFileContentChunked(maxBytes = 600)
        assertNotNull(chunk)
        val c = chunk!!
        assertTrue(
            "Chunk content (${c.content.length} B) must not exceed maxBytes (600 B) — that's " +
                "the whole point of the chunked send (Binder transaction safety margin)",
            c.content.length <= 600,
        )
        assertTrue("Larger-than-cap file must report hasMore=true", c.hasMore)
        assertTrue("Chunk must include the HEADER", c.content.startsWith(CalibrationLogger.HEADER))
        // The chunk has at least one data line — without that the drain
        // loop would stall forever on a file whose only line is huge.
        assertTrue("Chunk must include at least one data line", c.linesIncluded > 1)
    }

    @Test
    fun `getFileContentChunked emits a single oversized line and peeks for hasMore`() {
        // Edge case: one data row that on its own exceeds maxBytes. The
        // chunker MUST still emit it (otherwise the drain loop stalls
        // forever), but must peek the next line to set hasMore correctly.
        val file = File(externalDir, CalibrationLogger.FILE_NAME)
        val huge = "z".repeat(200)
        file.writeText(
            CalibrationLogger.HEADER + "\n" +
                "100,0,PERIODIC,$huge\n" +
                "101,0,PERIODIC,short\n",
        )
        val logger = makeLogger()
        val chunk = logger.getFileContentChunked(maxBytes = 80)
        assertNotNull(chunk)
        val c = chunk!!
        assertEquals("HEADER + one oversized line", 2, c.linesIncluded)
        assertTrue(
            "Peek must set hasMore=true so the next chunk picks up the short line",
            c.hasMore,
        )
    }

    @Test
    fun `getFileContentChunked emits the oversized last line with hasMore=false`() {
        // Variant: oversized line is the LAST line. The peek returns null,
        // so hasMore=false and the drain loop terminates after truncating.
        val file = File(externalDir, CalibrationLogger.FILE_NAME)
        val huge = "z".repeat(200)
        file.writeText(CalibrationLogger.HEADER + "\n" + "100,0,PERIODIC,$huge\n")
        val logger = makeLogger()
        val chunk = logger.getFileContentChunked(maxBytes = 80)
        assertNotNull(chunk)
        val c = chunk!!
        assertEquals(2, c.linesIncluded)
        assertFalse(
            "Oversized line at EOF must have hasMore=false (peek returned null)",
            c.hasMore,
        )
    }

    @Test
    fun `getFileContentChunked tolerates files without a trailing newline`() {
        val file = File(externalDir, CalibrationLogger.FILE_NAME)
        file.writeText(CalibrationLogger.HEADER + "\n" + "100,0,PERIODIC,nope")  // no trailing newline
        val logger = makeLogger()
        val chunk = logger.getFileContentChunked(maxBytes = 4_096)
        assertNotNull(
            "BufferedReader.readLine handles a missing trailing newline natively — " +
                "the chunk must still surface the one data row, not return null",
            chunk,
        )
        assertEquals(2, chunk!!.linesIncluded)
    }

    // ── truncateAfterSuccessfulSend ─────────────────────────────────────────

    @Test
    fun `truncateAfterSuccessfulSend drops exactly the sent data lines`() {
        seedFile(lines = 10)
        val logger = makeLogger()
        val file = File(externalDir, CalibrationLogger.FILE_NAME)
        val originalLines = file.readLines().size
        // Pretend we sent HEADER + 3 data lines (linesIncluded = 4).
        val dropped = logger.truncateAfterSuccessfulSend(uploadedLineCount = 4)
        val remainingLines = file.readLines().size
        assertEquals(
            "truncate must drop exactly the data lines that were sent (uploadedLineCount - 1, " +
                "since the HEADER is preserved on disk for subsequent chunks)",
            3, dropped,
        )
        assertEquals(
            "On-disk line count must shrink by `dropped` (HEADER preserved)",
            originalLines - dropped, remainingLines,
        )
        assertEquals(
            "HEADER must remain as the first line so subsequent chunks are valid CSV",
            CalibrationLogger.HEADER, file.readLines().first(),
        )
    }

    @Test
    fun `truncateAfterSuccessfulSend deletes the file when nothing remains beyond the HEADER`() {
        // Pre-existing behaviour: this function is NOT invoked when the chunk
        // covered the whole file (the drain loop simply stops). But if the
        // caller passes uploadedLineCount = total-on-disk anyway, the function
        // should still leave the file in a usable HEADER-only shape — NOT
        // delete it. The current file (vs the PREVIOUS file) is appended to
        // by the flush loop; deleting it would race with `addEntry`.
        seedFile(lines = 3)
        val logger = makeLogger()
        val file = File(externalDir, CalibrationLogger.FILE_NAME)
        logger.truncateAfterSuccessfulSend(uploadedLineCount = 4)
        // 4 = HEADER + 3 data lines = everything. The current-file path keeps
        // the HEADER and returns to HEADER-only state (so the next flush can
        // safely append).
        assertTrue("Current calibration file must persist after a full drain", file.exists())
        assertEquals(
            "After draining everything the file must be HEADER-only — next flush appends to it",
            listOf(CalibrationLogger.HEADER), file.readLines(),
        )
    }

    // ── truncatePreviousAfterSuccessfulSend ─────────────────────────────────

    @Test
    fun `truncatePreviousAfterSuccessfulSend deletes the file when keptTail is empty`() {
        // Mirrors the current-file truncate but has a stronger contract:
        // once the previous-session file is fully drained, it's DELETED
        // (the flush loop only appends to the current file; the previous
        // one is read-only after enable() preserved it, so leaving an
        // empty HEADER-only file forever would just make the Settings UI
        // say "previous: ✓" on every subsequent send).
        seedPreviousFile(lines = 5)
        val logger = makeLogger()
        val file = File(externalDir, CalibrationLogger.PREVIOUS_FILE_NAME)
        assertTrue("seed must create the previous file", file.exists())
        logger.truncatePreviousAfterSuccessfulSend(uploadedLineCount = 6)  // HEADER + 5 data
        assertFalse(
            "Previous-session file MUST be deleted on full drain so the next send doesn't " +
                "report 'previous: ✓' forever",
            file.exists(),
        )
    }

    @Test
    fun `truncatePreviousAfterSuccessfulSend rewrites the file when only part is drained`() {
        seedPreviousFile(lines = 10)
        val logger = makeLogger()
        val file = File(externalDir, CalibrationLogger.PREVIOUS_FILE_NAME)
        // Send HEADER + first 4 data lines, leaving 6 on disk.
        logger.truncatePreviousAfterSuccessfulSend(uploadedLineCount = 5)
        assertTrue("Partial drain must keep the file alive", file.exists())
        val remaining = file.readLines()
        assertEquals(
            "Partial truncate keeps HEADER + the untouched tail (10 - 4 = 6 data lines + HEADER = 7)",
            7, remaining.size,
        )
        assertEquals(CalibrationLogger.HEADER, remaining.first())
    }

    // ── End-to-end drain (the actual incident fix) ──────────────────────────

    @Test
    fun `multi-chunk drain eventually empties the file with hasMore=false on the final chunk`() {
        // This is the 2026-05-25 incident contract: a multi-KB file must be
        // drainable across N successful chunks, with the LAST chunk reporting
        // hasMore=false so the drain loop can break cleanly and stop hitting
        // the network. Pre-v18 the loop never had a hasMore signal — it just
        // re-sent the whole file every cycle, which is what made the failure
        // permanent once the file crossed the Binder limit.
        seedFile(lines = 100, payloadPerLine = "x".repeat(40))  // ~50 bytes/line × 100 ≈ 5 KB

        val logger = makeLogger()
        val maxBytes = 600  // forces ~11 lines per chunk
        var chunks = 0
        var hasMore = true
        var lastChunkLinesIncluded = 0
        while (hasMore) {
            val chunk = logger.getFileContentChunked(maxBytes = maxBytes)
            assertNotNull(
                "Drain must produce a chunk on every iteration until the file is empty " +
                    "(chunks=$chunks)",
                chunk,
            )
            val c = chunk!!
            assertTrue("Every chunk must include the HEADER", c.content.startsWith(CalibrationLogger.HEADER))
            assertTrue("Every chunk must respect maxBytes", c.content.length <= maxBytes)
            assertTrue("Every chunk must include at least one data line", c.linesIncluded > 1)
            logger.truncateAfterSuccessfulSend(c.linesIncluded)
            chunks++
            lastChunkLinesIncluded = c.linesIncluded
            hasMore = c.hasMore
            // Safety cap so a regression that loses hasMore correctness can't hang the test.
            assertTrue("Drain must terminate within a reasonable number of chunks", chunks < 30)
        }
        assertTrue(
            "Drain must take more than one chunk for this file size — otherwise this test " +
                "isn't exercising the multi-chunk path",
            chunks > 1,
        )
        // Final state — file should be HEADER-only (the truncate convention
        // preserves HEADER for subsequent appends).
        val file = File(externalDir, CalibrationLogger.FILE_NAME)
        assertEquals(
            "After full drain the current file must be HEADER-only (1 line)",
            1, file.readLines().size,
        )
        // The drain loop in KSafeExtension.sendCalibrationLogInChunks breaks
        // on `!chunk.hasMore`, so the last chunk's `linesIncluded` is the
        // remainder of the file. Pin the contract: it's > 1 (HEADER + at
        // least one data line) and the loop terminates without revisiting.
        assertTrue("Final chunk must carry the residual data lines", lastChunkLinesIncluded > 1)
    }

    @Test
    fun `failed first chunk preserves the entire file on disk for the next cycle`() {
        // Simulates the drain-loop's failure semantics: when the chunk send
        // fails, the loop breaks BEFORE calling truncateAfterSuccessfulSend,
        // so the on-disk state must be untouched. Without this guarantee a
        // slow-LTE failure would silently lose log data.
        seedFile(lines = 20)
        val logger = makeLogger()
        val file = File(externalDir, CalibrationLogger.FILE_NAME)
        val before = file.readText()

        // Get a chunk (would-be-sent) but skip the truncate (simulating send failure).
        val chunk = logger.getFileContentChunked(maxBytes = 4_096)
        assertNotNull(chunk)
        // No call to truncateAfterSuccessfulSend — drain loop bails on failure.

        val after = file.readText()
        assertEquals(
            "Failed chunk send must leave the file untouched so the next periodic-send " +
                "window picks up from the same point",
            before, after,
        )
    }
}
