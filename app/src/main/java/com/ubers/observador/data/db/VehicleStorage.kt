package com.ubers.observador.data.db

import androidx.room.*
import com.ubers.observador.domain.expenses.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "vehicle_profiles")
data class VehicleEntity(@PrimaryKey val id: String, val name: String, val workSharePct: Int,
    val startedAt: Long, val retiredAt: Long?, val updatedAt: Long)

@Entity(tableName = "component_plans", primaryKeys = ["vehicleId", "type"])
data class ComponentPlanEntity(val vehicleId: String, val type: String, val intervalKm: Long, val intervalDays: Int)

@Dao
interface VehicleDao {
    @Query("SELECT * FROM vehicle_profiles ORDER BY startedAt, id") fun observeVehicles(): Flow<List<VehicleEntity>>
    @Query("SELECT * FROM component_plans ORDER BY vehicleId, type") fun observePlans(): Flow<List<ComponentPlanEntity>>
    @Query("SELECT * FROM vehicle_profiles ORDER BY startedAt, id") suspend fun vehicles(): List<VehicleEntity>
    @Query("SELECT * FROM component_plans ORDER BY vehicleId, type") suspend fun plans(): List<ComponentPlanEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(vehicle: VehicleEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(plan: ComponentPlanEntity)
}

fun VehicleEntity.toDomain() = VehicleProfile(id, name, workSharePct, startedAt, retiredAt, updatedAt)
fun VehicleProfile.toEntity() = VehicleEntity(id, name, workSharePct, startedAt, retiredAt, updatedAt)
fun ComponentPlanEntity.toDomain() = ComponentPlan(vehicleId, ExpenseType.fromStorage(type), intervalKm, intervalDays)
fun ComponentPlan.toEntity() = ComponentPlanEntity(vehicleId, type.storageKey, intervalKm, intervalDays)
