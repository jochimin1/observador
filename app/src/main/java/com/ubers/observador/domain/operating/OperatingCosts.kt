package com.ubers.observador.domain.operating

import com.ubers.observador.domain.WorkdayClock
import com.ubers.observador.domain.distance.*
import com.ubers.observador.domain.earnings.EarningsWindow
import com.ubers.observador.domain.earnings.TripIncome
import com.ubers.observador.domain.expenses.*
import com.ubers.observador.domain.finance.*
import com.ubers.observador.domain.online.*
import com.ubers.observador.domain.trips.Trip
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.time.LocalDate

enum class FixedCostBasis(val label: String) { WORKDAY("Por jornada"), HOUR("Por hora en línea"), UNKNOWN("Desconocido") }

/** Immutable parameters. Money is DOP centavos; distance efficiency is thousandths of km/gal. */
data class OperatingCostModel(
    val id: String, val revision: Int, val effectiveDay: String, val effectiveAtMs: Long,
    val createdAtMs: Long, val zoneId: String, val workdayHour: Int,
    val fuelPriceMinor: Long, val kmPerGallonMilli: Long, val maintenancePerKmMinor: Long,
    val depreciationPerKmMinor: Long, val fixedMinor: Long, val fixedBasis: FixedCostBasis,
    val looseWorkSharePct: Int = 100, val algorithm: String = ALGORITHM
) {
    companion object { const val ALGORITHM = "OPERATING_COST_V1" }
    fun valid() = algorithm == ALGORITHM && revision > 0 && fuelPriceMinor in 1..10_000_000L &&
        kmPerGallonMilli in 100..1_000_000L && maintenancePerKmMinor in 0..10_000_000L &&
        depreciationPerKmMinor in 0..10_000_000L && fixedMinor in 0..10_000_000L &&
        fixedBasis != FixedCostBasis.UNKNOWN && looseWorkSharePct in 0..100
}

data class OperatingCostInput(val fuelPrice: String, val kmPerGallon: String, val maintenance: String,
    val depreciation: String, val fixed: String, val basis: FixedCostBasis, val looseShare: String, val effectiveDay: String) {
    fun values(): List<Long>? = runCatching {
        fun decimal(t: String, digits: Int): Long {
            require(Regex("[0-9]{1,8}(?:[.,][0-9]{1,$digits})?").matches(t.trim()))
            return BigDecimal(t.trim().replace(',', '.')).movePointRight(digits).longValueExact()
        }
        listOf(decimal(fuelPrice, 2), decimal(kmPerGallon, 3), decimal(maintenance, 2),
            decimal(depreciation, 2), decimal(fixed, 2), looseShare.trim().toLong())
    }.getOrNull()
}

enum class OperatingIssue(val label: String) {
    MODEL("Falta un modelo de costos vigente o sus parámetros no son válidos"),
    MONEY("Faltan ingresos confirmados; el precio ofertado no los sustituye"),
    TRIP_GPS("Faltan kilómetros completos del viaje o hay tramos sin señal"),
    TIME("Falta la aceptación o un final válido del viaje"),
    ONLINE("Falta tiempo en línea confirmado o hay tiempo posible sin confirmar"),
    GPS("La medición GPS no cubre al menos el 90 % del tiempo confirmado o presenta huecos"),
    OVERLAP("Hay viajes con tiempos solapados; revisa sus cierres"),
    OPEN_TRIP("Hay un viaje en curso: su ingreso y costo todavía no están cerrados"),
    NO_ACTIVITY("No hay actividad registrada en este periodo")
}

data class OperatingCostParts(val fuel: Long = 0, val maintenance: Long = 0, val depreciation: Long = 0,
    val fixed: Long = 0, val loose: Long = 0) {
    val total: Long get() = fuel + maintenance + depreciation + fixed + loose
    operator fun plus(b: OperatingCostParts) = OperatingCostParts(fuel+b.fuel, maintenance+b.maintenance,
        depreciation+b.depreciation, fixed+b.fixed, loose+b.loose)
}

