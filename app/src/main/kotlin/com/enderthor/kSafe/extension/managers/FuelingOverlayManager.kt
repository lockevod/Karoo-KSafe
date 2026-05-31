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
import timber.log.Timber

/**
 * Standalone overlay for the tappable fueling prompt. DELIBERATELY separate from
 * [SosOverlayManager] (own class, own view, own layout) so the safety-critical SOS overlay
 * cannot be affected by changes here. Requires SYSTEM_ALERT_WINDOW.
 */
class FuelingOverlayManager(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: View? = null

    /** Shows the prompt with a single action button. Safe to call from any thread. */
    fun showPrompt(title: String, detail: String, buttonLabel: String, autoDismissMs: Long, onButton: () -> Unit) {
        mainHandler.post {
            try {
                if (!Settings.canDrawOverlays(context)) {
                    Timber.w("FuelingOverlay: SYSTEM_ALERT_WINDOW not granted — skipped")
                    return@post
                }
                removeInternal()
                val v = LayoutInflater.from(context).inflate(R.layout.overlay_fueling_prompt, null, false)
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT,
                ).apply { gravity = Gravity.TOP; y = 0 }
                v.findViewById<TextView>(R.id.tv_fuel_title)?.text = title
                v.findViewById<TextView>(R.id.tv_fuel_detail)?.text = detail
                v.findViewById<TextView>(R.id.btn_fuel_action)?.apply {
                    text = buttonLabel
                    setOnClickListener { onButton() }
                }
                windowManager.addView(v, params)
                view = v
                mainHandler.postDelayed({ if (view === v) removeInternal() }, autoDismissMs)
                Timber.d("FuelingOverlay: shown")
            } catch (e: Exception) {
                Timber.e(e, "FuelingOverlay: show error")
            }
        }
    }

    /** Removes the overlay. Safe to call from any thread. */
    fun remove() { mainHandler.post { removeInternal() } }

    private fun removeInternal() {
        view?.let { v ->
            try { windowManager.removeView(v); view = null }
            catch (e: Exception) { Timber.w(e, "FuelingOverlay: removeView threw") }
        }
    }
}
