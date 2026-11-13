package com.ubers.observador.data

import com.ubers.observador.domain.backup.MaintenanceGate
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
import com.ubers.observador.domain.WorkdayClock
import com.ubers.observador.domain.earnings.AssignCheck
import com.ubers.observador.domain.earnings.AssignRejection
import com.ubers.observador.domain.earnings.ManualAssignment
import com.ubers.observador.domain.finance.FinancialObservationType
import com.ubers.observador.domain.finance.FinancialSource
import com.ubers.observador.domain.trips.Trip
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The only way money is written (Fase 7C). An observation and its trip's rebuilt summary are
 * written in one Room transaction, so the summary can never disagree with the ledger; and the
 * summary can always be rebuilt from the ledger alone ([rebuildTripFinancialSummary]).
 */
/** Inputs of one Ganancias period (Fase 8C). */
data class EarningsInputs(
    val completedTrips: List<Trip>,
    val summaries: Map<String, TripFinancialSummary>,
    /** Unassigned amounts seen in the period plus every void that cancels them. */
    val unassigned: List<FinancialObservation>
)

class FinancialRepository(private val db: AppDatabase) {

    private val money = db.financialDao()
    private val trips = db.tripDao()

    /** A batch and its rebuilt summary commit together. Retries of the same command do not add money twice. */
    suspend fun recordManualMoney(tripId: String, request: com.ubers.observador.domain.finance.ManualMoneyRequest, nowMs: Long): com.ubers.observador.domain.finance.MoneyEditResult = MaintenanceGate.shared.access {
        db.withTransaction {
            val trip = trips.getById(tripId)?.toDomain()
                ?: return@withTransaction com.ubers.observador.domain.finance.MoneyEditResult.Rejected("El viaje ya no existe.")
            val policy = com.ubers.observador.domain.finance.ManualMoney
            policy.validate(trip, request)?.let { return@withTransaction com.ubers.observador.domain.finance.MoneyEditResult.Rejected(it) }
            val valueId = policy.id(tripId, request.commandId, "value")
            val history = tripObservations(tripId).distinctBy { it.observationId }
            history.firstOrNull { it.observationId == valueId }?.let { existing ->
                val expected = policy.rows(trip, request, null, existing.observedAtMs).last()
                return@withTransaction if (existing.type == expected.type && existing.semantics == expected.semantics && existing.amount == expected.amount)
                    com.ubers.observador.domain.finance.MoneyEditResult.Saved(request.commandId)
                else com.ubers.observador.domain.finance.MoneyEditResult.Rejected("Ese identificador ya corresponde a otro cambio.")
            }
            val live = policy.live(history)
            val original = request.replacesId?.let { id -> live.firstOrNull { it.observationId == id } }
                ?: if (request.replacesId == null && request.type == FinancialObservationType.TRIP_TOTAL) {
                    live.filter { it.semantics == com.ubers.observador.domain.finance.AmountSemantics.ABSOLUTE_TOTAL }
                        .maxWithOrNull(compareBy<FinancialObservation> { it.source == FinancialSource.MANUAL && policy.ENTRY in it.evidence }.thenBy { it.observedAtMs })
                } else null
            if (request.replacesId != null && original == null) return@withTransaction com.ubers.observador.domain.finance.MoneyEditResult.Rejected("Ese importe cambió. Vuelve a abrir su ficha.")
            if (original != null && original.type != request.type) return@withTransaction com.ubers.observador.domain.finance.MoneyEditResult.Rejected("Corrige el importe conservando su tipo.")
            if (original != null && original.amount.currencyCode != Currencies.DOP) return@withTransaction com.ubers.observador.domain.finance.MoneyEditResult.Rejected("Esta edición admite RD$. No se puede cambiar la moneda del importe original.")
            val at = maxOf(nowMs, (history.maxOfOrNull { it.observedAtMs } ?: 0L) + 1)
            policy.rows(trip, request, original, at).forEach { row -> money.insertObservation(row.toEntity(nowMs)) }
            rebuild(tripId, nowMs)
            com.ubers.observador.domain.finance.MoneyEditResult.Saved(request.commandId)
        }
    }

