package com.ubers.observador.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Plain reads and writes of trips and their events. The rules live in TripAggregator and the
 * transaction in TripRepository: this DAO decides nothing.
 */
@Dao
interface TripDao {

    // A duplicate session or event id is a bug upstream: ABORT makes it fail loudly and roll back
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTrip(trip: TripEntity)

    @Update
    suspend fun updateTrip(trip: TripEntity): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEvent(event: RideEventEntity)

    @Query("SELECT * FROM trips WHERE id = :id")
    suspend fun getById(id: String): TripEntity?

    @Query("SELECT * FROM trips WHERE lifecycle_session_id = :sessionId")
    suspend fun getByLifecycleSessionId(sessionId: String): TripEntity?

    @Query("SELECT * FROM trips WHERE status IN (:statuses) ORDER BY updated_at DESC")
    suspend fun getByStatuses(statuses: List<String>): List<TripEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM ride_events WHERE event_id = :eventId)")
    suspend fun eventExists(eventId: String): Boolean

    @Query("SELECT COALESCE(MAX(sequence), 0) + 1 FROM ride_events WHERE trip_id = :tripId")
    suspend fun nextSequence(tripId: String): Int

    @Query("SELECT * FROM ride_events WHERE trip_id = :tripId ORDER BY sequence")
    suspend fun getEventsForTrip(tripId: String): List<RideEventEntity>

    @Query("SELECT * FROM ride_events WHERE trip_id = :tripId ORDER BY sequence")
    fun observeEventsForTrip(tripId: String): Flow<List<RideEventEntity>>

    @Query("SELECT COUNT(*) FROM ride_events WHERE trip_id = :tripId")
    suspend fun countEventsForTrip(tripId: String): Int

    @Query("SELECT * FROM trips WHERE id = :id")
    fun observeTrip(id: String): Flow<TripEntity?>

    /** The open trip, if any (newest first in case a bug left more than one). */
    @Query("SELECT * FROM trips WHERE status IN (:openStatuses) ORDER BY updated_at DESC LIMIT 1")
    fun observeActiveTrip(openStatuses: List<String>): Flow<TripEntity?>

    @Query("SELECT * FROM trips ORDER BY created_at DESC LIMIT :limit")
    fun observeRecentTrips(limit: Int): Flow<List<TripEntity>>

    @Query("SELECT * FROM trips WHERE created_at >= :from AND created_at < :to ORDER BY created_at DESC")
    fun observeTripsBetween(from: Long, to: Long): Flow<List<TripEntity>>

    @Query("SELECT trip_id, COUNT(*) AS count FROM ride_events GROUP BY trip_id")
    fun observeEventCounts(): Flow<List<TripEventCount>>

    @Query("SELECT COUNT(*) FROM trips")
    fun observeTripCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM ride_events")
    fun observeEventCount(): Flow<Int>

    @Query("DELETE FROM trips WHERE id = :id")
    suspend fun deleteTrip(id: String): Int

    /** Deletes the trips whose id starts with [prefix]; their events go with them (CASCADE). */
    @Query("DELETE FROM trips WHERE id LIKE :prefix || '%'")
    suspend fun deleteTripsByIdPrefix(prefix: String): Int

    @Query("SELECT * FROM trips")
    suspend fun getAllTrips(): List<TripEntity>

    @Query("SELECT * FROM ride_events")
    suspend fun getAllEvents(): List<RideEventEntity>
}
