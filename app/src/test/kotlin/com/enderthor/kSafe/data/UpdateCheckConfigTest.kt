package com.enderthor.kSafe.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckConfigTest {

    @Test
    fun `update check defaults on`() {
        assertTrue("update-availability check must default ON", KSafeConfig().updateCheckEnabled)
    }

    @Test
    fun `config version is at least 22`() {
        assertTrue("CONFIG_VERSION must be bumped for the new serialized field", CONFIG_VERSION >= 22)
    }

    @Test
    fun `migrating a v21 config stamps it to the current version and keeps the new default`() {
        val migrated = KSafeConfig(configVersion = 21).migrateToLatest()
        assertEquals(CONFIG_VERSION, migrated.configVersion)
        assertTrue(migrated.updateCheckEnabled)
    }
}
