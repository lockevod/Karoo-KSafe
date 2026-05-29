package com.enderthor.kSafe.extension.util

import com.enderthor.kSafe.data.CrashProfileSetting
import com.enderthor.kSafe.data.CrashSensitivity
import com.enderthor.kSafe.data.KSafeConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class CrashProfileResolverTest {
    private val global = KSafeConfig(
        crashDetectionEnabled = true,
        crashSensitivity = CrashSensitivity.MEDIUM,
        minSpeedForCrashKmh = 10,
        customCrashThreshold = 45,
        crashConfirmSpeedKmh = 5,
    )

    @Test fun `null active id returns global unchanged`() {
        assertEquals(global, resolveEffectiveCrashConfig(global, null))
    }

    @Test fun `unknown id returns global unchanged`() {
        val g = global.copy(crashProfileSettings = listOf(CrashProfileSetting("p1", "Gravel", useGlobal = false)))
        assertEquals(g, resolveEffectiveCrashConfig(g, "OTHER"))
    }

    @Test fun `useGlobal entry returns global unchanged`() {
        val g = global.copy(crashProfileSettings = listOf(CrashProfileSetting("p1", "Gravel", useGlobal = true)))
        assertEquals(g, resolveEffectiveCrashConfig(g, "p1"))
    }

    @Test fun `custom entry substitutes the crash fields only`() {
        val setting = CrashProfileSetting("p1", "Enduro", useGlobal = false,
            crashSensitivity = CrashSensitivity.LOW, customCrashThreshold = 60,
            minSpeedForCrashKmh = 3, crashConfirmSpeedKmh = 3)
        val g = global.copy(crashProfileSettings = listOf(setting))
        val eff = resolveEffectiveCrashConfig(g, "p1")
        assertEquals(CrashSensitivity.LOW, eff.crashSensitivity)
        assertEquals(60, eff.customCrashThreshold)
        assertEquals(3, eff.minSpeedForCrashKmh)
        assertEquals(3, eff.crashConfirmSpeedKmh)
        assertEquals(global.countdownSeconds, eff.countdownSeconds)
    }

    @Test fun `global off forces effective off even when custom enabled`() {
        val setting = CrashProfileSetting("p1", "x", useGlobal = false, crashDetectionEnabled = true)
        val g = global.copy(crashDetectionEnabled = false, crashProfileSettings = listOf(setting))
        assertEquals(false, resolveEffectiveCrashConfig(g, "p1").crashDetectionEnabled)
    }

    @Test fun `custom off disables even when global on`() {
        val setting = CrashProfileSetting("p1", "indoor", useGlobal = false, crashDetectionEnabled = false)
        val g = global.copy(crashDetectionEnabled = true, crashProfileSettings = listOf(setting))
        assertEquals(false, resolveEffectiveCrashConfig(g, "p1").crashDetectionEnabled)
    }

    @Test fun `learn appends an unseen profile as useGlobal`() {
        val out = learnProfile(emptyList(), "p1", "Gravel")
        assertEquals(1, out.size)
        assertEquals("p1", out[0].profileId)
        assertEquals("Gravel", out[0].profileName)
        assertEquals(true, out[0].useGlobal)
    }

    @Test fun `learn refreshes the name on rename, keeping custom fields`() {
        val seed = listOf(CrashProfileSetting("p1", "Gravel", useGlobal = false,
            crashSensitivity = CrashSensitivity.LOW))
        val out = learnProfile(seed, "p1", "Gravel Race")
        assertEquals(1, out.size)
        assertEquals("Gravel Race", out[0].profileName)
        assertEquals(false, out[0].useGlobal)
        assertEquals(CrashSensitivity.LOW, out[0].crashSensitivity)
    }

    @Test fun `learn is a no-op when id and name already match`() {
        val seed = listOf(CrashProfileSetting("p1", "Gravel"))
        assertEquals(seed, learnProfile(seed, "p1", "Gravel"))
    }

    @Test fun `learn prunes a stale same-name orphan (delete-then-recreate)`() {
        // Old "Gravel" (id A, custom) deleted on the Karoo; recreated as id B, same name.
        val seed = listOf(CrashProfileSetting("A", "Gravel", useGlobal = false,
            crashSensitivity = CrashSensitivity.LOW))
        val out = learnProfile(seed, "B", "Gravel")
        assertEquals(1, out.size)
        assertEquals("B", out[0].profileId)      // orphan A removed
        assertEquals(true, out[0].useGlobal)     // recreated profile starts fresh
    }

    @Test fun `learn keeps other-named entries when pruning`() {
        val seed = listOf(
            CrashProfileSetting("A", "Gravel"),
            CrashProfileSetting("C", "Enduro", useGlobal = false),
        )
        val out = learnProfile(seed, "B", "Gravel")   // recreate Gravel as B
        assertEquals(setOf("B", "C"), out.map { it.profileId }.toSet())  // Enduro untouched
    }
}
