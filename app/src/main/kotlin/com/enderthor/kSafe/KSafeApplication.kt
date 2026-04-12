package com.enderthor.kSafe

import android.app.Application
import timber.log.Timber

class KSafeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
    }
}
