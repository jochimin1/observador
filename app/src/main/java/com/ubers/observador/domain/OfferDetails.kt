package com.ubers.observador.domain

import com.ubers.observador.data.db.OfferEntity
import com.ubers.observador.domain.models.UberOffer

/** Everything the detail panel shows for one offer, computed with the driver's current settings. */
data class OfferDetail(
    val offer: OfferEntity,
    val quality: OfferQuality,
    val breakdown: ScoreBreakdown
)

object OfferDetails {

    /**
     * Recomputes [offer] with [settings] (the same rule the dashboard list uses), so the panel's
     * score, fuel and profit always match the row the driver tapped.
     */
    fun of(
        offer: OfferEntity,
        settings: EngineSettings,
        cuts: QualityThresholds,
        engine: ProfitEngine = ProfitEngine()
    ): OfferDetail {
        val analysis = engine.analyze(
            UberOffer(offer.price, offer.pickupKm, offer.pickupMin, offer.tripKm, offer.tripMin, offer.detectedAt),
            settings
        )
        val current = offer.copy(
            totalKm = analysis.totalKm,
            totalMin = analysis.totalMin,
            rdpPerKm = analysis.rdpPerKm,
            rdpPerHour = analysis.rdpPerHour,
            fuelCost = analysis.fuelCost,
            netProfit = analysis.netProfit,
            score = analysis.score
        )
        return OfferDetail(
            offer = current,
            quality = cuts.classify(analysis.score),
            breakdown = engine.breakdown(analysis, settings)
        )
    }
}
