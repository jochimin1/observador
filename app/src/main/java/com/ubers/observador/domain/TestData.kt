package com.ubers.observador.domain

/**
 * 1.39.0: one rule for test rows everywhere. Rows created by the Laboratorio tools carry ids or
 * hashes starting with [PREFIX]; real figures (Inicio, Ganancias, Gastos, reports, the weekly
 * summary) leave them out unless the driver turns «Mostrar datos de prueba» on in Laboratorio,
 * or ticks the switch of a single export in Reportes.
 */
object TestData {
    const val PREFIX = "TEST-"

    fun isTest(id: String): Boolean = id.startsWith(PREFIX)

    fun keep(id: String, includeTest: Boolean): Boolean = includeTest || !isTest(id)

    fun <T> filter(rows: List<T>, includeTest: Boolean, id: (T) -> String): List<T> =
        if (includeTest) rows else rows.filterNot { isTest(id(it)) }
}
