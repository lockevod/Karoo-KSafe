package com.enderthor.kSafe

import android.app.Application
import timber.log.Timber

class KSafeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val debug = false
        if (BuildConfig.DEBUG || debug) {
            Timber.plant(Timber.DebugTree())
        }
    }
}
