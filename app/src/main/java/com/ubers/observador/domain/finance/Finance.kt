package com.ubers.observador.domain.finance

import com.ubers.observador.domain.money.MoneyAmount
import com.ubers.observador.domain.money.MoneyMath
import com.ubers.observador.domain.money.MoneyResult
import java.math.BigDecimal
import java.security.MessageDigest

/** What an amount is. Fixed storage keys; UNKNOWN is never silently turned into OTHER. */
enum class FinancialObservationType(val storageKey: String) {
    TRIP_TOTAL("TRIP_TOTAL"),
    FARE("FARE"),
    TIP("TIP"),
    PROMOTION("PROMOTION"),
    ADJUSTMENT("ADJUSTMENT"),
    CANCELLATION_FEE("CANCELLATION_FEE"),
    TOLL_REIMBURSEMENT("TOLL_REIMBURSEMENT"),
    OTHER("OTHER"),
    UNKNOWN("UNKNOWN"),
    /** Append-only correction: cancels the observation in [FinancialObservation.voidsObservationId]. */
    VOID("VOID");

    /** Types allowed to be negative (a deduction). A negative total or tip is an anomaly. */
    val mayBeNegative: Boolean get() = this == ADJUSTMENT || this == OTHER

    companion object {
        fun fromStorage(key: String?) = entries.firstOrNull { it.storageKey == key } ?: UNKNOWN
    }
}

/**
 * How an amount relates to the total, the key to never counting twice:
 * ABSOLUTE_TOTAL replaces the base; DELTA adds to the latest base; COMPONENT is a part of a
 * total, shown in the breakdown and never added on top of it.
 */
enum class AmountSemantics(val storageKey: String) {
    ABSOLUTE_TOTAL("ABSOLUTE_TOTAL"),
    DELTA("DELTA"),
    COMPONENT("COMPONENT");

    companion object {
        fun fromStorage(key: String?) = entries.firstOrNull { it.storageKey == key } ?: COMPONENT
    }
}

enum class FinancialScope(val storageKey: String) {
    TRIP("TRIP"), PERIOD("PERIOD"), UNKNOWN("UNKNOWN");

    companion object {
        fun fromStorage(key: String?) = entries.firstOrNull { it.storageKey == key } ?: UNKNOWN
    }
}

enum class FinancialSource(val storageKey: String) {
    UBER_SCREEN("UBER_SCREEN"), MANUAL("MANUAL"), TEST("TEST");

    companion object {
        fun fromStorage(key: String?) = entries.firstOrNull { it.storageKey == key } ?: UBER_SCREEN
    }
}

/** Only HIGH links an amount to a trip automatically; anything else stays unassigned. */
enum class LinkConfidence(val storageKey: String) {
    HIGH("HIGH"), MEDIUM("MEDIUM"), LOW("LOW"), UNKNOWN("UNKNOWN");

    companion object {
        fun fromStorage(key: String?) = entries.firstOrNull { it.storageKey == key } ?: UNKNOWN
    }
}

enum class FinancialCompleteness(val storageKey: String) {
    /** An absolute total was observed. */
    CONFIRMED("CONFIRMED"),
    /** Some money was observed, but no total: the trip's earnings stay unknown. */
    PARTIAL("PARTIAL"),
    UNKNOWN("UNKNOWN");

    companion object {
        fun fromStorage(key: String?) = entries.firstOrNull { it.storageKey == key } ?: UNKNOWN
    }
}

enum class FinancialAnomaly(val storageKey: String) {
    CURRENCY_MISMATCH("CURRENCY_MISMATCH"),
    CONFLICTING_TOTALS("CONFLICTING_TOTALS"),
    NEGATIVE_UNEXPECTED("NEGATIVE_UNEXPECTED"),
    COMPONENT_TOTAL_MISMATCH("COMPONENT_TOTAL_MISMATCH"),
    TOTAL_REGRESSION("TOTAL_REGRESSION"),
    LOW_CONFIDENCE("LOW_CONFIDENCE"),
    UNKNOWN_TYPE("UNKNOWN_TYPE");

    companion object {
        fun fromStorage(key: String?) = entries.firstOrNull { it.storageKey == key }
    }
}

/**
 * One amount Observador saw, stored once and never edited. Codes only: no screen text, no
 * rider data. [observedAtMs] is when it was seen, which is not always when it was earned.
 */
