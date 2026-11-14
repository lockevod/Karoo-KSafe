package com.enderthor.kSafe.extension.pacing

/** Route elevation profile, distance-ascending, in RIDING direction once [reversed] is applied. */
class ElevationProfile(private val distM: DoubleArray, private val eleM: DoubleArray) {
    init { require(distM.size == eleM.size && distM.size >= 2) }
    val totalM: Double get() = distM.last()

    fun eleAt(d: Double): Double {
        if (d <= distM[0]) return eleM[0]
        if (d >= distM.last()) return eleM.last()
        var lo = 0; var hi = distM.size - 1
        while (hi - lo > 1) { val mid = (lo + hi) ushr 1; if (distM[mid] <= d) lo = mid else hi = mid }
        val span = distM[hi] - distM[lo]
        val f = if (span > 0) (d - distM[lo]) / span else 0.0
        return eleM[lo] + f * (eleM[hi] - eleM[lo])
    }

    fun gradeAt(d: Double, windowM: Double = 100.0): Double {
        val a = (d - windowM / 2).coerceIn(0.0, totalM)
        val b = (d + windowM / 2).coerceIn(0.0, totalM)
        return if (b - a < 1.0) 0.0 else (eleAt(b) - eleAt(a)) / (b - a) * 100.0
    }

    fun gainBetween(fromM: Double, toM: Double, stepM: Double = 25.0): Double {
        if (!fromM.isFinite() || !toM.isFinite() || !(stepM > 0.0)) return 0.0
        // eleAt is constant outside the profile, so clamping keeps results and bounds the loop.
        val from = fromM.coerceIn(0.0, totalM); val to = toM.coerceIn(0.0, totalM)
        if (to <= from) return 0.0
        var gain = 0.0; var d = from; var prev = eleAt(from)
        while (d < to) { d = minOf(d + stepM, to); val e = eleAt(d); if (e > prev) gain += e - prev; prev = e }
        return gain
    }

    fun reversed(): ElevationProfile {
        val n = distM.size; val t = totalM
        return ElevationProfile(DoubleArray(n) { t - distM[n - 1 - it] }, DoubleArray(n) { eleM[n - 1 - it] })
    }

    companion object {
        fun fromKaroo(encoded: String?): ElevationProfile? {
            if (encoded.isNullOrEmpty()) return null
            val pts = Polyline.decode(encoded, precision = 1)
                .filter { it.lat.isFinite() && it.lng.isFinite() }
            // Distance must be non-decreasing; drop backwards samples rather than trust them.
            val d = ArrayList<Double>(); val e = ArrayList<Double>()
            for (p in pts) if (d.isEmpty() || p.lat >= d.last()) { d.add(p.lat); e.add(p.lng) }
            return if (d.size >= 2 && d.last() > d.first()) ElevationProfile(d.toDoubleArray(), e.toDoubleArray()) else null
        }
    }
}
