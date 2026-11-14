package com.enderthor.kSafe.extension.pacing

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Ported from KGhost geo/Polyline.kt + geo/LatLng.kt (same author). */

/** A WGS84 coordinate. */
data class LatLng(val lat: Double, val lng: Double)

/** Google encoded-polyline decoder + spherical geometry helpers. */
object Polyline {
    private const val EARTH_R_M = 6_371_000.0

    /** Decodes a Google encoded polyline (precision 5) into coordinates. */
    fun decode(encoded: String, precision: Int = 5): List<LatLng> {
        val factor = 10.0.pow(precision.toDouble())
        val out = ArrayList<LatLng>()
        var index = 0; var lat = 0; var lng = 0
        // The `index < encoded.length` guards on each inner do/while (and the break after the lat
        // group) make this tolerant of a TRUNCATED/corrupt polyline: a well-formed varint always ends
        // on a char with b < 0x20, so on valid input these guards never trip early.
        while (index < encoded.length) {
            var result = 0; var shift = 0; var b: Int
            do { b = encoded[index++].code - 63; result = result or ((b and 0x1f) shl shift); shift += 5 } while (b >= 0x20 && index < encoded.length)
            lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1
            if (index >= encoded.length) break // truncated mid-point: no lng group -> drop the partial pair
            result = 0; shift = 0
            do { b = encoded[index++].code - 63; result = result or ((b and 0x1f) shl shift); shift += 5 } while (b >= 0x20 && index < encoded.length)
            // Still on a continuation byte: the string ended mid-group, longitude incomplete -> drop the pair.
            if (b >= 0x20) break
            lng += if (result and 1 != 0) (result shr 1).inv() else result shr 1
            out.add(LatLng(lat / factor, lng / factor))
        }
        return out
    }

    /** Great-circle distance in metres. */
    fun haversineM(a: LatLng, b: LatLng): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val la1 = Math.toRadians(a.lat); val la2 = Math.toRadians(b.lat)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(la1) * cos(la2) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * EARTH_R_M * atan2(sqrt(h), sqrt(1 - h))
    }
}

/** Result of projecting a point onto a [PolylinePath]. */
data class Projection(val distanceAlongM: Double, val perpDistM: Double, val vertexIndex: Int)

/** A decoded route path with precomputed cumulative distances and point->path projection. */
class PolylinePath(val points: List<LatLng>) {
    init { require(points.size >= 2) { "PolylinePath needs at least 2 points" } }

    val cumulativeM: DoubleArray = DoubleArray(points.size).also { c ->
        for (i in 1 until points.size) c[i] = c[i - 1] + Polyline.haversineM(points[i - 1], points[i])
    }
    val totalM: Double get() = cumulativeM.last()

    // Per-segment constant (segment i runs points[i] -> points[i + 1]), computed once.
    private val segMPerDegLng = DoubleArray(points.size - 1) { 111_320.0 * cos(Math.toRadians(points[it].lat)) }

    /**
     * Nearest projection of [p] onto the path. Uses an equirectangular local approximation for
     * the per-segment point->segment distance, and returns the cumulative distance-along of the
     * projected foot point.
     */
    fun nearestProjection(p: LatLng): Projection =
        nearestProjectionInRange(p, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)

    /**
     * Windowed projection for self-overlapping routes: only segments whose cumulative-distance range
     * intersects `[aroundDistanceM - backWindowM, aroundDistanceM + fwdWindowM]` are considered, which
     * keeps the projection on the CURRENT pass. Returns null when NO segment intersects the window
     * (no global fallback), so the caller can treat it as "don't trust GPS this tick".
     */
    fun nearestProjectionInWindowOrNull(
        p: LatLng,
        aroundDistanceM: Double,
        backWindowM: Double,
        fwdWindowM: Double,
    ): Projection? {
        val windowed = nearestProjectionInRange(p, aroundDistanceM - backWindowM, aroundDistanceM + fwdWindowM)
        return if (windowed.perpDistM == Double.MAX_VALUE) null else windowed
    }

    /**
     * Index of the first segment `i` whose far end `cumulativeM[i + 1] >= windowLoM`. Lower-bound
     * binary search over the non-decreasing `cumulativeM`. Returns `points.size - 1` (empty loop
     * range) when every segment ends before [windowLoM]; 0 for NEGATIVE_INFINITY.
     */
    private fun firstSegmentFrom(windowLoM: Double): Int {
        var lo = 1
        var hi = points.size // exclusive
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (cumulativeM[mid] >= windowLoM) hi = mid else lo = mid + 1
        }
        return (lo - 1).coerceAtMost(points.size - 1)
    }

    /**
     * Core projection scan restricted to segments whose `[cumulativeM[i], cumulativeM[i + 1]]`
     * range intersects `[windowLoM, windowHiM]`. With an infinite window this is the plain global
     * nearest projection.
     */
    private fun nearestProjectionInRange(p: LatLng, windowLoM: Double, windowHiM: Double): Projection {
        var best = Projection(0.0, Double.MAX_VALUE, 0)
        val start = firstSegmentFrom(windowLoM)
        for (i in start until points.size - 1) {
            val segLoM = cumulativeM[i]; val segHiM = cumulativeM[i + 1]
            // No later segment can intersect the window once its start passes windowHiM.
            if (segLoM > windowHiM) break
            if (segHiM < windowLoM) continue

            val a = points[i]; val b = points[i + 1]
            // Local metric plane (metres) centred at `a`.
            val mPerDegLat = 111_320.0
            val mPerDegLng = segMPerDegLng[i]
            val ax = 0.0; val ay = 0.0
            val bx = (b.lng - a.lng) * mPerDegLng; val by = (b.lat - a.lat) * mPerDegLat
            val px = (p.lng - a.lng) * mPerDegLng; val py = (p.lat - a.lat) * mPerDegLat
            val segLen2 = (bx - ax) * (bx - ax) + (by - ay) * (by - ay)
            val t = if (segLen2 == 0.0) 0.0 else (((px - ax) * (bx - ax) + (py - ay) * (by - ay)) / segLen2).coerceIn(0.0, 1.0)
            val fx = ax + t * (bx - ax); val fy = ay + t * (by - ay)
            val perp = sqrt((px - fx) * (px - fx) + (py - fy) * (py - fy))
            if (perp < best.perpDistM) {
                val along = cumulativeM[i] + t * (cumulativeM[i + 1] - cumulativeM[i])
                best = Projection(along, perp, i)
            }
        }
        return best
    }
}