data class FinancialObservation(
    val observationId: String,
    val observedAtMs: Long,
    val lifecycleSessionId: String?,
    val tripId: String?,
    val type: FinancialObservationType,
    val semantics: AmountSemantics,
    val amount: MoneyAmount,
    val scope: FinancialScope,
    val confidence: Double,
    val evidence: List<String>,
    val source: FinancialSource,
    val screenType: String?,
    val linkConfidence: LinkConfidence,
    /** Components read together from one breakdown share this id. */
    val breakdownGroupId: String? = null,
    val voidsObservationId: String? = null
)

/** Every threshold of the money rules in one place. */
object FinancePolicy {
    /** Observations below this confidence are kept but never summed ("pendiente de revisar"). */
    const val MIN_CONFIDENCE = 0.85

    /** Two different totals seen this close together are a conflict, not an update. */
    const val CONFLICT_WINDOW_MS = 60_000L

    /** Reconciliation tolerance: one minor unit (RD$0.01). */
    const val RECONCILIATION_TOLERANCE_MINOR = 1L

    /** An earnings screen links to the trip that ended at most this long before. */
    const val LINK_WINDOW_MS = 10 * 60_000L

    /** Unassigned amounts with the same value inside this window are one reading. */
    const val UNASSIGNED_DEDUP_BUCKET_MS = 10 * 60_000L
}

/**
 * Deterministic ids: the same reading of the same screen always produces the same id, so 50
 * accessibility events, a re-opened trip detail or a restart store it once (the database keeps
 * the id unique). Linked amounts have no time in the key (re-reading later is the same money);
 * unassigned ones use a time bucket, so a second, later tip of the same value is not lost.
 * Built from codes and the amount only: no screen text, no rider data.
 */
object FinancialIds {

    fun observationId(
        sessionOrTrip: String?,
        type: FinancialObservationType,
        semantics: AmountSemantics,
        amount: MoneyAmount,
        screenType: String?,
        observedAtMs: Long
    ): String {
        val bucket = if (sessionOrTrip == null) (observedAtMs / FinancePolicy.UNASSIGNED_DEDUP_BUCKET_MS).toString() else ""
        return "obs-" + hash("${sessionOrTrip.orEmpty()}|${type.storageKey}|${semantics.storageKey}|${amount.minorUnits}|${amount.currencyCode}|${screenType.orEmpty()}|$bucket")
    }

    /** Components read together from one breakdown (same rule for time as [observationId]). */
    fun groupId(sessionOrTrip: String?, componentsKey: String, observedAtMs: Long): String {
        val bucket = if (sessionOrTrip == null) (observedAtMs / FinancePolicy.UNASSIGNED_DEDUP_BUCKET_MS).toString() else ""
        return "grp-" + hash("${sessionOrTrip.orEmpty()}|$componentsKey|$bucket")
    }

    /** One logical platform snapshot per period, amount and calendar day of capture. */
    fun snapshotId(periodType: PlatformPeriodType, amount: MoneyAmount, dayKey: String): String =
        "snap-" + hash("${periodType.storageKey}|${amount.minorUnits}|${amount.currencyCode}|$dayKey")

    private fun hash(raw: String): String =
        MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(24)
}

/** The money of one trip, rebuilt at any time from its observations. */
data class TripFinancialSummary(
    val tripId: String,
    val currencyCode: String?,
    /** What the offer showed: a reference, never income. */
    val offeredAmount: MoneyAmount?,
    val latestAbsoluteTotal: MoneyAmount?,
    /** Best real figure known; null when no total was observed. Never the offered amount. */
    val effectiveEarnings: MoneyAmount?,
    val fare: Long = 0,
    val tip: Long = 0,
    val promotion: Long = 0,
    val adjustment: Long = 0,
    val cancellationFee: Long = 0,
    val other: Long = 0,
    val completeness: FinancialCompleteness,
    val anomalies: Set<FinancialAnomaly> = emptySet(),
    val observationCount: Int = 0,
    /** Kept but not summed: low confidence or UNKNOWN type. */
    val pendingReviewCount: Int = 0,
    val firstObservedAt: Long? = null,
    val lastObservedAt: Long? = null
) {
    val hasAnomalies: Boolean get() = anomalies.isNotEmpty()

    /** Real minus offered, and the percentage; null unless both exist in the same currency and offered > 0. */
    fun differenceFromOffer(): Pair<MoneyAmount, BigDecimal>? {
        val offered = offeredAmount ?: return null
        val actual = effectiveEarnings ?: return null
        val percent = MoneyMath.percentChange(offered, actual) ?: return null
        return MoneyAmount(actual.minorUnits - offered.minorUnits, actual.currencyCode) to percent
    }
}