    suspend fun voidManualMoney(tripId: String, observationId: String, commandId: String, nowMs: Long): Boolean = MaintenanceGate.shared.access {
        db.withTransaction {
            if (trips.getById(tripId)?.toDomain()?.status?.isOpen != false) return@withTransaction false
            val policy = com.ubers.observador.domain.finance.ManualMoney
            val history = tripObservations(tripId).distinctBy { it.observationId }
            val target = policy.live(history).firstOrNull { it.observationId == observationId } ?: return@withTransaction false
            if (money.observationExists(policy.id(tripId, commandId, "void"))) return@withTransaction false
            val row = policy.void(target, commandId, maxOf(nowMs, target.observedAtMs + 1),
                listOf(policy.ACTION, policy.COMMAND + commandId, policy.REPLACES + observationId))
            money.insertObservation(row.toEntity(nowMs))
            rebuild(tripId, nowMs)
            true
        }
    }

    suspend fun undoManualMoney(tripId: String, commandId: String, undoId: String, nowMs: Long): Boolean = MaintenanceGate.shared.access {
        db.withTransaction {
            val trip = trips.getById(tripId)?.toDomain() ?: return@withTransaction false
            if (trip.status.isOpen) return@withTransaction false
            val history = tripObservations(tripId).distinctBy { it.observationId }
            val policy = com.ubers.observador.domain.finance.ManualMoney
            val rows = policy.undoRows(trip, commandId, history, undoId, nowMs) ?: return@withTransaction false
            if (rows.isEmpty() || rows.any { money.observationExists(it.observationId) }) return@withTransaction false
            rows.forEach { money.insertObservation(it.toEntity(nowMs)) }
            rebuild(tripId, nowMs)
            true
        }
    }

