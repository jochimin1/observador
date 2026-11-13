package com.ubers.observador.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** Codes and versions only. Raw node text, names, addresses and coordinates never enter this table. */
@Entity(tableName = "reading_audits", indices = [Index(value = ["observedAt"])])
data class ReadingAuditEntity(
    @PrimaryKey val id: String,
    val observedAt: Long,
    val parserVersion: String,
    val classifierVersion: String,
    val uberVersion: String?,
    val screenType: String,
    val confidence: Double,
    val provisional: Boolean,
    val reasonCodes: String,
    val acceptedCards: Int,
    val rejectedCards: Int,
    val lifecycleState: String? = null,
    val lifecycleSessionId: String? = null,
    val eventCodes: String = "",
    val financialType: String? = null,
    val financialConfidence: Double? = null
)

@Dao
interface ReadingAuditDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(row: ReadingAuditEntity)

    @Query("DELETE FROM reading_audits WHERE id NOT IN (SELECT id FROM reading_audits ORDER BY observedAt DESC, id DESC LIMIT :limit)")
    suspend fun trim(limit: Int)

    @Transaction
    suspend fun record(row: ReadingAuditEntity) { insert(row); trim(10_000) }

    @Query("UPDATE reading_audits SET lifecycleState = :state, lifecycleSessionId = :session, eventCodes = :events WHERE id = :id")
    suspend fun lifecycle(id: String, state: String, session: String?, events: String)

    @Query("UPDATE reading_audits SET financialType = :type, financialConfidence = :confidence WHERE id = :id")
    suspend fun financial(id: String, type: String, confidence: Double)

    @Query("SELECT * FROM reading_audits ORDER BY observedAt DESC, id DESC LIMIT 1")
    fun observeLatest(): Flow<ReadingAuditEntity?>

    @Query("SELECT * FROM reading_audits WHERE id = :id")
    suspend fun get(id: String): ReadingAuditEntity?

    @Query("SELECT * FROM reading_audits ORDER BY observedAt DESC, id DESC LIMIT :limit")
    suspend fun latest(limit: Int): List<ReadingAuditEntity>
}