/**
 * Turns a trip's observations into its money. Pure and deterministic, so the stored summary can
 * always be rebuilt from the ledger and compared.
 *
 * Effective total = the latest ABSOLUTE_TOTAL + every DELTA observed after it. A later absolute
 * becomes the new base and absorbs earlier deltas (100, tip +20, then total 120 = 120, never 140).
 * COMPONENTs only fill the breakdown. Without an absolute total there is no effective total:
 * completeness is PARTIAL and the earnings stay null, even if the offer price is known.
 */
object TripFinancialAggregator {

    fun aggregate(tripId: String, offered: MoneyAmount?, observations: List<FinancialObservation>): TripFinancialSummary {
        val voided = observations.mapNotNull { it.voidsObservationId }.toSet()
        val live = observations.filter { it.type != FinancialObservationType.VOID && it.observationId !in voided }
            .sortedWith(compareBy<FinancialObservation> { it.observedAtMs }.thenBy { it.observationId })
        val anomalies = LinkedHashSet<FinancialAnomaly>()

        val pending = live.filter { it.confidence < FinancePolicy.MIN_CONFIDENCE || it.type == FinancialObservationType.UNKNOWN }
        pending.forEach {
            anomalies += if (it.type == FinancialObservationType.UNKNOWN) FinancialAnomaly.UNKNOWN_TYPE else FinancialAnomaly.LOW_CONFIDENCE
        }
        val usable0 = live - pending.toSet()
        val currency = usable0.firstOrNull { it.semantics == AmountSemantics.ABSOLUTE_TOTAL }?.amount?.currencyCode
            ?: usable0.firstOrNull()?.amount?.currencyCode
        val wrongCurrency = usable0.filter { it.amount.currencyCode != currency }
        if (wrongCurrency.isNotEmpty()) anomalies += FinancialAnomaly.CURRENCY_MISMATCH
        val negative = usable0.filter { it.amount.isNegative && !it.type.mayBeNegative }
        if (negative.isNotEmpty()) anomalies += FinancialAnomaly.NEGATIVE_UNEXPECTED
        val usable = usable0 - wrongCurrency.toSet() - negative.toSet()

        val absolutes = usable.filter { it.semantics == AmountSemantics.ABSOLUTE_TOTAL }
        val latest = absolutes.lastOrNull()
        absolutes.zipWithNext().forEach { (a, b) ->
            val close = b.observedAtMs - a.observedAtMs <= FinancePolicy.CONFLICT_WINDOW_MS
            if (a.amount != b.amount && close) anomalies += FinancialAnomaly.CONFLICTING_TOTALS
            if (b.amount.minorUnits < a.amount.minorUnits && !close) {
                val explained = usable.any {
                    it.type == FinancialObservationType.ADJUSTMENT && it.amount.isNegative &&
                        it.observedAtMs in a.observedAtMs..b.observedAtMs
                }
                if (!explained) anomalies += FinancialAnomaly.TOTAL_REGRESSION
            }
        }
        val deltas = usable.filter { it.semantics == AmountSemantics.DELTA }
        val deltasAfter = if (latest == null) emptyList() else deltas.filter { it.observedAtMs > latest.observedAtMs }
        val effective = latest?.let { base ->
            (MoneyMath.sum(listOf(base.amount) + deltasAfter.map { it.amount }) as? MoneyResult.Ok)?.amount
        }

        // Breakdown: the latest group of components, plus deltas still counting on top of the base
        val components = usable.filter { it.semantics == AmountSemantics.COMPONENT }
        val latestGroup = components.lastOrNull()?.breakdownGroupId
        val breakdown = components.filter { it.breakdownGroupId == latestGroup } + (if (latest == null) deltas else deltasAfter)
        fun part(type: FinancialObservationType) = breakdown.filter { it.type == type }.sumOf { it.amount.minorUnits }
        if (latest != null && latestGroup != null) {
            val groupSum = components.filter { it.breakdownGroupId == latestGroup }.sumOf { it.amount.minorUnits }
            val groupTime = components.last().observedAtMs
            if (groupTime <= latest.observedAtMs && groupSum != latest.amount.minorUnits) {
                anomalies += FinancialAnomaly.COMPONENT_TOTAL_MISMATCH
            }
        }

        val completeness = when {
            effective != null -> FinancialCompleteness.CONFIRMED
            usable.isNotEmpty() -> FinancialCompleteness.PARTIAL
            else -> FinancialCompleteness.UNKNOWN
        }
        return TripFinancialSummary(
            tripId = tripId,
            currencyCode = currency,
            offeredAmount = offered,
            latestAbsoluteTotal = latest?.amount,
            effectiveEarnings = effective,
            fare = part(FinancialObservationType.FARE),
            tip = part(FinancialObservationType.TIP),
            promotion = part(FinancialObservationType.PROMOTION),
            adjustment = part(FinancialObservationType.ADJUSTMENT),
            cancellationFee = part(FinancialObservationType.CANCELLATION_FEE),
            other = part(FinancialObservationType.OTHER) + part(FinancialObservationType.TOLL_REIMBURSEMENT),
            completeness = completeness,
            anomalies = anomalies,
            observationCount = live.size,
            pendingReviewCount = pending.size,
            firstObservedAt = live.firstOrNull()?.observedAtMs,
            lastObservedAt = live.lastOrNull()?.observedAtMs
        )
    }
}

