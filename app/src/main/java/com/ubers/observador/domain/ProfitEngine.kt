package com.ubers.observador.domain

import com.ubers.observador.domain.models.OfferAnalysis
import com.ubers.observador.domain.models.UberOffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The four 0-100 sub-scores behind an offer's final score. */
data class ScoreBreakdown(
    val km: Double,
    val hour: Double,
    val pickupKm: Double,
    val pickupMin: Double
) {
    val total: Int
        get() = (
            km * WEIGHT_KM_PCT + hour * WEIGHT_HOUR_PCT +
                pickupKm * WEIGHT_PICKUP_KM_PCT + pickupMin * WEIGHT_PICKUP_MIN_PCT
            ).div(100.0).roundToInt().coerceIn(0, 100)

    companion object {
        const val WEIGHT_KM_PCT = 35
        const val WEIGHT_HOUR_PCT = 30
        const val WEIGHT_PICKUP_KM_PCT = 20
        const val WEIGHT_PICKUP_MIN_PCT = 15
    }
}

class ProfitEngine {

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
        return ScoreBreakdown(subKm, subHour, subPickupKm, subPickupMin)
    }

    private companion object {
        const val MIN_DIVISOR = 0.01
    }
}