data class OperatingTripResult(val tripId: String, val modelRevision: Int?, val fixedRevision: Int?,
    val incomeMinor: Long?, val cost: OperatingCostParts, val distanceMm: Long?, val committedMs: Long?,
    val issues: Set<OperatingIssue>) {
    val profitMinor: Long? get() = incomeMinor?.takeIf { issues.isEmpty() }?.minus(cost.total)
}

data class OperatingReport(val incomeMinor: Long, val costs: OperatingCostParts, val unallocated: OperatingCostParts,
    val trips: List<OperatingTripResult>, val onlineMs: Long, val committedMs: Long, val distanceMm: Long,
    val gpsCoveragePct: Int?, val revisions: Set<Int>, val issues: Set<OperatingIssue>) {
    val profitMinor: Long? get() = (incomeMinor - costs.total).takeIf { issues.isEmpty() }
    val perOnlineHour: Long? get() = profitMinor?.let { OperatingMath.rate(it, 3_600_000, onlineMs.takeIf { t -> t >= 900_000 } ?: 0) }
    val perCommittedHour: Long? get() = profitMinor?.let { OperatingMath.rate(it, 3_600_000, committedMs.takeIf { t -> t >= 900_000 } ?: 0) }
    val perKm: Long? get() = profitMinor?.let { OperatingMath.rate(it, 1_000_000, distanceMm) }
}

object OperatingMath {
    fun rate(value: Long, factor: Long, divisor: Long): Long? = if (divisor <= 0) null else
        BigDecimal.valueOf(value).multiply(BigDecimal.valueOf(factor)).divide(BigDecimal.valueOf(divisor), 0, RoundingMode.HALF_UP).longValueExact()
    /** Largest remainder; the allocated centavos always add up to the one rounded daily budget. */
    fun allocate(total: Long, weights: Map<String, Long>): Map<String, Long> {
        require(total >= 0 && weights.values.all { it >= 0 })
        val sum = weights.values.fold(BigInteger.ZERO) { a,b -> a + BigInteger.valueOf(b) }
        if (sum == BigInteger.ZERO) return weights.mapValues { 0L }
        val parts = weights.mapValues { (_, w) -> BigInteger.valueOf(total).multiply(BigInteger.valueOf(w)).divideAndRemainder(sum) }
        val result = parts.mapValues { it.value[0].longValueExact() }.toMutableMap()
        val remaining = (total - result.values.sum()).toInt()
        parts.entries.sortedWith(compareByDescending<Map.Entry<String, Array<BigInteger>>> { it.value[1] }.thenBy { it.key })
            .take(remaining).forEach { result[it.key] = result.getValue(it.key) + 1 }
        return result
    }
}

/** Closure accounting: full trip km at closure, plus empty km of the period. Never add GPS trip buckets twice. */
object OperatingCosts {
    private const val POOL = "__unallocated__"
    fun modelAt(models: List<OperatingCostModel>, at: Long) = models.filter { it.effectiveAtMs <= at }
        .maxWithOrNull(compareBy<OperatingCostModel> { it.effectiveAtMs }.thenBy { it.revision })?.takeIf { it.valid() }
    fun endedAt(t: Trip): Long? = if (t.status.isOpen) null else t.completedAt ?: t.cancelledAt ?: t.interruptedAt

    fun variable(m: OperatingCostModel, mm: Long) = OperatingCostParts(
        fuel = requireNotNull(OperatingMath.rate(m.fuelPriceMinor, mm, m.kmPerGallonMilli * 1_000)),
        maintenance = requireNotNull(OperatingMath.rate(m.maintenancePerKmMinor, mm, 1_000_000)),
        depreciation = requireNotNull(OperatingMath.rate(m.depreciationPerKmMinor, mm, 1_000_000)))