enum class PlatformPeriodType(val storageKey: String) {
    DAY("DAY"), WEEK("WEEK"), CUSTOM("CUSTOM"), UNKNOWN("UNKNOWN");

    companion object {
        fun fromStorage(key: String?) = entries.firstOrNull { it.storageKey == key } ?: UNKNOWN
    }
}

/**
 * "Uber says this period totals X": a reference for reconciliation, never income. Period limits
 * are null unless the screen states them; Observador does not assume Uber's day or week.
 */
data class PlatformEarningsSnapshot(
    val snapshotId: String,
    val capturedAtMs: Long,
    val periodType: PlatformPeriodType,
    val periodStartMs: Long?,
    val periodEndMs: Long?,
    val amount: MoneyAmount,
    val confidence: Double,
    val screenType: String?,
    val evidence: List<String>
)

/** Real money of a period, trips and other income kept apart. Amounts in minor units of [currencyCode]. */
data class EarningsPeriodSummary(
    val currencyCode: String,
    val tripEarnings: Long,
    val tips: Long,
    val promotions: Long,
    val adjustments: Long,
    /** Other income in trips (cancellation fees, tolls, other), in minor units. */
    val otherEarnings: Long,
    /** Period income not linked to any trip (promotion, adjustment, other), shown apart, never hidden inside trips. */
    val unassignedEarnings: Long,
    val totalObservedEarnings: Long,
    val completedTripCount: Int,
    val confirmedTripCount: Int,
    val partialTripCount: Int,
    val unknownTripCount: Int,
    /** Amounts kept but not summed (low confidence, unknown type, other currency, unlinked). */
    val pendingReviewCount: Int,
    /** Of those, amounts waiting to be linked to a trip ("Pendientes de vincular"). */
    val unlinkedPendingCount: Int
) {
    /** Share of completed trips whose money is confirmed. Not the same as confidence. */
    val coverage: BigDecimal?
        get() = if (completedTripCount == 0) null
        else BigDecimal(confirmedTripCount * 100).divide(BigDecimal(completedTripCount), 0, com.ubers.observador.domain.money.MoneyRounding.MODE)
}

object EarningsPeriods {

    /** Unlinked income that can count for a period: never a trip total or a tip (they belong to a trip). */
    private val PERIOD_INCOME = setOf(FinancialObservationType.PROMOTION, FinancialObservationType.ADJUSTMENT, FinancialObservationType.OTHER)

