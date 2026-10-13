package com.ubers.observador.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** Fase 8B storage: whole tables are small (a few rows a day), sums happen in the domain. */
@Dao
interface ExpenseDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertFuel(e: FuelEntryEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertExpense(e: ExpenseEntryEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertRecurring(e: RecurringCostEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertOdometer(e: OdometerReadingEntity)

    @Query("DELETE FROM fuel_entries WHERE id = :id") suspend fun deleteFuel(id: String): Int
    @Query("DELETE FROM expense_entries WHERE id = :id") suspend fun deleteExpense(id: String): Int
    @Query("DELETE FROM recurring_costs WHERE id = :id") suspend fun deleteRecurring(id: String): Int
    @Query("DELETE FROM odometer_readings WHERE id = :id") suspend fun deleteOdometer(id: String): Int

    @Query("SELECT * FROM fuel_entries ORDER BY at") fun observeFuel(): Flow<List<FuelEntryEntity>>
    @Query("SELECT * FROM expense_entries ORDER BY at") fun observeExpenses(): Flow<List<ExpenseEntryEntity>>
    @Query("SELECT * FROM recurring_costs ORDER BY starts_on") fun observeRecurring(): Flow<List<RecurringCostEntity>>
    @Query("SELECT * FROM odometer_readings ORDER BY at") fun observeOdometer(): Flow<List<OdometerReadingEntity>>

    @Query("SELECT * FROM fuel_entries ORDER BY at") suspend fun getFuel(): List<FuelEntryEntity>
    @Query("SELECT * FROM expense_entries ORDER BY at") suspend fun getExpenses(): List<ExpenseEntryEntity>
    @Query("SELECT * FROM odometer_readings ORDER BY at") suspend fun getOdometer(): List<OdometerReadingEntity>

    @Query("DELETE FROM fuel_entries WHERE id LIKE :prefix || '%'") suspend fun deleteFuelByPrefix(prefix: String): Int
    @Query("DELETE FROM expense_entries WHERE id LIKE :prefix || '%'") suspend fun deleteExpensesByPrefix(prefix: String): Int
    @Query("DELETE FROM recurring_costs WHERE id LIKE :prefix || '%'") suspend fun deleteRecurringByPrefix(prefix: String): Int
    @Query("DELETE FROM odometer_readings WHERE id LIKE :prefix || '%'") suspend fun deleteOdometerByPrefix(prefix: String): Int

    @Transaction
    suspend fun deleteTest(prefix: String): Int =
        deleteFuelByPrefix(prefix) + deleteExpensesByPrefix(prefix) + deleteRecurringByPrefix(prefix) + deleteOdometerByPrefix(prefix)
}
