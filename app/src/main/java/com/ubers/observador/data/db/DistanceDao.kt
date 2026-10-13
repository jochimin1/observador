package com.ubers.observador.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** Fase 8E storage. Sums happen in the domain (DayDistance), never in SQL. */
@Dao
interface DistanceDao {

    /** A stretch is one row: its checkpoints and its close replace the row (same id). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSegment(segment: DistanceSegmentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSegments(segments: List<DistanceSegmentEntity>)

    @Query("SELECT * FROM distance_segments WHERE ended_at IS NULL")
    suspend fun getOpenSegments(): List<DistanceSegmentEntity>

    @Query("SELECT * FROM distance_segments WHERE workday = :workday ORDER BY started_at")
    fun observeWorkday(workday: String): Flow<List<DistanceSegmentEntity>>

    @Query("SELECT * FROM distance_segments WHERE started_at >= :from ORDER BY started_at")
    fun observeSince(from: Long): Flow<List<DistanceSegmentEntity>>

    @Query("SELECT * FROM distance_segments ORDER BY started_at")
    suspend fun getAllSegments(): List<DistanceSegmentEntity>

    @Query("SELECT * FROM distance_segments ORDER BY started_at")
    fun observeAllSegments(): Flow<List<DistanceSegmentEntity>>

    @Query("SELECT * FROM distance_segments ORDER BY started_at DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<DistanceSegmentEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTripDistance(distance: TripDistanceEntity)

    @Query("SELECT * FROM trip_distances WHERE trip_id = :tripId")
    fun observeTripDistance(tripId: String): Flow<TripDistanceEntity?>

    @Query("SELECT * FROM trip_distances WHERE trip_id IN (:tripIds)")
    suspend fun getTripDistances(tripIds: List<String>): List<TripDistanceEntity>

    @Query("DELETE FROM distance_segments WHERE id LIKE :prefix || '%'")
    suspend fun deleteSegmentsByIdPrefix(prefix: String): Int

    @Transaction
    suspend fun replaceSegmentsByIdPrefix(prefix: String, segments: List<DistanceSegmentEntity>) {
        deleteSegmentsByIdPrefix(prefix)
        upsertSegments(segments)
    }
}
