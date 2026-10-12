package com.ubers.observador.domain.finance

import com.ubers.observador.domain.money.MoneyAmount

sealed interface FinancialPersistResult {
    data class Stored(val observationId: String, val tripId: String?) : FinancialPersistResult
    data class DuplicateIgnored(val observationId: String) : FinancialPersistResult
}

/**
 * What to store for one observation. A HIGH link becomes a trip id only if the session's trip
 * exists; otherwise the amount is kept unassigned (never attached to a guess).
 */
object FinancialWriteRules {

    fun resolve(observation: FinancialObservation, alreadyStored: Boolean, tripIdForSession: String?): Pair<FinancialObservation?, FinancialPersistResult> {
        if (alreadyStored) return null to FinancialPersistResult.DuplicateIgnored(observation.observationId)
        val linked = observation.tripId ?: tripIdForSession?.takeIf { observation.linkConfidence == LinkConfidence.HIGH }
        val stored = if (linked != null) {
            observation.copy(tripId = linked, scope = FinancialScope.TRIP)
        } else {
            observation.copy(
                tripId = null,
                linkConfidence = if (observation.linkConfidence == LinkConfidence.HIGH) LinkConfidence.LOW else observation.linkConfidence
            )
        }
        return stored to FinancialPersistResult.Stored(stored.observationId, stored.tripId)
    }
}

/** Debug counters of the money writer. Memory only. */
data class FinancialMetrics(
    val financialScreensProcessed: Long = 0,
    val financialScreensUnknown: Long = 0,
    val observationsCreated: Long = 0,
    val duplicatesIgnored: Long = 0,
    val unassignedObservations: Long = 0,
    val summariesUpdated: Long = 0,
    val snapshotsCreated: Long = 0,
    val moneyParseFailures: Long = 0,
    val ambiguousAmounts: Long = 0,
    val unlabeledAmounts: Long = 0,
    val persistenceErrors: Long = 0
) {
    fun afterDetection(d: FinancialDetection) = copy(
        financialScreensProcessed = financialScreensProcessed + if (d.classification.type != FinancialScreenType.UNKNOWN) 1 else 0,
        moneyParseFailures = moneyParseFailures + d.parseFailures,
        ambiguousAmounts = ambiguousAmounts + d.ambiguousAmounts,
        unlabeledAmounts = unlabeledAmounts + d.unlabeledAmounts
    )

    fun afterPersist(result: FinancialPersistResult) = when (result) {
        is FinancialPersistResult.DuplicateIgnored -> copy(duplicatesIgnored = duplicatesIgnored + 1)
        is FinancialPersistResult.Stored -> copy(
            observationsCreated = observationsCreated + 1,
            unassignedObservations = unassignedObservations + if (result.tripId == null) 1 else 0,
            summariesUpdated = summariesUpdated + if (result.tripId != null) 1 else 0
        )
    }
}

/**
 * Observations, summaries and platform snapshots in memory, with exactly the rules of the
 * database (FinancialWriteRules + TripFinancialAggregator). For JVM tests and the TEST seeder.
 */
class InMemoryFinancialLedger(private val tripIdForSession: (String) -> String?, private val offeredFor: (String) -> MoneyAmount?) {

    private val stored = LinkedHashMap<String, FinancialObservation>()
    private val summaries = LinkedHashMap<String, TripFinancialSummary>()
    private val snapshotsById = LinkedHashMap<String, PlatformEarningsSnapshot>()

    val observations: List<FinancialObservation> get() = stored.values.toList()
    val snapshots: List<PlatformEarningsSnapshot> get() = snapshotsById.values.toList()
    fun summary(tripId: String): TripFinancialSummary? = summaries[tripId]
    fun unassigned(): List<FinancialObservation> = stored.values.filter { it.tripId == null }

    fun persist(observation: FinancialObservation): FinancialPersistResult {
        val (row, result) = FinancialWriteRules.resolve(
            observation,
            alreadyStored = observation.observationId in stored,
            tripIdForSession = observation.lifecycleSessionId?.let(tripIdForSession)
        )
        if (row != null) {
            stored[row.observationId] = row
            row.tripId?.let { rebuild(it) }
            // A void may target a linked observation: rebuild that trip too
            row.voidsObservationId?.let { stored[it]?.tripId }?.let { rebuild(it) }
        }
        return result
    }

    /** False when the same logical snapshot was already stored. */
    fun persistSnapshot(snapshot: PlatformEarningsSnapshot): Boolean {
        if (snapshot.snapshotId in snapshotsById) return false
        snapshotsById[snapshot.snapshotId] = snapshot
        return true
    }

    /** The summary rebuilt from the ledger alone; what the database stores must equal this. */
    fun rebuild(tripId: String): TripFinancialSummary {
        val forTrip = stored.values.filter { it.tripId == tripId }
        val voids = stored.values.filter { v -> v.voidsObservationId != null && forTrip.any { it.observationId == v.voidsObservationId } }
        return TripFinancialAggregator.aggregate(tripId, offeredFor(tripId), forTrip + voids).also { summaries[tripId] = it }
    }
}
