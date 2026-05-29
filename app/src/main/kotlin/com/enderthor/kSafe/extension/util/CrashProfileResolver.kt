package com.enderthor.kSafe.extension.util

import com.enderthor.kSafe.data.KSafeConfig

/**
 * Effective crash config for the active Karoo profile. If the active profile has a
 * "custom" override (matched by [activeProfileId]), the global crash fields are replaced
 * by the override's; otherwise [global] is returned unchanged.
 *
 * The global `crashDetectionEnabled` is the kill switch — a per-profile custom setting can
 * only further restrict it (logical AND), never re-enable. See the spec.
 */
fun resolveEffectiveCrashConfig(global: KSafeConfig, activeProfileId: String?): KSafeConfig {
    val setting = activeProfileId?.let { id -> global.crashProfileSettings.firstOrNull { it.profileId == id } }
    if (setting == null || setting.useGlobal) return global
    return global.copy(
        crashDetectionEnabled = global.crashDetectionEnabled && setting.crashDetectionEnabled,
        crashSensitivity = setting.crashSensitivity,
        customCrashThreshold = setting.customCrashThreshold,
        minSpeedForCrashKmh = setting.minSpeedForCrashKmh,
        crashConfirmSpeedKmh = setting.crashConfirmSpeedKmh,
    )
}
