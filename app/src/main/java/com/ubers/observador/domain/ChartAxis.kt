package com.ubers.observador.domain

/**
 * 1.29.2: the labels under the Violeta columns. Vico stops the app if an axis label is blank
 * (crash of 18/10 in Ganancias «Mes» and Mejores horas), so every column gets a real label and
 * the axis shows one of each [every]. Pure, so the rule is tested on the JVM.
 */
object ChartAxis {

    /** The label of column [index]: [labels] when it has text, else its 1-based number. Never blank. */
    fun label(labels: List<String>, index: Int): String =
        labels.getOrNull(index)?.takeIf { it.isNotBlank() } ?: (index + 1).toString()

    /** How many columns apart the labels go so they fit: every one up to 10, then 1 of each 5 (2 for 24 hours). */
    fun every(count: Int, dense: Int = 5): Int = if (count <= 10) 1 else dense
}
