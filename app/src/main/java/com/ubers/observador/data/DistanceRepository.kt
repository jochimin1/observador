package com.ubers.observador.data

import androidx.room.withTransaction
import com.ubers.observador.data.db.AppDatabase
import com.ubers.observador.data.db.toDomain
import com.ubers.observador.data.db.toEntity
import com.ubers.observador.domain.distance.DayDistance
import com.ubers.observador.domain.distance.DistanceSegment
import com.ubers.observador.domain.distance.DistanceUpdate
import com.ubers.observador.domain.distance.TripDistance
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate

/**
 * Fase 8E data access. Only totals reach the database: the domain objects it receives carry no
 * position. Written only by DistanceMonitor's writer; screens read.
 */
class DistanceRepository(private val db: AppDatabase) {

    private val dao = db.distanceDao()
    private val trips = db.tripDao()

    /**
     * Saves stretches and trip kilometres in one transaction. A trip's kilometres are keyed by
     * its trip, found from the lifecycle session; a session with no stored trip is skipped.
     */
    suspend fun save(update: DistanceUpdate) {
        if (update.isEmpty) return
        db.withTransaction {
            update.segments.forEach { dao.upsertSegment(it.toEntity()) }
            update.trips.forEach { t ->
                val trip = trips.getByLifecycleSessionId(t.tripSessionId) ?: return@forEach
                dao.upsertTripDistance(t.toEntity(trip.id))
            }
        }
    }

    suspend fun getOpenSegments(): List<DistanceSegment> = dao.getOpenSegments().map { it.toDomain() }

    fun observeDay(workday: LocalDate): Flow<DayDistance> =
        dao.observeWorkday(workday.toString()).map { rows -> DayDistance.of(workday, rows.map { it.toDomain() }) }

    fun observeRecent(limit: Int): Flow<List<DistanceSegment>> = dao.observeRecent(limit).map { l -> l.map { it.toDomain() } }

    fun observeTripDistance(tripId: String): Flow<TripDistance?> =
        dao.observeTripDistance(tripId).map { it?.toDomain(sessionId = "") }

    /** Debug only: replaces the TEST stretches; real ones are never touched. */
    suspend fun replaceTestSegments(prefix: String, segments: List<DistanceSegment>) {
        require(segments.all { it.id.startsWith(prefix) }) { "test stretches must start with $prefix" }
        dao.replaceSegmentsByIdPrefix(prefix, segments.map { it.toEntity() })
    }

    suspend fun deleteTestSegments(prefix: String): Int = dao.deleteSegmentsByIdPrefix(prefix)
}
