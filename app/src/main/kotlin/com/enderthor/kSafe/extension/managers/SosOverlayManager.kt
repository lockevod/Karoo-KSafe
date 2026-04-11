package com.enderthor.kSafe.extension.managers

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.enderthor.kSafe.R
import com.enderthor.kSafe.data.EmergencyReason
import timber.log.Timber

/**
 * Manages a full-screen-width SOS cancel overlay using WindowManager.TYPE_APPLICATION_OVERLAY.
 *
 * This is the same approach used by ki2 (OverlayWindowHandler / BaseOverlayManager) and
 * karoo-powerbar — it draws on top of any app screen on the device without needing to
 * inject into a specific Activity.
 *
 * Requires: android.permission.SYSTEM_ALERT_WINDOW (declared in manifest).
 * On devices where the permission is not pre-granted, showOrUpdate() will log a warning
 * and do nothing; the user must grant "Draw over other apps" in Settings.
 */
class SosOverlayManager(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: View? = null

    /**
     * Shows the overlay (first call) or updates the countdown text (subsequent calls).
     * Safe to call from any thread — always dispatched to the main thread.
     */
    fun showOrUpdate(reason: EmergencyReason, remainingSeconds: Int, onCancel: () -> Unit) {
        mainHandler.post {
            try {
                if (!Settings.canDrawOverlays(context)) {
                    Timber.w("SosOverlay: SYSTEM_ALERT_WINDOW not granted — overlay skipped")
                    return@post
                }

                if (overlayView == null) {
                    val inflater = LayoutInflater.from(context)
                    val view = inflater.inflate(R.layout.overlay_sos_cancel, null, false)

                    val params = WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        // FLAG_NOT_FOCUSABLE  → overlay won't steal keyboard/button focus
                        // FLAG_NOT_TOUCH_MODAL → touches outside overlay pass through to app below
                        // FLAG_LAYOUT_IN_SCREEN → layout relative to full screen
                        // NOTE: FLAG_NOT_TOUCHABLE is NOT set → buttons inside are tappable
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT
                    ).apply {
                        gravity = Gravity.TOP
                        y = 0
                    }

                    windowManager.addView(view, params)
                    overlayView = view
                    Timber.d("SosOverlay: added via WindowManager")

                    view.findViewById<View>(R.id.btn_cancel_sos)?.setOnClickListener {
                        Timber.d("SosOverlay: Cancel tapped by user")
                        removeOverlayInternal()
                        onCancel()
                    }
                }

                // Update dynamic text fields every second
                overlayView?.apply {
                    findViewById<TextView>(R.id.tv_sos_reason)?.text = reason.label
                    findViewById<TextView>(R.id.tv_sos_countdown)?.text = "${remainingSeconds}s"
                }
            } catch (e: Exception) {
                Timber.e(e, "SosOverlay: unexpected error")
            }
        }
    }

    /** Removes the overlay. Safe to call from any thread. */
    fun removeOverlay() {
        mainHandler.post { removeOverlayInternal() }
    }

    private fun removeOverlayInternal() {
        overlayView?.let { view ->
            try {
                windowManager.removeView(view)
                Timber.d("SosOverlay: removed")
            } catch (e: Exception) {
                Timber.w(e, "SosOverlay: error removing overlay")
            }
            overlayView = null
        }
    }
}
