package com.ubers.observador.service

import com.ubers.observador.domain.health.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale

object PerformanceMonitor {
    private val values = MutableStateFlow<Map<PerformanceMetric, TimingStats>>(emptyMap())
    val timings = values.asStateFlow()
    fun record(metric: PerformanceMetric, durationNanos: Long) {
        values.update { it + (metric to (it[metric] ?: TimingStats()).add(durationNanos.coerceAtLeast(0))) }
    }
    inline fun <T> measure(metric: PerformanceMetric, block: () -> T): T {
        val start = System.nanoTime()
        try { return block() } finally { record(metric, System.nanoTime() - start) }
    }
    fun text(): String = buildString {
        appendLine("Rendimiento desde el inicio del proceso (tiempos locales, sin datos de viajes)")
        PerformanceMetric.entries.forEach { metric ->
            val stat = values.value[metric]
            appendLine(metric.label + ": " + if (stat == null) "Sin muestras" else
                String.format(Locale.US, "n=%d; media=%.2f ms; máximo=%.2f ms; último=%.2f ms", stat.count, stat.averageMs, stat.maxNanos / 1_000_000.0, stat.lastNanos / 1_000_000.0))
        }
        val runtime = Runtime.getRuntime()
        appendLine("Heap Java usado: ${(runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024} MiB; máximo: ${runtime.maxMemory() / 1024 / 1024} MiB")
        val q = PersistenceQueue.sharedHealth().value
        appendLine("Guardado: pendientes=${q.pending}; máximo=${q.peakPending}; completados=${q.completed}; rechazados=${q.rejected}; fallos=${q.failures}")
        appendLine("Guardado: espera última=${q.lastWaitNanos / 1_000_000} ms; espera máxima=${q.maxWaitNanos / 1_000_000} ms; escritura última=${q.lastRunNanos / 1_000_000} ms; escritura máxima=${q.maxRunNanos / 1_000_000} ms")
    }
}
