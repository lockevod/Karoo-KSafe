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

    /**
     * Shows the prompt with a single action button. Safe to call from any thread.
     *
     * [abortIf] is re-evaluated on the main thread INSIDE the posted runnable, immediately
     * before the window is added. The caller's own pre-check (e.g. "no active emergency")
     * runs synchronously when it decides to call this, but the actual addView is deferred to
     * a later main-loop turn — so an emergency that starts in that gap would otherwise let a
     * fueling overlay surface on top of the SOS screen. Re-checking here closes that race and
     * also covers the nested LOG→UNDO follow-up, which is posted at button-tap time.
     */
    fun showPrompt(
        title: String,
        detail: String,
        buttonLabel: String,
        autoDismissMs: Long,
        abortIf: () -> Boolean = { false },
        onButton: () -> Unit,
    ) {
        mainHandler.post {
            try {
                if (abortIf()) {
                    Timber.d("FuelingOverlay: aborted before show (guard tripped, e.g. emergency active)")
                    removeInternal()   // clear any prior view so nothing lingers over the SOS screen
                    return@post
                }
                if (!Settings.canDrawOverlays(context)) {
                    Timber.w("FuelingOverlay: SYSTEM_ALERT_WINDOW not granted — skipped")
                    return@post
                }
                // Remove any prior view before adding a fresh one so overlays never stack —
                // route through removeInternal() so every teardown shares one code path
                // (same isAttachedToWindow guard + ref-clear).
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
            // Only remove an attached view — removeView on a detached one throws. Unlike the
            // SOS overlay (which keeps a stale ref to update in place), the fueling prompt
            // always rebuilds on the next show, so the ref is cleared unconditionally here.
            if (v.isAttachedToWindow) {
                try { windowManager.removeView(v) }
                catch (e: Exception) { Timber.w(e, "FuelingOverlay: removeView threw") }
            }
            view = null
        }
    }
}
