package com.ubers.observador.domain.scoring

import com.ubers.observador.domain.OfferDecisionFeatures
import com.ubers.observador.domain.OfferQuality
import com.ubers.observador.domain.ProfitEngine
import com.ubers.observador.domain.ProfitSettingsSnapshot
import com.ubers.observador.domain.ScoreBreakdown
import com.ubers.observador.domain.operating.OperatingCostModel
import com.ubers.observador.domain.operating.OperatingCostParts
import com.ubers.observador.domain.operating.OperatingCosts
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * 1.44.0: how long the driver usually waits at the pickup point («Llegué» → viaje iniciado), from
 * their own history. Only built with [MIN_SAMPLES] real waits or more; otherwise there is no
 * estimate and the score says so instead of inventing one.
 */
data class WaitEstimate(val samples: Int, val lowMs: Long, val typicalMs: Long, val highMs: Long) {
    companion object {
        const val MIN_SAMPLES = 10
        /** A wait longer than this is a reading error or a trip left open, not a wait. */
        const val MAX_SAMPLE_MS = 30 * 60_000L

        /** p25 / median / p75 of the valid waits; null below [MIN_SAMPLES]. */
        fun of(waitsMs: List<Long>): WaitEstimate? {
            val valid = waitsMs.filter { it in 0..MAX_SAMPLE_MS }.sorted()
            if (valid.size < MIN_SAMPLES) return null
            return WaitEstimate(valid.size, percentile(valid, 25), percentile(valid, 50), percentile(valid, 75))
        }

        /** Nearest-rank percentile of an already sorted, non-empty list: always one of the samples. */
        internal fun percentile(sorted: List<Long>, p: Int): Long {
            val rank = ((p / 100.0) * sorted.size).let { kotlin.math.ceil(it).toInt() }.coerceIn(1, sorted.size)
            return sorted[rank - 1]
        }
    }
}

/** What V2 may know when an offer appears, besides the offer itself. Nothing about the outcome. */
data class ScoreV2Inputs(
    /** Every operating cost model (1.42.0); the one in force when the offer appeared is used. */
    val costModels: List<OperatingCostModel> = emptyList(),
    val pickupWait: WaitEstimate? = null
) {
    companion object { val NONE = ScoreV2Inputs() }
}

/** Plain-language reasons behind a V2 score, most important first. */
enum class ScoreFactor(val label: String) {
    LOSS("No cubre lo que te cuesta hacerlo"),
    BELOW_HOUR_TARGET("Paga por debajo de tu objetivo por hora"),
    BELOW_KM_TARGET("Paga por debajo de tu objetivo por km"),
    LONG_PICKUP("Recogida larga"),
    HIGH_COST("Costo elevado"),
    LONG_WAIT("Sueles esperar bastante en la recogida");
}

/** What is missing, so the result is read with the right confidence. */
enum class ScoreCoverage(val label: String) {
    NO_COST_MODEL("Sin modelo de costos vigente en esa fecha: se usa el puntaje V1"),
    NO_WAIT_HISTORY("Sin historial suficiente de esperas en la recogida: no se suman al tiempo"),
}

data class ScoreV2Result(
    /** [ProfitEngineV2.ALGORITHM_VERSION], or V1's when there was no cost model to use. */
    val algorithm: String,
    val score: Int,
    /** Score range from the wait history (p75 wait → [low], p25 → [high]); equal to [score] without it. */
    val low: Int,
    val high: Int,
    val category: OfferQuality,
    val offeredMinor: Long,
    /** Variable cost of the whole distance (recogida + viaje): fuel, maintenance, depreciation. */
    val cost: OperatingCostParts?,
    val netMinor: Long?,
    val netPerKm: Double?,
    /** Net per hour with the typical wait (or none). */
    val netPerHour: Double?,
    val breakdown: ScoreBreakdown?,
    val factors: List<ScoreFactor>,
    val coverage: List<ScoreCoverage>,
    val costRevision: Int?,
    val waitTypicalMs: Long?
) {
    val usedV1: Boolean get() = algorithm != ProfitEngineV2.ALGORITHM_VERSION
    val isRange: Boolean get() = low != high
}

/**
 * 1.44.0: the second scoring formula. Same four parts and weights as V1 (so the driver's settings
 * mean the same things), but the km and hour parts are judged on what is LEFT after the variable
 * cost of the whole distance, and the hour part includes the usual wait at the pickup when the
 * history supports it. Fixed costs stay out on purpose: they do not depend on taking this offer.
 *
 * Deterministic: the same offer, settings and inputs always give the same result. V1 is untouched
 * and remains the score the driver sees; V2 runs in shadow until it proves better.
 */
object ProfitEngineV2 {

    const val ALGORITHM_VERSION = "PROFIT_ENGINE_V2"
    /** Cost from this share of the price is "elevado". */
    const val HIGH_COST_SHARE_PCT = 30
    /** A typical wait from this long gets mentioned. */
    const val LONG_WAIT_MS = 5 * 60_000L
    private const val MIN_DIVISOR = 0.01

