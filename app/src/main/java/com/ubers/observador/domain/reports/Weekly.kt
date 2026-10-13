package com.ubers.observador.domain.reports

import com.ubers.observador.domain.WorkdayClock
import com.ubers.observador.domain.earnings.DayBar
import com.ubers.observador.domain.earnings.EarningsFormat
import com.ubers.observador.domain.earnings.EarningsRange
import com.ubers.observador.domain.earnings.EarningsReport
import com.ubers.observador.domain.earnings.EarningsWindow
import com.ubers.observador.domain.earnings.EarningsWindows
import com.ubers.observador.domain.finance.EarningsPeriodSummary
import com.ubers.observador.domain.money.MoneyMath
import com.ubers.observador.domain.money.MoneyAmount
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The week against the one before, only when both have enough confirmed money. */
data class WeekComparison(
    val previousIncomeMinor: Long,
    val differenceMinor: Long,
    /** One decimal; null when the previous week earned 0. */
    val percent: BigDecimal?
)

/**
 * The weekly summary (Fase 8D), built only from what Ganancias already computes: the report
 * has no formulas of its own.
 */
data class WeeklyReport(
    val window: EarningsWindow,
    val income: EarningsPeriodSummary,
    val onlineMs: Long?,
    val busyPercent: Int?,
    /** Real income per confirmed hour online; null without enough time (the 8A rule). */
    val perHourMinor: Long?,
    val expensesWorkMinor: Long?,
    val netMinor: Long?,
    val bestDay: DayBar?,
    val goalMinor: Long?,
    /** Null without a weekly goal. */
    val goalReached: Boolean?,
    val comparison: WeekComparison?,
    /** Why there is no comparison (null when there is one). */
    val notComparable: String?
)

object WeeklyReports {

    /** Comparison only when both weeks have at least this share of trips with confirmed money. */
    const val MIN_COVERAGE_PCT = 80

    const val NOT_COMPARABLE_MONEY = "No comparable: falta dinero de algunos viajes"
    const val NOT_COMPARABLE_EMPTY = "No comparable: una de las semanas no tiene viajes"

    fun coveragePct(s: EarningsPeriodSummary): Int? = s.coverage?.toInt()

    fun build(
        current: EarningsReport,
        previous: EarningsReport?,
        onlineMs: Long?,
        busyPercent: Int?,
        perHourMinor: Long?,
        expensesWorkMinor: Long?,
        goalMinor: Long
    ): WeeklyReport {
        val s = current.summary
        val (comparison, why) = compare(s, previous?.summary)
        val net = if (expensesWorkMinor == null || (s.completedTripCount == 0 && s.totalObservedEarnings == 0L)) null
        else s.totalObservedEarnings - expensesWorkMinor
        return WeeklyReport(
            window = current.window,
            income = s,
            onlineMs = onlineMs,
            busyPercent = busyPercent,
            perHourMinor = perHourMinor,
            expensesWorkMinor = expensesWorkMinor,
            netMinor = net,
            bestDay = current.bestDay,
            goalMinor = goalMinor.takeIf { it > 0 },
            goalReached = if (goalMinor > 0) s.totalObservedEarnings >= goalMinor else null,
            comparison = comparison,
            notComparable = why
        )
    }

    fun compare(current: EarningsPeriodSummary, previous: EarningsPeriodSummary?): Pair<WeekComparison?, String?> {
        if (previous == null) return null to NOT_COMPARABLE_EMPTY
        val a = coveragePct(current)
        val b = coveragePct(previous)
        if (a == null || b == null) return null to NOT_COMPARABLE_EMPTY
        if (a < MIN_COVERAGE_PCT || b < MIN_COVERAGE_PCT) return null to NOT_COMPARABLE_MONEY
        if (current.currencyCode != previous.currencyCode) return null to NOT_COMPARABLE_MONEY
        val diff = current.totalObservedEarnings - previous.totalObservedEarnings
        val percent = MoneyMath.percentChange(
            MoneyAmount(previous.totalObservedEarnings, previous.currencyCode),
            MoneyAmount(current.totalObservedEarnings, current.currencyCode)
        )
        return WeekComparison(previous.totalObservedEarnings, diff, percent) to null
    }

    /** The notification text: "RD$9,870.00 · 40 de 42 viajes con dinero confirmado". */
    fun notificationText(r: WeeklyReport): String =
        (if (EarningsFormat.isMinimum(r.income)) "Al menos " else "") +
            EarningsFormat.money(r.income.totalObservedEarnings, r.income.currencyCode) + " · " + EarningsFormat.coverage(r.income).replaceFirstChar { it.lowercase() }
}

