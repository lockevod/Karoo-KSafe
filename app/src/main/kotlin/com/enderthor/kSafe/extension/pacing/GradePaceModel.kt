package com.enderthor.kSafe.extension.pacing

import kotlinx.serialization.Serializable

@Serializable
data class GradePaceDto(val schemaVersion: Int, val kinds: Map<String, List<List<Double>>>)

/**
 * Rider moving speed per 1 % grade bin (−20..+20), per bike kind. Idea and binning from KGhost
 * engine/GradePace.kt; learned from KSafe's own rides because KSafe cannot read KGhost's private files.
 */
class GradePaceModel(private val bins: MutableMap<BikeKind, Array<Bin>> = mutableMapOf()) {
    data class Bin(var metres: Double = 0.0, var seconds: Double = 0.0)

    private fun arr(kind: BikeKind) = bins.getOrPut(kind) { Array(N) { Bin() } }
    private fun idx(gradePct: Double) = (Math.round(gradePct).toInt().coerceIn(-MAX, MAX)) + MAX

    fun learn(kind: BikeKind, gradePct: Double, metres: Double, seconds: Double) {
        if (metres <= 0 || seconds <= 0) return
        val b = arr(kind)[idx(gradePct)]; b.metres += metres; b.seconds += seconds
    }

    fun isConfident(kind: BikeKind, gradePct: Double) = (bins[kind]?.get(idx(gradePct))?.metres ?: 0.0) >= MIN_BIN_M

    fun speedKmh(kind: BikeKind, gradePct: Double): Double {
        val b = bins[kind]?.get(idx(gradePct))
        if (b != null && b.metres >= MIN_BIN_M && b.seconds > 0) return b.metres / b.seconds * 3.6
        return defaultKmh(kind, idx(gradePct) - MAX.toDouble())
    }

    fun secondsBetween(kind: BikeKind, profile: ElevationProfile, fromM: Double, toM: Double): Double {
        if (!fromM.isFinite() || !toM.isFinite()) return 0.0
        val from = fromM.coerceIn(0.0, profile.totalM); val to = toM.coerceIn(0.0, profile.totalM)
        var s = 0.0; var d = from
        while (d < to) { val step = minOf(STEP_M, to - d)
            s += step / (speedKmh(kind, profile.gradeAt(d + step / 2)) / 3.6); d += step }
        return s
    }

    fun toDto() = GradePaceDto(SCHEMA, bins.mapKeys { it.key.name }.mapValues { (_, a) -> a.map { listOf(it.metres, it.seconds) } })

    companion object {
        const val SCHEMA = 1
        private const val MAX = 20
        private const val N = 2 * MAX + 1
        private const val MIN_BIN_M = 400.0     // KGhost GRADE_MIN_BIN_M
        private const val STEP_M = 100.0
        // Population defaults (km/h) by grade; linear interpolation, flat beyond the ends.
        private val ROAD = listOf(-8.0 to 45.0, -4.0 to 38.0, 0.0 to 28.0, 2.0 to 22.0, 4.0 to 16.0, 6.0 to 12.0, 8.0 to 10.0, 10.0 to 8.0, 12.0 to 7.0, 20.0 to 5.0)
        private val MTB = listOf(-8.0 to 25.0, -4.0 to 22.0, 0.0 to 18.0, 2.0 to 14.0, 4.0 to 10.0, 6.0 to 8.0, 8.0 to 6.5, 10.0 to 5.5, 12.0 to 4.5, 20.0 to 3.5)

        fun defaultKmh(kind: BikeKind, g: Double): Double {
            val t = if (kind == BikeKind.MTB) MTB else ROAD
            val v = when { g <= t.first().first -> t.first().second; g >= t.last().first -> t.last().second
                else -> { val i = t.indexOfFirst { it.first >= g }; val (g0, v0) = t[i - 1]; val (g1, v1) = t[i]
                    v0 + (v1 - v0) * (g - g0) / (g1 - g0) } }
            return if (kind == BikeKind.GRAVEL) v * 0.85 else v
        }

        fun fromDto(dto: GradePaceDto): GradePaceModel {
            val m = mutableMapOf<BikeKind, Array<Bin>>()
            for ((k, list) in dto.kinds) { val kind = runCatching { BikeKind.valueOf(k) }.getOrNull() ?: continue
                if (list.size != N) continue
                m[kind] = Array(N) { Bin(list[it].getOrElse(0) { 0.0 }, list[it].getOrElse(1) { 0.0 }) } }
            return GradePaceModel(m)
        }
    }
}
