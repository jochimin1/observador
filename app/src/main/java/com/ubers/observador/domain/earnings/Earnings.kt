package com.ubers.observador.domain.earnings

import com.ubers.observador.domain.WorkdayClock
import com.ubers.observador.domain.finance.AmountSemantics
import com.ubers.observador.domain.finance.EarningsPeriodSummary
import com.ubers.observador.domain.finance.EarningsPeriods
import com.ubers.observador.domain.finance.FinancePolicy
import com.ubers.observador.domain.finance.FinancialCompleteness
import com.ubers.observador.domain.finance.FinancialObservation
import com.ubers.observador.domain.finance.FinancialObservationType
import com.ubers.observador.domain.finance.FinancialScope
import com.ubers.observador.domain.finance.TripFinancialSummary
import com.ubers.observador.domain.money.Currencies
import com.ubers.observador.domain.money.MoneyAmount
import com.ubers.observador.domain.trips.Trip
import com.ubers.observador.domain.trips.TripStatus
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** The three ways of looking at money in Ganancias (Fase 8C). */
enum class EarningsRange(val label: String) { DAY("Hoy"), WEEK("Semana"), MONTH("Mes") }

/**
 * One period of Ganancias, in working days of the driver's clock: [firstDay]..[lastDay] included,
 * [startMs] to [endMs) in time. [offset] 0 is the current period, -1 the one before, and so on.
 */
data class EarningsWindow(
    val range: EarningsRange,
    val offset: Int,
    val firstDay: LocalDate,
    val lastDay: LocalDate,
    val startMs: Long,
    val endMs: Long
) {
    val isCurrent: Boolean get() = offset == 0
    val days: List<LocalDate>
        get() = generateSequence(firstDay) { it.plusDays(1) }.takeWhile { !it.isAfter(lastDay) }.toList()
}

object EarningsWindows {

    /**
     * Day = one working day; week = 7 working days from [weekStart] (decision 6: Monday at the
     * workday start hour); month = calendar month of working days. There is no future: a
     * positive offset is the current period.
     */
    fun of(range: EarningsRange, offset: Int, nowMs: Long, clock: WorkdayClock, weekStart: DayOfWeek = DayOfWeek.MONDAY): EarningsWindow {
        val o = minOf(offset, 0)
        val today = clock.workdayOf(nowMs)
        val (first, last) = when (range) {
            EarningsRange.DAY -> today.plusDays(o.toLong()).let { it to it }
            EarningsRange.WEEK -> today.with(TemporalAdjusters.previousOrSame(weekStart)).plusWeeks(o.toLong()).let { it to it.plusDays(6) }
            EarningsRange.MONTH -> today.withDayOfMonth(1).plusMonths(o.toLong()).let { it to it.withDayOfMonth(it.lengthOfMonth()) }
        }
        return EarningsWindow(range, o, first, last, clock.startOf(first), clock.startOf(last.plusDays(1)))
    }

    /** Working days left in [window], today included; 0 once it is over. */
    fun daysLeft(window: EarningsWindow, nowMs: Long, clock: WorkdayClock): Int {
        val today = clock.workdayOf(nowMs)
        if (today.isAfter(window.lastDay)) return 0
        val from = if (today.isBefore(window.firstDay)) window.firstDay else today
        return ChronoUnit.DAYS.between(from, window.lastDay).toInt() + 1
    }
}

/** One completed trip as Ganancias lists it: what the offer said next to the real money. */
data class TripMoneyRow(
    val tripId: String,
    val completedAt: Long,
    /** A reference, never income. */
    val offered: MoneyAmount?,
    /** Null unless a total was observed (CONFIRMED). */
    val real: MoneyAmount?,
    val completeness: FinancialCompleteness,
    val difference: MoneyAmount?,
    val differencePercent: BigDecimal?,
    val tipMinor: Long,
    val hasAnomalies: Boolean,
    val pendingReviewCount: Int
)

/** Real income of one working day. Trips without confirmed money add nothing; they are counted apart. */
data class DayBar(val day: LocalDate, val incomeMinor: Long, val completedTrips: Int, val confirmedTrips: Int) {
    val unconfirmedTrips: Int get() = completedTrips - confirmedTrips
}

/** An amount Observador saw without a trip that is not counted anywhere yet. */
data class PendingAmount(
    val observation: FinancialObservation,
    /** False for amounts that cannot go to a trip (low confidence, unknown type, other currency, part of a breakdown). */
    val assignable: Boolean
)

/** A live manual assignment, which can be undone. */
data class ManualAssignmentRow(val copy: FinancialObservation, val tripId: String, val originalId: String?)

data class EarningsReport(
    val window: EarningsWindow,
    val summary: EarningsPeriodSummary,
    val bars: List<DayBar>,
    /** The day with the most real income; null without any. */
    val bestDay: DayBar?,
    /** Newest first. */
    val trips: List<TripMoneyRow>,
    /** "Pendientes de vincular": never summed. Newest first. */
    val pending: List<PendingAmount>
)

