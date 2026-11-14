package com.enderthor.kSafe.extension.pacing

import com.enderthor.kSafe.extension.util.renderAlertText
import java.util.Locale
import kotlin.math.roundToInt

/**
 * In-ride text for a live [PacingAlert] (phase 1b). Never tells the rider to push: HIGH_MARGIN only
 * describes the margin. A missing number drops its clause instead of printing a guess.
 */
object PacingAlertText {
    data class Text(val title: String, val detail: String)

    const val TITLE_MAX = 40
    const val DETAIL_MAX = 90

    fun render(a: PacingAlert, es: Boolean): Text {
        val km = a.blockKm?.let { f1(it) }
        val (title, detail) = when {
            a.source == AlertSource.FUEL_HINT -> {
                val lead = km?.let { if (es) "Puerto en $it km. " else "Climb in $it km. " } ?: ""
                val g = a.grams
                val tail = when {
                    g == null -> ""
                    a.withLogs -> if (es) "Déficit ~$g g." else "Deficit ~$g g."
                    else -> if (es) "~$g g quemados sin registrar comida." else "~$g g burned, no food logged."
                }
                (if (es) "Buen momento para comer" else "Good moment to eat") to (lead + tail).trim()
            }
            a.source == AlertSource.W_PRIME -> {
                val w = a.wPct?.let { "W′ ${(it * 100).roundToInt()} %. " } ?: ""
                val ease = if (es) "Suelta antes del puerto" else "Ease off before the climb"
                (if (es) "Recupera antes de subir" else "Recover before the climb") to (w + ease + (km?.let { " ($it km)." } ?: "."))
            }
            a.state == PacingState.CONSERVE -> {
                val len = a.blockLenKm; val grade = a.blockGradePct
                if (km == null || len == null || grade == null) {
                    if (es) "Conserva energía" to "Queda ruta dura y vas justo."
                    else "Save energy" to "Hard riding left and you are on the limit."
                } else {
                    val g = grade.roundToInt()
                    val cap = a.ceilingW?.let { if (es) " Súbelo ≤$it W." else " Ride it ≤$it W." } ?: ""
                    if (es) "Conserva para el puerto" to "En $km km: ${f1(len)} km al $g %.$cap"
                    else "Save it for the climb" to "In $km km: ${f1(len)} km at $g %.$cap"
                }
            }
            a.state == PacingState.HIGH_MARGIN -> {
                val lead = km?.let { if (es) "Puerto en $it km." else "Climb in $it km." }
                val margin = a.ceilingW?.let { if (es) "Margen hasta ~$it W." else "Margin up to ~$it W." }
                (if (es) "Margen alto" else "High margin") to listOfNotNull(lead, margin).joinToString(" ")
            }
            else -> if (es) "Ritmo correcto" to "Vuelves a ir bien. Mantén." else "Pace OK" to "Back on track. Keep it."
        }
        return Text(renderAlertText(title, emptyMap(), TITLE_MAX), renderAlertText(detail, emptyMap(), DETAIL_MAX))
    }

    private fun f1(v: Double) = String.format(Locale.US, "%.1f", v)
}
