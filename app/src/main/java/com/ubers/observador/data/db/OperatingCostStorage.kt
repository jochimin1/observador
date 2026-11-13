package com.ubers.observador.data.db

import androidx.room.*
import com.ubers.observador.domain.operating.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "operating_cost_models", indices = [Index(value = ["effectiveAtMs"], unique = true), Index(value = ["revision"], unique = true)])
data class OperatingCostModelEntity(
    @PrimaryKey val id: String, val revision: Int, val effectiveDay: String, val effectiveAtMs: Long,
    val createdAtMs: Long, val zoneId: String, val workdayHour: Int,
    val fuelPriceMinor: Long, val kmPerGallonMilli: Long, val maintenancePerKmMinor: Long,
    val depreciationPerKmMinor: Long, val fixedMinor: Long, val fixedBasis: String,
    val looseWorkSharePct: Int, val algorithm: String
)

@Dao
interface OperatingCostDao {
    @Query("SELECT * FROM operating_cost_models ORDER BY effectiveAtMs, revision")
    fun observeAll(): Flow<List<OperatingCostModelEntity>>
    @Query("SELECT * FROM operating_cost_models ORDER BY effectiveAtMs, revision")
    suspend fun getAll(): List<OperatingCostModelEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(row: OperatingCostModelEntity)
}

fun OperatingCostModelEntity.toDomain() = OperatingCostModel(id, revision, effectiveDay, effectiveAtMs,
    createdAtMs, zoneId, workdayHour, fuelPriceMinor, kmPerGallonMilli, maintenancePerKmMinor,
    depreciationPerKmMinor, fixedMinor, FixedCostBasis.entries.firstOrNull { it.name == fixedBasis } ?: FixedCostBasis.UNKNOWN,
    looseWorkSharePct, algorithm)

fun OperatingCostModel.toEntity() = OperatingCostModelEntity(id, revision, effectiveDay, effectiveAtMs,
    createdAtMs, zoneId, workdayHour, fuelPriceMinor, kmPerGallonMilli, maintenancePerKmMinor,
    depreciationPerKmMinor, fixedMinor, fixedBasis.name, looseWorkSharePct, algorithm)
