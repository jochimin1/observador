package com.ubers.observador.data

import androidx.room.withTransaction
import com.ubers.observador.data.db.*
import com.ubers.observador.domain.backup.MaintenanceGate
import com.ubers.observador.domain.expenses.*
import com.ubers.observador.domain.vehicle.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.util.UUID

/** 1.53.0: how an installation or repair is paid, if at all. */
sealed interface TireMoney {
    /** Nothing is written to Gastos (a free check, or the driver will register it later). */
    data object None : TireMoney
    /** A new expense with this amount; editing the event updates that same expense, never a second one. */
    data class New(val amountMinor: Long) : TireMoney
    /** An expense already in Gastos, not linked to any other event. */
    data class Link(val expenseId: String) : TireMoney
}

/**
 * 1.53.0 «Mi moto y neumáticos». Everything is written in one transaction with its checks: the
 * dates belong to the motorbike, the odometer never goes backwards silently and one expense pays
 * one event at most. Deleting a record never deletes money: the expense stays in Gastos.
 */
class TireRepository(private val db: AppDatabase) {
    private val dao = db.tireDao()
    private val expenses = db.expenseDao()

    fun observe(): Flow<TireData> = combine(dao.observeTires(), dao.observeEvents()) { t, e ->
        TireData(t.mapNotNull { it.toDomain() }, e.mapNotNull { it.toDomain() })
    }

    private suspend fun data() = TireData(dao.tires().mapNotNull { it.toDomain() }, dao.events().mapNotNull { it.toDomain() })

    private suspend fun timeline(vehicleId: String): List<OdometerPoint> = Odometer.timeline(
        expenses.getFuel().map { it.toDomain() }.filter { it.vehicleId == vehicleId },
        expenses.getExpenses().map { it.toDomain() }.filter { it.vehicleId == vehicleId },
        expenses.getOdometer().map { it.toDomain() }.filter { it.vehicleId == vehicleId })

    private suspend fun vehicleProblem(vehicleId: String, at: Long): String? {
        val vehicles = db.vehicleDao().vehicles().map { it.toDomain() }
        val v = vehicles.firstOrNull { it.id == vehicleId } ?: if (vehicleId == INITIAL_VEHICLE && vehicles.isEmpty()) VehicleProfile() else null
        return if (v == null || !v.contains(at)) "La fecha no pertenece al periodo de esta moto." else null
    }

    private suspend fun odometerProblem(vehicleId: String, km: Long?, at: Long, excludeId: String? = null): String? {
        if (km == null) return null
        if (!TireRules.validOdometer(km)) return "Odómetro no válido."
        return when (val c = Odometer.check(km, at, timeline(vehicleId), excludeId)) {
            is OdometerCheck.Lower -> "El odómetro (${ExpenseFormat.km(km)} km) es menor que la lectura anterior (${ExpenseFormat.km(c.previousKm)} km). Si cambiaste de odómetro, regístralo primero en Gastos."
            OdometerCheck.Ok -> null
        }
    }

    /** Writes the money of [event] and returns the expense id to keep on it, or an error. */
    private suspend fun money(event: TireEvent, vehicleId: String, money: TireMoney, all: TireData, now: Long): Result<String?> {
        val type = event.kind.expenseType
        return when (money) {
            TireMoney.None -> Result.success(null)
            is TireMoney.New -> {
                if (type == null) return Result.failure(IllegalArgumentException("Una revisión de presión no lleva gasto."))
                if (money.amountMinor !in ExpenseLimits.AMOUNT_MIN_MINOR..ExpenseLimits.AMOUNT_MAX_MINOR)
                    return Result.failure(IllegalArgumentException("Importe entre RD$1 y RD$100,000."))
                // The same expense when the event already had one of its own kind: never a duplicate
                val existing = event.expenseId?.let { id -> expenses.getExpenses().firstOrNull { it.id == id }?.toDomain() }
                    ?.takeIf { it.type == type && it.vehicleId == vehicleId }
                val entry = existing?.copy(atMs = event.atMs, amountMinor = money.amountMinor, odometerKm = event.odometerKm, updatedAt = now)
                    ?: ExpenseEntry(UUID.randomUUID().toString(), event.atMs, type, money.amountMinor, odometerKm = event.odometerKm,
                        createdAt = now, updatedAt = now, vehicleId = vehicleId)
                expenses.upsertExpense(entry.toEntity())
                Result.success(entry.id)
            }
            is TireMoney.Link -> {
                val e = expenses.getExpenses().firstOrNull { it.id == money.expenseId }?.toDomain()
                    ?: return Result.failure(IllegalArgumentException("Ese gasto ya no existe en Gastos."))
                when {
                    type == null || e.type != type -> Result.failure(IllegalArgumentException("Ese gasto es de otro tipo (${e.type.label})."))
                    e.vehicleId != vehicleId -> Result.failure(IllegalArgumentException("Ese gasto es de otra moto."))
                    TireRules.alreadyLinked(all, e.id, event.id) -> Result.failure(IllegalArgumentException("Ese gasto ya está enlazado a otro registro."))
                    else -> Result.success(e.id)
                }
            }
        }
    }

