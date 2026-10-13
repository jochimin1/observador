package com.ubers.observador.domain.expenses

import com.ubers.observador.domain.WorkdayClock
import com.ubers.observador.domain.finance.EarningsPeriodSummary
import java.time.LocalDate
import java.time.temporal.ChronoUnit

// ---------------------------------------------------------------- odometer

/** One odometer value, from a fill-up, an expense or a reading. [epoch] counts resets before it. */
data class OdometerPoint(val atMs: Long, val km: Long, val isReset: Boolean, val epoch: Int, val sourceId: String)

sealed interface OdometerCheck {
    data object Ok : OdometerCheck
    /** Lower than the previous value: saved only if the driver confirms a reset. */
    data class Lower(val previousKm: Long, val previousAt: Long) : OdometerCheck
}

/**
 * All odometer values in time order. A reset ("cambié de odómetro o de motor") starts a new
 * epoch: distances are never computed across one.
 */
object Odometer {

    fun timeline(fuel: List<FuelEntry>, expenses: List<ExpenseEntry>, readings: List<OdometerReading>): List<OdometerPoint> {
        data class Raw(val at: Long, val km: Long, val reset: Boolean, val id: String)
        val raw = fuel.mapNotNull { f -> f.odometerKm?.let { Raw(f.atMs, it, false, f.id) } } +
            expenses.mapNotNull { e -> e.odometerKm?.let { Raw(e.atMs, it, false, e.id) } } +
            readings.map { Raw(it.atMs, it.km, it.isReset, it.id) }
        var epoch = 0
        // A reset sorts before other values at the same instant: it opens the new count
        return raw.sortedWith(compareBy<Raw> { it.at }.thenBy { if (it.reset) 0 else 1 }).map { r ->
            if (r.reset) epoch++
            OdometerPoint(r.at, r.km, r.reset, epoch, r.id)
        }
    }

    /** The last value before [atMs] (other than [excludeId], the entry being edited). */
    fun check(km: Long, atMs: Long, timeline: List<OdometerPoint>, excludeId: String? = null): OdometerCheck {
        val previous = timeline.filter { it.sourceId != excludeId && it.atMs <= atMs }.lastOrNull() ?: return OdometerCheck.Ok
        return if (km < previous.km) OdometerCheck.Lower(previous.km, previous.atMs) else OdometerCheck.Ok
    }

    fun epochAt(atMs: Long, timeline: List<OdometerPoint>): Int = timeline.lastOrNull { it.atMs <= atMs }?.epoch ?: 0
}

// ---------------------------------------------------------------- fuel efficiency

enum class DistanceSource { ODOMETER, GPS }

/** One full-to-full stretch: the km driven on the fuel bought after the first full tank up to the second. */
data class FuelStretch(
    val fromAt: Long,
    val toAt: Long,
    val km: Long,
    val gallonsMilli: Long,
    val amountMinor: Long,
    val source: DistanceSource
) {
    val kmPerGallonTenths: Long get() = ExpenseMath.divHalfUp(km * 10_000, gallonsMilli)
    val costPerKmMinor: Long get() = ExpenseMath.divHalfUp(amountMinor, km)
}

data class FuelEfficiency(
    val stretches: List<FuelStretch> = emptyList(),
    val last: FuelStretch? = null,
    /** Median of the last 5 stretches. */
    val medianKmPerGallonTenths: Long? = null,
    val medianCostPerKmMinor: Long? = null,
    /** Median price per gallon of the fills in those stretches. */
    val medianPricePerGallonMinor: Long? = null
) {
    val sample: Int get() = minOf(stretches.size, FuelRules.MEDIAN_OF)
}

object FuelRules {

    const val MEDIAN_OF = 5
    /** Stretches needed before suggesting values for Ajustes. */
    const val SUGGEST_FROM = 3

