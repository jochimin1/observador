package com.ubers.observador.domain.earnings

import com.ubers.observador.domain.WorkdayClock
import com.ubers.observador.domain.finance.AmountSemantics
import com.ubers.observador.domain.finance.FinancialAnomaly
import com.ubers.observador.domain.finance.FinancialObservation
import com.ubers.observador.domain.finance.FinancialObservationType
import com.ubers.observador.domain.finance.FinancialScope
import com.ubers.observador.domain.finance.FinancialSource
import com.ubers.observador.domain.finance.LinkConfidence
import com.ubers.observador.domain.finance.TripFinancialAggregator
import com.ubers.observador.domain.finance.TripFinancialSummary
import com.ubers.observador.domain.money.Currencies
import com.ubers.observador.domain.money.MoneyAmount
import com.ubers.observador.domain.trips.Trip
import com.ubers.observador.domain.trips.TripStatus
import java.security.MessageDigest

/** Why an amount cannot go to a trip. */
enum class AssignRejection(val message: String) {
    NOT_PENDING("Esta cantidad ya no está pendiente."),
    NOT_ASSIGNABLE("Esta cantidad no se puede asignar: está por revisar o es parte de un desglose."),
    TRIP_NOT_COMPLETED("Solo se puede asignar a un viaje terminado."),
    CURRENCY_MISMATCH("El viaje tiene dinero en otra moneda.")
}

/** What the trip's money would look like after the assignment, for the confirmation dialog. */
data class AssignPreview(
    val before: TripFinancialSummary,
    val after: TripFinancialSummary,
    /** A total over another, different total: the dialog warns. */
    val replacesTotal: MoneyAmount?,
    val newAnomalies: Set<FinancialAnomaly>
)

sealed interface AssignCheck {
    data class Ok(val preview: AssignPreview) : AssignCheck
    data class Rejected(val reason: AssignRejection) : AssignCheck
}

/**
 * Fase 8C: putting an unassigned amount on a trip by hand. Nothing is ever edited: the original
 * gets a VOID and a copy linked to the trip is written (source MANUAL, evidence
 * [EVIDENCE_ASSIGN]); undo voids the copy and writes the amount again without a trip; "no es
 * mío" only voids. The copy keeps the time the amount was seen, so the 7C rules (a later total
 * replaces the base, a tip after the total adds) apply as if it had been linked from the start.
 */
object ManualAssignment {

    const val EVIDENCE_ASSIGN = "MANUAL_ASSIGNMENT"
    const val EVIDENCE_UNDO = "MANUAL_UNDO"
    const val EVIDENCE_REJECT = "MANUAL_REJECT"
    /** Followed by the original observation id (a hash, never screen text). */
    const val EVIDENCE_FROM = "ASSIGNED_FROM:"

    private const val TEST_PREFIX = "TEST-"

    fun isAssignable(o: FinancialObservation, currencyCode: String = Currencies.DOP): Boolean =
        o.tripId == null && o.type != FinancialObservationType.VOID &&
            EarningsReports.isTrustworthy(o, currencyCode)

    /**
     * Completed trips of the amount's working day and the one before, closest in time first.
     */
    fun candidates(o: FinancialObservation, trips: List<Trip>, clock: WorkdayClock): List<Trip> {
        val day = clock.workdayOf(o.observedAtMs)
        val days = setOf(day, day.minusDays(1))
        return trips.filter { t ->
            val at = t.completedAt
            t.status == TripStatus.COMPLETED && at != null && clock.workdayOf(at) in days
        }.sortedWith(compareBy<Trip> { Math.abs(requireNotNull(it.completedAt) - o.observedAtMs) }.thenBy { it.id })
    }

    /**
     * Checks an assignment and previews it with the trip's current observations ([tripObservations]
     * includes the voids that target them), using the same aggregator as the database.
     */
    fun check(
        o: FinancialObservation,
        alreadyVoided: Boolean,
        trip: Trip,
        offered: MoneyAmount?,
        tripObservations: List<FinancialObservation>,
        currencyCode: String = Currencies.DOP
    ): AssignCheck {
        if (alreadyVoided || o.tripId != null || o.type == FinancialObservationType.VOID) return AssignCheck.Rejected(AssignRejection.NOT_PENDING)
        if (!isAssignable(o, currencyCode)) return AssignCheck.Rejected(AssignRejection.NOT_ASSIGNABLE)
        if (trip.status != TripStatus.COMPLETED || trip.completedAt == null) return AssignCheck.Rejected(AssignRejection.TRIP_NOT_COMPLETED)
        val before = TripFinancialAggregator.aggregate(trip.id, offered, tripObservations)
        if (before.currencyCode != null && before.currencyCode != o.amount.currencyCode) return AssignCheck.Rejected(AssignRejection.CURRENCY_MISMATCH)
        val copy = linkedCopy(o, trip.id, 0L)
        val after = TripFinancialAggregator.aggregate(trip.id, offered, tripObservations + copy)
        val existing = before.latestAbsoluteTotal
        val replaces = existing?.takeIf { o.semantics == AmountSemantics.ABSOLUTE_TOTAL && it != o.amount }
        return AssignCheck.Ok(AssignPreview(before, after, replaces, after.anomalies - before.anomalies))
    }

