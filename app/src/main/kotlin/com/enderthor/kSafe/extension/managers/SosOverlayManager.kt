package com.enderthor.kSafe.extension.managers

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
    /** Separate reference from [overlayView] so the static info overlay and the live SOS
     *  countdown overlay never clobber each other's WindowManager view. */
    private var infoView: View? = null

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

                // Add a fresh overlay when there is none, OR when the held
                // reference is stale — a detached view left behind by a
                // removeView() that threw. Updating text on a detached view
                // would silently leave the rider with no visible overlay.
                val existing = overlayView
                if (existing == null || !existing.isAttachedToWindow) {
                    if (existing != null) {
                        runCatching { windowManager.removeView(existing) }
                        overlayView = null
                    }
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

    /**
     * Shows a static, dismissable info overlay (no countdown, no auto-dismiss) over any
     * screen — launcher, Settings or the ride screen. Used for the "SOS delivery failed"
     * alarm where a drawer [android.app.Notification] is too easy to miss, especially after
     * the ride has ended (which is exactly when the sender's ~30-min retry loop tends to give
     * up). Stays until the rider taps Dismiss (or [removeInfoOverlay] / teardown clears it) —
     * sticky on purpose, because "your SOS reached nobody" must not be dismissable by simply
     * looking away. Safe to call from any thread. No-op (logs) if SYSTEM_ALERT_WINDOW isn't
     * granted — the caller is expected to have already checked [Settings.canDrawOverlays] to
     * decide whether to use this or fall back to a notification.
     */
    fun showInfo(title: String, message: String, onDismiss: () -> Unit = {}) {
        mainHandler.post {
            try {
                if (!Settings.canDrawOverlays(context)) {
                    Timber.w("SosOverlay: SYSTEM_ALERT_WINDOW not granted — info overlay skipped")
                    return@post
                }
                // One-shot overlay: always rebuild. Reusing a possibly-detached view (left by a
                // removeView that threw) would risk a silent no-show on the most critical alert.
                infoView?.let { runCatching { windowManager.removeView(it) } }
                infoView = null

                val view = LayoutInflater.from(context).inflate(R.layout.overlay_info, null, false)
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP
                    y = 0
                }

                view.findViewById<TextView>(R.id.tv_info_title)?.text = title
                view.findViewById<TextView>(R.id.tv_info_message)?.text = message
                view.findViewById<View>(R.id.btn_dismiss_info)?.setOnClickListener {
                    Timber.d("SosOverlay: info Dismiss tapped")
                    removeInfoInternal()
                    onDismiss()
                }

                windowManager.addView(view, params)
                infoView = view
                Timber.d("SosOverlay: info overlay added")
            } catch (e: Exception) {
                Timber.e(e, "SosOverlay: info overlay error")
            }
        }
    }

    /** Removes the countdown overlay. Safe to call from any thread. */
    fun removeOverlay() {
        mainHandler.post { removeOverlayInternal() }
    }

    /** Removes the info overlay. Safe to call from any thread. */
    fun removeInfoOverlay() {
        mainHandler.post { removeInfoInternal() }
    }

    private fun removeInfoInternal() {
        infoView?.let { view ->
            try {
                windowManager.removeView(view)
                infoView = null
            } catch (e: Exception) {
                Timber.w(e, "SosOverlay: info removeView threw — keeping reference for next show")
            }
        }
    }

    private fun removeOverlayInternal() {
        overlayView?.let { view ->
            try {
                windowManager.removeView(view)
                Timber.d("SosOverlay: removed")
                overlayView = null  // only clear if the remove call actually succeeded
            } catch (e: Exception) {
                // Keep the reference: if the view is still attached, the next
                // showOrUpdate updates it in place; if removeView left it
                // detached, showOrUpdate detects that (isAttachedToWindow) and
                // re-adds a fresh overlay. Either way the rider is not stranded.
                Timber.w(e, "SosOverlay: removeView threw — keeping reference for next show")
            }
        }
    }
}