    fun evaluate(features: OfferDecisionFeatures, settings: ProfitSettingsSnapshot, inputs: ScoreV2Inputs): ScoreV2Result {
        val offered = features.offeredAmount.minorUnits
        val model = OperatingCosts.modelAt(inputs.costModels, features.detectedAtMs)
        if (model == null) {
            val v1 = ProfitEngine().evaluate(features, settings)
            return ScoreV2Result(
                algorithm = ProfitEngine.ALGORITHM_VERSION, score = v1.score, low = v1.score, high = v1.score,
                category = v1.category, offeredMinor = offered, cost = null, netMinor = null, netPerKm = null,
                netPerHour = null, breakdown = null, factors = pickupFactors(features, settings),
                coverage = listOf(ScoreCoverage.NO_COST_MODEL), costRevision = null, waitTypicalMs = null
            )
        }
        val totalKm = features.pickupKm + features.tripKm
        val distanceMm = (totalKm * 1_000_000).roundToLong().coerceAtLeast(0)
        val parts = OperatingCosts.variable(model, distanceMm)
        // Only fuel, maintenance and depreciation: they grow with the km of this offer
        val cost = parts.copy(fixed = 0, loose = 0)
        val net = offered - cost.total
        val wait = inputs.pickupWait
        val engagedMin = features.estimatedEngagedMinutes.toDouble()

        fun perHour(waitMs: Long): Double? {
            val minutes = engagedMin + waitMs / 60_000.0
            return if (minutes > 0) net / 100.0 / (minutes / 60.0) else null
        }
        val netPerKm = if (totalKm > 0) net / 100.0 / totalKm else null
        val typicalHour = perHour(wait?.typicalMs ?: 0)

        fun breakdown(hourly: Double?) = ScoreBreakdown(
            km = sub(netPerKm, settings.minRdpPerKm),
            hour = sub(hourly, settings.minRdpPerHour),
            pickupKm = max(0.0, (1.0 - features.pickupKm / max(settings.maxPickupKm, MIN_DIVISOR)) * 100.0),
            pickupMin = max(0.0, (1.0 - features.pickupMinutes.toDouble() / max(settings.maxPickupMinutes.toDouble(), MIN_DIVISOR)) * 100.0),
            weights = settings.weights
        )
        val main = breakdown(typicalHour)
        val score = main.total
        val low = wait?.let { breakdown(perHour(it.highMs)).total } ?: score
        val high = wait?.let { breakdown(perHour(it.lowMs)).total } ?: score

        val factors = buildList {
            if (net <= 0) add(ScoreFactor.LOSS)
            if (net > 0 && typicalHour != null && typicalHour < settings.minRdpPerHour) add(ScoreFactor.BELOW_HOUR_TARGET)
            if (net > 0 && netPerKm != null && netPerKm < settings.minRdpPerKm) add(ScoreFactor.BELOW_KM_TARGET)
            addAll(pickupFactors(features, settings))
            if (net > 0 && offered > 0 && cost.total * 100 >= offered * HIGH_COST_SHARE_PCT) add(ScoreFactor.HIGH_COST)
            if (wait != null && wait.typicalMs >= LONG_WAIT_MS) add(ScoreFactor.LONG_WAIT)
        }
        return ScoreV2Result(
            algorithm = ALGORITHM_VERSION, score = score, low = min(low, score), high = max(high, score),
            category = settings.thresholds.classify(score), offeredMinor = offered, cost = cost, netMinor = net,
            netPerKm = netPerKm, netPerHour = typicalHour, breakdown = main, factors = factors,
            coverage = if (wait == null) listOf(ScoreCoverage.NO_WAIT_HISTORY) else emptyList(),
            costRevision = model.revision, waitTypicalMs = wait?.typicalMs
        )
    }

    /** At the target -> 50, at twice the target -> 100; a loss or an unknown value -> 0. */
    private fun sub(value: Double?, target: Double): Double =
        if (value == null || value <= 0) 0.0 else min(value / max(target, MIN_DIVISOR) * 50.0, 100.0)

    private fun pickupFactors(f: OfferDecisionFeatures, s: ProfitSettingsSnapshot) =
        if (f.pickupKm > s.maxPickupKm || f.pickupMinutes > s.maxPickupMinutes) listOf(ScoreFactor.LONG_PICKUP) else emptyList()
}

/**
 * Picks the formula a model version names. V1 needs nothing else; V2 needs a cost model in force
 * (null otherwise, so a shadow test never stores a V1 score under V2's name). An unknown
 * algorithm (a newer backup) is never scored with a wrong formula.
 */
object ScoringAlgorithms {
    fun score(algorithm: String, features: OfferDecisionFeatures, settings: ProfitSettingsSnapshot, inputs: ScoreV2Inputs): Int? = when (algorithm) {
        ProfitEngine.ALGORITHM_VERSION -> ProfitEngine().evaluate(features, settings).score
        ProfitEngineV2.ALGORITHM_VERSION -> ProfitEngineV2.evaluate(features, settings, inputs).takeIf { !it.usedV1 }?.score
        else -> null
    }

    fun label(algorithm: String): String = when (algorithm) {
        ProfitEngine.ALGORITHM_VERSION -> "V1"
        ProfitEngineV2.ALGORITHM_VERSION -> "V2"
        else -> algorithm
    }
}
