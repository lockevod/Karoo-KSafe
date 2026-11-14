package com.enderthor.kSafe.extension.pacing

/** Test-only encoders for Karoo route polylines (lat/lng at precision 5, elevation at precision 1). */
internal object TestPolyline {
    fun latLng(p: List<Pair<Double, Double>>) = encode(p, 1e5)
    fun elev(p: List<Pair<Double, Double>>) = encode(p, 10.0)

    private fun encode(pts: List<Pair<Double, Double>>, f: Double): String {
        val sb = StringBuilder(); var pa = 0; var pb = 0
        fun enc(v: Int) { var x = if (v < 0) (v shl 1).inv() else v shl 1
            while (x >= 0x20) { sb.append(((0x20 or (x and 0x1f)) + 63).toChar()); x = x shr 5 }
            sb.append((x + 63).toChar()) }
        for ((a, b) in pts) { val ia = Math.round(a * f).toInt(); val ib = Math.round(b * f).toInt()
            enc(ia - pa); enc(ib - pb); pa = ia; pb = ib }
        return sb.toString()
    }
}