    /**
     * Full-tank method: between two fills marked full, km = odometer at the second − odometer at
     * the first, gallons and money = what was bought after the first up to the second, included.
     * Never across an odometer reset. Without both odometers, the GPS km of 8E between the two
     * fills are used when there are some ([gpsKmBetween]); otherwise the stretch is skipped.
     */
    fun efficiency(
        fuel: List<FuelEntry>,
        timeline: List<OdometerPoint>,
        gpsKmBetween: (Long, Long) -> Long? = { _, _ -> null }
    ): FuelEfficiency {
        val fills = fuel.sortedBy { it.atMs }
        val fullIdx = fills.indices.filter { fills[it].fullTank }
        val stretches = fullIdx.zipWithNext().mapNotNull { (a, b) ->
            val first = fills[a]
            val second = fills[b]
            val bought = fills.subList(a + 1, b + 1)
            val gallons = bought.sumOf { it.gallonsMilli }
            val money = bought.sumOf { it.amountMinor }
            val o1 = first.odometerKm
            val o2 = second.odometerKm
            val sameEpoch = Odometer.epochAt(first.atMs, timeline) == Odometer.epochAt(second.atMs, timeline)
            when {
                o1 != null && o2 != null && sameEpoch && o2 > o1 ->
                    FuelStretch(first.atMs, second.atMs, o2 - o1, gallons, money, DistanceSource.ODOMETER)
                else -> gpsKmBetween(first.atMs, second.atMs)?.takeIf { it > 0 }
                    ?.let { FuelStretch(first.atMs, second.atMs, it, gallons, money, DistanceSource.GPS) }
            }
        }.filter { it.gallonsMilli > 0 }
        if (stretches.isEmpty()) return FuelEfficiency()
        val recent = stretches.takeLast(MEDIAN_OF)
        val usedFills = fills.filter { f -> recent.any { f.atMs > it.fromAt && f.atMs <= it.toAt } }
        return FuelEfficiency(
            stretches = stretches,
            last = stretches.last(),
            medianKmPerGallonTenths = median(recent.map { it.kmPerGallonTenths }),
            medianCostPerKmMinor = median(recent.map { it.costPerKmMinor }),
            medianPricePerGallonMinor = median(usedFills.map { it.pricePerGallonMinor })
        )
    }

    fun median(values: List<Long>): Long? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2] + 1) / 2
    }
}

// ---------------------------------------------------------------- fixed costs

/**
 * Fixed costs spread per day: monthly = amount / days of that month; biweekly = / 15; weekly =
 * / 7. Each day takes its share of a running total, so a whole month (or cycle) adds up to the
 * amount exactly, never a centavo more or less.
 */
object RecurringCosts {

    fun amountOn(cost: RecurringCost, day: LocalDate): Long {
        if (day.isBefore(cost.startsOn)) return 0
        cost.endsOn?.let { if (day.isAfter(it)) return 0 }
        val (n, d) = when (val cycle = cost.frequency.cycleDays) {
            null -> day.lengthOfMonth().toLong() to day.dayOfMonth.toLong()
            else -> cycle.toLong() to (Math.floorMod(ChronoUnit.DAYS.between(cost.startsOn, day), cycle.toLong()) + 1)
        }
        return cost.amountMinor * d / n - cost.amountMinor * (d - 1) / n
    }

    /** Sum for the days [from]..[to], both included. */
    fun amountBetween(cost: RecurringCost, from: LocalDate, to: LocalDate): Long {
        var day = maxOf(from, cost.startsOn)
        val last = cost.endsOn?.let { minOf(it, to) } ?: to
        var sum = 0L
        while (!day.isAfter(last)) {
            sum += amountOn(cost, day)
            day = day.plusDays(1)
        }
        return sum
    }
}

// ---------------------------------------------------------------- period summary and net

data class ExpenseSummary(
    val from: LocalDate,
    val to: LocalDate,
    val fuelMinor: Long = 0,
    val maintenanceMinor: Long = 0,
    val looseMinor: Long = 0,
    val fixedMinor: Long = 0,
    /** Share of the motorbike used for work (decision 8: one % for everything, 100 % by default). */
    val workSharePct: Int = 100
) {
    val totalMinor: Long get() = fuelMinor + maintenanceMinor + looseMinor + fixedMinor

    /** What counts against income: the total × work share, half up. Always shown beside the total. */
    val workMinor: Long get() = ExpenseMath.divHalfUp(totalMinor * workSharePct, 100)

    fun of(group: ExpenseGroup): Long = when (group) {
        ExpenseGroup.FUEL -> fuelMinor
        ExpenseGroup.MAINTENANCE -> maintenanceMinor
        ExpenseGroup.LOOSE -> looseMinor
        ExpenseGroup.FIXED -> fixedMinor
    }
}

/** Net = real confirmed income − work expenses. Partial when some trip has no confirmed money; none without income. */
data class NetResult(
    val incomeMinor: Long,
    val expensesMinor: Long,
    val netMinor: Long,
    val completedTrips: Int,
    val confirmedTrips: Int
) {
    val partial: Boolean get() = confirmedTrips < completedTrips
}

object ExpenseRules {

    /**
     * Cash basis: a fill-up counts on the working day it was paid. Days are working days of
     * [clock] (the same 4:00 start as the rest of the app), [from]..[to] included.
     */
    fun summarize(
        from: LocalDate,
        to: LocalDate,
        clock: WorkdayClock,
        fuel: List<FuelEntry>,
        expenses: List<ExpenseEntry>,
        recurring: List<RecurringCost>,
        workSharePct: Int
    ): ExpenseSummary {
        fun inPeriod(atMs: Long) = clock.workdayOf(atMs).let { !it.isBefore(from) && !it.isAfter(to) }
        val inExpenses = expenses.filter { inPeriod(it.atMs) }
        return ExpenseSummary(
            from = from,
            to = to,
            fuelMinor = fuel.filter { inPeriod(it.atMs) }.sumOf { it.amountMinor },
            maintenanceMinor = inExpenses.filter { it.type.group == ExpenseGroup.MAINTENANCE }.sumOf { it.amountMinor },
            looseMinor = inExpenses.filter { it.type.group == ExpenseGroup.LOOSE }.sumOf { it.amountMinor },
            fixedMinor = recurring.sumOf { RecurringCosts.amountBetween(it, from, to) },
            workSharePct = workSharePct.coerceIn(ExpenseLimits.WORK_SHARE_MIN, ExpenseLimits.WORK_SHARE_MAX)
        )
    }

