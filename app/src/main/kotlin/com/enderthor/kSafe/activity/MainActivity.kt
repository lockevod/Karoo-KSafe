package com.enderthor.kSafe.activity

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
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
        // The previous version auto-launched the system Settings page from onCreate if the
        // SYSTEM_ALERT_WINDOW permission was missing — but TabLayout also shows an in-app
        // banner with a "Grant" button, so the rider was getting a double-prompt at first
        // launch (Settings dialog appears before the app UI is even visible, then the
        // banner is also there when they return). The banner is the gentler, more
        // explanatory prompt; rely on it alone. Logged for first-launch diagnostics so the
        // rider's logcat shows why the overlay isn't appearing yet.
        if (!Settings.canDrawOverlays(this)) {
            Timber.w("KSafe: SYSTEM_ALERT_WINDOW not granted — banner in TabLayout will prompt the rider")
        }
    }
}
