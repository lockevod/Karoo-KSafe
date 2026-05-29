package com.enderthor.kSafe.extension.util

import com.enderthor.kSafe.data.CrashProfileSetting
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

/**
 * Returns [settings] reconciled for the just-activated profile ([id], [name]):
 *  - **prunes stale same-name orphans**: drops entries with `profileName == name` but a
 *    different id. The Karoo forbids two live profiles with the same name, so such an
 *    entry is a deleted-then-recreated predecessor (see spec) — removed automatically.
 *  - appends a `useGlobal = true` entry if [id] is unseen,
 *  - refreshes the stored [name] on a rename of this profile (matched by id, custom
 *    fields preserved),
 *  - returns the list unchanged when nothing differs.
 * Matching/identity is by id only; the name is used solely to prune orphans.
 */
fun learnProfile(settings: List<CrashProfileSetting>, id: String, name: String): List<CrashProfileSetting> {
    // Remove stale predecessors that reused this name under a different id. The target's
    // own entry (matched by id) is never pruned even if its name equals [name].
    val pruned = settings.filterNot { it.profileName == name && it.profileId != id }
    val existing = pruned.firstOrNull { it.profileId == id }
    return when {
        existing == null -> pruned + CrashProfileSetting(profileId = id, profileName = name)
        existing.profileName != name -> pruned.map { if (it.profileId == id) it.copy(profileName = name) else it }
        else -> pruned
    }
}
