package com.ubers.observador.domain

import java.util.Locale

/** Outcome of validating what the driver typed into a settings field. */
sealed interface FieldResult<out T> {
    data class Valid<T>(val value: T) : FieldResult<T>
    data class Invalid(val message: String) : FieldResult<Nothing>
}

/** Accepted ranges for every numeric setting. The minimum thresholds can never be 0 or negative. */
object SettingsLimits {
    const val FUEL_PRICE_MAX = 5_000.0      // 0 = not set
    const val KM_PER_GALLON_MAX = 500.0     // 0 = not set
    const val COST_PER_KM_MIN = 0.01
    const val COST_PER_KM_MAX = 100.0
    const val MIN_RDP_PER_KM_MIN = 0.5
    const val MIN_RDP_PER_KM_MAX = 500.0
    const val MIN_RDP_PER_HOUR_MIN = 10.0
    const val MIN_RDP_PER_HOUR_MAX = 20_000.0
    const val PICKUP_KM_MIN = 0.5
    const val PICKUP_KM_MAX = 50.0
    const val PICKUP_MIN_MIN = 1
    const val PICKUP_MIN_MAX = 120
    const val GOOD_FROM_MIN = 0
    const val GOOD_FROM_MAX = 99
    const val EXCELLENT_FROM_MIN = 1
    const val EXCELLENT_FROM_MAX = 100
    const val OVERLAY_SECONDS_MIN = 5
    const val OVERLAY_SECONDS_MAX = 60
    const val GRACE_SECONDS_MIN = 10
    const val GRACE_SECONDS_MAX = 600
    const val WORKDAY_START_MIN = 0
    const val WORKDAY_START_MAX = 23
}

object FieldValidation {

    private val DECIMAL = Regex("""^(\d+\.?\d*|\.\d+)$""")
    private val INTEGER = Regex("""^\d+$""")

    fun parseDecimal(text: String, min: Double, max: Double): FieldResult<Double> {
        val clean = text.trim().replace(',', '.')
        if (clean.isEmpty()) return FieldResult.Invalid("Escribe un valor")
        if (clean.startsWith("-")) return FieldResult.Invalid("No puede ser negativo")
        if (!DECIMAL.matches(clean)) return FieldResult.Invalid("Escribe solo números")
        val value = clean.toDoubleOrNull()
        if (value == null || !value.isFinite() || value < min || value > max) {
            return FieldResult.Invalid(rangeMessage(formatNumber(min), formatNumber(max)))
        }
        return FieldResult.Valid(value)
    }

    fun parseInt(text: String, min: Int, max: Int): FieldResult<Int> {
        val clean = text.trim()
        if (clean.isEmpty()) return FieldResult.Invalid("Escribe un valor")
        if (clean.startsWith("-")) return FieldResult.Invalid("No puede ser negativo")
        if (!INTEGER.matches(clean)) return FieldResult.Invalid("Escribe un número entero")
        val value = clean.toIntOrNull()
        if (value == null || value < min || value > max) {
            return FieldResult.Invalid(rangeMessage(min.toString(), max.toString()))
        }
        return FieldResult.Valid(value)
    }

    private fun rangeMessage(min: String, max: String) = "Escribe un valor entre $min y $max"
}

/** 6.0 -> "6", 6.5 -> "6.5", 1.25 -> "1.25". Always uses a dot, whatever the phone's locale. */
fun formatNumber(value: Double): String {
    if (value == Math.floor(value) && Math.abs(value) < 1e9) {
        return String.format(Locale.US, "%.0f", value)
    }
    return String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')
}
