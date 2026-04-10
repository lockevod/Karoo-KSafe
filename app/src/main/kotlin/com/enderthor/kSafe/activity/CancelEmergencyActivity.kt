package com.enderthor.kSafe.activity

import android.app.Activity
import android.os.Bundle
import com.enderthor.kSafe.extension.KSafeExtension
import timber.log.Timber

/**
 * Transparent, no-history activity launched from the SystemNotification "Cancel" button.
 * Cancels the active emergency countdown and finishes immediately.
 * This allows the user to cancel an emergency even when no data fields are visible.
 */
class CancelEmergencyActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.d("CancelEmergencyActivity: user tapped Cancel from notification")
        KSafeExtension.getInstance()?.cancelEmergency()
        finish()
    }
}
