package com.ubers.observador.domain.intelligence

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Every threshold of Fase 7D in one place. Sample labels describe how much data there is, not
 * formal statistical significance.
 */
object IntelligencePolicy {
    /** Name of the analysis formulas, saved with each calibration run. */
    const val ANALYSIS_VERSION = "INTELLIGENCE_V1"

    // Sample confidence (per metric): 0-9, 10-29, 30-99, 100+
    const val EXPLORATORY_FROM = 10
    const val MODERATE_FROM = 30
    const val STRONG_FROM = 100

    /** Fewer pairs than this and a rank correlation is not computed at all. */
    const val MIN_CORRELATION_PAIRS = 3

    /** A score bucket or category counts for the "higher is better" check only with this many results. */
    const val MIN_BUCKET_N = 5

    // Calibration gates: <30 diagnosis only, 30-49 experimental, 50+ candidate for shadow
    const val CALIBRATION_MIN_SAMPLES = 30
    const val CANDIDATE_MIN_SAMPLES = 50

    /** Older 70% calibrates, newest 30% validates (time order, never random). */
    const val TRAIN_FRACTION = 0.70

    /** A candidate must improve rank correlation by at least this much, in validation too. */
    const val MIN_RHO_IMPROVEMENT = 0.05

    /** More "excellent but below target" trips than this (absolute rate) is a critical regression. */
    const val CRITICAL_REGRESSION_RATE = 0.10

    /** Results needed in shadow before "Aplicar" can be offered. */
    const val SHADOW_MIN_RESULTS = 30

    /** Median time or hourly error beyond these is called a bias. */
    const val HIGH_TIME_BIAS = 0.15
    const val HIGH_HOURLY_ERROR = 0.20

    /** Two categories within this relative distance of each other "behave the same". */
    const val COLLAPSE_RATIO = 0.05

    /** Correlations below this (with enough data) are called weak. */
    const val WEAK_RHO = 0.30

    /** Coverage below this is shown as a data problem before any conclusion. */
    const val LOW_COVERAGE = 0.70

    /** Recent vs previous window, and the results each needs before a change is called. */
    const val DRIFT_WINDOW_DAYS = 14
    const val DRIFT_MIN_PER_PERIOD = 20

    /** Fewer trips per hour-slot or heatmap cell than this is "pocos datos". */
    const val CELL_MIN = 5

    /** Weekday/weekend split only when each side has this many results. */
    const val DAY_TYPE_MIN = 10

    /** Weight grid: steps of 10 points, each weight at least 10 (84 combinations). */
    const val GRID_STEP = 10
    const val GRID_MIN_WEIGHT = 10

    /** Quartile spread needs at least this many results (two per quartile). */
    const val MIN_QUARTILE_N = 8
}

/** How much data a figure stands on. Not a statistical test. */
enum class SampleConfidence {
    INSUFFICIENT, EXPLORATORY, MODERATE, STRONG;

    companion object {
        fun of(n: Int): SampleConfidence = when {
            n >= IntelligencePolicy.STRONG_FROM -> STRONG
            n >= IntelligencePolicy.MODERATE_FROM -> MODERATE
            n >= IntelligencePolicy.EXPLORATORY_FROM -> EXPLORATORY
            else -> INSUFFICIENT
        }
    }
}

/** N total and N usable for one metric. Shown with every figure. */
data class Coverage(val total: Int, val valid: Int) {
    val ratio: Double? get() = if (total == 0) null else valid.toDouble() / total
    val confidence: SampleConfidence get() = SampleConfidence.of(valid)
}

/** P25, median and P75 of a set of values. */
data class Distribution(val n: Int, val p25: Double, val median: Double, val p75: Double) {
    companion object {
        fun of(values: List<Double>): Distribution? {
            if (values.isEmpty()) return null
            val sorted = values.sorted()
            return Distribution(
                sorted.size,
                Stats.percentileSorted(sorted, 0.25),
                Stats.percentileSorted(sorted, 0.50),
                Stats.percentileSorted(sorted, 0.75)
            )
        }
    }
}

object Stats {

    /**
     * Percentile with linear interpolation between closest ranks (the common "type 7"): the
     * median of an even list is the mean of the two middle values. Null for an empty list.
     */
    fun percentile(values: List<Double>, p: Double): Double? =
        if (values.isEmpty()) null else percentileSorted(values.sorted(), p)

    fun median(values: List<Double>): Double? = percentile(values, 0.5)

    internal fun percentileSorted(sorted: List<Double>, p: Double): Double {
        require(p in 0.0..1.0)
        if (sorted.size == 1) return sorted[0]
        val position = p * (sorted.size - 1)
        val low = floor(position).toInt()
        val high = minOf(low + 1, sorted.size - 1)
        return sorted[low] + (sorted[high] - sorted[low]) * (position - low)
    }

    /**
     * Median of money in minor units. Amounts are exact as Double (far below 2^53); the result is
     * rounded half up back to a whole minor unit, so money leaves the statistics as a Long again.
     */
    fun medianMinor(values: List<Long>): Long? = percentileMinor(values, 0.5)

    fun percentileMinor(values: List<Long>, p: Double): Long? =
        percentile(values.map { it.toDouble() }, p)?.let { Math.round(it) }
}

/**
 * Spearman rank correlation in plain Kotlin: Pearson correlation of the ranks, with tied values
 * given their average rank. Null (never 0) when there are too few pairs or one side is constant,
 * because "no answer" and "no relation" are different things.
 */
object Spearman {

    fun rho(x: List<Double>, y: List<Double>): Double? {
        require(x.size == y.size) { "x and y must pair up" }
        if (x.size < IntelligencePolicy.MIN_CORRELATION_PAIRS) return null
        return pearson(ranks(x), ranks(y))
    }

    /** Average ranks, 1-based: [10, 20, 20, 30] -> [1, 2.5, 2.5, 4]. */
    fun ranks(values: List<Double>): List<Double> {
        val order = values.indices.sortedBy { values[it] }
        val ranks = DoubleArray(values.size)
        var i = 0
        while (i < order.size) {
            var j = i
            while (j + 1 < order.size && values[order[j + 1]] == values[order[i]]) j++
            val average = (i + j) / 2.0 + 1.0
            for (k in i..j) ranks[order[k]] = average
            i = j + 1
        }
        return ranks.toList()
    }

    private fun pearson(a: List<Double>, b: List<Double>): Double? {
        val meanA = a.average()
        val meanB = b.average()
        var cov = 0.0
        var varA = 0.0
        var varB = 0.0
        for (i in a.indices) {
            val da = a[i] - meanA
            val db = b[i] - meanB
            cov += da * db
            varA += da * da
            varB += db * db
        }
        if (varA == 0.0 || varB == 0.0) return null
        return (cov / sqrt(varA * varB)).coerceIn(-1.0, 1.0)
    }
}

/** Words for a rank correlation, centralised so every screen says the same thing. */
enum class CorrelationStrength {
    NONE, WEAK, MODERATE, STRONG;

    companion object {
        fun of(rho: Double): CorrelationStrength = when {
            abs(rho) < 0.10 -> NONE
            abs(rho) < IntelligencePolicy.WEAK_RHO -> WEAK
            abs(rho) < 0.50 -> MODERATE
            else -> STRONG
        }
    }
}
