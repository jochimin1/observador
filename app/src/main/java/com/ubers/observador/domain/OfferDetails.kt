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

    /**
     * 1.44.0: the same offer judged by V2 with the driver's current settings and the cost model
     * in force on the offer's day. Null when the stored price cannot be read as money.
     */
    fun scoreV2(
        offer: OfferEntity,
        settings: EngineSettings,
        cuts: QualityThresholds,
        inputs: com.ubers.observador.domain.scoring.ScoreV2Inputs
    ): com.ubers.observador.domain.scoring.ScoreV2Result? {
        val amount = com.ubers.observador.domain.money.MoneyAmount.fromLegacyDouble(
            offer.price, com.ubers.observador.domain.money.Currencies.DOP
        ) ?: return null
        val features = OfferDecisionFeatures(amount, offer.pickupKm, offer.pickupMin, offer.tripKm, offer.tripMin, offer.detectedAt)
        return com.ubers.observador.domain.scoring.ProfitEngineV2.evaluate(features, ProfitSettingsSnapshot.of(settings, cuts), inputs)
    }
}
