package com.ubers.observador.domain.reports

import com.ubers.observador.domain.earnings.Today
import com.ubers.observador.domain.sectors.CloseSummaries
import com.ubers.observador.domain.sectors.CloseSummary
import java.time.DayOfWeek
import java.time.ZonedDateTime

/**
 * 1.61.0: «Revisión de la semana», one notification on the chosen day (from 7 pm): the week's money
 * and time with the same sources as Ganancias, the «Cerrar» sectors, the tyres, and what is left
 * to review. It only summarises: it changes nothing and deletes nothing.
 */
data class WeekReviewFigures(
    val incomeMinor: Long,
    val currency: String,
    val profitMinor: Long?,
    val perKmMinor: Long?,
    val onlineMs: Long,
    val completedTrips: Int,
    val confirmedTrips: Int,
    val pendingReview: Int,
    val closes: CloseSummary,
    /** «Delantera: Falta revisar»… */
    val tires: List<String>,
    /** Sensors not heard in the last day. */
    val silentSensors: List<String>,
    /** Uber's money readings still to be confirmed in Ganancias. */
    val moneyToConfirm: Boolean
)

object WeekReview {
    const val HOUR = 19
    const val DAY_MS = 86_400_000L

    /** On the first day of a week, the week just ended is reviewed; otherwise the week so far. */
    fun offset(weekStartMs: Long, now: Long): Int = if (now - weekStartMs < DAY_MS) -1 else 0

    fun due(now: ZonedDateTime, day: DayOfWeek, lastKey: String?, key: String): Boolean =
        now.dayOfWeek == day && now.hour >= HOUR && lastKey != key

    fun title(f: WeekReviewFigures): String =
        "Tu semana: " + if (f.profitMinor != null) "neto ${Today.money(f.profitMinor, f.currency)}" else "ingreso ${Today.money(f.incomeMinor, f.currency)}"

    fun lines(f: WeekReviewFigures): List<String> {
        val out = ArrayList<String>()
        val money = buildString {
            append("Ingreso ${Today.money(f.incomeMinor, f.currency)}")
            f.profitMinor?.let { append(" · neto ${Today.money(it, f.currency)}") }
            f.perKmMinor?.let { append(" · neto ${Today.money(it, f.currency)} por km") }
        }
        out += money
        out += "${Today.duration(f.onlineMs)} en línea · ${f.completedTrips} ${if (f.completedTrips == 1) "viaje" else "viajes"}"
        if (!f.closes.isEmpty) out += CloseSummaries.line("Sectores «Cerrar»", f.closes)
        if (f.tires.isNotEmpty()) out += "Gomas: " + f.tires.joinToString("; ")
        val todo = ArrayList<String>()
        val missing = f.completedTrips - f.confirmedTrips
        if (missing > 0) todo += "$missing ${if (missing == 1) "viaje" else "viajes"} sin dinero confirmado"
        if (f.pendingReview > 0) todo += "${f.pendingReview} importes por revisar"
        if (f.moneyToConfirm) todo += "confirmar con Uber 3 viajes en Ganancias"
        f.silentSensors.forEach { todo += "sensor $it sin señal" }
        out += if (todo.isEmpty()) "Nada pendiente por revisar." else "Por revisar: " + todo.joinToString(", ") + "."
        return out
    }
}
