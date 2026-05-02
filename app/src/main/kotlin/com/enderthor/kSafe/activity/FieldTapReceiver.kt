package com.enderthor.kSafe.activity

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.enderthor.kSafe.extension.KSafeExtension
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * BroadcastReceiver for data field taps.
 * Each field sends a broadcast with a unique intent action so Android creates
 * genuinely different PendingIntents (action IS part of PendingIntent identity).
 * Using a broadcast avoids bringing the app to the foreground (unlike startActivity).
 */
class FieldTapReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ext = KSafeExtension.getInstance() ?: run {
            Timber.w("FieldTapReceiver: KSafeExtension not available")
            return
        }
        when (intent.action) {
            ACTION_SOS -> {
                Timber.d("FieldTapReceiver: SOS tap")
                ext.handleSOSTap()
            }
            ACTION_TIMER -> {
                Timber.d("FieldTapReceiver: Timer tap")
                ext.handleCheckinTap()
            }
            ACTION_CUSTOM_MESSAGE -> {
                Timber.d("FieldTapReceiver: Custom message tap (slot 1)")
                ext.launch { ext.sendCustomMessage(1) }
            }
            ACTION_CUSTOM_MESSAGE_2 -> {
                Timber.d("FieldTapReceiver: Custom message tap (slot 2)")
                ext.launch { ext.sendCustomMessage(2) }
            }
            ACTION_CUSTOM_MESSAGE_3 -> {
                Timber.d("FieldTapReceiver: Custom message tap (slot 3)")
                ext.launch { ext.sendCustomMessage(3) }
            }
            ACTION_WEBHOOK_1 -> {
                Timber.d("FieldTapReceiver: Webhook tap (slot 1)")
                ext.handleWebhookTap(1)
            }
            ACTION_WEBHOOK_2 -> {
                Timber.d("FieldTapReceiver: Webhook tap (slot 2)")
                ext.handleWebhookTap(2)
            }
        }
    }

    companion object {
        const val ACTION_SOS              = "com.enderthor.kSafe.TAP_SOS"
        const val ACTION_TIMER            = "com.enderthor.kSafe.TAP_TIMER"
        const val ACTION_CUSTOM_MESSAGE   = "com.enderthor.kSafe.TAP_CUSTOM_MESSAGE"
        const val ACTION_CUSTOM_MESSAGE_2 = "com.enderthor.kSafe.TAP_CUSTOM_MESSAGE_2"
        const val ACTION_CUSTOM_MESSAGE_3 = "com.enderthor.kSafe.TAP_CUSTOM_MESSAGE_3"
        const val ACTION_WEBHOOK_1        = "com.enderthor.kSafe.TAP_WEBHOOK_1"
        const val ACTION_WEBHOOK_2        = "com.enderthor.kSafe.TAP_WEBHOOK_2"
    }
}

