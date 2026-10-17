package com.ubers.observador.data

import androidx.room.withTransaction
import com.ubers.observador.data.db.AppDatabase
import com.ubers.observador.data.db.toDomain
import com.ubers.observador.data.db.toEntity
import com.ubers.observador.data.db.toRunEntity
import com.ubers.observador.domain.ProfitModelVersion
import com.ubers.observador.domain.ProfitSettingsSnapshot
import com.ubers.observador.domain.intelligence.CandidateComparisonReport
import com.ubers.observador.domain.intelligence.IntelligenceDataset
import com.ubers.observador.domain.intelligence.IntelligenceDatasetBuilder
import com.ubers.observador.domain.intelligence.ModelVersionRules
import com.ubers.observador.domain.intelligence.ShadowEvaluation
import com.ubers.observador.domain.money.Currencies
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * Fase 7D data access. Loads one period in a few batched queries (no query per trip), hands
 * plain domain objects to the pure analysis, and stores model versions, shadow scores and
 * calibration runs. It never changes trips, money or offers.
 */
class IntelligenceRepository(private val db: AppDatabase) {

    private val dao = db.intelligenceDao()
    private val trips = db.tripDao()
    private val money = db.financialDao()

    /**
     * Trips done in [from, to) with their money, as samples. Offers are never income. Since
     * 1.25.0, with [includeUnconfirmed] also the trips closed by «Buscando» (ended without the
     * summary), each with the last point of its route (8K) and its GPS km when fully measured (8E).
     */
    suspend fun loadDataset(
        from: Long,
        to: Long,
        currencyCode: String = Currencies.DOP,
        includeUnconfirmed: Boolean = true
    ): IntelligenceDataset = db.withTransaction {
        val done = (if (includeUnconfirmed) trips.getTripsEndedBetween(from, to) else trips.getTripsCompletedBetween(from, to))
            .map { it.toDomain() }
        val ids = done.map { it.id }
        val summaries = ids.chunked(SQLITE_BATCH).flatMap { money.getSummaries(it) }
            .associate { it.tripId to it.toDomain(currencyCode) }
        val routeEnds = routeEnds(ids)
        val realKm = ids.chunked(SQLITE_BATCH).flatMap { db.distanceDao().getTripDistances(it) }
            .filter { it.coverage == com.ubers.observador.domain.distance.TripDistanceCoverage.FULL.storageKey }
            .associate { it.tripId to it.pickupMm + it.tripMm }
        IntelligenceDatasetBuilder.build(done, summaries, currencyCode, includeUnconfirmed, routeEnds, realKm)
    }

    /** The last point of each route of [tripIds] (8K): the best end of a trip closed by «Buscando». */
    suspend fun routeEnds(tripIds: List<String>): Map<String, Long> =
        tripIds.chunked(SQLITE_BATCH).flatMap { db.routeDao().getRoutes(it) }
            .filter { it.status != "OPEN" }
            .mapNotNull { r -> r.endAt?.let { r.tripId to it } }
            .toMap()

    suspend fun acceptedOfferHashesSince(from: Long): Set<String> = trips.getAcceptedOfferHashesSince(from).toSet()

    suspend fun offersSince(from: Long) = db.offerDao().getSince(from)

    suspend fun versions(): List<ProfitModelVersion> = dao.getVersions().map { it.toDomain() }

    fun observeVersions(): Flow<List<ProfitModelVersion>> = dao.observeVersions().map { l -> l.map { it.toDomain() } }

    suspend fun version(id: String): ProfitModelVersion? = dao.getVersion(id)?.toDomain()

    /**
     * The settings in use become a version if they are not one yet (first run, or edited in
     * Ajustes). Returns the active version.
     */
    suspend fun ensureCurrent(inUse: ProfitSettingsSnapshot, nowMs: Long): ProfitModelVersion = db.withTransaction {
        val versions = versions()
        ModelVersionRules.ensureCurrent(versions, inUse, nowMs, ::newId)?.also { dao.insertVersion(it.toEntity()) }
            ?: requireNotNull(ModelVersionRules.active(versions))
    }

    /** Stores a calibration and its candidate together. The active model is not touched. */
    suspend fun recordCandidate(report: CandidateComparisonReport, periodStart: Long?, totalTrips: Int, nowMs: Long, runIdPrefix: String = ""): ProfitModelVersion? =
        db.withTransaction {
            val runId = runIdPrefix + "run-" + newId()
            dao.insertRun(report.toRunEntity(runId, nowMs, periodStart, totalTrips))
            report.candidate?.let { candidate ->
                ModelVersionRules.recordCandidate(versions(), candidate, runId).also { dao.insertVersion(it.toEntity()) }
            }
        }

    suspend fun insertVersion(version: ProfitModelVersion) = dao.insertVersion(version.toEntity())

    /** False when this offer was already scored for this candidate. */
    suspend fun saveShadow(evaluation: ShadowEvaluation): Boolean = dao.insertShadow(evaluation.toEntity()) != -1L

    suspend fun shadowEvaluations(candidateId: String): List<ShadowEvaluation> = dao.getShadow(candidateId).mapNotNull { it.toDomain() }

    fun observeShadowCount(): Flow<Int> = dao.observeShadowCount()

    fun observeRuns(limit: Int) = dao.observeRuns(limit)

    /** Debug only: removes TEST versions, shadow scores and runs. */
    suspend fun deleteTestData(prefix: String) = db.withTransaction {
        dao.deleteShadowByIdPrefix(prefix)
        dao.deleteRunsByIdPrefix(prefix)
        dao.deleteVersionsByIdPrefix(prefix)
    }

    suspend fun saveShadowBatch(evaluations: List<ShadowEvaluation>) = db.withTransaction {
        evaluations.forEach { dao.insertShadow(it.toEntity()) }
    }

    private fun newId() = UUID.randomUUID().toString()

    private companion object {
        /** SQLite allows 999 parameters per statement on older Android versions. */
        const val SQLITE_BATCH = 900
    }
}
