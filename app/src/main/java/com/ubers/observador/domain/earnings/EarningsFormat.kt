package com.ubers.observador.domain.earnings

import com.ubers.observador.domain.finance.EarningsPeriodSummary
import com.ubers.observador.domain.finance.FinancialObservationType
import com.ubers.observador.domain.finance.ReconciliationStatus
import com.ubers.observador.domain.money.MoneyAmount
import com.ubers.observador.domain.money.MoneyFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Texts of Ganancias. Words fixed by the plan: "referencia", "a tu ritmo de hoy"; never "vas a ganar". */
object EarningsFormat {

    private val ES = Locale.forLanguageTag("es-DO")
    private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", ES)
    private val SHORT = DateTimeFormatter.ofPattern("d MMM", ES)

    fun money(minor: Long, currency: String): String = MoneyFormat.format(MoneyAmount(minor, currency))

    /** "Hoy", "Ayer", "Esta semana", "6 – 12 oct", "Este mes", "septiembre 2026". */
    fun windowLabel(w: EarningsWindow): String = when (w.range) {
        EarningsRange.DAY -> when (w.offset) {
            0 -> "Hoy"
            -1 -> "Ayer"
            else -> DAY.format(w.firstDay).replace(".", "").replaceFirstChar { it.uppercase() }
        }
        EarningsRange.WEEK -> when (w.offset) {
            0 -> "Esta semana"
            -1 -> "Semana pasada"
            else -> "${SHORT.format(w.firstDay)} – ${SHORT.format(w.lastDay)}".replace(".", "")
        }
        EarningsRange.MONTH -> when (w.offset) {
            0 -> "Este mes"
            -1 -> "Mes pasado"
            else -> "${w.firstDay.month.getDisplayName(TextStyle.FULL, ES)} ${w.firstDay.year}".replaceFirstChar { it.uppercase() }
        }
    }

    /** The dates under the period name, e.g. "lun 6 oct – dom 12 oct". */
    fun windowDates(w: EarningsWindow): String =
        if (w.firstDay == w.lastDay) DAY.format(w.firstDay).replace(".", "")
        else "${DAY.format(w.firstDay)} – ${DAY.format(w.lastDay)}".replace(".", "")

    /** "al menos" when some completed trip has no confirmed money: the real figure can only be higher. */
    fun isMinimum(s: EarningsPeriodSummary): Boolean = s.confirmedTripCount < s.completedTripCount

    /** "12 de 14 viajes con dinero confirmado". */
    fun coverage(s: EarningsPeriodSummary): String = when {
        s.completedTripCount == 0 -> "Sin viajes terminados en este periodo"
        s.completedTripCount == 1 -> if (s.confirmedTripCount == 1) "1 viaje con dinero confirmado" else "1 viaje, sin dinero confirmado"
        else -> "${s.confirmedTripCount} de ${s.completedTripCount} viajes con dinero confirmado"
    }

    /** "1 h 25 min", "45 min". */
    fun minutes(total: Long): String {
        val h = total / 60
        val m = total % 60
        return when {
            h == 0L -> "$m min"
            m == 0L -> "$h h"
            else -> "$h h $m min"
        }
    }

    fun pace(p: GoalPace): String = "A tu ritmo de hoy, unas ${minutes(p.minutesToGoal)} en línea"

    fun dayShort(day: LocalDate): String = day.dayOfWeek.getDisplayName(TextStyle.SHORT, ES).replace(".", "").take(3)

    fun typeLabel(type: FinancialObservationType): String = when (type) {
        FinancialObservationType.TRIP_TOTAL -> "Total de viaje"
        FinancialObservationType.FARE -> "Tarifa"
        FinancialObservationType.TIP -> "Propina"
        FinancialObservationType.PROMOTION -> "Promoción"
        FinancialObservationType.ADJUSTMENT -> "Ajuste"
        FinancialObservationType.CANCELLATION_FEE -> "Tarifa de cancelación"
        FinancialObservationType.TOLL_REIMBURSEMENT -> "Peaje"
        FinancialObservationType.OTHER -> "Otro ingreso"
        FinancialObservationType.UNKNOWN -> "Sin tipo"
        FinancialObservationType.VOID -> "Anulación"
    }

    fun reconciliation(status: ReconciliationStatus): String = when (status) {
        ReconciliationStatus.MATCHED -> "Coincide con Uber"
        ReconciliationStatus.DIFFERENCE -> "Hay una diferencia con Uber"
        ReconciliationStatus.INCOMPLETE -> "Diferencia: faltan viajes con dinero confirmado"
        ReconciliationStatus.NO_REFERENCE -> "Sin referencia de Uber en este periodo"
        ReconciliationStatus.CURRENCY_MISMATCH -> "Uber muestra otra moneda"
        ReconciliationStatus.PERIOD_MISMATCH -> "Periodo distinto: Uber no dice desde y hasta cuándo cuenta, no se compara"
    }
}

/** The stages of a trip that Observador saw, in order, for its timeline in Ganancias. */
object TripTimeline {
    fun of(trip: com.ubers.observador.domain.trips.Trip): List<Pair<String, Long>> = listOfNotNull(
        trip.offer?.offerSeenAt?.let { "Oferta vista" to it },
        trip.acceptedAt?.let { "Aceptado" to it },
        trip.pickupNavigationAt?.let { "Rumbo a recoger" to it },
        trip.arrivedAt?.let { "Llegaste al punto" to it },
        trip.tripStartedAt?.let { "Viaje iniciado" to it },
        trip.completedAt?.let { "Viaje terminado" to it }
    ).sortedBy { it.second }
}
