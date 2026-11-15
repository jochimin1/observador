package com.ubers.observador.domain.health

/** Fixed-size aggregates: no history of screens, locations, money or unbounded sample lists. */
enum class PerformanceMetric(val label: String) {
    STARTUP("Inicio hasta contenido preparado"), READING("Lectura de pantalla"),
    OVERLAY("Oferta hasta tarjeta añadida"), OPERATING("Cálculo de costos del periodo")
}

data class TimingStats(val count: Long = 0, val totalNanos: Long = 0, val maxNanos: Long = 0, val lastNanos: Long = 0) {
    fun add(nanos: Long): TimingStats {
        require(nanos >= 0)
        return copy(count = count + 1, totalNanos = totalNanos + nanos,
            maxNanos = maxOf(maxNanos, nanos), lastNanos = nanos)
    }
    val averageMs: Double? get() = if (count == 0L) null else totalNanos.toDouble() / count / 1_000_000
}
