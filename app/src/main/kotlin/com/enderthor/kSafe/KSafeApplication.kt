package com.enderthor.kSafe

import android.app.Application
import android.util.Log
import timber.log.Timber

class KSafeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        } else {
            // In release: only WARN and ERROR reach logcat (useful for ADB diagnostics)
            // DEBUG/INFO/VERBOSE are stripped entirely by ProGuard -assumenosideeffects
            Timber.plant(object : Timber.Tree() {
                override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                    if (priority >= Log.WARN) {
                        Log.println(priority, tag ?: "KSafe", message)
                        t?.let { Log.e(tag ?: "KSafe", message, it) }
                    }
                }
            })
        }
    }
}
