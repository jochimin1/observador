package com.ubers.observador.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** Fase 8A storage. Metrics happen in the domain, never in SQL. */
@Dao
interface OnlineDao {

    /** A session is one row: its last sighting and its close replace the row (same id). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: OnlineSessionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(sessions: List<OnlineSessionEntity>)

    @Query("SELECT * FROM online_sessions WHERE ended_at IS NULL ORDER BY started_at DESC")
    suspend fun getOpen(): List<OnlineSessionEntity>

    @Query("SELECT * FROM online_sessions WHERE ended_at IS NULL ORDER BY started_at DESC LIMIT 1")
    fun observeOpen(): Flow<OnlineSessionEntity?>

    /** Sessions touching [from, to): started before its end and not closed before its start. */
    @Query(
        "SELECT * FROM online_sessions WHERE started_at < :to AND " +
            "(ended_at IS NULL OR COALESCE(possible_end_at, ended_at) >= :from) ORDER BY started_at"
    )
    fun observeBetween(from: Long, to: Long): Flow<List<OnlineSessionEntity>>

    @Query(
        "SELECT * FROM online_sessions WHERE started_at < :to AND " +
            "(ended_at IS NULL OR COALESCE(possible_end_at, ended_at) >= :from) ORDER BY started_at"
    )
    suspend fun getBetween(from: Long, to: Long): List<OnlineSessionEntity>

    @Query("SELECT * FROM online_sessions ORDER BY started_at DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<OnlineSessionEntity>>

    @Query("SELECT COUNT(*) FROM online_sessions")
    fun observeCount(): Flow<Int>

    @Query("DELETE FROM online_sessions WHERE id LIKE :prefix || '%'")
    suspend fun deleteByIdPrefix(prefix: String): Int

    @Transaction
    suspend fun replaceByIdPrefix(prefix: String, sessions: List<OnlineSessionEntity>) {
        deleteByIdPrefix(prefix)
        upsertAll(sessions)
    }
}