    suspend fun persistObservation(input: FinancialObservation, nowMs: Long): FinancialPersistResult = MaintenanceGate.shared.access {
        db.withTransaction {
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
    }

    /** False when the same logical snapshot was already stored. */
    suspend fun persistSnapshot(snapshot: PlatformEarningsSnapshot): Boolean = MaintenanceGate.shared.access {
        db.withTransaction {
            if (money.snapshotExists(snapshot.snapshotId)) {
                false
            } else {
                money.insertSnapshot(snapshot.toEntity())
                true
            }
        }
    }

    /** Recomputes and stores a trip's summary from its observations only. */
    suspend fun rebuildTripFinancialSummary(tripId: String, nowMs: Long): TripFinancialSummary = MaintenanceGate.shared.access {
        db.withTransaction {
            rebuild(tripId, nowMs)
        }
    }

    /** Rebuilds every summary (debug, and recovery after any doubt). Returns how many. */
    suspend fun rebuildAll(nowMs: Long): Int = MaintenanceGate.shared.access {
        db.withTransaction {
            val ids = money.getTripIdsWithObservations()
            ids.forEach { rebuild(it, nowMs) }
            ids.size
        }
    }

    /** Stored summaries that disagree with a fresh rebuild (total or completeness). Should always be 0. */
    suspend fun driftedSummaries(): Int = MaintenanceGate.shared.access {
        db.withTransaction {
            money.getTripIdsWithObservations().count { id ->
                val fresh = compute(id)
                val stored = money.getSummary(id)?.toDomain(Currencies.DOP)
                trips.getById(id) != null &&
                    (stored?.effectiveEarnings != fresh.effectiveEarnings || stored?.completeness != fresh.completeness)
            }
        }
    }

    /** The summary as computed from the ledger right now, without storing it (for checks). */
    suspend fun computeSummary(tripId: String): TripFinancialSummary = MaintenanceGate.shared.access {
        db.withTransaction { compute(tripId) }
    }

    suspend fun getStoredSummary(tripId: String): TripFinancialSummary? = MaintenanceGate.shared.access {
        money.getSummary(tripId)?.toDomain(Currencies.DOP)
    }

    fun observeSummary(tripId: String): Flow<TripFinancialSummary?> =
        money.observeSummary(tripId).map { it?.toDomain(Currencies.DOP) }

    fun observeUnassigned(): Flow<List<FinancialObservation>> = money.observeUnassigned().map { l -> l.map { it.toDomain() } }

    fun observeRecentObservations(limit: Int): Flow<List<FinancialObservation>> =
        money.observeRecentObservations(limit).map { l -> l.map { it.toDomain() } }

    fun observeLatestPlatformSnapshot(period: PlatformPeriodType): Flow<PlatformEarningsSnapshot?> =
        money.observeLatestSnapshot(period.storageKey).map { it?.toDomain() }

    suspend fun getLatestPlatformSnapshot(): PlatformEarningsSnapshot? = MaintenanceGate.shared.access {
        money.getLatestSnapshot()?.toDomain()
    }

    /** Real money of trips completed in [from, to) plus unassigned amounts seen then. Offers never count. */
    suspend fun periodEarnings(from: Long, to: Long, currencyCode: String = Currencies.DOP): EarningsPeriodSummary = MaintenanceGate.shared.access {
        db.withTransaction {
                val candidates = trips.getTripsForIncomeBetween(from, to).map { it.toDomain() }
                val ids = candidates.map { it.id }
                val stored = if (ids.isEmpty()) emptyMap() else money.getSummaries(ids).associateBy { it.tripId }
                val summaries = candidates.filter { com.ubers.observador.domain.earnings.TripIncome.earnedAt(it, stored[it.id]?.toDomain(currencyCode)) != null }.map { trip ->
                    val id = trip.id
                    stored[id]?.toDomain(currencyCode) ?: TripFinancialSummary(
                        tripId = id, currencyCode = null, offeredAmount = null, latestAbsoluteTotal = null,
                        effectiveEarnings = null, completeness = FinancialCompleteness.UNKNOWN
                    )
                }
                EarningsPeriods.summarize(currencyCode, summaries, unassignedWithVoids(from, to),
                    candidates.filter { it.status == com.ubers.observador.domain.trips.TripStatus.CANCELLED }.map { it.id }.toSet())
            }
    }

    // ---------------------------------------------------------------- Fase 8C: Ganancias

    /** What Ganancias needs for [from, to), read in one transaction so it is consistent. */
    suspend fun earningsInputs(from: Long, to: Long, currencyCode: String = Currencies.DOP): EarningsInputs = MaintenanceGate.shared.access {
        db.withTransaction {
            val completed = trips.getTripsForIncomeBetween(from, to).map { it.toDomain() }
            val ids = completed.map { it.id }
            val summaries = if (ids.isEmpty()) emptyMap() else money.getSummaries(ids).associate { it.tripId to it.toDomain(currencyCode) }
            EarningsInputs(completed, summaries, unassignedWithVoids(from, to))
        }
    }

    /** Stored summaries of [tripIds] (Fase 8D exports), read in chunks under SQLite's variable limit. */
    suspend fun summariesFor(tripIds: List<String>, currencyCode: String = Currencies.DOP): Map<String, TripFinancialSummary> = MaintenanceGate.shared.access {
        db.withTransaction {
                tripIds.distinct().chunked(500).flatMap { ids -> money.getSummaries(ids) }.associate { it.tripId to it.toDomain(currencyCode) }
            }
    }

    fun observeManual(): Flow<List<FinancialObservation>> = money.observeManual().map { l -> l.map { it.toDomain() } }

    /** A trip's own observations and the voids that cancel them (its money history). */
    suspend fun tripObservations(tripId: String): List<FinancialObservation> = MaintenanceGate.shared.access {
        db.withTransaction {
            (money.getObservationsForTrip(tripId) + money.getVoidsForTrip(tripId)).distinctBy { it.observationId }.map { it.toDomain() }
        }
    }

    /** Completed trips the amount may belong to: its working day and the one before, closest first. */
    suspend fun assignmentCandidates(observationId: String, clock: WorkdayClock): List<Pair<Trip, TripFinancialSummary?>> = MaintenanceGate.shared.access {
        db.withTransaction {
            val o = money.getObservation(observationId)?.toDomain() ?: return@withTransaction emptyList()
            val day = clock.workdayOf(o.observedAtMs)
            val pool = trips.getTripsEndedBetween(clock.startOf(day.minusDays(1)), clock.startOf(day.plusDays(1))).map { it.toDomain() }
            val candidates = ManualAssignment.candidates(o, pool, clock)
            val summaries = if (candidates.isEmpty()) emptyMap() else money.getSummaries(candidates.map { it.id }).associateBy { it.tripId }
            candidates.map { it to summaries[it.id]?.toDomain(Currencies.DOP) }
        }
    }

    /** The checks and the preview of the confirmation dialog, without writing anything. */
    suspend fun previewAssignment(observationId: String, tripId: String): AssignCheck = MaintenanceGate.shared.access {
        db.withTransaction { checkAssignment(observationId, tripId) }
    }

    /**
     * Puts an unassigned amount on a trip: the VOID of the original and the linked copy are
     * written in one transaction, then the trip's summary is rebuilt. Checked again inside the
     * transaction, so a double tap or a stale screen cannot assign twice.
     */
    suspend fun assignToTrip(observationId: String, tripId: String, nowMs: Long): AssignCheck = MaintenanceGate.shared.access {
        db.withTransaction {
            val check = checkAssignment(observationId, tripId)
            if (check is AssignCheck.Ok) {
                val original = requireNotNull(money.getObservation(observationId)).toDomain()
                ManualAssignment.assign(original, tripId, nowMs).forEach { persistObservation(it, nowMs) }
            }
            check
        }
    }

    /** Voids a manual copy and writes the amount again without a trip. False if it was already undone. */
    suspend fun undoAssignment(copyId: String, nowMs: Long): Boolean = MaintenanceGate.shared.access {
        db.withTransaction {
            val copy = money.getObservation(copyId)?.toDomain()
            if (copy == null || copy.tripId == null || copy.source != FinancialSource.MANUAL || money.isVoided(copyId)) {
                false
            } else {
                val original = ManualAssignment.originalIdOf(copy)?.let { money.getObservation(it)?.toDomain() }
                ManualAssignment.undo(copy, original, nowMs).forEach { persistObservation(it, nowMs) }
                true
            }
        }
    }

    /** "No es mío / mal leído": only a VOID. False if the amount is gone or not unassigned. */
    suspend fun rejectObservation(observationId: String, nowMs: Long): Boolean = MaintenanceGate.shared.access {
        db.withTransaction {
            val o = money.getObservation(observationId)?.toDomain()
            if (o == null || o.tripId != null || o.type == FinancialObservationType.VOID || money.isVoided(observationId)) {
                false
            } else {
                persistObservation(ManualAssignment.reject(o, nowMs), nowMs)
                true
            }
        }
    }

    private suspend fun checkAssignment(observationId: String, tripId: String): AssignCheck {
        val o = money.getObservation(observationId)?.toDomain() ?: return AssignCheck.Rejected(AssignRejection.NOT_PENDING)
        val trip = trips.getById(tripId)?.toDomain() ?: return AssignCheck.Rejected(AssignRejection.TRIP_NOT_COMPLETED)
        val offered = trip.offer?.offeredAmount?.let { MoneyAmount.fromLegacyDouble(it, Currencies.DOP) }
        val history = (money.getObservationsForTrip(tripId) + money.getVoidsForTrip(tripId)).map { it.toDomain() }
        return ManualAssignment.check(o, money.isVoided(observationId), trip, offered, history)
    }

    private suspend fun unassignedWithVoids(from: Long, to: Long): List<FinancialObservation> =
        (money.getUnassignedBetween(from, to) + money.getVoidsForUnassignedBetween(from, to))
            .distinctBy { it.observationId }.map { it.toDomain() }

    /** Debug only: removes TEST money (ids starting with [prefix]); real money is never touched. */
    suspend fun deleteTestData(prefix: String) = MaintenanceGate.shared.access {
        db.withTransaction {
            money.deleteObservationsByIdPrefix(prefix)
            money.deleteSummariesByTripPrefix(prefix)
            money.deleteSnapshotsByIdPrefix(prefix)
        }
    }

    /** Debug only: loads TEST observations and snapshots through the same write path as real ones. */
    suspend fun loadTestData(prefix: String, observations: List<FinancialObservation>, snapshots: List<PlatformEarningsSnapshot>, nowMs: Long) = MaintenanceGate.shared.access {
        db.withTransaction {
                require(observations.all { it.observationId.startsWith(prefix) } && snapshots.all { it.snapshotId.startsWith(prefix) })
                deleteTestData(prefix)
                observations.forEach { persistObservation(it, nowMs) }
                snapshots.forEach { persistSnapshot(it) }
            }
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