    /** Null without real income in the period: there is no net to show (never offers as income). */
    fun net(income: EarningsPeriodSummary?, expenses: ExpenseSummary): NetResult? {
        if (income == null || (income.completedTripCount == 0 && income.totalObservedEarnings == 0L)) return null
        return NetResult(
            incomeMinor = income.totalObservedEarnings,
            expensesMinor = expenses.workMinor,
            netMinor = income.totalObservedEarnings - expenses.workMinor,
            completedTrips = income.completedTripCount,
            confirmedTrips = income.confirmedTripCount
        )
    }
}

// ---------------------------------------------------------------- maintenance

enum class ReminderLevel { OK, SOON, OVERDUE, NO_DATA }

data class MaintenanceReminder(
    val type: ExpenseType,
    val intervalKm: Long,
    val lastChangeKm: Long? = null,
    val lastChangeAt: Long? = null,
    val currentKm: Long? = null,
    val currentAt: Long? = null,
    /** The current km adds GPS km (8E) to the last odometer written. */
    val estimated: Boolean = false,
    val remainingKm: Long? = null,
    val level: ReminderLevel
)

object MaintenanceReminders {

    /** Warn when 10 % of the interval or less is left. */
    const val SOON_SHARE_PCT = 10

    /**
     * Per type with an interval (km > 0): last change = the latest maintenance of that type with
     * an odometer; current = the latest odometer of any source, plus GPS km since it when there
     * are some; remaining = last change + interval − current. A reset after the last change
     * leaves it without data until the next change is written.
     */
    fun of(
        intervals: Map<ExpenseType, Long>,
        expenses: List<ExpenseEntry>,
        timeline: List<OdometerPoint>,
        gpsKmSince: (Long) -> Long? = { null }
    ): List<MaintenanceReminder> {
        val current = timeline.lastOrNull()
        return intervals.filter { it.value > 0 }.map { (type, interval) ->
            val change = expenses.filter { it.type == type && it.odometerKm != null }.maxByOrNull { it.atMs }
            if (change == null || current == null) {
                return@map MaintenanceReminder(type, interval, level = ReminderLevel.NO_DATA)
            }
            if (Odometer.epochAt(change.atMs, timeline) != current.epoch) {
                return@map MaintenanceReminder(type, interval, change.odometerKm, change.atMs, level = ReminderLevel.NO_DATA)
            }
            val gps = gpsKmSince(current.atMs)?.takeIf { it > 0 }
            val now = current.km + (gps ?: 0)
            val remaining = change.odometerKm!! + interval - now
            val level = when {
                remaining < 0 -> ReminderLevel.OVERDUE
                remaining * 100 <= interval * SOON_SHARE_PCT -> ReminderLevel.SOON
                else -> ReminderLevel.OK
            }
            MaintenanceReminder(type, interval, change.odometerKm, change.atMs, now, current.atMs, gps != null, remaining, level)
        }
    }
}

// ---------------------------------------------------------------- suggestion for Ajustes

/** Real values to offer in Ajustes; only fuel cost per offer changes, never the score. */
data class SettingsSuggestion(val kmPerGallonTenths: Long, val pricePerGallonMinor: Long, val stretches: Int)

object SettingsSuggestions {

    /**
     * With [FuelRules.SUGGEST_FROM] stretches or more, suggests the real km/gallon and price per
     * gallon when Ajustes differs by 3 % or more in either.
     */
    fun of(efficiency: FuelEfficiency, settingsKmPerGallon: Double, settingsPricePerGallon: Double): SettingsSuggestion? {
        if (efficiency.stretches.size < FuelRules.SUGGEST_FROM) return null
        val kmpg = efficiency.medianKmPerGallonTenths ?: return null
        val price = efficiency.medianPricePerGallonMinor ?: return null
        fun differs(real: Double, current: Double) = current <= 0 || Math.abs(real - current) / current >= 0.03
        val realKmpg = kmpg / 10.0
        val realPrice = price / 100.0
        return if (differs(realKmpg, settingsKmPerGallon) || differs(realPrice, settingsPricePerGallon)) {
            SettingsSuggestion(kmpg, price, efficiency.stretches.size)
        } else {
            null
        }
    }
}
