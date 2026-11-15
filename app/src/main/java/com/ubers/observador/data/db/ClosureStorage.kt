package com.ubers.observador.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** Append-only; no update, delete or REPLACE operation is exposed. Backups include this table. */
@Entity(tableName = "report_closures", indices = [Index(value = ["scope", "revision"], unique = true)])
data class ClosureEntity(
    @PrimaryKey val id: String,
    val scope: String,
    val revision: Int,
    val createdAt: Long,
    val startMs: Long,
    val endMs: Long,
    val zone: String,
    val startHour: Int,
    val includeTest: Boolean,
    val profitGoal: Long?,
    val timeGoal: Long?,
    val payload: String
)

@Dao
interface ClosureDao {
    @Insert suspend fun insert(value: ClosureEntity)
    @Query("SELECT * FROM report_closures ORDER BY createdAt DESC, revision DESC")
    fun observe(): Flow<List<ClosureEntity>>
    @Query("SELECT MAX(revision) FROM report_closures WHERE scope = :scope")
    suspend fun lastRevision(scope: String): Int?
}
