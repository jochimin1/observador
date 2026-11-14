package com.ubers.observador.data

import androidx.room.withTransaction
import com.ubers.observador.data.db.*
import com.ubers.observador.domain.backup.MaintenanceGate
import com.ubers.observador.domain.expenses.*
import kotlinx.coroutines.flow.combine
import java.util.UUID

class VehicleRepository(private val db: AppDatabase) {
    private val dao = db.vehicleDao()
    fun observe() = combine(dao.observeVehicles(), dao.observePlans()) { v, p ->
        VehicleCareData(v.map { it.toDomain() }, p.map { it.toDomain() })
    }
    suspend fun get() = MaintenanceGate.shared.access {
        db.withTransaction { VehicleCareData(dao.vehicles().map { it.toDomain() }, dao.plans().map { it.toDomain() }) }
    }

    suspend fun save(name: String, share: Int, replace: Boolean, now: Long): String? = MaintenanceGate.shared.access {
        db.withTransaction {
            if (name.trim().length !in 1..60 || name.any { it.isISOControl() } || share !in 0..100)
                return@withTransaction "Nombre de 1 a 60 caracteres y uso laboral de 0 a 100 %."
            val current = dao.vehicles().lastOrNull { it.retiredAt == null }?.toDomain() ?: VehicleProfile()
            if (replace) {
                if (now <= current.startedAt) return@withTransaction "La nueva moto debe empezar después de la anterior."
                val open = db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM trips WHERE status IN ('TO_PICKUP','ARRIVED_OR_WAITING','IN_TRIP','UNKNOWN')").use { it.moveToFirst(); it.getInt(0) }
                if (open > 0) return@withTransaction "Cierra el viaje activo antes de cambiar de moto."
                if (db.workShiftDao().open() != null) return@withTransaction "Termina la jornada abierta antes de cambiar de moto."
                val future = listOf("fuel_entries", "expense_entries", "odometer_readings").sumOf { table ->
                    db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM $table WHERE vehicle_id = ? AND at >= ?", arrayOf<Any>(current.id, now)).use { it.moveToFirst(); it.getInt(0) }
                }
                if (future > 0) return@withTransaction "Hay registros con fecha posterior al cambio. Corrige sus fechas antes de cambiar de moto."
                dao.put(current.copy(retiredAt = now, updatedAt = now).toEntity())
                dao.put(VehicleProfile(UUID.randomUUID().toString(), name.trim(), share, now, updatedAt = now).toEntity())
            } else dao.put(current.copy(name = name.trim(), workSharePct = share, updatedAt = now).toEntity())
            null
        }
    }

    /** 1.46.0: the measured share the driver confirmed, written on the active motorbike. */
    suspend fun setActiveShare(share: Int, now: Long): Unit = MaintenanceGate.shared.access {
        db.withTransaction {
            val current = dao.vehicles().lastOrNull { it.retiredAt == null }?.toDomain() ?: VehicleProfile()
            dao.put(current.copy(workSharePct = share.coerceIn(0, 100), updatedAt = now).toEntity())
        }
    }

    suspend fun savePlan(plan: ComponentPlan): String? = MaintenanceGate.shared.access {
        db.withTransaction {
            val active = dao.vehicles().lastOrNull { it.retiredAt == null }?.id ?: INITIAL_VEHICLE
            if (!plan.valid() || plan.vehicleId != active) return@withTransaction "Intervalo: 0–100,000 km y 0–3,650 días, para la moto activa."
            dao.put(plan.toEntity())
            null
        }
    }
}
