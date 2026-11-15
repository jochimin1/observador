package com.ubers.observador.data

import androidx.room.withTransaction
import com.ubers.observador.data.db.*
import com.ubers.observador.domain.backup.MaintenanceGate
import com.ubers.observador.domain.vehicle.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/** 1.54.0: sensor associations and recorded advertisements. Nothing here is ever deleted on its own. */
class TireSensorRepository(private val db: AppDatabase) {
    private val dao = db.tireSensorDao()

    fun observeBindings(): Flow<List<SensorBinding>> = dao.observeSensors().map { l -> l.mapNotNull { it.toDomain() } }
    fun observeLatest(): Flow<List<TireSensorFrameEntity>> = dao.observeLatest()
    fun observeFrameCount(): Flow<Int> = dao.observeCount()

    /** Associates [sensorId] with [position] of [vehicleId]. Returns an error or null. */
    suspend fun bind(vehicleId: String, position: WheelPosition, sensorId: String, now: Long): String? = MaintenanceGate.shared.access {
        db.withTransaction {
            val id = SensorIds.normalize(sensorId) ?: return@withTransaction "El ID del sensor son 6 caracteres, por ejemplo 13AE32"
            val all = dao.sensors().mapNotNull { it.toDomain() }
            val new = SensorBinding(UUID.randomUUID().toString(), vehicleId, position, id, boundAt = now, createdAt = now, updatedAt = now)
            SensorBindings.bind(all, new).fold({ written -> written.forEach { dao.put(it.toEntity()) }; null }, { it.message })
        }
    }

    /** The kit card's double code: both wheels at once, in one transaction. */
    suspend fun bindPair(vehicleId: String, front: String, rear: String, now: Long): String? = MaintenanceGate.shared.access {
        runCatching { db.withTransaction {
            var all = dao.sensors().mapNotNull { it.toDomain() }
            // Free both wheels first so swapping the two sensors is allowed
            listOf(WheelPosition.FRONT, WheelPosition.REAR).forEach { pos ->
                SensorBindings.active(all, vehicleId, pos)?.takeIf { it.sensorId != (if (pos == WheelPosition.FRONT) front else rear) }?.let {
                    val closed = it.copy(unboundAt = now, updatedAt = now); dao.put(closed.toEntity())
                    all = all.map { b -> if (b.id == closed.id) closed else b }
                }
            }
            for ((pos, id) in listOf(WheelPosition.FRONT to front, WheelPosition.REAR to rear)) {
                val new = SensorBinding(UUID.randomUUID().toString(), vehicleId, pos, id, boundAt = now, createdAt = now, updatedAt = now)
                val written = SensorBindings.bind(all, new).getOrElse { throw IllegalStateException(it.message) }
                written.forEach { dao.put(it.toEntity()) }
                all = all.filter { a -> written.none { it.id == a.id } } + written
            }
            null
        } }.getOrElse { it.message ?: "No se pudo asociar." }
    }

    suspend fun unbind(vehicleId: String, position: WheelPosition, now: Long): Unit = MaintenanceGate.shared.access {
        db.withTransaction {
            dao.sensors().mapNotNull { it.toDomain() }.let { SensorBindings.active(it, vehicleId, position) }
                ?.let { dao.put(it.copy(unboundAt = now, updatedAt = now).toEntity()) }
        }
    }

    /** Writes a batch of recorded frames, each with what the provisional parser read from it. */
    suspend fun saveFrames(sessionId: String, frames: List<BleFrame>, sensorIds: Collection<String>) = MaintenanceGate.shared.access {
        if (frames.isEmpty()) return@access
        dao.putFrames(frames.map { f -> toEntity(sessionId, f, sensorIds) })
    }

    suspend fun frames(): List<TireSensorFrameEntity> = MaintenanceGate.shared.access { dao.frames() }

    companion object {
        fun toEntity(sessionId: String, f: BleFrame, sensorIds: Collection<String>): TireSensorFrameEntity {
            val mine = sensorIds.firstOrNull { Advertisement.mentions(f, it) }
            val parse = TpmsParser.parse(f)
            val r = (parse as? TpmsParse.Ok)?.reading
            return TireSensorFrameEntity(UUID.randomUUID().toString(), sessionId, f.receivedAt, f.address, f.name, f.rssi, f.hex, mine,
                TpmsParser.VERSION, r?.sensorId, r?.pressurePa, r?.temperatureCenti, r?.batteryPct, r?.leak?.let { if (it) 1 else 0 },
                (parse as? TpmsParse.Rejected)?.reason)
        }
    }
}
