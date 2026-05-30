package com.enderthor.kSafe.activity

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Filesystem + permission seam for the config backup (import/export). The backup lives in a
 * fixed SHARED folder (/sdcard/KSafe) so it survives an app uninstall/reinstall — unlike the
 * app-private external-files dir, which the OS wipes on uninstall. Reaching shared storage with
 * a plain File needs "All files access" on API 30+ (or legacy WRITE_EXTERNAL_STORAGE below
 * that). Requested lazily — only when the user runs a backup. See SettingsScreen.
 */
object BackupStorage {
    const val DIR_NAME = "KSafe"
    const val EXPORT_NAME = "ksafe_export.json"
    const val IMPORT_NAME = "ksafe_import.json"

    /** /sdcard/KSafe — the persistent backup directory. */
    fun backupDir(): File = File(Environment.getExternalStorageDirectory(), DIR_NAME)

    /** The file Export writes to. */
    fun exportFile(): File = File(backupDir(), EXPORT_NAME)

    /** True once the user has granted the access the backup needs for this API level. */
    fun hasAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }

    /**
     * Intent to this app's All-files-access settings screen (API 30+), or null if no Activity
     * can handle it (a Karoo OS that doesn't expose the screen → caller shows the adb fallback)
     * or below API 30 (caller requests the runtime WRITE_EXTERNAL_STORAGE permission instead).
     */
    fun allFilesSettingsIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val intent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
        return if (intent.resolveActivity(context.packageManager) != null) intent else null
    }

    /**
     * Which file Import should read: the new shared location first, else the legacy app-private
     * path (one-version back-compat), else null if neither exists. Pure — callers pass the two
     * candidate directories so it stays unit-testable.
     */
    fun resolveImportFile(sharedDir: File, legacyDir: File?): File? {
        val shared = File(sharedDir, IMPORT_NAME)
        if (shared.exists()) return shared
        val legacy = legacyDir?.let { File(it, IMPORT_NAME) }
        return if (legacy != null && legacy.exists()) legacy else null
    }
}
