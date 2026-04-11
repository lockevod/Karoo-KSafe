package com.enderthor.kSafe.activity

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.enderthor.kSafe.screens.TabLayout
import timber.log.Timber
import androidx.core.net.toUri

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Composable
fun Main() {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        TabLayout()
    }
}

@SuppressLint("SetTextI18n")
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Main() }
        requestOverlayPermissionIfNeeded()
    }

    /**
     * KSafe needs SYSTEM_ALERT_WINDOW ("Draw over other apps") to show the SOS cancel overlay
     * on top of the Karoo ride screen from any screen. Same approach as ki2 and karoo-powerbar.
     * If not granted, open the system settings screen for the user to enable it.
     */
    private fun requestOverlayPermissionIfNeeded() {
        if (!Settings.canDrawOverlays(this)) {
            Timber.w("KSafe: SYSTEM_ALERT_WINDOW not granted — opening Settings for user")
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                "package:$packageName".toUri()
            )
            startActivity(intent)
        }
    }
}
