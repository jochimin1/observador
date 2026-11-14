package com.ubers.observador.data

import androidx.room.withTransaction
import com.ubers.observador.data.db.AppDatabase
import com.ubers.observador.data.db.toDomain
import com.ubers.observador.data.db.toEntity
import com.ubers.observador.domain.backup.MaintenanceGate
import com.ubers.observador.domain.expenses.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.util.UUID

/**
 * 1.46.0: work shifts. Every write re-checks the rules inside one transaction, and the shift row
 * and its odometer readings are written together, so a shift never points at a missing reading.
 */
class WorkShiftRepository(private val db: AppDatabase) {
    private val dao = db.workShiftDao()
    private val odometer = db.expenseDao()

    fun observe(): Flow<List<WorkShift>> = combine(dao.observeAll(), odometer.observeOdometer()) { links, readings ->
        WorkShiftRules.attach(links.map { it.toDomain() }, readings.map { it.toDomain() })
    }

    suspend fun all(): List<WorkShift> = MaintenanceGate.shared.access { db.withTransaction { load() } }

    private suspend fun load() = WorkShiftRules.attach(dao.all().map { it.toDomain() }, odometer.getOdometer().map { it.toDomain() })

    private suspend fun timeline(vehicleId: String): List<OdometerPoint> {
        return Odometer.timeline(odometer.getFuel().map { it.toDomain() }.filter { it.vehicleId == vehicleId },
            odometer.getExpenses().map { it.toDomain() }.filter { it.vehicleId == vehicleId },
            odometer.getOdometer().map { it.toDomain() }.filter { it.vehicleId == vehicleId })
    }

    private suspend fun activeVehicle(): VehicleProfile =
        db.vehicleDao().vehicles().lastOrNull { it.retiredAt == null }?.toDomain() ?: VehicleProfile()

    private fun openTrips(): Int = db.openHelper.writableDatabase
        .query("SELECT COUNT(*) FROM trips WHERE status IN ('TO_PICKUP','ARRIVED_OR_WAITING','IN_TRIP','UNKNOWN')")
        .use { it.moveToFirst(); it.getInt(0) }

    /** Returns the problem in plain language, or null when saved. */
    suspend fun start(km: Long, atMs: Long, reset: Boolean, nowMs: Long): String? = MaintenanceGate.shared.access {
        db.withTransaction {
            val vehicle = activeVehicle()
            if (!vehicle.contains(atMs)) return@withTransaction "Esa hora es anterior a la ficha de la moto activa."
            when (val c = WorkShiftRules.checkStart(km, atMs, nowMs, load(), timeline(vehicle.id), reset)) {
                is ShiftCheck.Rejected -> return@withTransaction c.message
                is ShiftCheck.Confirm, ShiftCheck.Ok -> Unit
            }
            val reading = OdometerReading(UUID.randomUUID().toString(), atMs, km, reset, nowMs, nowMs, vehicle.id)
            odometer.upsertOdometer(reading.toEntity())
            dao.put(WorkShiftLink(UUID.randomUUID().toString(), vehicle.id, reading.id, null, nowMs, nowMs).toEntity())
            null
        }
    }

    suspend fun finish(shiftId: String, km: Long, atMs: Long, nowMs: Long, confirmed: Boolean): ShiftCheck = MaintenanceGate.shared.access {
        db.withTransaction<ShiftCheck> {
            val shifts = load()
            val shift = shifts.firstOrNull { it.id == shiftId } ?: return@withTransaction ShiftCheck.Rejected("La jornada ya no existe.")
            if (shift.end == null && openTrips() > 0) return@withTransaction ShiftCheck.Rejected("Cierra el viaje activo antes de terminar la jornada.")
            when (val c = WorkShiftRules.checkEnd(shift, km, atMs, nowMs, shifts)) {
                is ShiftCheck.Rejected -> return@withTransaction c
                is ShiftCheck.Confirm -> if (!confirmed) return@withTransaction c
                ShiftCheck.Ok -> Unit
            }
            val reading = shift.end?.copy(atMs = atMs, km = km, updatedAt = nowMs)
                ?: OdometerReading(UUID.randomUUID().toString(), atMs, km, false, nowMs, nowMs, shift.vehicleId)
            odometer.upsertOdometer(reading.toEntity())
            dao.put(WorkShiftLink(shift.id, shift.vehicleId, shift.start.id, reading.id, shift.createdAt, nowMs).toEntity())
            ShiftCheck.Ok
        }
    }

    /** Corrects the start of a shift (same reading id: the correction is kept in its updated_at). */
    suspend fun editStart(shiftId: String, km: Long, atMs: Long, reset: Boolean, nowMs: Long): String? = MaintenanceGate.shared.access {
        db.withTransaction {
            val shifts = load()
            val shift = shifts.firstOrNull { it.id == shiftId } ?: return@withTransaction "La jornada ya no existe."
            if (shift.end != null && (km > shift.end.km || atMs >= shift.end.atMs)) return@withTransaction "El inicio debe ser anterior y menor que el final de la jornada."
            when (val c = WorkShiftRules.checkStart(km, atMs, nowMs, shifts, timeline(shift.vehicleId), reset, editingId = shift.id)) {
                is ShiftCheck.Rejected -> return@withTransaction c.message
                else -> Unit
            }
            odometer.upsertOdometer(shift.start.copy(atMs = atMs, km = km, isReset = reset, updatedAt = nowMs).toEntity())
            dao.put(WorkShiftLink(shift.id, shift.vehicleId, shift.start.id, shift.end?.id, shift.createdAt, nowMs).toEntity())
            null
        }
    }

    /** Deletes the shift and its readings; returns what is needed to undo it. */
    suspend fun delete(shiftId: String): WorkShift? = MaintenanceGate.shared.access {
        db.withTransaction {
            val shift = load().firstOrNull { it.id == shiftId } ?: return@withTransaction null
            dao.delete(shift.id)
            odometer.deleteOdometer(shift.start.id)
            shift.end?.let { odometer.deleteOdometer(it.id) }
            shift
        }
    }

    suspend fun restore(shift: WorkShift): Unit = MaintenanceGate.shared.access {
        db.withTransaction {
            odometer.upsertOdometer(shift.start.toEntity())
            shift.end?.let { odometer.upsertOdometer(it.toEntity()) }
            dao.put(WorkShiftLink(shift.id, shift.vehicleId, shift.start.id, shift.end?.id, shift.createdAt, shift.updatedAt).toEntity())
        }
    }

    /** A reading used by a shift is edited or deleted from the shift, not from the list of Gastos. */
    suspend fun isShiftReading(readingId: String): Boolean = MaintenanceGate.shared.access { dao.usingReading(readingId) > 0 }

    suspend fun hasOpen(): Boolean = MaintenanceGate.shared.access { dao.open() != null }
}