    fun report(window: EarningsWindow, clock: WorkdayClock, now: Long, models: List<OperatingCostModel>,
        trips: List<Trip>, summaries: Map<String, TripFinancialSummary>, distances: Map<String, TripDistance>,
        segments: List<DistanceSegment>, sessions: List<OnlineSession>, expenses: List<ExpenseEntry>,
        income: EarningsPeriodSummary): OperatingReport {
        val issues = linkedSetOf<OperatingIssue>()
        val results = mutableListOf<OperatingTripResult>()
        var pool = OperatingCostParts()
        var onlineMs = 0L
        var coveredMs = 0L
        var operatingMm = 0L
        val revisions = linkedSetOf<Int>()
        val end = minOf(window.endMs, now)
        val closed = trips.filter { endedAt(it)?.let { at -> at >= window.startMs && at < end } == true }
        if (trips.any { it.status.isOpen && it.createdAt < end }) issues += OperatingIssue.OPEN_TRIP
        window.days.forEach { day ->
            val start = clock.startOf(day)
            val stop = minOf(clock.startOf(day.plusDays(1)), end)
            if (stop <= start) return@forEach
            val span = TimeSpan(start, stop)
            val dayTrips = closed.filter { clock.workdayOf(requireNotNull(endedAt(it))) == day }
            val daySessions = sessions.filter { it.startedAt < stop && it.possibleEnd(now) > start }
            val confirmed = OnlineMetrics.union(daySessions.flatMap { it.confirmedSpans(now) }.mapNotNull { OnlineMetrics.clip(it, span) })
            val online = OnlineMetrics.length(confirmed)
            val possible = OnlineMetrics.length(OnlineMetrics.union(daySessions.mapNotNull { OnlineMetrics.clip(TimeSpan(it.startedAt, it.possibleEnd(now)), span) }))
            val daySegments = segments.filter { it.startedAt < stop && (it.endedAt ?: it.updatedAt) > start }
            val activity = dayTrips.isNotEmpty() || online > 0 || daySegments.any { it.emptyMm > 0 }
            if (!activity) return@forEach
            val dayModel = modelAt(models, start)
            if (dayModel == null) issues += OperatingIssue.MODEL else revisions += dayModel.revision
            var timeValid = online > 0 && possible == online && daySessions.all { it.anomalies.isEmpty() && it.startKind == OnlineStartKind.EXACT }
            if (!timeValid) issues += OperatingIssue.ONLINE
            val goodSpans = daySegments.filter { it.fixesUsed >= 2 && it.gapMm == 0L && it.secondsWithoutSignal == 0L && it.offlineMm == 0L }
                .mapNotNull { OnlineMetrics.clip(TimeSpan(it.startedAt, it.endedAt ?: it.updatedAt), span) }
            val coverage = OnlineMetrics.length(OnlineMetrics.intersect(OnlineMetrics.union(goodSpans), confirmed))
            val hasGap = daySegments.any { s ->
                (s.gapMm > 0 || s.secondsWithoutSignal > 0) &&
                    (s.emptyMm > 0 || s.pickupMm > 0 || s.passengerMm > 0 ||
                        confirmed.any { it.startMs < (s.endedAt ?: s.updatedAt) && it.endMs > s.startedAt })
            }
            if (online <= 0 || coverage * 100 < online * 90 || hasGap) issues += OperatingIssue.GPS
            onlineMs += online
            coveredMs += coverage
            val fullSpans = dayTrips.mapNotNull { t -> t.acceptedAt?.let { a -> endedAt(t)?.takeIf { it > a }?.let { TimeSpan(a, it) } } }
            val overlap = OnlineMetrics.length(OnlineMetrics.union(fullSpans)) < fullSpans.sumOf { it.durationMs }
            if (overlap) issues += OperatingIssue.OVERLAP
            val busy = OnlineMetrics.length(OnlineMetrics.intersect(OnlineMetrics.union(fullSpans), confirmed))
            val knownBusy = OnlineMetrics.length(OnlineMetrics.union(fullSpans.mapNotNull { OnlineMetrics.clip(it, span) }))
            if (knownBusy > busy + 60_000) { timeValid = false; issues += OperatingIssue.ONLINE }
            val missingDuration = fullSpans.size != dayTrips.size
            val weights = dayTrips.associate { it.id to (it.acceptedAt?.let { a -> (requireNotNull(endedAt(it)) - a).coerceAtLeast(0) } ?: 0L) } +
                (POOL to (online - busy).coerceAtLeast(0))
            val budget = dayModel?.let { if (it.fixedBasis == FixedCostBasis.WORKDAY) it.fixedMinor else OperatingMath.rate(it.fixedMinor, online, 3_600_000) ?: 0 } ?: 0
            val allocation = OperatingMath.allocate(budget, weights)
            pool += OperatingCostParts(fixed = if (weights.values.sum() == 0L) budget else allocation[POOL] ?: 0)
            dayTrips.forEach { t ->
                val problems = linkedSetOf<OperatingIssue>()
                val at = t.acceptedAt ?: t.pickupNavigationAt ?: t.createdAt
                val model = modelAt(models, at)
                if (model == null || dayModel == null) problems += OperatingIssue.MODEL else revisions += model.revision
                val summary = summaries[t.id]
                val real = summary?.effectiveEarnings?.takeIf { summary.completeness == FinancialCompleteness.CONFIRMED && it.currencyCode == "DOP" && TripIncome.earnedAt(t, summary) != null }?.minorUnits
                if (real == null) problems += OperatingIssue.MONEY
                val distance = distances[t.id]
                val mm = distance?.let { it.pickupMm + it.tripMm }
                if (distance == null || distance.coverage != TripDistanceCoverage.FULL || distance.gapMm > 0 || mm == null || mm < 0) problems += OperatingIssue.TRIP_GPS
                val duration = t.acceptedAt?.let { a -> (requireNotNull(endedAt(t)) - a).takeIf { it > 0 } }
                if (duration == null) problems += OperatingIssue.TIME
                if (missingDuration && dayModel?.fixedMinor != 0L) problems += OperatingIssue.TIME
                if (overlap) problems += OperatingIssue.OVERLAP
                if (dayModel?.fixedMinor != 0L && !timeValid) problems += OperatingIssue.ONLINE
                val variable = if (model != null && mm != null && OperatingIssue.TRIP_GPS !in problems) variable(model, mm) else OperatingCostParts()
                val cost = variable + OperatingCostParts(fixed = allocation[t.id] ?: 0)
                results += OperatingTripResult(t.id, model?.revision, dayModel?.revision, real, cost, mm, duration, problems)
                operatingMm += mm ?: 0
                issues += problems
            }
            daySegments.forEach segmentLoop@ { segment ->
                val segmentEnd = segment.endedAt ?: segment.updatedAt
                val clipped = OnlineMetrics.clip(TimeSpan(segment.startedAt, segmentEnd), span) ?: return@segmentLoop
                val mm = OperatingMath.rate(segment.emptyMm, clipped.durationMs, segmentEnd - segment.startedAt) ?: 0
                operatingMm += mm
                if (segment.emptyMm > 0 && (clipped.startMs != segment.startedAt || clipped.endMs != segmentEnd)) issues += OperatingIssue.GPS
                if (dayModel != null) pool += variable(dayModel, mm)
            }
        }
        expenses.filter { it.atMs >= window.startMs && it.atMs < end && it.type.group == ExpenseGroup.LOOSE }.forEach { e ->
            val model = modelAt(models, e.atMs)
            if (model == null || e.currencyCode != "DOP") issues += OperatingIssue.MODEL
            else { pool += OperatingCostParts(loose = OperatingMath.rate(e.amountMinor, model.looseWorkSharePct.toLong(), 100) ?: 0); revisions += model.revision }
        }
        if (closed.isEmpty() && onlineMs == 0L) issues += OperatingIssue.NO_ACTIVITY
        if (income.pendingReviewCount > 0 || income.partialTripCount > 0 || income.unknownTripCount > 0) issues += OperatingIssue.MONEY
        val total = results.fold(pool) { a,b -> a + b.cost }
        return OperatingReport(income.totalObservedEarnings, total, pool, results.sortedBy { it.tripId }, onlineMs,
            results.sumOf { it.committedMs ?: 0 }, operatingMm, if (onlineMs > 0) (coveredMs * 100 / onlineMs).toInt() else null, revisions, issues)
    }
}