    /** Registers the tyre on a wheel; a tyre already there is closed and kept. Returns an error or null. */
    suspend fun install(vehicleId: String, position: WheelPosition, brand: String?, model: String?, size: String?,
        target: Pressure?, at: Long, odometerKm: Long?, pay: TireMoney, note: String?, now: Long): String? = MaintenanceGate.shared.access {
        runCatching {
            db.withTransaction {
                vehicleProblem(vehicleId, at)?.let { return@withTransaction it }
                if (at > now + 60_000) return@withTransaction "La fecha no puede ser futura."
                odometerProblem(vehicleId, odometerKm, at)?.let { return@withTransaction it }
                val all = data()
                val tire = Tire(UUID.randomUUID().toString(), vehicleId, position, TireRules.cleanText(brand), TireRules.cleanText(model),
                    TireRules.cleanText(size), target, at, odometerKm, createdAt = now, updatedAt = now)
                val written = TireRules.install(all, tire)
                val event = TireEvent(UUID.randomUUID().toString(), tire.id, TireEventKind.INSTALL, at, odometerKm = odometerKm,
                    note = TireRules.cleanText(note, TireRules.NOTE_MAX), createdAt = now, updatedAt = now)
                val expenseId = money(event, vehicleId, pay, all, now).getOrElse { return@withTransaction it.message }
                written.forEach { dao.put(it.toEntity()) }
                dao.put(event.copy(expenseId = expenseId).toEntity())
                null
            }
        }.getOrElse { it.message ?: "No se pudo guardar." }
    }

    /** Edits what describes the tyre (not its dates: a wrong installation is deleted and registered again). */
    suspend fun editTire(id: String, brand: String?, model: String?, size: String?, target: Pressure?, now: Long): String? = MaintenanceGate.shared.access {
        runCatching {
            db.withTransaction {
                val tire = dao.tires().firstOrNull { it.id == id }?.toDomain() ?: return@withTransaction "Esa goma ya no existe."
                dao.put(tire.copy(brand = TireRules.cleanText(brand), model = TireRules.cleanText(model), size = TireRules.cleanText(size),
                    target = target, updatedAt = now).toEntity())
                null
            }
        }.getOrElse { it.message ?: "No se pudo guardar." }
    }

    /** A new or edited check or repair (same id when editing). */
    suspend fun saveEvent(event: TireEvent, pay: TireMoney, now: Long): String? = MaintenanceGate.shared.access {
        runCatching {
            db.withTransaction {
                val all = data()
                val tire = all.tires.firstOrNull { it.id == event.tireId } ?: return@withTransaction "Esa goma ya no existe."
                val clean = event.copy(note = TireRules.cleanText(event.note, TireRules.NOTE_MAX), updatedAt = now)
                TireRules.eventProblem(tire, clean, now)?.let { return@withTransaction it }
                vehicleProblem(tire.vehicleId, clean.atMs)?.let { return@withTransaction it }
                // The odometer of this event's own expense is not a previous reading
                odometerProblem(tire.vehicleId, clean.odometerKm, clean.atMs, excludeId = clean.expenseId)?.let { return@withTransaction it }
                val expenseId = money(clean, tire.vehicleId, pay, all, now).getOrElse { return@withTransaction it.message }
                dao.put(clean.copy(expenseId = expenseId).toEntity())
                null
            }
        }.getOrElse { it.message ?: "No se pudo guardar." }
    }

    /** Deletes one check or repair. Its expense, if any, stays in Gastos. */
    suspend fun deleteEvent(id: String): String? = MaintenanceGate.shared.access {
        db.withTransaction {
            val e = dao.events().firstOrNull { it.id == id }?.toDomain() ?: return@withTransaction null
            if (e.kind == TireEventKind.INSTALL) return@withTransaction "La instalación se borra junto con la goma."
            dao.deleteEvent(id)
            null
        }
    }

    /**
     * Deletes a tyre registered by mistake with its records; expenses stay in Gastos. If it had
     * closed the previous tyre of the wheel, that one becomes the active tyre again.
     */
    suspend fun deleteTire(id: String, now: Long): String? = MaintenanceGate.shared.access {
        db.withTransaction {
            val all = data()
            val tire = all.tires.firstOrNull { it.id == id } ?: return@withTransaction null
            dao.deleteEventsOf(id)
            dao.deleteTire(id)
            if (tire.active) {
                all.tires.filter { it.vehicleId == tire.vehicleId && it.position == tire.position && it.removedAt == tire.installedAt }
                    .maxByOrNull { it.installedAt }
                    ?.let { dao.put(it.copy(removedAt = null, removedOdometerKm = null, updatedAt = now).toEntity()) }
            }
            null
        }
    }
}