/** When the "Tu resumen de la semana está listo" alarm fires: shortly after the new week starts. */
object WeeklySchedule {

    /** A few minutes after the start, so the last amounts of the night are stored. */
    const val DELAY_MS = 15 * 60_000L

    fun nextTrigger(nowMs: Long, clock: WorkdayClock, weekStart: DayOfWeek): Long {
        val week = EarningsWindows.of(EarningsRange.WEEK, 0, nowMs, clock, weekStart)
        val thisOne = week.startMs + DELAY_MS
        return if (thisOne > nowMs) thisOne else clock.startOf(week.firstDay.plusDays(7)) + DELAY_MS
    }
}

/** One page with the totals of a period: the lines of the PDF, pure so they are tested. */
data class PeriodSummary(
    val window: EarningsWindow,
    val income: EarningsPeriodSummary,
    val onlineMs: Long?,
    val busyPercent: Int?,
    val perHourMinor: Long?,
    val km: Long?,
    val expensesWorkMinor: Long?,
    val expensesTotalMinor: Long?,
    val workSharePct: Int,
    val bestDay: DayBar?,
    val generatedAtMs: Long
)

object PeriodSummaryText {

    /** Always on the PDF (plan 8D). */
    const val NOTE = "Resumen generado por Observador con lo que registró de la app de Uber. No es un documento oficial de Uber."
    const val OFFICIAL = "Para un comprobante oficial de ingresos, el válido es el de Uber."

    private val ES = Locale.forLanguageTag("es-DO")
    private val DAY = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM yyyy", ES)

    fun title(s: PeriodSummary): String = "Observador · " + EarningsFormat.windowLabel(s.window).let {
        if (s.window.offset == 0 || s.window.offset == -1) "$it (${EarningsFormat.windowDates(s.window)})" else it
    }

    /** Label and value, in order. Real money only; the offer price never appears as income. */
    fun lines(s: PeriodSummary): List<Pair<String, String>> {
        val c = s.income.currencyCode
        fun m(minor: Long) = EarningsFormat.money(minor, c)
        val out = ArrayList<Pair<String, String>>()
        out += "Periodo" to EarningsFormat.windowDates(s.window)
        out += "Ingreso bruto real" to (if (EarningsFormat.isMinimum(s.income)) "al menos " else "") + m(s.income.totalObservedEarnings)
        out += "Cobertura" to EarningsFormat.coverage(s.income)
        out += "Viajes (total confirmado)" to m(s.income.tripEarnings)
        if (s.income.tips != 0L) out += "Propinas (dentro del total)" to m(s.income.tips)
        if (s.income.promotions != 0L) out += "Promociones (dentro del total)" to m(s.income.promotions)
        if (s.income.adjustments != 0L) out += "Ajustes (dentro del total)" to m(s.income.adjustments)
        if (s.income.otherEarnings != 0L) out += "Otros ingresos (dentro del total)" to m(s.income.otherEarnings)
        if (s.income.pendingReviewCount > 0) out += "Cantidades sin sumar" to "${s.income.pendingReviewCount} (pendientes de vincular o por revisar)"
        s.onlineMs?.let { out += "Tiempo en línea" to com.ubers.observador.domain.online.OnlineFormat.duration(it) }
        s.busyPercent?.let { out += "Ocupación" to "$it %" }
        s.perHourMinor?.let { out += "Ganancia por hora en línea" to m(it) + "/h" }
        s.km?.takeIf { it > 0 }?.let { out += "Km por GPS (aprox.)" to "$it km" }
        if (s.expensesWorkMinor != null) {
            out += "Gastos de trabajo" to m(s.expensesWorkMinor) + if (s.workSharePct < 100 && s.expensesTotalMinor != null) " (${s.workSharePct} % de ${m(s.expensesTotalMinor)})" else ""
            if (s.window.range != EarningsRange.DAY && (s.income.completedTripCount > 0 || s.income.totalObservedEarnings != 0L)) {
                out += "Ganancia neta" to m(s.income.totalObservedEarnings - s.expensesWorkMinor) +
                    if (EarningsFormat.isMinimum(s.income)) " (parcial)" else ""
            }
        }
        s.bestDay?.let { out += "Mejor jornada" to DAY.format(it.day) + " · " + m(it.incomeMinor) }
        return out
    }
}
