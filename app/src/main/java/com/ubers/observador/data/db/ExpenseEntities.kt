package com.ubers.observador.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.ubers.observador.domain.expenses.ExpenseEntry
import com.ubers.observador.domain.expenses.ExpenseType
import com.ubers.observador.domain.expenses.Frequency
import com.ubers.observador.domain.expenses.FuelEntry
import com.ubers.observador.domain.expenses.OdometerReading
import com.ubers.observador.domain.expenses.RecurringCost
import com.ubers.observador.domain.expenses.RecurringType
import java.time.LocalDate

/*
 * Fase 8B tables: what the driver writes by hand, the only rows of the app that can be edited
 * and deleted (with confirmation and undo). Money in minor units, gallons in thousandths,
 * odometer in whole km. No free-text notes, by design.
 */

@Entity(tableName = "fuel_entries", indices = [Index(value = ["at"])])
data class FuelEntryEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "at") val at: Long,
    @ColumnInfo(name = "gallons_milli") val gallonsMilli: Long,
    @ColumnInfo(name = "amount_minor") val amountMinor: Long,
    @ColumnInfo(name = "currency_code") val currencyCode: String,
    @ColumnInfo(name = "odometer_km") val odometerKm: Long?,
    @ColumnInfo(name = "full_tank") val fullTank: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)

@Entity(tableName = "expense_entries", indices = [Index(value = ["at"]), Index(value = ["type"])])
data class ExpenseEntryEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "at") val at: Long,
    @ColumnInfo(name = "type") val type: String,
    @ColumnInfo(name = "amount_minor") val amountMinor: Long,
    @ColumnInfo(name = "currency_code") val currencyCode: String,
    @ColumnInfo(name = "odometer_km") val odometerKm: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)

@Entity(tableName = "recurring_costs")
data class RecurringCostEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "type") val type: String,
    @ColumnInfo(name = "amount_minor") val amountMinor: Long,
    @ColumnInfo(name = "currency_code") val currencyCode: String,
    @ColumnInfo(name = "frequency") val frequency: String,
    /** ISO date (yyyy-MM-dd). */
    @ColumnInfo(name = "starts_on") val startsOn: String,
    @ColumnInfo(name = "ends_on") val endsOn: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)

@Entity(tableName = "odometer_readings", indices = [Index(value = ["at"])])
data class OdometerReadingEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "at") val at: Long,
    @ColumnInfo(name = "km") val km: Long,
    @ColumnInfo(name = "is_reset") val isReset: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)

fun FuelEntry.toEntity() = FuelEntryEntity(id, atMs, gallonsMilli, amountMinor, currencyCode, odometerKm, fullTank, createdAt, updatedAt)
fun FuelEntryEntity.toDomain() = FuelEntry(id, at, gallonsMilli, amountMinor, currencyCode, odometerKm, fullTank, createdAt, updatedAt)

fun ExpenseEntry.toEntity() = ExpenseEntryEntity(id, atMs, type.storageKey, amountMinor, currencyCode, odometerKm, createdAt, updatedAt)
fun ExpenseEntryEntity.toDomain() = ExpenseEntry(id, at, ExpenseType.fromStorage(type), amountMinor, currencyCode, odometerKm, createdAt, updatedAt)

fun RecurringCost.toEntity() = RecurringCostEntity(
    id, type.storageKey, amountMinor, currencyCode, frequency.storageKey, startsOn.toString(), endsOn?.toString(), createdAt, updatedAt
)
fun RecurringCostEntity.toDomain() = RecurringCost(
    id, RecurringType.fromStorage(type), amountMinor, currencyCode, Frequency.fromStorage(frequency),
    LocalDate.parse(startsOn), endsOn?.let(LocalDate::parse), createdAt, updatedAt
)

fun OdometerReading.toEntity() = OdometerReadingEntity(id, atMs, km, isReset, createdAt, updatedAt)
fun OdometerReadingEntity.toDomain() = OdometerReading(id, at, km, isReset, createdAt, updatedAt)
