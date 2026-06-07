package com.enderthor.kSafe.extension.managers

import com.enderthor.kSafe.extension.jsonWithUnknownKeys
import kotlinx.serialization.Serializable
import timber.log.Timber

/**
 * Subset of the OTA `manifest.json` we care about for the update notice.
 * Defaults make a missing/garbled manifest decode to "no update" rather than throw.
 */
@Serializable
data class UpdateManifest(
    val latestVersionCode: Int = 0,
    val latestVersion: String = "",
)

/**
 * Pure decision logic for the update-availability notice. No Android / network /
 * overlay dependencies so it is JVM-unit-testable; the service does the I/O and
 * calls these to decide.
 */
object UpdateChecker {

    /** A newer build exists iff the published versionCode is strictly greater than ours. */
    fun isNewer(latestVersionCode: Int, currentVersionCode: Int): Boolean =
        latestVersionCode > currentVersionCode

    /** Parse the manifest body; null on any malformed / non-JSON input (caller treats as "no update"). */
    fun parseManifest(raw: String): UpdateManifest? =
        try {
            jsonWithUnknownKeys.decodeFromString<UpdateManifest>(raw)
        } catch (e: Exception) {
            Timber.d(e, "UpdateChecker: manifest parse failed (${raw.take(60)})")
            null
        }

    /**
     * Whether to show the notice this boot. All must hold:
     * toggle on; restartCount a multiple of everyN; not already shown today;
     * a newer version is available; and no ride is active.
     */
    fun shouldNotify(
        enabled: Boolean,
        restartCount: Int,
        everyN: Int,
        lastNoticeEpochDay: Long,
        todayEpochDay: Long,
        isNewer: Boolean,
        rideActive: Boolean,
    ): Boolean =
        enabled &&
            isNewer &&
            !rideActive &&
            everyN > 0 && restartCount % everyN == 0 &&
            lastNoticeEpochDay != todayEpochDay
}
