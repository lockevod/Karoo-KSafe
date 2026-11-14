package com.enderthor.kSafe.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PacingConfigMigrationTest {
    @Test fun `v28 config migrates to v29 with pacing off`() {
        val m = KSafeConfig(configVersion = 28).migrateToLatest()
        assertEquals(29, m.configVersion); assertFalse(m.pacingEnabled)
    }
}
