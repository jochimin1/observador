package com.ubers.observador.domain.expenses

import com.ubers.observador.domain.money.Currencies
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/*
 * Fase 8B: real expenses. The only thing in the app the driver writes by hand, so it can be
 * corrected or deleted. Money in minor units (Long), gallons in thousandths (Long), odometer in
 * whole km (Long): no Double anywhere in a stored value.
 */

enum class ExpenseGroup(val label: String) {
    FUEL("Combustible"),
    MAINTENANCE("Mantenimiento"),
    LOOSE("Gastos sueltos"),
    FIXED("Gastos fijos")
}

/** A one-off expense. Fixed storage keys. */
enum class ExpenseType(val storageKey: String, val group: ExpenseGroup, val label: String) {
    OIL("OIL", ExpenseGroup.MAINTENANCE, "Aceite"),
    TIRES("TIRES", ExpenseGroup.MAINTENANCE, "Gomas"),
    CHAIN_KIT("CHAIN_KIT", ExpenseGroup.MAINTENANCE, "Cadena o kit de arrastre"),
    BRAKES("BRAKES", ExpenseGroup.MAINTENANCE, "Frenos"),
    MAINTENANCE_OTHER("MAINTENANCE_OTHER", ExpenseGroup.MAINTENANCE, "Otro mantenimiento"),
    WASH("WASH", ExpenseGroup.LOOSE, "Lavado"),
    PARKING_TOLL("PARKING_TOLL", ExpenseGroup.LOOSE, "Parqueo o peaje"),
    FINE("FINE", ExpenseGroup.LOOSE, "Multa"),
    OTHER("OTHER", ExpenseGroup.LOOSE, "Otro gasto");

    companion object {
        fun fromStorage(key: String?): ExpenseType = entries.firstOrNull { it.storageKey == key } ?: OTHER

        /** The maintenance kinds that can have a reminder (decision: oil required, the rest optional). */
        val REMINDABLE = listOf(OIL, CHAIN_KIT, TIRES, BRAKES)
    }
}

enum class RecurringType(val storageKey: String, val label: String) {
    BIKE_PAYMENT("BIKE_PAYMENT", "Cuota del motor"),
    DATA_PLAN("DATA_PLAN", "Plan de datos"),
    INSURANCE("INSURANCE", "Seguro"),
    OTHER("OTHER", "Otro gasto fijo");

    companion object {
        fun fromStorage(key: String?): RecurringType = entries.firstOrNull { it.storageKey == key } ?: OTHER
    }
}

enum class Frequency(val storageKey: String, val label: String, val cycleDays: Int?) {
    WEEKLY("WEEKLY", "Semanal", 7),
    BIWEEKLY("BIWEEKLY", "Quincenal", 15),
    MONTHLY("MONTHLY", "Mensual", null);

    companion object {
        fun fromStorage(key: String?): Frequency = entries.firstOrNull { it.storageKey == key } ?: MONTHLY
    }
}

data class FuelEntry(
    val id: String,
    val atMs: Long,
    val gallonsMilli: Long,
    val amountMinor: Long,
    val currencyCode: String = Currencies.DOP,
    val odometerKm: Long? = null,
    val fullTank: Boolean,
    val createdAt: Long,
    val updatedAt: Long
) {
    /** Price of one gallon in minor units, half up. */
    val pricePerGallonMinor: Long get() = ExpenseMath.divHalfUp(amountMinor * 1000, gallonsMilli)
}

data class ExpenseEntry(
    val id: String,
    val atMs: Long,
    val type: ExpenseType,
    val amountMinor: Long,
    val currencyCode: String = Currencies.DOP,
    val odometerKm: Long? = null,
    val createdAt: Long,
    val updatedAt: Long
)

data class RecurringCost(
    val id: String,
    val type: RecurringType,
    val amountMinor: Long,
    val currencyCode: String = Currencies.DOP,
    val frequency: Frequency,
    val startsOn: LocalDate,
    val endsOn: LocalDate? = null,
    val createdAt: Long,
    val updatedAt: Long
)

/** An odometer written on its own. [isReset]: "cambié de odómetro o de motor", a new count starts here. */
data class OdometerReading(
    val id: String,
    val atMs: Long,
    val km: Long,
    val isReset: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long
)

/** Everything the driver wrote in Gastos. */
data class ExpenseData(
    val fuel: List<FuelEntry> = emptyList(),
    val expenses: List<ExpenseEntry> = emptyList(),
    val recurring: List<RecurringCost> = emptyList(),
    val readings: List<OdometerReading> = emptyList()
)

