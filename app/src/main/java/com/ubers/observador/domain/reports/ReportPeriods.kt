package com.ubers.observador.domain.reports

import com.ubers.observador.domain.StatsPeriod
import com.ubers.observador.domain.WorkdayClock
import com.ubers.observador.domain.earnings.EarningsFormat
import com.ubers.observador.domain.earnings.EarningsRange
import com.ubers.observador.domain.earnings.EarningsWindow
import com.ubers.observador.domain.earnings.EarningsWindows
import java.time.DayOfWeek
import java.time.LocalDate

/** The periods of «Reportes y exportación» (Fase 8J). CUSTOM uses the chosen dates. */
enum class ReportPeriod(val label: String) {
    TODAY("Hoy"),
    THIS_WEEK("Esta semana"),
    THIS_MONTH("Este mes"),
    LAST_WEEK("Semana pasada"),
    LAST_MONTH("Mes pasado"),
    CUSTOM("Rango personalizado")
}

/** A period asked for by another screen (Inicio, Ganancias, Gastos). */
data class ReportPreset(val period: ReportPeriod, val from: LocalDate? = null, val to: LocalDate? = null)

object ReportPeriods {

    /** A custom range is no week or month of the calendar. */
    const val CUSTOM_OFFSET = -999

    /**
     * The working-day window of a period, with the driver's start hour and week start, exactly as
     * Ganancias computes it. Null for a custom range without dates.
     */
    fun windowOf(period: ReportPeriod, from: LocalDate?, to: LocalDate?, nowMs: Long, clock: WorkdayClock, weekStart: DayOfWeek): EarningsWindow? =
        when (period) {
            ReportPeriod.TODAY -> EarningsWindows.of(EarningsRange.DAY, 0, nowMs, clock, weekStart)
            ReportPeriod.THIS_WEEK -> EarningsWindows.of(EarningsRange.WEEK, 0, nowMs, clock, weekStart)
            ReportPeriod.LAST_WEEK -> EarningsWindows.of(EarningsRange.WEEK, -1, nowMs, clock, weekStart)
            ReportPeriod.THIS_MONTH -> EarningsWindows.of(EarningsRange.MONTH, 0, nowMs, clock, weekStart)
            ReportPeriod.LAST_MONTH -> EarningsWindows.of(EarningsRange.MONTH, -1, nowMs, clock, weekStart)
            ReportPeriod.CUSTOM -> if (from == null || to == null) null else {
                val a = minOf(from, to)
                val b = maxOf(from, to)
                EarningsWindow(if (a == b) EarningsRange.DAY else EarningsRange.WEEK, CUSTOM_OFFSET, a, b, clock.startOf(a), clock.startOf(b.plusDays(1)))
            }
        }

    /** "Esta semana (lun 13 oct – dom 19 oct)". */
    fun labelOf(p: ReportPeriod, w: EarningsWindow): String = "${p.label} (${EarningsFormat.windowDates(w)})"

    /** What Ganancias is showing, as a preset. */
    fun forWindow(w: EarningsWindow): ReportPreset = when {
        w.range == EarningsRange.DAY && w.offset == 0 -> ReportPreset(ReportPeriod.TODAY)
        w.range == EarningsRange.WEEK && w.offset == 0 -> ReportPreset(ReportPeriod.THIS_WEEK)
        w.range == EarningsRange.WEEK && w.offset == -1 -> ReportPreset(ReportPeriod.LAST_WEEK)
        w.range == EarningsRange.MONTH && w.offset == 0 -> ReportPreset(ReportPeriod.THIS_MONTH)
        w.range == EarningsRange.MONTH && w.offset == -1 -> ReportPreset(ReportPeriod.LAST_MONTH)
        else -> ReportPreset(ReportPeriod.CUSTOM, w.firstDay, w.lastDay)
    }

    /** Inicio's chips: "Hoy" is today; 7 and 30 days become a range ending today; "Todo" asks for dates. */
    fun forStats(p: StatsPeriod, today: LocalDate): ReportPreset = when (val n = p.workdays) {
        1 -> ReportPreset(ReportPeriod.TODAY)
        null -> ReportPreset(ReportPeriod.CUSTOM)
        else -> ReportPreset(ReportPeriod.CUSTOM, today.minusDays(n - 1L), today)
    }
}
