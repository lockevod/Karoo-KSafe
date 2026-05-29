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
}
