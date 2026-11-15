package com.ubers.observador.data.db

import androidx.room.*
import com.ubers.observador.domain.vehicle.*
import kotlinx.coroutines.flow.Flow

/** 1.53.0: one tyre on one wheel; a change closes it (removed_at) and keeps it. */
@Entity(tableName = "tire_components", indices = [Index(value = ["vehicle_id", "position"])])
data class TireEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "vehicle_id") val vehicleId: String,
    val position: String,
    val brand: String?,
    val model: String?,
    val size: String?,
    @ColumnInfo(name = "target_pa") val targetPa: Int?,
    @ColumnInfo(name = "target_entered") val targetEntered: String?,
    @ColumnInfo(name = "target_unit") val targetUnit: String?,
    @ColumnInfo(name = "installed_at") val installedAt: Long,
    @ColumnInfo(name = "installed_odometer_km") val installedOdometerKm: Long?,
    @ColumnInfo(name = "removed_at") val removedAt: Long?,
    @ColumnInfo(name = "removed_odometer_km") val removedOdometerKm: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)

/** 1.53.0: installation, manual check or repair. expense_id is unique: one expense, one event. */
@Entity(tableName = "tire_events", indices = [Index(value = ["tire_id"]), Index(value = ["expense_id"], unique = true)])
data class TireEventEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "tire_id") val tireId: String,
    val kind: String,
    val at: Long,
    @ColumnInfo(name = "pressure_pa") val pressurePa: Int?,
    @ColumnInfo(name = "pressure_entered") val pressureEntered: String?,
    @ColumnInfo(name = "pressure_unit") val pressureUnit: String?,
    val temperature: String,
    @ColumnInfo(name = "odometer_km") val odometerKm: Long?,
    @ColumnInfo(name = "expense_id") val expenseId: String?,
    val note: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)

@Dao
interface TireDao {
    @Query("SELECT * FROM tire_components ORDER BY installed_at, id") fun observeTires(): Flow<List<TireEntity>>
    @Query("SELECT * FROM tire_events ORDER BY at, id") fun observeEvents(): Flow<List<TireEventEntity>>
    @Query("SELECT * FROM tire_components ORDER BY installed_at, id") suspend fun tires(): List<TireEntity>
    @Query("SELECT * FROM tire_events ORDER BY at, id") suspend fun events(): List<TireEventEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(tire: TireEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(event: TireEventEntity)
    @Query("DELETE FROM tire_events WHERE id = :id") suspend fun deleteEvent(id: String): Int
    @Query("DELETE FROM tire_events WHERE tire_id = :tireId") suspend fun deleteEventsOf(tireId: String): Int
    @Query("DELETE FROM tire_components WHERE id = :id") suspend fun deleteTire(id: String): Int
}

fun TireEntity.toDomain(): Tire? {
    val pos = WheelPosition.fromStorage(position) ?: return null
    return Tire(id, vehicleId, pos, brand, model, size, Pressure.of(targetPa, targetEntered, targetUnit),
        installedAt, installedOdometerKm, removedAt, removedOdometerKm, createdAt, updatedAt)
}

fun Tire.toEntity() = TireEntity(id, vehicleId, position.storageKey, brand, model, size,
    target?.pascals, target?.entered, target?.unit?.storageKey, installedAt, installedOdometerKm, removedAt, removedOdometerKm, createdAt, updatedAt)

fun TireEventEntity.toDomain(): TireEvent? {
    val k = TireEventKind.fromStorage(kind) ?: return null
    return TireEvent(id, tireId, k, at, Pressure.of(pressurePa, pressureEntered, pressureUnit), TireTemperature.fromStorage(temperature),
        odometerKm, expenseId, note, createdAt, updatedAt)
}

fun TireEvent.toEntity() = TireEventEntity(id, tireId, kind.storageKey, atMs, pressure?.pascals, pressure?.entered, pressure?.unit?.storageKey,
    temperature.storageKey, odometerKm, expenseId, note, createdAt, updatedAt)

/** 1.54.0: a sensor on a wheel, kept after it is replaced or removed (unbound_at). */
@Entity(tableName = "tire_sensors", indices = [Index(value = ["sensor_id"]), Index(value = ["vehicle_id", "position"])])
data class TireSensorEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "vehicle_id") val vehicleId: String,
    val position: String,
    @ColumnInfo(name = "sensor_id") val sensorId: String,
    val family: String,
    @ColumnInfo(name = "bound_at") val boundAt: Long,
    @ColumnInfo(name = "unbound_at") val unboundAt: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)

/**
 * 1.54.0: every Bluetooth advertisement recorded, as received (raw_hex), with what the provisional
 * parser read from it. Kept forever; travels in the backup and in «Exportar todo».
 */
@Entity(tableName = "tire_sensor_frames", indices = [Index(value = ["received_at"]), Index(value = ["sensor_id", "received_at"])])
data class TireSensorFrameEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "received_at") val receivedAt: Long,
    val address: String,
    val name: String?,
    val rssi: Int,
    @ColumnInfo(name = "raw_hex") val rawHex: String,
    /** The associated sensor whose ID the frame mentions, if any. */
    @ColumnInfo(name = "sensor_id") val sensorId: String?,
    @ColumnInfo(name = "parser_version") val parserVersion: String,
    @ColumnInfo(name = "parsed_id") val parsedId: String?,
    @ColumnInfo(name = "pressure_pa") val pressurePa: Int?,
    @ColumnInfo(name = "temperature_centi") val temperatureCenti: Int?,
    @ColumnInfo(name = "battery_pct") val batteryPct: Int?,
    val leak: Int?,
    val problem: String?
)

@Dao
interface TireSensorDao {
    @Query("SELECT * FROM tire_sensors ORDER BY bound_at, id") fun observeSensors(): Flow<List<TireSensorEntity>>
    @Query("SELECT * FROM tire_sensors ORDER BY bound_at, id") suspend fun sensors(): List<TireSensorEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(sensor: TireSensorEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putFrames(frames: List<TireSensorFrameEntity>)
    /** The newest frame of each associated sensor. */
    @Query("SELECT * FROM tire_sensor_frames AS f WHERE f.sensor_id IS NOT NULL AND f.received_at = (SELECT MAX(g.received_at) FROM tire_sensor_frames AS g WHERE g.sensor_id = f.sensor_id)")
    fun observeLatest(): Flow<List<TireSensorFrameEntity>>
    @Query("SELECT COUNT(*) FROM tire_sensor_frames") fun observeCount(): Flow<Int>
    @Query("SELECT * FROM tire_sensor_frames ORDER BY received_at, id") suspend fun frames(): List<TireSensorFrameEntity>
}

fun TireSensorEntity.toDomain(): SensorBinding? {
    val pos = WheelPosition.fromStorage(position) ?: return null
    return SensorBinding(id, vehicleId, pos, sensorId, family, boundAt, unboundAt, createdAt, updatedAt)
}

fun SensorBinding.toEntity() = TireSensorEntity(id, vehicleId, position.storageKey, sensorId, family, boundAt, unboundAt, createdAt, updatedAt)