    /**
     * Sums real money only: CONFIRMED trip summaries, plus unlinked amounts whose period is
     * confirmed (scope PERIOD, a DELTA of promotion, adjustment or other income) in
     * [currencyCode]. An unlinked trip total or tip is never added: it may belong to a trip
     * already counted (a trip detail reopened, a tip already inside a later total). It waits as
     * "pendiente de vincular". Offer prices, platform snapshots, components, voided,
     * low-confidence and other-currency amounts never enter the total.
     */
    fun summarize(
        currencyCode: String,
        completedTrips: List<TripFinancialSummary>,
        unassigned: List<FinancialObservation>
    ): EarningsPeriodSummary {
        val confirmed = completedTrips.filter { it.completeness == FinancialCompleteness.CONFIRMED && it.currencyCode == currencyCode }
        val voided = unassigned.mapNotNull { it.voidsObservationId }.toSet()
        val live = unassigned.filter { it.type != FinancialObservationType.VOID && it.observationId !in voided }
        val trustworthy = live.filter {
            it.confidence >= FinancePolicy.MIN_CONFIDENCE && it.type != FinancialObservationType.UNKNOWN &&
                it.amount.currencyCode == currencyCode && it.semantics != AmountSemantics.COMPONENT &&
                (!it.amount.isNegative || it.type.mayBeNegative)
        }
        val usable = trustworthy.filter {
            it.semantics == AmountSemantics.DELTA && it.scope == FinancialScope.PERIOD && it.type in PERIOD_INCOME
        }
        val unlinked = trustworthy.filter { it !in usable }
        fun unassignedOf(type: FinancialObservationType) = usable.filter { it.type == type }.sumOf { it.amount.minorUnits }
        val tripEarnings = confirmed.sumOf { requireNotNull(it.effectiveEarnings).minorUnits }
        val unassignedSum = usable.sumOf { it.amount.minorUnits }
        return EarningsPeriodSummary(
            currencyCode = currencyCode,
            tripEarnings = tripEarnings,
            tips = confirmed.sumOf { it.tip },
            promotions = confirmed.sumOf { it.promotion } + unassignedOf(FinancialObservationType.PROMOTION),
            adjustments = confirmed.sumOf { it.adjustment } + unassignedOf(FinancialObservationType.ADJUSTMENT),
            otherEarnings = confirmed.sumOf { it.other + it.cancellationFee } + unassignedOf(FinancialObservationType.OTHER),
            unassignedEarnings = unassignedSum,
            totalObservedEarnings = Math.addExact(tripEarnings, unassignedSum),
            completedTripCount = completedTrips.size,
            confirmedTripCount = confirmed.size,
            partialTripCount = completedTrips.count { it.completeness == FinancialCompleteness.PARTIAL },
            unknownTripCount = completedTrips.count { it.completeness == FinancialCompleteness.UNKNOWN },
            pendingReviewCount = completedTrips.sumOf { it.pendingReviewCount } + (live.size - usable.size),
            unlinkedPendingCount = unlinked.size
        )
    }
}

enum class ReconciliationStatus { MATCHED, DIFFERENCE, INCOMPLETE, NO_REFERENCE, CURRENCY_MISMATCH, PERIOD_MISMATCH }

data class Reconciliation(
    val status: ReconciliationStatus,
    val local: MoneyAmount?,
    val platform: MoneyAmount?,
    /** platform - local, when comparable. */
    val difference: MoneyAmount?
)

object Reconciler {

    /**
     * Compares Observador's money for [periodStartMs, periodEndMs) with Uber's figure for the
     * same period. Uber's period must state the same limits: a 4:00-4:00 working day is never
     * compared with an Uber "day" of unknown limits. A difference is shown, never forced to fit.
     */
    fun reconcile(
        local: EarningsPeriodSummary,
        periodStartMs: Long,
        periodEndMs: Long,
        snapshot: PlatformEarningsSnapshot?
    ): Reconciliation {
        val localAmount = MoneyAmount(local.totalObservedEarnings, local.currencyCode)
        if (snapshot == null) return Reconciliation(ReconciliationStatus.NO_REFERENCE, localAmount, null, null)
        if (snapshot.periodStartMs != periodStartMs || snapshot.periodEndMs != periodEndMs) {
            return Reconciliation(ReconciliationStatus.PERIOD_MISMATCH, localAmount, snapshot.amount, null)
        }
        if (snapshot.amount.currencyCode != local.currencyCode) {
            return Reconciliation(ReconciliationStatus.CURRENCY_MISMATCH, localAmount, snapshot.amount, null)
        }
        val difference = MoneyAmount(snapshot.amount.minorUnits - localAmount.minorUnits, local.currencyCode)
        val status = when {
            Math.abs(difference.minorUnits) <= FinancePolicy.RECONCILIATION_TOLERANCE_MINOR -> ReconciliationStatus.MATCHED
            local.confirmedTripCount < local.completedTripCount || local.pendingReviewCount > 0 -> ReconciliationStatus.INCOMPLETE
            else -> ReconciliationStatus.DIFFERENCE
        }
        return Reconciliation(status, localAmount, snapshot.amount, difference)
    }

    /** Snapshot regressions (Uber's period total going down) are flagged, not treated as errors. */
    fun regressions(snapshots: List<PlatformEarningsSnapshot>): List<Pair<PlatformEarningsSnapshot, PlatformEarningsSnapshot>> =
        snapshots.sortedBy { it.capturedAtMs }.zipWithNext()
            .filter { (a, b) -> a.periodType == b.periodType && a.periodStartMs == b.periodStartMs && b.amount.minorUnits < a.amount.minorUnits }
}
