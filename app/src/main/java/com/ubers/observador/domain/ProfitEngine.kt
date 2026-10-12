package com.ubers.observador.domain

import com.ubers.observador.domain.models.OfferAnalysis
import com.ubers.observador.domain.models.UberOffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * How much each sub-score counts, in whole percent (sum 100). Part of the model's settings since
 * Fase 7D, so a calibrated candidate can change them through the same engine; the defaults are
 * the weights every score used before (35/30/20/15), so nothing changes until the user applies one.
 */
data class ScoreWeights(
    val kmPct: Int = 35,
    val hourPct: Int = 30,
    val pickupKmPct: Int = 20,
    val pickupMinPct: Int = 15
) {
    val isValid: Boolean
        get() = listOf(kmPct, hourPct, pickupKmPct, pickupMinPct).all { it in 0..100 } &&
            kmPct + hourPct + pickupKmPct + pickupMinPct == 100

    companion object {
        val DEFAULT = ScoreWeights()

        /** Stored values that do not add up (or are missing) fall back to [DEFAULT], never to a broken score. */
        fun of(kmPct: Int?, hourPct: Int?, pickupKmPct: Int?, pickupMinPct: Int?): ScoreWeights {
            if (kmPct == null || hourPct == null || pickupKmPct == null || pickupMinPct == null) return DEFAULT
            return ScoreWeights(kmPct, hourPct, pickupKmPct, pickupMinPct).takeIf { it.isValid } ?: DEFAULT
        }
    }
}

/** The four 0-100 sub-scores behind an offer's final score. */
data class ScoreBreakdown(
    val km: Double,
    val hour: Double,
    val pickupKm: Double,
    val pickupMin: Double,
    val weights: ScoreWeights = ScoreWeights.DEFAULT
) {
    val total: Int
        get() = (
            km * weights.kmPct + hour * weights.hourPct +
                pickupKm * weights.pickupKmPct + pickupMin * weights.pickupMinPct
            ).div(100.0).roundToInt().coerceIn(0, 100)

    companion object {
        // The original weights (algorithm PROFIT_ENGINE_V1 defaults); the active ones live in EngineSettings.weights
        const val WEIGHT_KM_PCT = 35
        const val WEIGHT_HOUR_PCT = 30
        const val WEIGHT_PICKUP_KM_PCT = 20
        const val WEIGHT_PICKUP_MIN_PCT = 15
    }
}

/**
 * The one scoring formula. The active model and any candidate go through this same code: only
 * their settings differ (Fase 7D rule: no second engine). If the formula itself ever changes,
 * [ALGORITHM_VERSION] changes too, so old scores are never compared as if they were the same thing.
 */
class ProfitEngine {

    /**
     * Scores an offer from what was known when it appeared, and nothing else: the signature takes
     * no trip, earnings or real times, so a decision model cannot see the future.
     */
    fun evaluate(features: OfferDecisionFeatures, settings: ProfitSettingsSnapshot): ProfitEvaluation {
        val analysis = analyze(features.toOffer(), settings.toEngineSettings())
        return ProfitEvaluation(
            score = analysis.score,
            category = settings.thresholds.classify(analysis.score),
            predictedRdpPerHour = analysis.rdpPerHour,
            predictedRdpPerKm = analysis.rdpPerKm,
            estimatedMinutes = analysis.totalMin
        )
    }

    fun analyze(offer: UberOffer, settings: EngineSettings): OfferAnalysis {
        val totalKm = offer.pickupKm + offer.tripKm
        val totalMin = offer.pickupMin + offer.tripMin
        val rdpPerKm = if (totalKm > 0) offer.price / totalKm else 0.0
        val rdpPerHour = if (totalMin > 0) offer.price / (totalMin / 60.0) else 0.0
        val fuelCost = settings.costPerKm * totalKm
        val netProfit = offer.price - fuelCost
        val score = computeBreakdown(offer, rdpPerKm, rdpPerHour, settings).total

        return OfferAnalysis(
            offer = offer,
            totalKm = totalKm,
            totalMin = totalMin,
            rdpPerKm = rdpPerKm,
            rdpPerHour = rdpPerHour,
            fuelCost = fuelCost,
            netProfit = netProfit,
            score = score
        )
    }

    /** Sub-scores of an already analysed offer, for showing how the score was built. */
    fun breakdown(analysis: OfferAnalysis, settings: EngineSettings): ScoreBreakdown =
        computeBreakdown(analysis.offer, analysis.rdpPerKm, analysis.rdpPerHour, settings)

    private fun computeBreakdown(
        offer: UberOffer,
        rdpPerKm: Double,
        rdpPerHour: Double,
        settings: EngineSettings
    ): ScoreBreakdown {
        // Each sub-score 0-100 (higher = better)
        // At exactly the minimum threshold → 50. At 2x minimum → 100.
        // Thresholds are validated in Settings; the floors only protect values saved by older versions.
        val subKm = min(rdpPerKm / max(settings.minRdpPerKm, MIN_DIVISOR) * 50.0, 100.0)
        val subHour = min(rdpPerHour / max(settings.minRdpPerHour, MIN_DIVISOR) * 50.0, 100.0)
        val subPickupKm = max(0.0, (1.0 - offer.pickupKm / max(settings.maxPickupKm, MIN_DIVISOR)) * 100.0)
        val subPickupMin = max(
            0.0,
            (1.0 - offer.pickupMin.toDouble() / max(settings.maxPickupMin.toDouble(), MIN_DIVISOR)) * 100.0
        )
        return ScoreBreakdown(subKm, subHour, subPickupKm, subPickupMin, settings.weights)
    }

    companion object {
        /** Identifies the formula (not the settings). Bump only if computeBreakdown changes. */
        const val ALGORITHM_VERSION = "PROFIT_ENGINE_V1"
        private const val MIN_DIVISOR = 0.01
    }
}
