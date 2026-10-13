package com.ubers.observador.data

import androidx.room.withTransaction
import com.ubers.observador.data.db.AppDatabase
import com.ubers.observador.data.db.toDomain
import com.ubers.observador.data.db.toEntity
import com.ubers.observador.domain.finance.TripFinancialSummary
import com.ubers.observador.domain.money.Currencies
import com.ubers.observador.domain.online.OnlineMetrics
import com.ubers.observador.domain.online.OnlinePolicy
import com.ubers.observador.domain.online.OnlineSession
import com.ubers.observador.domain.online.OnlineSummary
import com.ubers.observador.domain.online.TimeSpan
import com.ubers.observador.domain.trips.Trip
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** What the online metrics read besides the sessions, loaded in three batched queries. */
data class OnlineMetricsInputs(
    val trips: List<Trip>,
    val offerTimes: List<Long>,
    val money: Map<String, TripFinancialSummary>
)

/**
 * Fase 8A data access. Sessions are written only by the single writer (OnlineSessionCoordinator);
 * screens read them. It never changes trips, money or offers.
 */
class OnlineRepository(private val db: AppDatabase) {

    private val dao = db.onlineDao()
    private val trips = db.tripDao()
    private val offers = db.offerDao()
    private val money = db.financialDao()

    /** Saves whole rows in order, in one transaction (a close and a new start land together). */
    suspend fun save(sessions: List<OnlineSession>) {
        if (sessions.isEmpty()) return
        db.withTransaction { sessions.forEach { dao.upsert(it.toEntity()) } }
    }

    suspend fun getOpen(): List<OnlineSession> = dao.getOpen().map { it.toDomain() }

    fun observeOpen(): Flow<OnlineSession?> = dao.observeOpen().map { it?.toDomain() }

    fun observeBetween(from: Long, to: Long): Flow<List<OnlineSession>> =
        dao.observeBetween(from, to).map { list -> list.map { it.toDomain() } }

    suspend fun getBetween(from: Long, to: Long): List<OnlineSession> = dao.getBetween(from, to).map { it.toDomain() }

    fun observeRecent(limit: Int): Flow<List<OnlineSession>> = dao.observeRecent(limit).map { list -> list.map { it.toDomain() } }

    fun observeCount(): Flow<Int> = dao.observeCount()

    suspend fun metricsInputs(from: Long, to: Long, currencyCode: String = Currencies.DOP): OnlineMetricsInputs =
        db.withTransaction {
            val touching = trips.getTripsTouching(from, to).map { it.toDomain() }
            val completedIds = touching.filter { it.completedAt != null }.map { it.id }
            val summaries = if (completedIds.isEmpty()) emptyMap() else {
                money.getSummaries(completedIds).associate { it.tripId to it.toDomain(currencyCode) }
            }
            OnlineMetricsInputs(touching, offers.getDetectedTimesBetween(from, to), summaries)
        }

    /** The metrics of [from, to) at [nowMs], from the given sessions. */
    suspend fun summarize(sessions: List<OnlineSession>, from: Long, to: Long, nowMs: Long, policy: OnlinePolicy = OnlinePolicy()): OnlineSummary {
        if (sessions.isEmpty()) return OnlineSummary()
        val inputs = metricsInputs(from, to)
        return OnlineMetrics.summarize(TimeSpan(from, to), sessions, inputs.trips, inputs.offerTimes, inputs.money, nowMs, policy)
    }

    /** Debug only: replaces the TEST sessions (ids starting with [prefix]); real ones are never touched. */
    suspend fun replaceTestSessions(prefix: String, sessions: List<OnlineSession>) {
        require(sessions.all { it.id.startsWith(prefix) }) { "test sessions must start with $prefix" }
        dao.replaceByIdPrefix(prefix, sessions.map { it.toEntity() })
    }

    suspend fun deleteTestSessions(prefix: String): Int = dao.deleteByIdPrefix(prefix)
}
