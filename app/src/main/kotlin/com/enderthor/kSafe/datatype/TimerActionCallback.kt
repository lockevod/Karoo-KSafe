package com.enderthor.kSafe.datatype

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.enderthor.kSafe.extension.KSafeExtension
import timber.log.Timber

class TimerActionCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        Timber.d("Check-in tap received")
        KSafeExtension.getInstance()?.handleCheckinTap()
    }
}
