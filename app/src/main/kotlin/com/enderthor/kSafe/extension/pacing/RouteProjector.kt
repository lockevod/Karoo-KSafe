package com.enderthor.kSafe.extension.pacing

import kotlin.math.cos
import kotlin.math.sqrt

enum class PosReason { OK, REJOIN, STALE_FIX, POOR_ACCURACY, OFF_ROUTE, NO_ROUTE }
data class RoutePos(val distanceM: Double?, val reason: PosReason)

/**
 * GPS → distance along route, windowed around the last good position so it cannot flip onto another
 * pass of a self-overlapping route. Constants and approach from KGhost KGhostExtension (route
 * projector), which replaced `routeDistance − DISTANCE_TO_DESTINATION` after field wrong-pass reports.
 */
class RouteProjector(val path: PolylinePath) {
    var lastGoodM: Double? = null; private set
    private var lastGoodOdoM = 0.0

    fun update(fix: LatLng, accuracyM: Double?, fixAgeMs: Long, odometerM: Double, rejoinActive: Boolean): RoutePos {
        if (rejoinActive) return RoutePos(null, PosReason.REJOIN)
        if (fixAgeMs > FIX_FRESH_MS) return RoutePos(null, PosReason.STALE_FIX)
        if (accuracyM != null && accuracyM > MAX_ACCURACY_M) return RoutePos(null, PosReason.POOR_ACCURACY)
        val base = lastGoodM
        val ridden = (odometerM - lastGoodOdoM).coerceAtLeast(0.0)
        // Lost for a long stretch → allow a fresh global bootstrap (the window would never reach back).
        if (base != null && ridden > REBOOTSTRAP_M) lastGoodM = null
        val good = lastGoodM
        val proj = if (good == null) path.nearestProjection(fix) else {
            val fwd = ridden.coerceAtMost(FWD_GROW_MAX_M) + FWD_M
            // FORWARD first: on an out-and-back the turn point has two equally close candidates
            // (the outbound pass just behind, the return pass just ahead). Prefer the window that only
            // looks ahead; fall back to the full back window only when nothing ahead is on the line.
            clippedProjection(fix, good - FWD_BACK_TOL_M, good + fwd)
                ?.takeIf { it.perpDistM <= MAX_PERP_M }
                ?: clippedProjection(fix, good - BACK_M, good + fwd)
        }
        if (proj == null || proj.perpDistM > MAX_PERP_M) return RoutePos(null, PosReason.OFF_ROUTE)
        val ceiling = (lastGoodM ?: Double.MAX_VALUE) + ridden + CEILING_SLACK_M
        val d = minOf(proj.distanceAlongM, ceiling)
        lastGoodM = d; lastGoodOdoM = odometerM
        return RoutePos(d, PosReason.OK)
    }

    /**
     * Like [PolylinePath.nearestProjectionInWindowOrNull] but the foot point is CLAMPED to
     * [loM, hiM] along the route, so a segment straddling the window edge cannot yield a foot point
     * on the other pass. Same equirectangular arithmetic as Geo.kt. ponytail: linear segment scan.
     */
    private fun clippedProjection(p: LatLng, loM: Double, hiM: Double): Projection? {
        val pts = path.points; val cum = path.cumulativeM
        var best: Projection? = null
        for (i in 0 until pts.size - 1) {
            if (cum[i] > hiM) break
            if (cum[i + 1] < loM) continue
            val len = cum[i + 1] - cum[i]
            val a = pts[i]; val b = pts[i + 1]
            val mLng = 111_320.0 * cos(Math.toRadians(a.lat))
            val bx = (b.lng - a.lng) * mLng; val by = (b.lat - a.lat) * 111_320.0
            val px = (p.lng - a.lng) * mLng; val py = (p.lat - a.lat) * 111_320.0
            val seg2 = bx * bx + by * by
            val tRaw = if (seg2 == 0.0) 0.0 else (px * bx + py * by) / seg2
            val tLo = if (len > 0.0) ((loM - cum[i]) / len).coerceIn(0.0, 1.0) else 0.0
            val tHi = if (len > 0.0) ((hiM - cum[i]) / len).coerceIn(0.0, 1.0) else 1.0
            val t = tRaw.coerceIn(tLo, tHi)
            val fx = t * bx; val fy = t * by
            val perp = sqrt((px - fx) * (px - fx) + (py - fy) * (py - fy))
            if (perp < (best?.perpDistM ?: Double.MAX_VALUE)) best = Projection(cum[i] + t * len, perp, i)
        }
        return best
    }

    companion object {
        const val FIX_FRESH_MS = 5_000L
        const val MAX_ACCURACY_M = 25.0
        const val BACK_M = 120.0
        const val FWD_BACK_TOL_M = 20.0
        const val FWD_M = 300.0
        const val FWD_GROW_MAX_M = 400.0
        const val MAX_PERP_M = 40.0
        const val CEILING_SLACK_M = 50.0
        const val REBOOTSTRAP_M = 2_000.0
    }
}
