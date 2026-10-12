package com.ubers.observador.data

import androidx.room.withTransaction
import com.ubers.observador.data.db.AppDatabase
import com.ubers.observador.data.db.toDomain
import com.ubers.observador.data.db.toEntity
import com.ubers.observador.domain.finance.EarningsPeriodSummary
import com.ubers.observador.domain.finance.EarningsPeriods
import com.ubers.observador.domain.finance.FinancialCompleteness
import com.ubers.observador.domain.finance.FinancialObservation
import com.ubers.observador.domain.finance.FinancialPersistResult
import com.ubers.observador.domain.finance.FinancialScope
import com.ubers.observador.domain.finance.LinkConfidence
import com.ubers.observador.domain.finance.FinancialWriteRules
import com.ubers.observador.domain.finance.PlatformEarningsSnapshot
import com.ubers.observador.domain.finance.PlatformPeriodType
import com.ubers.observador.domain.finance.TripFinancialAggregator
import com.ubers.observador.domain.finance.TripFinancialSummary
import com.ubers.observador.domain.money.Currencies
import com.ubers.observador.domain.money.MoneyAmount
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The only way money is written (Fase 7C). An observation and its trip's rebuilt summary are
 * written in one Room transaction, so the summary can never disagree with the ledger; and the
 * summary can always be rebuilt from the ledger alone ([rebuildTripFinancialSummary]).
 */
class FinancialRepository(private val db: AppDatabase) {

    private val money = db.financialDao()
    private val trips = db.tripDao()

    suspend fun persistObservation(input: FinancialObservation, nowMs: Long): FinancialPersistResult = db.withTransaction {
        // A trip id that is not stored is never kept: the amount waits unlinked instead
        val observation = input.tripId?.takeIf { trips.getById(it) == null }?.let {
            input.copy(tripId = null, scope = FinancialScope.UNKNOWN, linkConfidence = LinkConfidence.LOW)
        } ?: input
        val tripForSession = observation.lifecycleSessionId?.let { trips.getByLifecycleSessionId(it)?.id }
        val (row, result) = FinancialWriteRules.resolve(observation, money.observationExists(observation.observationId), tripForSession)
        if (row != null) {
            money.insertObservation(row.toEntity(nowMs))
            row.tripId?.let { rebuild(it, nowMs) }
            row.voidsObservationId?.let { money.getObservation(it)?.tripId }?.let { rebuild(it, nowMs) }
        }
        result
    }

    /** False when the same logical snapshot was already stored. */
    suspend fun persistSnapshot(snapshot: PlatformEarningsSnapshot): Boolean = db.withTransaction {
        if (money.snapshotExists(snapshot.snapshotId)) {
            false
        } else {
            money.insertSnapshot(snapshot.toEntity())
            true
        }
    }

    /** Recomputes and stores a trip's summary from its observations only. */
    suspend fun rebuildTripFinancialSummary(tripId: String, nowMs: Long): TripFinancialSummary = db.withTransaction {
        rebuild(tripId, nowMs)
    }

    /** Rebuilds every summary (debug, and recovery after any doubt). Returns how many. */
    suspend fun rebuildAll(nowMs: Long): Int = db.withTransaction {
        val ids = money.getTripIdsWithObservations()
        ids.forEach { rebuild(it, nowMs) }
        ids.size
    }

    /** Stored summaries that disagree with a fresh rebuild (total or completeness). Should always be 0. */
    suspend fun driftedSummaries(): Int = db.withTransaction {
        money.getTripIdsWithObservations().count { id ->
            val fresh = compute(id)
            val stored = money.getSummary(id)?.toDomain(Currencies.DOP)
            trips.getById(id) != null &&
                (stored?.effectiveEarnings != fresh.effectiveEarnings || stored?.completeness != fresh.completeness)
        }
    }

    /** The summary as computed from the ledger right now, without storing it (for checks). */
    suspend fun computeSummary(tripId: String): TripFinancialSummary = db.withTransaction { compute(tripId) }

    suspend fun getStoredSummary(tripId: String): TripFinancialSummary? =
        money.getSummary(tripId)?.toDomain(Currencies.DOP)

    fun observeSummary(tripId: String): Flow<TripFinancialSummary?> =
        money.observeSummary(tripId).map { it?.toDomain(Currencies.DOP) }

    fun observeUnassigned(): Flow<List<FinancialObservation>> = money.observeUnassigned().map { l -> l.map { it.toDomain() } }

    fun observeRecentObservations(limit: Int): Flow<List<FinancialObservation>> =
        money.observeRecentObservations(limit).map { l -> l.map { it.toDomain() } }

    fun observeLatestPlatformSnapshot(period: PlatformPeriodType): Flow<PlatformEarningsSnapshot?> =
        money.observeLatestSnapshot(period.storageKey).map { it?.toDomain() }

    suspend fun getLatestPlatformSnapshot(): PlatformEarningsSnapshot? = money.getLatestSnapshot()?.toDomain()

    /** Real money of trips completed in [from, to) plus unassigned amounts seen then. Offers never count. */
    suspend fun periodEarnings(from: Long, to: Long, currencyCode: String = Currencies.DOP): EarningsPeriodSummary =
        db.withTransaction {
            val completed = trips.getTripsCompletedBetween(from, to).map { it.id }
            val stored = if (completed.isEmpty()) emptyMap() else money.getSummaries(completed).associateBy { it.tripId }
            val summaries = completed.map { id ->
                stored[id]?.toDomain(currencyCode) ?: TripFinancialSummary(
                    tripId = id, currencyCode = null, offeredAmount = null, latestAbsoluteTotal = null,
                    effectiveEarnings = null, completeness = FinancialCompleteness.UNKNOWN
                )
            }
            EarningsPeriods.summarize(currencyCode, summaries, money.getUnassignedBetween(from, to).map { it.toDomain() })
        }

    /** Debug only: removes TEST money (ids starting with [prefix]); real money is never touched. */
    suspend fun deleteTestData(prefix: String) = db.withTransaction {
        money.deleteObservationsByIdPrefix(prefix)
        money.deleteSummariesByTripPrefix(prefix)
        money.deleteSnapshotsByIdPrefix(prefix)
    }

    /** Debug only: loads TEST observations and snapshots through the same write path as real ones. */
    suspend fun loadTestData(prefix: String, observations: List<FinancialObservation>, snapshots: List<PlatformEarningsSnapshot>, nowMs: Long) =
        db.withTransaction {
            require(observations.all { it.observationId.startsWith(prefix) } && snapshots.all { it.snapshotId.startsWith(prefix) })
            deleteTestData(prefix)
            observations.forEach { persistObservation(it, nowMs) }
            snapshots.forEach { persistSnapshot(it) }
        }

    private suspend fun compute(tripId: String): TripFinancialSummary {
        val offered = trips.getById(tripId)?.offeredAmount?.let { MoneyAmount.fromLegacyDouble(it, Currencies.DOP) }
        val observations = (money.getObservationsForTrip(tripId) + money.getVoidsForTrip(tripId)).map { it.toDomain() }
        return TripFinancialAggregator.aggregate(tripId, offered, observations)
    }

    private suspend fun rebuild(tripId: String, nowMs: Long): TripFinancialSummary {
        val summary = compute(tripId)
        if (trips.getById(tripId) != null) money.upsertSummary(summary.toEntity(nowMs))
        return summary
    }
}
