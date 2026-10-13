package com.ubers.observador.data

import androidx.room.withTransaction
import com.ubers.observador.data.db.AppDatabase
import com.ubers.observador.data.db.toDomain
import com.ubers.observador.data.db.toEntity
import com.ubers.observador.domain.expenses.ExpenseData
import com.ubers.observador.domain.expenses.ExpenseEntry
import com.ubers.observador.domain.expenses.FuelEntry
import com.ubers.observador.domain.expenses.OdometerReading
import com.ubers.observador.domain.expenses.RecurringCost
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.util.UUID

/** A deleted row, kept as it was so "Deshacer" puts it back exactly. */
sealed interface DeletedExpense {
    data class Fuel(val entry: FuelEntry) : DeletedExpense
    data class Expense(val entry: ExpenseEntry) : DeletedExpense
    data class Recurring(val entry: RecurringCost) : DeletedExpense
    data class Odometer(val entry: OdometerReading) : DeletedExpense
}

/**
 * Fase 8B data access. Unlike observations from Uber, these rows are the driver's own: they can
 * be edited (same id, new updated_at) and deleted. A confirmed odometer reset is written in the
 * same transaction as the entry that needed it.
 */
class ExpenseRepository(private val db: AppDatabase) {

    private val dao = db.expenseDao()

    fun observeAll(): Flow<ExpenseData> = combine(
        dao.observeFuel(), dao.observeExpenses(), dao.observeRecurring(), dao.observeOdometer()
    ) { f, e, r, o -> ExpenseData(f.map { it.toDomain() }, e.map { it.toDomain() }, r.map { it.toDomain() }, o.map { it.toDomain() }) }

    suspend fun getAll(): ExpenseData = db.withTransaction {
        ExpenseData(dao.getFuel().map { it.toDomain() }, dao.getExpenses().map { it.toDomain() }, emptyList(), dao.getOdometer().map { it.toDomain() })
    }

    /** [resetAtKm]: the driver confirmed "cambié de odómetro o de motor" for this lower odometer. */
    suspend fun saveFuel(entry: FuelEntry, resetAtKm: Long? = null) = db.withTransaction {
        resetAtKm?.let { dao.upsertOdometer(reset(entry.atMs, it).toEntity()) }
        dao.upsertFuel(entry.toEntity())
    }

    suspend fun saveExpense(entry: ExpenseEntry, resetAtKm: Long? = null) = db.withTransaction {
        resetAtKm?.let { dao.upsertOdometer(reset(entry.atMs, it).toEntity()) }
        dao.upsertExpense(entry.toEntity())
    }

    suspend fun saveRecurring(entry: RecurringCost) = dao.upsertRecurring(entry.toEntity())

    suspend fun saveOdometer(entry: OdometerReading) = dao.upsertOdometer(entry.toEntity())

    suspend fun delete(item: DeletedExpense) {
        when (item) {
            is DeletedExpense.Fuel -> dao.deleteFuel(item.entry.id)
            is DeletedExpense.Expense -> dao.deleteExpense(item.entry.id)
            is DeletedExpense.Recurring -> dao.deleteRecurring(item.entry.id)
            is DeletedExpense.Odometer -> dao.deleteOdometer(item.entry.id)
        }
    }

    suspend fun restore(item: DeletedExpense) {
        when (item) {
            is DeletedExpense.Fuel -> dao.upsertFuel(item.entry.toEntity())
            is DeletedExpense.Expense -> dao.upsertExpense(item.entry.toEntity())
            is DeletedExpense.Recurring -> dao.upsertRecurring(item.entry.toEntity())
            is DeletedExpense.Odometer -> dao.upsertOdometer(item.entry.toEntity())
        }
    }

    /** Debug only: replaces the TEST rows (ids starting with [prefix]); real ones are never touched. */
    suspend fun replaceTestData(prefix: String, data: ExpenseData) = db.withTransaction {
        dao.deleteTest(prefix)
        data.fuel.forEach { dao.upsertFuel(it.toEntity()) }
        data.expenses.forEach { dao.upsertExpense(it.toEntity()) }
        data.recurring.forEach { dao.upsertRecurring(it.toEntity()) }
        data.readings.forEach { dao.upsertOdometer(it.toEntity()) }
    }

    suspend fun deleteTestData(prefix: String): Int = dao.deleteTest(prefix)

    private fun reset(atMs: Long, km: Long) =
        // One millisecond before the entry, so the new count starts with it
        OdometerReading(UUID.randomUUID().toString(), atMs - 1, km, isReset = true, createdAt = atMs, updatedAt = atMs)
}
