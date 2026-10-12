package com.ubers.observador.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Plain reads and writes of money. The rules (totals, deltas, links) live in the domain. */
@Dao
interface FinancialDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertObservation(observation: FinancialObservationEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM financial_observations WHERE observation_id = :id)")
    suspend fun observationExists(id: String): Boolean

    @Query("SELECT * FROM financial_observations WHERE observation_id = :id")
    suspend fun getObservation(id: String): FinancialObservationEntity?

    @Query("SELECT * FROM financial_observations WHERE trip_id = :tripId ORDER BY observed_at, observation_id")
    suspend fun getObservationsForTrip(tripId: String): List<FinancialObservationEntity>

    /** VOID rows cancelling one of the trip's observations (a void itself may be unassigned). */
    @Query(
        "SELECT * FROM financial_observations WHERE voids_observation_id IN " +
            "(SELECT observation_id FROM financial_observations WHERE trip_id = :tripId)"
    )
    suspend fun getVoidsForTrip(tripId: String): List<FinancialObservationEntity>

    @Query("SELECT * FROM financial_observations WHERE trip_id IS NULL AND observed_at >= :from AND observed_at < :to ORDER BY observed_at")
    suspend fun getUnassignedBetween(from: Long, to: Long): List<FinancialObservationEntity>

    @Query("SELECT * FROM financial_observations WHERE trip_id IS NULL ORDER BY observed_at DESC")
    fun observeUnassigned(): Flow<List<FinancialObservationEntity>>

    @Query("SELECT * FROM financial_observations ORDER BY observed_at DESC LIMIT :limit")
    fun observeRecentObservations(limit: Int): Flow<List<FinancialObservationEntity>>

    @Query("SELECT DISTINCT trip_id FROM financial_observations WHERE trip_id IS NOT NULL")
    suspend fun getTripIdsWithObservations(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSummary(summary: TripFinancialSummaryEntity)

    @Query("SELECT * FROM trip_financial_summaries WHERE trip_id = :tripId")
    suspend fun getSummary(tripId: String): TripFinancialSummaryEntity?

    @Query("SELECT * FROM trip_financial_summaries WHERE trip_id = :tripId")
    fun observeSummary(tripId: String): Flow<TripFinancialSummaryEntity?>

    @Query("SELECT * FROM trip_financial_summaries WHERE trip_id IN (:tripIds)")
    suspend fun getSummaries(tripIds: List<String>): List<TripFinancialSummaryEntity>

    @Query("DELETE FROM trip_financial_summaries WHERE trip_id = :tripId")
    suspend fun deleteSummary(tripId: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSnapshot(snapshot: PlatformEarningsSnapshotEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM platform_earnings_snapshots WHERE snapshot_id = :id)")
    suspend fun snapshotExists(id: String): Boolean

    @Query("SELECT * FROM platform_earnings_snapshots WHERE period_type = :periodType ORDER BY captured_at DESC LIMIT 1")
    fun observeLatestSnapshot(periodType: String): Flow<PlatformEarningsSnapshotEntity?>

    @Query("SELECT * FROM platform_earnings_snapshots ORDER BY captured_at DESC LIMIT 1")
    suspend fun getLatestSnapshot(): PlatformEarningsSnapshotEntity?

    // Debug TEST data only
    @Query("DELETE FROM financial_observations WHERE observation_id LIKE :prefix || '%'")
    suspend fun deleteObservationsByIdPrefix(prefix: String): Int

    @Query("DELETE FROM trip_financial_summaries WHERE trip_id LIKE :prefix || '%'")
    suspend fun deleteSummariesByTripPrefix(prefix: String): Int

    @Query("DELETE FROM platform_earnings_snapshots WHERE snapshot_id LIKE :prefix || '%'")
    suspend fun deleteSnapshotsByIdPrefix(prefix: String): Int

    @Query("SELECT COUNT(*) FROM financial_observations")
    fun observeObservationCount(): Flow<Int>
}
