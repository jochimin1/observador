package com.ubers.observador.domain.vehicle

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 1.55.0: what the wheel charts draw. Manual checks and sensor frames are kept as separate series
 * (never joined into one line), values are converted to the driver's unit, and gaps longer than
 * [GAP_MS] break the sensor line so missing data is not drawn as if it existed.
 */
data class ChartPoint(val atMs: Long, val value: Double)

data class ChartSeries(
    val manual: List<ChartPoint>,
    /** Sensor points, split into runs at every gap. */
    val sensorRuns: List<List<ChartPoint>>,
    /** Target and its margin, in the same unit (pressure only). */
    val target: Double?,
    val low: Double?,
    val high: Double?,
    val minY: Double,
    val maxY: Double,
    val fromMs: Long,
    val toMs: Long,
    val unitLabel: String,
    /** The sensor pressure is still unconfirmed (absolute or gauge): drawn as sent, labelled. */
    val sensorProvisional: Boolean
) {
    val empty: Boolean get() = manual.isEmpty() && sensorRuns.all { it.isEmpty() }
}

object TireChart {
    const val GAP_MS = 30 * 60_000L
    /** Enough points to see the shape; more only slows the drawing. */
    const val MAX_SENSOR_POINTS = 600

    private fun pressureIn(pa: Int, unit: PressureUnit): Double =
        BigDecimal(pa).divide(unit.pascals, 4, RoundingMode.HALF_UP).toDouble()

    private fun tempIn(centi: Int, unit: TemperatureUnit): Double =
        if (unit == TemperatureUnit.C) centi / 100.0 else centi / 100.0 * 1.8 + 32

    fun runs(points: List<ChartPoint>, gapMs: Long = GAP_MS): List<List<ChartPoint>> {
        val sorted = points.sortedBy { it.atMs }
        val thinned = if (sorted.size <= MAX_SENSOR_POINTS) sorted
            else sorted.filterIndexed { i, _ -> i % ((sorted.size + MAX_SENSOR_POINTS - 1) / MAX_SENSOR_POINTS) == 0 }
        val out = ArrayList<MutableList<ChartPoint>>()
        for (p in thinned) {
            val run = out.lastOrNull()
            if (run == null || p.atMs - run.last().atMs > gapMs) out += mutableListOf(p) else run += p
        }
        return out
    }

    private fun bounds(values: List<Double>): Pair<Double, Double> {
        if (values.isEmpty()) return 0.0 to 1.0
        val lo = values.min(); val hi = values.max()
        val pad = ((hi - lo) * 0.1).coerceAtLeast(if (hi == 0.0) 1.0 else kotlin.math.abs(hi) * 0.05)
        return (lo - pad) to (hi + pad)
    }

    /** [sensor]: (receivedAt, pascals) of the wheel's sensor frames, as sent. */
    fun pressure(manual: List<TireEvent>, sensor: List<Pair<Long, Int>>, target: Pressure?, tolerancePct: Int, unit: PressureUnit,
        fromMs: Long, toMs: Long): ChartSeries {
        val m = manual.filter { it.kind == TireEventKind.CHECK && it.atMs in fromMs..toMs }.mapNotNull { e -> e.pressure?.let { ChartPoint(e.atMs, pressureIn(it.pascals, unit)) } }
        val s = runs(sensor.filter { it.first in fromMs..toMs }.map { ChartPoint(it.first, pressureIn(it.second, unit)) })
        val t = target?.let { pressureIn(it.pascals, unit) }
        val lo = t?.let { it * (100 - tolerancePct) / 100 }
        val hi = t?.let { it * (100 + tolerancePct) / 100 }
        val (a, b) = bounds(m.map { it.value } + s.flatten().map { it.value } + listOfNotNull(lo, hi))
        return ChartSeries(m, s, t, lo, hi, a, b, fromMs, toMs, unit.label, sensorProvisional = !TpmsParser.VALIDATED)
    }

    /** Temperature has no manual series: only the sensor sends it. */
    fun temperature(sensor: List<Pair<Long, Int>>, unit: TemperatureUnit, fromMs: Long, toMs: Long): ChartSeries {
        val s = runs(sensor.filter { it.first in fromMs..toMs }.map { ChartPoint(it.first, tempIn(it.second, unit)) })
        val (a, b) = bounds(s.flatten().map { it.value })
        return ChartSeries(emptyList(), s, null, null, null, a, b, fromMs, toMs, unit.label, sensorProvisional = false)
    }

    /** x in 0..1 for a time, y in 0..1 (0 = bottom) for a value. */
    fun x(s: ChartSeries, atMs: Long): Float = if (s.toMs <= s.fromMs) 0f else ((atMs - s.fromMs).toDouble() / (s.toMs - s.fromMs)).toFloat().coerceIn(0f, 1f)
    fun y(s: ChartSeries, v: Double): Float = if (s.maxY <= s.minY) 0.5f else ((v - s.minY) / (s.maxY - s.minY)).toFloat().coerceIn(0f, 1f)

    /** What a screen reader says for a chart instead of the drawing. */
    fun description(s: ChartSeries, what: String): String {
        if (s.empty) return "$what: sin datos en este periodo."
        val parts = ArrayList<String>()
        if (s.manual.isNotEmpty()) parts += "${s.manual.size} revisiones manuales, de ${fmt(s.manual.minOf { it.value })} a ${fmt(s.manual.maxOf { it.value })} ${s.unitLabel}"
        val all = s.sensorRuns.flatten()
        if (all.isNotEmpty()) parts += "${all.size} lecturas del sensor${if (s.sensorProvisional) " por confirmar" else ""}, de ${fmt(all.minOf { it.value })} a ${fmt(all.maxOf { it.value })} ${s.unitLabel}" +
            if (s.sensorRuns.size > 1) ", con ${s.sensorRuns.size - 1} ${if (s.sensorRuns.size == 2) "hueco" else "huecos"}" else ""
        s.target?.let { parts += "objetivo ${fmt(it)} ${s.unitLabel}" }
        return "$what: " + parts.joinToString("; ") + "."
    }

    fun fmt(v: Double): String = BigDecimal(v).setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }.toPlainString()
}
