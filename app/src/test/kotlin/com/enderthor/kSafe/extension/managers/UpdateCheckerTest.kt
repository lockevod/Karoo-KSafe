package com.enderthor.kSafe.extension.managers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun `isNewer is true only when latest versionCode is strictly greater`() {
        assertTrue(UpdateChecker.isNewer(latestVersionCode = 202606040, currentVersionCode = 202606031))
        assertFalse(UpdateChecker.isNewer(latestVersionCode = 202606031, currentVersionCode = 202606031))
        assertFalse(UpdateChecker.isNewer(latestVersionCode = 202606010, currentVersionCode = 202606031))
    }

    @Test
    fun `parseManifest reads latestVersionCode and latestVersion`() {
        val raw = """{"label":"KSafe","latestVersion":"2.2.0","latestVersionCode":202606040,"extra":"ignored"}"""
        val m = UpdateChecker.parseManifest(raw)
        assertEquals(202606040, m?.latestVersionCode)
        assertEquals("2.2.0", m?.latestVersion)
    }

    @Test
    fun `parseManifest returns null on malformed or non-JSON body`() {
        assertNull(UpdateChecker.parseManifest("<html>404</html>"))
        assertNull(UpdateChecker.parseManifest(""))
    }

    @Test
    fun `parseManifest tolerates a manifest missing the version fields`() {
        // Defaults (latestVersionCode=0) make isNewer() false — treated as "no update".
        val m = UpdateChecker.parseManifest("""{"label":"KSafe"}""")
        assertEquals(0, m?.latestVersionCode)
    }

    // should-notify truth table. Baseline = all conditions satisfied → true.
    private fun should(
        enabled: Boolean = true,
        restartCount: Int = 3,
        everyN: Int = 3,
        lastNoticeEpochDay: Long = 100L,
        todayEpochDay: Long = 101L,
        isNewer: Boolean = true,
        rideActive: Boolean = false,
    ) = UpdateChecker.shouldNotify(enabled, restartCount, everyN, lastNoticeEpochDay, todayEpochDay, isNewer, rideActive)

    @Test
    fun `shouldNotify is true when every condition is satisfied`() {
        assertTrue(should())
    }

    @Test
    fun `shouldNotify is false when toggle off`() {
        assertFalse(should(enabled = false))
    }

    @Test
    fun `shouldNotify is false when restartCount is not a multiple of N`() {
        assertFalse(should(restartCount = 4, everyN = 3))
        assertTrue(should(restartCount = 6, everyN = 3))
    }

    @Test
    fun `shouldNotify is false when already shown today`() {
        assertFalse(should(lastNoticeEpochDay = 101L, todayEpochDay = 101L))
    }

    @Test
    fun `shouldNotify is false when no newer version`() {
        assertFalse(should(isNewer = false))
    }

    @Test
    fun `shouldNotify is false when a ride is active`() {
        assertFalse(should(rideActive = true))
    }
}
