package com.enderthor.kSafe.extension.pacing

import kotlin.math.exp

/**
 * Skiba differential W′bal (Skiba et al. 2015): above CP dW = −(P−CP)·dt; below CP
 * dW = (CP−P)·(W0−W)/W0·dt. Integrated per power sample (~1 Hz). A gap > [GAP_MS] is integrated at 0 W
 * (a dropout must not hold the last wattage). FTP is used as CP; a 20-min moving mean above it marks
 * the FTP stale for the ride and the balance untrusted.
 */
class WPrimeBalance(private val cpW: Int, private val w0: Double) {
    var wJ: Double = w0; private set
    /** Meaningless unless [trusted]; consumers must gate on it. */
    val pct: Double get() = (wJ / w0).coerceIn(0.0, 1.0)
    var ftpStale = false; private set
    val trusted: Boolean get() = cpW > 0 && !ftpStale

    private var lastMs = -1L
    /** False until the first power sample: a fresh balance can still be rebuilt with no loss. */
    val hasSamples: Boolean get() = lastMs >= 0
    private val ring = IntArray(WINDOW_S); private var ringN = 0; private var ringI = 0; private var ringSum = 0L

    fun onPower(powerW: Int, tMs: Long) {
        if (cpW <= 0) return
        if (lastMs >= 0) {
            val dtMs = tMs - lastMs
            if (dtMs > 0) {
                val p = if (dtMs > GAP_MS) 0.0 else powerW.toDouble()
                integrate(p, (dtMs.coerceAtMost(MAX_DT_MS)) / 1000.0)
            }
        }
        lastMs = tMs
        pushRing(powerW)
    }

    private fun integrate(p: Double, dt: Double) {
        wJ = if (p > cpW) wJ - (p - cpW) * dt else wJ + (cpW - p) * (w0 - wJ) / w0 * dt
        wJ = wJ.coerceIn(0.0, w0)
    }

    private fun pushRing(p: Int) {
        if (ringN == WINDOW_S) ringSum -= ring[ringI] else ringN++
        ring[ringI] = p; ringSum += p; ringI = (ringI + 1) % WINDOW_S
        if (ringN == WINDOW_S && ringSum.toDouble() / WINDOW_S > cpW) ftpStale = true
    }

    /** W′ % after riding [seconds] at constant [powerW] from now (closed form of the same model).
     *  Meaningless unless [trusted]; consumers must gate on it. */
    fun projectPct(seconds: Double, powerW: Double): Double {
        val w = if (powerW > cpW) wJ - (powerW - cpW) * seconds
                else w0 - (w0 - wJ) * exp(-(cpW - powerW) * seconds / w0)
        return (w / w0).coerceIn(0.0, 1.0)
    }

    companion object {
        const val GAP_MS = 5_000L
        const val MAX_DT_MS = 600_000L
        const val WINDOW_S = 1200
        fun defaultWPrimeJ(kind: BikeKind): Double = if (kind == BikeKind.MTB) 20_000.0 else 15_000.0
    }
}
