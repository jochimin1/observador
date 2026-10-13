package com.ubers.observador.domain

import com.ubers.observador.data.db.OfferEntity
import com.ubers.observador.domain.trips.Trip
import com.ubers.observador.domain.trips.TripStatus

/**
 * The home screen shows trips you actually did, not every offer seen (an offer may have been
 * taken by someone else or let go). Each completed trip keeps a copy of its offer (Fase 7B), so
 * it becomes one row with what that offer promised; the rest of the dashboard (re-scoring with
 * today's settings, summary, best hours, detail) works on these rows unchanged. Offers stay
 * stored: they feed "ofertas por hora en línea" and Análisis.
 *
 * Left out: cancelled or interrupted trips, and trips joined mid-way whose offer was not seen.
 */
object CompletedTrips {

    fun asOfferRows(trips: List<Trip>): List<OfferEntity> =
        trips.asSequence()
            .filter { it.status == TripStatus.COMPLETED }
            .mapNotNull { t ->
                val offer = t.offer ?: return@mapNotNull null
                OfferEntity(
                    // Unique and stable for a completed trip: the list keys and the detail use it
                    id = t.completedAt ?: t.updatedAt,
                    offerHash = t.relatedOfferHash ?: t.id,
                    detectedAt = offer.offerSeenAt,
                    price = offer.offeredAmount,
                    pickupKm = offer.pickupKm,
                    pickupMin = offer.pickupMinutes,
                    tripKm = offer.tripKm,
                    tripMin = offer.tripMinutes,
                    totalKm = offer.pickupKm + offer.tripKm,
                    totalMin = offer.pickupMinutes + offer.tripMinutes,
                    rdpPerKm = 0.0,
                    rdpPerHour = 0.0,
                    fuelCost = 0.0,
                    netProfit = 0.0,
                    score = offer.scoreAtDetection
                )
            }
            .distinctBy { it.id }
            .sortedByDescending { it.detectedAt }
            .toList()
}