object ExpenseMath {
    /** a / b rounded half up, for non-negative a and positive b. */
    fun divHalfUp(a: Long, b: Long): Long = if (b <= 0) 0 else (a * 2 + b) / (b * 2)
}

/** Field limits, as in Ajustes. */
object ExpenseLimits {
    const val GALLONS_MIN_MILLI = 100L          // 0.1 gal
    const val GALLONS_MAX_MILLI = 20_000L       // 20 gal
    const val AMOUNT_MIN_MINOR = 100L           // RD$1
    const val AMOUNT_MAX_MINOR = 10_000_000L    // RD$100,000
    const val ODOMETER_MAX_KM = 9_999_999L
    const val WORK_SHARE_MIN = 0
    const val WORK_SHARE_MAX = 100
    const val INTERVAL_MAX_KM = 100_000L
}

sealed interface FieldResult<out T> {
    data class Ok<T>(val value: T) : FieldResult<T>
    data class Error(val message: String) : FieldResult<Nothing>
}

/** Turns what the driver typed into exact units, or a plain-language problem. */
object ExpenseInput {

    private fun decimal(text: String): BigDecimal? =
        text.trim().replace(",", "").removePrefix("RD$").trim().takeIf { it.isNotEmpty() }?.toBigDecimalOrNull()

    fun gallons(text: String): FieldResult<Long> {
        val d = decimal(text) ?: return FieldResult.Error("Escribe los galones, por ejemplo 2.5")
        if (d.scale() > 3) return FieldResult.Error("Como máximo 3 decimales")
        val milli = d.movePointRight(3).setScale(0, RoundingMode.HALF_UP).longValueExact()
        return if (milli in ExpenseLimits.GALLONS_MIN_MILLI..ExpenseLimits.GALLONS_MAX_MILLI) FieldResult.Ok(milli)
        else FieldResult.Error("Entre 0.1 y 20 galones")
    }

    fun amount(text: String): FieldResult<Long> {
        val d = decimal(text) ?: return FieldResult.Error("Escribe el importe, por ejemplo 350")
        if (d.scale() > 2) return FieldResult.Error("Como máximo 2 decimales")
        val minor = d.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact()
        return if (minor in ExpenseLimits.AMOUNT_MIN_MINOR..ExpenseLimits.AMOUNT_MAX_MINOR) FieldResult.Ok(minor)
        else FieldResult.Error("Entre RD$1 y RD$100,000")
    }

    /** Empty means "not written" (allowed where the odometer is optional). */
    fun odometer(text: String, required: Boolean): FieldResult<Long?> {
        val t = text.trim().replace(",", "")
        if (t.isEmpty()) return if (required) FieldResult.Error("Escribe los km del odómetro") else FieldResult.Ok(null)
        val km = t.toLongOrNull() ?: return FieldResult.Error("Solo km enteros, sin decimales")
        return if (km in 0..ExpenseLimits.ODOMETER_MAX_KM) FieldResult.Ok(km) else FieldResult.Error("Entre 0 y 9,999,999 km")
    }

    /** "Precio por galón y total": the gallons follow, rounded to thousandths. */
    fun gallonsFromPrice(totalMinor: Long, pricePerGallonMinor: Long): FieldResult<Long> {
        if (pricePerGallonMinor <= 0) return FieldResult.Error("Escribe el precio por galón")
        val milli = ExpenseMath.divHalfUp(totalMinor * 1000, pricePerGallonMinor)
        return if (milli in ExpenseLimits.GALLONS_MIN_MILLI..ExpenseLimits.GALLONS_MAX_MILLI) FieldResult.Ok(milli)
        else FieldResult.Error("Salen ${milli / 1000.0} galones: revisa el precio o el total")
    }
}

/** Display: "2.500 gal" style is avoided; "2.5 gal", "RD$275.40", "12,345 km". */
object ExpenseFormat {
    fun gallons(milli: Long): String = BigDecimal.valueOf(milli, 3).stripTrailingZeros().let {
        if (it.scale() < 1) it.setScale(1) else it
    }.toPlainString()

    fun km(km: Long): String = "%,d".format(java.util.Locale.US, km)

    /** km per gallon with one decimal, from tenths. */
    fun tenths(tenths: Long): String = "${tenths / 10}.${tenths % 10}"
}