    /** The two rows of an assignment, written together: the VOID of [original], then the linked copy. */
    fun assign(original: FinancialObservation, tripId: String, nowMs: Long): List<FinancialObservation> =
        listOf(void(original, EVIDENCE_ASSIGN, nowMs, tripId), linkedCopy(original, tripId, nowMs))

    /**
     * Undo: the VOID of [copy] and the amount again without a trip, as it was first seen
     * ([original] when still stored; otherwise rebuilt from the copy).
     */
    fun undo(copy: FinancialObservation, original: FinancialObservation?, nowMs: Long): List<FinancialObservation> {
        val base = original ?: copy.copy(scope = FinancialScope.UNKNOWN, linkConfidence = LinkConfidence.LOW, source = FinancialSource.UBER_SCREEN)
        val again = base.copy(
            observationId = id(copy.observationId, "undo", "", nowMs),
            tripId = null,
            lifecycleSessionId = null,
            evidence = base.evidence.filterNot { it == EVIDENCE_ASSIGN || it.startsWith(EVIDENCE_FROM) } + EVIDENCE_UNDO,
            voidsObservationId = null
        )
        return listOf(void(copy, EVIDENCE_UNDO, nowMs, ""), again)
    }

    /** "No es mío / mal leído": only the VOID. */
    fun reject(original: FinancialObservation, nowMs: Long): FinancialObservation = void(original, EVIDENCE_REJECT, nowMs, "")

    /** Live manual assignments among [rows] (manual copies not voided by a later row). */
    fun live(rows: List<FinancialObservation>): List<ManualAssignmentRow> {
        val voided = rows.mapNotNull { it.voidsObservationId }.toSet()
        return rows.filter { it.tripId != null && it.source == FinancialSource.MANUAL && EVIDENCE_ASSIGN in it.evidence && it.observationId !in voided }
            .sortedByDescending { it.observedAtMs }
            .map { ManualAssignmentRow(it, requireNotNull(it.tripId), originalIdOf(it)) }
    }

    fun originalIdOf(copy: FinancialObservation): String? =
        copy.evidence.firstOrNull { it.startsWith(EVIDENCE_FROM) }?.removePrefix(EVIDENCE_FROM)

    private fun linkedCopy(o: FinancialObservation, tripId: String, nowMs: Long) = o.copy(
        observationId = id(o.observationId, "assign", tripId, nowMs),
        tripId = tripId,
        scope = FinancialScope.TRIP,
        source = FinancialSource.MANUAL,
        linkConfidence = LinkConfidence.HIGH,
        evidence = o.evidence.filterNot { it == EVIDENCE_UNDO || it.startsWith(EVIDENCE_FROM) } + EVIDENCE_ASSIGN + (EVIDENCE_FROM + o.observationId),
        voidsObservationId = null
    )

    private fun void(target: FinancialObservation, evidence: String, nowMs: Long, salt: String) = FinancialObservation(
        observationId = id(target.observationId, "void-$evidence", salt, nowMs),
        observedAtMs = nowMs,
        lifecycleSessionId = null,
        tripId = null,
        type = FinancialObservationType.VOID,
        semantics = AmountSemantics.DELTA,
        amount = MoneyAmount.zero(target.amount.currencyCode),
        scope = FinancialScope.UNKNOWN,
        confidence = 1.0,
        evidence = listOf(evidence),
        source = FinancialSource.MANUAL,
        screenType = null,
        linkConfidence = LinkConfidence.UNKNOWN,
        voidsObservationId = target.observationId
    )

    /** Deterministic; TEST amounts keep the TEST- prefix so the debug cleanup still finds them. */
    private fun id(from: String, action: String, salt: String, nowMs: Long): String {
        val prefix = if (from.startsWith(TEST_PREFIX)) TEST_PREFIX else ""
        val raw = "$from|$action|$salt|$nowMs"
        val hash = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(24)
        return "${prefix}man-$hash"
    }
}
