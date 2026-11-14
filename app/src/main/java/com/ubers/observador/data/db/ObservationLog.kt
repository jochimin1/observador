package com.ubers.observador.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/** Append-only local evidence. Included by full database backups and exports. */
@Entity(tableName = "observation_log", indices = [Index(value = ["kind", "atMs"])])
data class ObservationLog(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val kind: String,
    val atMs: Long = System.currentTimeMillis(),
    val payload: String
)

@Dao
interface ObservationLogDao {
    @Insert suspend fun insert(row: ObservationLog)
    @Query("SELECT * FROM observation_log WHERE kind = :kind ORDER BY atMs DESC LIMIT 1")
    fun latest(kind: String): Flow<ObservationLog?>
}
