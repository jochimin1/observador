package com.ubers.observador.data.db

import androidx.room.*
import com.ubers.observador.domain.expenses.WorkShiftLink
import kotlinx.coroutines.flow.Flow

/** 1.46.0: a work shift links its start and end odometer readings (rows of `odometer_readings`). */
@Entity(tableName = "work_shifts", indices = [Index(value = ["start_reading_id"], unique = true), Index(value = ["end_reading_id"], unique = true)])
data class WorkShiftEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "vehicle_id") val vehicleId: String,
    @ColumnInfo(name = "start_reading_id") val startReadingId: String,
    @ColumnInfo(name = "end_reading_id") val endReadingId: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)

@Dao
interface WorkShiftDao {
    @Query("SELECT * FROM work_shifts ORDER BY created_at, id") fun observeAll(): Flow<List<WorkShiftEntity>>
    @Query("SELECT * FROM work_shifts ORDER BY created_at, id") suspend fun all(): List<WorkShiftEntity>
    @Query("SELECT * FROM work_shifts WHERE end_reading_id IS NULL LIMIT 1") suspend fun open(): WorkShiftEntity?
    @Query("SELECT COUNT(*) FROM work_shifts WHERE start_reading_id = :readingId OR end_reading_id = :readingId") suspend fun usingReading(readingId: String): Int
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(shift: WorkShiftEntity)
    @Query("DELETE FROM work_shifts WHERE id = :id") suspend fun delete(id: String)
}

fun WorkShiftEntity.toDomain() = WorkShiftLink(id, vehicleId, startReadingId, endReadingId, createdAt, updatedAt)
fun WorkShiftLink.toEntity() = WorkShiftEntity(id, vehicleId, startReadingId, endReadingId, createdAt, updatedAt)
