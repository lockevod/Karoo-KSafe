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
        // Device shows ~34 chars of detail (ALERT_DETAIL_MAX_CHARS note): the actionable number comes first.
        val km = a.blockKm?.let { f1(it, es) }
        val climb = km?.let { if (es) "Puerto $it km" else "Climb $it km" }
        val (title, detail) = when {
            a.source == AlertSource.FUEL_HINT -> {
                val g = a.grams
                val d = when {
                    g == null -> climb?.let { if (es) "$it: come algo" else "$it: eat now" } ?: if (es) "Come algo" else "Eat now"
                    a.withLogs -> if (es) "Déficit ~$g g: come algo" else "Deficit ~$g g: eat now"
                    else -> if (es) "~$g g sin registrar. Come" else "~$g g unlogged. Eat now"
                }
                (if (es) "Buen momento para comer" else "Good moment to eat") to d
            }
            a.source == AlertSource.W_PRIME -> {
                val d = a.wPct?.let { val p = (it * 100).roundToInt(); if (es) "W′ $p %: suelta antes" else "W′ $p%: ease off first" }
                    ?: if (es) "Suelta antes del puerto" else "Ease off before the climb"
                (if (es) "Recupera antes de subir" else "Recover before the climb") to d
            }
            a.state == PacingState.CONSERVE -> {
                val len = a.blockLenKm; val grade = a.blockGradePct; val cap = a.ceilingW
                when {
                    climb == null -> if (es) "Conserva energía" to "Queda ruta dura y vas justo." else "Save energy" to "Hard ride left: ease off"
                    cap != null -> (if (es) "Conserva para el puerto" else "Save it for the climb") to "$climb: ≤$cap W"
                    len != null && grade != null -> {
                        val g = grade.roundToInt()
                        if (es) "Conserva para el puerto" to "$climb: ${f1(len, true)} km al $g %"
                        else "Save it for the climb" to "$climb: ${f1(len, false)} km at $g%"
                    }
                    else -> (if (es) "Conserva para el puerto" else "Save it for the climb") to climb
                }
            }
            a.state == PacingState.HIGH_MARGIN -> {
                val margin = a.ceilingW?.let { if (es) "hasta ~$it W" else "up to ~$it W" }
                val d = listOfNotNull(climb, margin).joinToString(": ").ifEmpty { if (es) "Margen alto" else "High margin" }
                (if (es) "Margen alto" else "High margin") to d
            }
            else -> if (es) "Ritmo correcto" to "Vuelves a ir bien. Mantén." else "Pace OK" to "Back on track. Keep it."
        }
        return Text(renderAlertText(title, emptyMap(), TITLE_MAX), renderAlertText(detail, emptyMap(), DETAIL_MAX))
    }

    /** One decimal in the rider's style: "2,1" in Spanish, "2.1" in English. */
    private fun f1(v: Double, es: Boolean) = String.format(if (es) Locale("es") else Locale.US, "%.1f", v)
}
