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
    fun `v15 config is bumped to CONFIG_VERSION with all other fields preserved`() {
        val current = KSafeConfig(
            configVersion = 15,
            wellnessCriticalThresholdBpm = 175,
            wellnessHighHrThreshold = 180,
        )
        val migrated = current.migrateToLatest()
        assertEquals(CONFIG_VERSION, migrated.configVersion)
        assertEquals(175, migrated.wellnessCriticalThresholdBpm)
        assertEquals(180, migrated.wellnessHighHrThreshold)
        // v15→v16 is a pure version stamp; the new buzzerOnEmergencyEnabled default (true)
        // applies to every existing rider so the audible-on-mute bypass is on out of the box.
        assertEquals(true, migrated.buzzerOnEmergencyEnabled)
        // v16→v17 default for the new reminder interval is 10 min — deliberately
        // less aggressive than the historical hard-coded 5-min cooldown.
        assertEquals(10, migrated.carbDeficitReminderIntervalMin)
        assertEquals(10, migrated.hydrationDeficitReminderIntervalMin)
    }

    @Test
    fun `v16 config is bumped to CONFIG_VERSION preserving rider opt-out and reminder defaults`() {
        val current = KSafeConfig(
            configVersion = 16,
            buzzerOnEmergencyEnabled = false,
        )
        val migrated = current.migrateToLatest()
        assertEquals(CONFIG_VERSION, migrated.configVersion)
        // Migration must not flip the rider's explicit opt-out back to the default.
        assertEquals(false, migrated.buzzerOnEmergencyEnabled)
        // v16→v17 stamps the new reminder fields at the conservative 10-min default.
        assertEquals(10, migrated.carbDeficitReminderIntervalMin)
        assertEquals(10, migrated.hydrationDeficitReminderIntervalMin)
    }

    @Test
    fun `v17 config is bumped to CONFIG_VERSION preserving fields and stamping physiology defaults`() {
        val current = KSafeConfig(
            configVersion = 17,
            buzzerOnEmergencyEnabled = false,
            carbDeficitReminderIntervalMin = 15,
            hydrationDeficitReminderIntervalMin = 10,
        )
        val migrated = current.migrateToLatest()
        assertEquals(CONFIG_VERSION, migrated.configVersion)
        assertEquals(false, migrated.buzzerOnEmergencyEnabled)
        assertEquals(15, migrated.carbDeficitReminderIntervalMin)
        assertEquals(10, migrated.hydrationDeficitReminderIntervalMin)
        // v17→v18 stamps the new rider-physiology fields at their "not set"
        // defaults so the tracker falls back to Swain HRR until the rider opens
        // Settings.
        assertEquals(0, migrated.riderAge)
        assertEquals(com.enderthor.kSafe.data.RiderSex.NOT_SET, migrated.riderSex)
    }

    @Test
    fun `v18 config migrates to CONFIG_VERSION preserving physiology fields`() {
        // v18 now migrates to v19 (per-profile crash overrides). Existing physiology
        // fields must survive the hop and crashProfileSettings defaults to empty.
        val current = KSafeConfig(
            configVersion = 18,
            riderAge = 40,
            riderSex = com.enderthor.kSafe.data.RiderSex.MALE,
        )
        val migrated = current.migrateToLatest()
        assertEquals(CONFIG_VERSION, migrated.configVersion)
        assertEquals(40, migrated.riderAge)
        assertEquals(com.enderthor.kSafe.data.RiderSex.MALE, migrated.riderSex)
    }

    @Test
    fun `v18 config migrates to CONFIG_VERSION with empty crashProfileSettings and fields preserved`() {
        val old = KSafeConfig(
            configVersion = 18,
            crashSensitivity = CrashSensitivity.HIGH,
            minSpeedForCrashKmh = 15,
        )
        val migrated = old.migrateToLatest()
        assertEquals(CONFIG_VERSION, migrated.configVersion)
        assertEquals(emptyList<CrashProfileSetting>(), migrated.crashProfileSettings)
        assertEquals(CrashSensitivity.HIGH, migrated.crashSensitivity)
        assertEquals(15, migrated.minSpeedForCrashKmh)
    }

    @Test
    fun `v19 config preserves existing crashProfileSettings`() {
        val setting = CrashProfileSetting(
            profileId = "p1",
            profileName = "Gravel",
            useGlobal = false,
            crashSensitivity = CrashSensitivity.LOW,
        )
        val old = KSafeConfig(configVersion = 19, crashProfileSettings = listOf(setting))
        val migrated = old.migrateToLatest()
        assertEquals(CONFIG_VERSION, migrated.configVersion)
        assertEquals(listOf(setting), migrated.crashProfileSettings)
    }

    @Test
    fun `v20 config migrates to current version with combined-field defaults`() {
        val old = KSafeConfig(configVersion = 20)
        val migrated = old.migrateToLatest()
        assertEquals(CONFIG_VERSION, migrated.configVersion)
        assertEquals(60, migrated.combinedCarbConcentrationPer500ml)
        assertEquals(250, migrated.combined1Ml)
        assertEquals(30, migrated.combined1Carbs)
        assertEquals(500, migrated.combined2Ml)
        assertEquals(60, migrated.combined2Carbs)
    }

    @Test
    fun `v21 migrates to 22 with fueling alert button mode defaulting to OFF`() {
        val old = KSafeConfig(configVersion = 21)
        val migrated = old.migrateToLatest()
        assertEquals(CONFIG_VERSION, migrated.configVersion)
        assertEquals(FuelingAlertButtonMode.OFF, migrated.fuelingAlertButtonMode)
    }

    @Test
    fun `v22 stamps to current version and defaults hrCaloriesEnabled off`() {
        val migrated = KSafeConfig(configVersion = 22).migrateToLatest()
        assertEquals(CONFIG_VERSION, migrated.configVersion)
        assertEquals(false, migrated.hrCaloriesEnabled)
    }

    @Test
    fun `explicit hrCaloriesEnabled true survives migration`() {
        val migrated = KSafeConfig(configVersion = 22, hrCaloriesEnabled = true).migrateToLatest()
        assertEquals(CONFIG_VERSION, migrated.configVersion)
        assertEquals(true, migrated.hrCaloriesEnabled)
    }

    @Test
    fun `v23 stamps to v24 and defaults fitStandardCaloriesSource to NONE`() {
        val migrated = KSafeConfig(configVersion = 23).migrateToLatest()
        assertEquals(CONFIG_VERSION, migrated.configVersion)
        assertEquals(FitCaloriesSource.NONE, migrated.fitStandardCaloriesSource)
    }

    @Test
    fun `explicit fitStandardCaloriesSource survives migration`() {
        val migrated = KSafeConfig(
            configVersion = 23,
            fitStandardCaloriesSource = FitCaloriesSource.KAROO,
        ).migrateToLatest()
        assertEquals(CONFIG_VERSION, migrated.configVersion)
        assertEquals(FitCaloriesSource.KAROO, migrated.fitStandardCaloriesSource)
    }

    @Test
    fun `v24 migrates to current and webhook 3 and 4 arrive at defaults`() {
        val old = KSafeConfig(
            configVersion = 24,
            webhook1Enabled = true,
            webhook1Label = "My WH1",
            webhook1Url = "https://example.com/1",
        )
        val migrated = old.migrateToLatest()
        assertEquals(CONFIG_VERSION, migrated.configVersion)
        assertEquals(true, migrated.webhook1Enabled)
        assertEquals("My WH1", migrated.webhook1Label)
        assertEquals("https://example.com/1", migrated.webhook1Url)
        assertEquals(false, migrated.webhook3Enabled)
        assertEquals("Action 3", migrated.webhook3Label)
        assertEquals("", migrated.webhook3Url)
        assertEquals(false, migrated.webhook4Enabled)
        assertEquals("Action 4", migrated.webhook4Label)
    }
}
