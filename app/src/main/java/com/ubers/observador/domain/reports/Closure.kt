package com.ubers.observador.domain.reports

import java.math.BigDecimal

/** A frozen, lossless ledger. All renderers consume these same records, including unknowns. */
data class ClosureLine(val section: String, val label: String, val value: String?, val unit: String = "", val reference: String = "")

object ClosureText {
    const val ALGORITHM = "CLOSURE_V1"
    fun csv(lines: List<ClosureLine>) = Csv.build(listOf("seccion", "concepto", "valor", "unidad", "referencia"),
        lines.map { listOf(it.section, it.label, it.value.orEmpty(), it.unit, it.reference) })
    fun json(lines: List<ClosureLine>) = "[" + lines.joinToString(",") {
        "{\"section\":${Json.str(it.section)},\"label\":${Json.str(it.label)},\"value\":${Json.str(it.value)},\"unit\":${Json.str(it.unit)},\"reference\":${Json.str(it.reference)}}"
    } + "]"
    fun display(line: ClosureLine): String = line.value?.let { value ->
        when (line.unit) {
            "DOP_centavos" -> "RD$ " + BigDecimal(value).movePointLeft(2).toPlainString()
            "ms" -> BigDecimal(value).divide(BigDecimal(3_600_000), 2, java.math.RoundingMode.HALF_UP).toPlainString() + " h"
            "mm" -> BigDecimal(value).movePointLeft(6).setScale(3, java.math.RoundingMode.HALF_UP).toPlainString() + " km"
            else -> value + if (line.unit.isEmpty()) "" else " ${line.unit}"
        }
    } ?: "Sin dato"

    fun goal(actual: Long?, target: Long?, maximum: Boolean = false): String = when {
        target == null -> "Sin meta"
        actual == null -> "No evaluable: faltan datos"
        maximum && actual > target -> "Tiempo máximo superado"
        maximum -> "Dentro del tiempo máximo"
        actual >= target -> "Meta alcanzada"
        else -> "Meta pendiente"
    }

    fun parseGoal(text: String, factor: Long): Long? {
        if (text.isBlank()) return null
        require(Regex("[0-9]{1,8}([.,][0-9]{1,2})?").matches(text.trim())) { "Usa un número positivo, con hasta dos decimales." }
        return BigDecimal(text.trim().replace(',', '.')).multiply(BigDecimal(factor)).longValueExact().also {
            require(it > 0) { "La meta debe ser mayor que cero; deja vacío para desactivarla." }
        }
    }
}
