package com.enderthor.kSafe.extension.pacing

import io.hammerhead.karooext.models.OnNavigationState.NavigationState.Climb

enum class BikeKind { ROAD, GRAVEL, MTB;
    companion object { fun of(routingPreference: String?): BikeKind = when (routingPreference?.uppercase()) {
        "MTB" -> MTB; "GRAVEL" -> GRAVEL; else -> ROAD } }
}

data class ClimbSeg(val startM: Double, val lengthM: Double, val gainM: Double) {
    val endM: Double get() = startM + lengthM
    val gradePct: Double get() = if (lengthM > 0) gainM / lengthM * 100.0 else 0.0
}

data class ClimbBlock(val id: Int, val startM: Double, val endM: Double, val gainM: Double) {
    val lengthM: Double get() = endM - startM
    val gradePct: Double get() = if (lengthM > 0) gainM / lengthM * 100.0 else 0.0
}

enum class ClimbSource { KAROO, OWN_REVERSED, OWN_UNCONFIRMED, OWN_EMPTY_KAROO, UNKNOWN }

data class RouteClimbsResult(val climbs: List<ClimbSeg>, val source: ClimbSource, val karooCount: Int, val karooConfirmed: Int)

/**
 * Climb source policy. Like upstream timklge/karoo-routegraph (Apache-2.0, RouteGraphUpdateManager),
 * the Karoo's own NavigatingRoute.climbs list is used — but only when each climb is CONFIRMED by the
 * route elevation profile and the route is not reversed (orientation of startDistance on reversed
 * routes is undocumented). Otherwise a simple own detector runs on the riding-direction profile.
 */
object RouteClimbs {
    private const val STEP_M = 50.0
    private const val START_GRADE_PCT = 3.0
    private const val LOOKAHEAD_M = 200.0
    private const val END_DROP_M = 20.0
    private const val MIN_LEN_M = 500.0
    private const val MIN_GAIN_M = 30.0
    private const val CONFIRM_FRACTION = 0.5

    fun resolve(karoo: List<Climb>, profile: ElevationProfile?, reversed: Boolean): RouteClimbsResult {
        if (profile == null) return RouteClimbsResult(emptyList(), ClimbSource.UNKNOWN, karoo.size, 0)
        val confirmed = karoo.count { profile.gainBetween(it.startDistance, it.startDistance + it.length) >= CONFIRM_FRACTION * it.totalElevation }
        val source = when {
            reversed -> ClimbSource.OWN_REVERSED
            karoo.isEmpty() -> ClimbSource.OWN_EMPTY_KAROO
            confirmed < karoo.size -> ClimbSource.OWN_UNCONFIRMED
            else -> ClimbSource.KAROO
        }
        val climbs = if (source == ClimbSource.KAROO)
            karoo.map { ClimbSeg(it.startDistance, it.length, profile.gainBetween(it.startDistance, it.startDistance + it.length)) }
        else detectOwn(profile)
        return RouteClimbsResult(climbs, source, karoo.size, confirmed)
    }

    /** Start where the next [LOOKAHEAD_M] rises at ≥ [START_GRADE_PCT]; end at the high point once the
     *  profile has dropped [END_DROP_M] below it (or at route end). Keep ≥ [MIN_LEN_M] and ≥ [MIN_GAIN_M]. */
    fun detectOwn(profile: ElevationProfile): List<ClimbSeg> {
        val out = ArrayList<ClimbSeg>()
        var d = 0.0
        while (d < profile.totalM) {
            val ahead = minOf(d + LOOKAHEAD_M, profile.totalM)
            val g = if (ahead - d < 1.0) 0.0 else (profile.eleAt(ahead) - profile.eleAt(d)) / (ahead - d) * 100.0
            if (g < START_GRADE_PCT) { d += STEP_M; continue }
            val start = d; var highD = d; var highE = profile.eleAt(d); var x = d
            while (x < profile.totalM) {
                x = minOf(x + STEP_M, profile.totalM)
                val e = profile.eleAt(x)
                if (e > highE) { highE = e; highD = x }
                if (highE - e >= END_DROP_M) break
                // flat plateau long enough also ends the climb
                if (x - highD >= 1000.0) break
            }
            val len = highD - start
            val gain = profile.gainBetween(start, highD)
            if (len >= MIN_LEN_M && gain >= MIN_GAIN_M) out.add(ClimbSeg(start, len, gain))
            d = maxOf(highD, d + STEP_M)
        }
        return out
    }

    fun isSignificant(c: ClimbSeg, kind: BikeKind): Boolean = when (kind) {
        BikeKind.MTB -> c.gainM >= 80.0 || (c.lengthM >= 1000.0 && c.gradePct >= 6.0)
        else -> c.gainM >= 150.0 || (c.lengthM >= 2000.0 && c.gradePct >= 5.0)
    }

    fun blocks(significant: List<ClimbSeg>, secondsBetween: (Double, Double) -> Double, maxGapS: Double = 600.0): List<ClimbBlock> {
        val out = ArrayList<ClimbBlock>()
        for (c in significant.sortedBy { it.startM }) {
            val last = out.lastOrNull()
            if (last != null && secondsBetween(last.endM, c.startM) < maxGapS)
                out[out.size - 1] = last.copy(endM = maxOf(last.endM, c.endM), gainM = last.gainM + c.gainM)
            else out.add(ClimbBlock(out.size, c.startM, c.endM, c.gainM))
        }
        return out
    }
}
