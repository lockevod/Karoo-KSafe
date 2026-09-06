package com.enderthor.kSafe

import android.app.Application
import android.util.Log
import timber.log.Timber

class KSafeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val testing = false // Set to true to enable debug logging in release builds (e.g. for ADB diagnostics)
        if (BuildConfig.DEBUG || testing ) {
            Timber.plant(Timber.DebugTree())
        } else {
            // In release: only WARN and ERROR reach logcat (useful for ADB diagnostics)
            // DEBUG/INFO/VERBOSE are stripped entirely by ProGuard -assumenosideeffects
            Timber.plant(object : Timber.Tree() {
                override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                    if (priority >= Log.WARN) {
                        // One record per call, at the caller's OWN priority. The previous
                        // form emitted the message twice whenever a throwable was attached —
                        // and the paths that attach one are exactly the retry loops (e.g. the
                        // location collector re-subscribing every 5 s), so the duplication
                        // scaled with the fault. Routing those through Log.e instead would
                        // fix the duplication but silently promote every Timber.w(e, …) to
                        // ERROR, so `logcat *:E` would fill with warnings. Append the stack
                        // trace to the message instead and keep the priority intact.
                        Log.println(
                            priority,
                            tag ?: "KSafe",
                            if (t != null) "$message\n${Log.getStackTraceString(t)}" else message,
                        )
                    }
                }
            })
        }
    }
}
