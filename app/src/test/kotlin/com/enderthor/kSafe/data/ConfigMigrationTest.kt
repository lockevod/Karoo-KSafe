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

    @Test
    fun `v13 with new detail fields already set keeps them and ignores legacy`() {
        @Suppress("DEPRECATION")
        val old = KSafeConfig(
            configVersion = 13,
            carbAlertCustomDetail = "legacy",
            carbAlertCustomDetailTime = "already-time",
            carbAlertCustomDetailDeficit = "already-deficit",
        )
        val migrated = old.migrateToLatest()
        assertEquals("already-time", migrated.carbAlertCustomDetailTime)
        assertEquals("already-deficit", migrated.carbAlertCustomDetailDeficit)
    }

    @Test
    fun `v14 with default 175 and default sustained 180 nudges critical to 185`() {
        val old = KSafeConfig(
            configVersion = 14,
            wellnessCriticalThresholdBpm = 175,
            wellnessHighHrThreshold = 180,
        )
        val migrated = old.migrateToLatest()
        assertEquals(CONFIG_VERSION, migrated.configVersion)
        assertEquals(185, migrated.wellnessCriticalThresholdBpm)
        assertEquals(180, migrated.wellnessHighHrThreshold)
    }

    @Test
    fun `v14 with customised critical keeps the customisation`() {
        val old = KSafeConfig(
            configVersion = 14,
            wellnessCriticalThresholdBpm = 170,
            wellnessHighHrThreshold = 180,
        )
        val migrated = old.migrateToLatest()
        assertEquals(CONFIG_VERSION, migrated.configVersion)
        assertEquals(170, migrated.wellnessCriticalThresholdBpm)
        assertEquals(180, migrated.wellnessHighHrThreshold)
    }

    @Test
    fun `v14 with customised sustained keeps both unchanged`() {
        val old = KSafeConfig(
            configVersion = 14,
            wellnessCriticalThresholdBpm = 175,
            wellnessHighHrThreshold = 175,
        )
        val migrated = old.migrateToLatest()
        assertEquals(CONFIG_VERSION, migrated.configVersion)
        assertEquals(175, migrated.wellnessCriticalThresholdBpm)
        assertEquals(175, migrated.wellnessHighHrThreshold)
    }

    @Test
    fun `v15 config is bumped to v16 with all other fields preserved`() {
        val current = KSafeConfig(
            configVersion = 15,
            wellnessCriticalThresholdBpm = 175,
            wellnessHighHrThreshold = 180,
        )
        val migrated = current.migrateToLatest()
        assertEquals(16, migrated.configVersion)
        assertEquals(175, migrated.wellnessCriticalThresholdBpm)
        assertEquals(180, migrated.wellnessHighHrThreshold)
        // v15→v16 is a pure version stamp; the new buzzerOnEmergencyEnabled default (true)
        // applies to every existing rider so the audible-on-mute bypass is on out of the box.
        assertEquals(true, migrated.buzzerOnEmergencyEnabled)
    }

    @Test
    fun `v16 config is left unchanged by the migration`() {
        val current = KSafeConfig(
            configVersion = 16,
            buzzerOnEmergencyEnabled = false,
        )
        val migrated = current.migrateToLatest()
        assertEquals(16, migrated.configVersion)
        // Migration must not flip the rider's explicit opt-out back to the default.
        assertEquals(false, migrated.buzzerOnEmergencyEnabled)
    }
}
