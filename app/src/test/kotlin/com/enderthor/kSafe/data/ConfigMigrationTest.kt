package com.enderthor.kSafe.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ConfigMigrationTest {

    @Test
    fun `v13 legacy carb detail is copied into both new carb fields`() {
        @Suppress("DEPRECATION")
        val old = KSafeConfig(
            configVersion = 13,
            carbAlertCustomDetail = "Eat your gel {elapsed}",
        )
        val migrated = old.migrateToLatest()
        assertEquals(CONFIG_VERSION, migrated.configVersion)
        assertEquals("Eat your gel {elapsed}", migrated.carbAlertCustomDetailTime)
        assertEquals("Eat your gel {elapsed}", migrated.carbAlertCustomDetailDeficit)
    }

    @Test
    fun `v13 legacy hydration detail is copied into both new hydration fields`() {
        @Suppress("DEPRECATION")
        val old = KSafeConfig(
            configVersion = 13,
            hydrationAlertCustomDetail = "Drink up — {target} ml/h",
        )
        val migrated = old.migrateToLatest()
        assertEquals(CONFIG_VERSION, migrated.configVersion)
        assertEquals("Drink up — {target} ml/h", migrated.hydrationAlertCustomDetailTime)
        assertEquals("Drink up — {target} ml/h", migrated.hydrationAlertCustomDetailDeficit)
    }

    @Test
    fun `v13 with blank legacy detail leaves all four new fields blank`() {
        val old = KSafeConfig(configVersion = 13)
        val migrated = old.migrateToLatest()
        assertEquals(CONFIG_VERSION, migrated.configVersion)
        assertEquals("", migrated.carbAlertCustomDetailTime)
        assertEquals("", migrated.carbAlertCustomDetailDeficit)
        assertEquals("", migrated.hydrationAlertCustomDetailTime)
        assertEquals("", migrated.hydrationAlertCustomDetailDeficit)
    }
}
