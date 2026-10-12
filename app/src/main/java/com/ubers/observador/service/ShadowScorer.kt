package com.ubers.observador.service

import android.util.Log
import com.ubers.observador.data.AppSettings
import com.ubers.observador.data.IntelligenceRepository
import com.ubers.observador.domain.OfferDecisionFeatures
import com.ubers.observador.domain.intelligence.ShadowScoring
import com.ubers.observador.domain.models.UberOffer
import com.ubers.observador.domain.money.Currencies
import com.ubers.observador.domain.money.MoneyAmount
import kotlinx.coroutines.flow.first

/**
 * Shadow mode (Fase 7D): when a candidate model is under test, each new offer is also scored
 * with it, silently, through the same engine. Nothing the driver sees changes: the overlay,
 * notifications and stored score all come from the active model. Only hash, scores and version
 * ids are stored.
 */
class ShadowScorer(private val settings: AppSettings, private val repository: IntelligenceRepository) {

    suspend fun score(offer: UberOffer, offerHash: String, nowMs: Long) {
        val candidateId = settings.shadowModelVersionId.first() ?: return
        val candidate = repository.version(candidateId) ?: return
        val active = repository.ensureCurrent(settings.profitSettings.first(), nowMs)
        val amount = MoneyAmount.fromLegacyDouble(offer.price, Currencies.DOP) ?: return
        val features = OfferDecisionFeatures(amount, offer.pickupKm, offer.pickupMin, offer.tripKm, offer.tripMin, offer.detectedAt)
        val stored = repository.saveShadow(ShadowScoring.evaluate(offerHash, features, active, candidate, nowMs))
        if (stored) Log.d(TAG, "Shadow scored an offer for candidate ${candidate.number}")
    }

    private companion object {
        const val TAG = "SHADOW"
    }
}