object EarningsReports {

    private val PERIOD_INCOME = setOf(FinancialObservationType.PROMOTION, FinancialObservationType.ADJUSTMENT, FinancialObservationType.OTHER)

    /**
     * Everything Ganancias shows for [window], with exactly the rules of [EarningsPeriods]: the
     * bars of the days add up to the period total. [unassigned] are the amounts without a trip
     * seen in the window plus every VOID that may cancel them (a void can be written days later).
     */
    fun build(
        window: EarningsWindow,
        clock: WorkdayClock,
        trips: List<Trip>,
        summaries: Map<String, TripFinancialSummary>,
        unassigned: List<FinancialObservation>,
        currencyCode: String = Currencies.DOP
    ): EarningsReport {
        val completed = trips.filter { t ->
            val at = t.completedAt
            t.status == TripStatus.COMPLETED && at != null && at >= window.startMs && at < window.endMs
        }
        fun summaryOf(t: Trip) = summaries[t.id] ?: TripFinancialSummary(
            tripId = t.id, currencyCode = null,
            offeredAmount = t.offer?.offeredAmount?.let { MoneyAmount.fromLegacyDouble(it, currencyCode) },
            latestAbsoluteTotal = null, effectiveEarnings = null, completeness = FinancialCompleteness.UNKNOWN
        )
        val voids = unassigned.filter { it.type == FinancialObservationType.VOID }
        val inWindow = unassigned.filter { it.type != FinancialObservationType.VOID && it.observedAtMs >= window.startMs && it.observedAtMs < window.endMs }
        val summary = EarningsPeriods.summarize(currencyCode, completed.map(::summaryOf), inWindow + voids)

        val bars = window.days.map { day ->
            val dayTrips = completed.filter { clock.workdayOf(requireNotNull(it.completedAt)) == day }
            val dayAmounts = inWindow.filter { clock.workdayOf(it.observedAtMs) == day }
            val s = EarningsPeriods.summarize(currencyCode, dayTrips.map(::summaryOf), dayAmounts + voids)
            DayBar(day, s.totalObservedEarnings, s.completedTripCount, s.confirmedTripCount)
        }
        val best = bars.filter { it.incomeMinor > 0 }.maxWithOrNull(compareBy<DayBar> { it.incomeMinor }.thenByDescending { it.day })

        val rows = completed.sortedByDescending { it.completedAt }.map { t ->
            val s = summaryOf(t)
            val offered = s.offeredAmount ?: t.offer?.offeredAmount?.let { MoneyAmount.fromLegacyDouble(it, currencyCode) }
            val diff = s.copy(offeredAmount = offered).differenceFromOffer()
            TripMoneyRow(
                tripId = t.id,
                completedAt = requireNotNull(t.completedAt),
                offered = offered,
                real = if (s.completeness == FinancialCompleteness.CONFIRMED) s.effectiveEarnings else null,
                completeness = s.completeness,
                difference = diff?.first,
                differencePercent = diff?.second,
                tipMinor = s.tip,
                hasAnomalies = s.hasAnomalies,
                pendingReviewCount = s.pendingReviewCount
            )
        }
        return EarningsReport(window, summary, bars, best, rows, pending(inWindow + voids, currencyCode))
    }

    /**
     * The unassigned amounts that are not counted: everything live except confirmed period income
     * (the same rule as [EarningsPeriods.summarize]). Newest first.
     */
    fun pending(unassigned: List<FinancialObservation>, currencyCode: String = Currencies.DOP): List<PendingAmount> {
        val voided = unassigned.mapNotNull { it.voidsObservationId }.toSet()
        val live = unassigned.filter { it.type != FinancialObservationType.VOID && it.observationId !in voided && it.tripId == null }
        return live.filter { !isCountedPeriodIncome(it, currencyCode) }
            .sortedWith(compareByDescending<FinancialObservation> { it.observedAtMs }.thenBy { it.observationId })
            .map { PendingAmount(it, ManualAssignment.isAssignable(it, currencyCode)) }
    }

    private fun trustworthy(o: FinancialObservation, currencyCode: String) =
        o.confidence >= FinancePolicy.MIN_CONFIDENCE && o.type != FinancialObservationType.UNKNOWN &&
            o.amount.currencyCode == currencyCode && o.semantics != AmountSemantics.COMPONENT &&
            (!o.amount.isNegative || o.type.mayBeNegative)

    internal fun isCountedPeriodIncome(o: FinancialObservation, currencyCode: String) =
        trustworthy(o, currencyCode) && o.semantics == AmountSemantics.DELTA && o.scope == FinancialScope.PERIOD && o.type in PERIOD_INCOME

    internal fun isTrustworthy(o: FinancialObservation, currencyCode: String) = trustworthy(o, currencyCode)
}
