package com.ubers.observador.domain

import com.ubers.observador.data.db.OfferEntity
import com.ubers.observador.domain.trips.Trip

/** Exclusion changes analytical inputs, never the stored offer, financial ledger or trip history. */
object ReadingEligibility {
    fun offers(rows: List<OfferEntity>): List<OfferEntity> = rows.filterNot { it.excludedFromAnalysis }

    fun excludedTripIds(rows: List<OfferEntity>, trips: List<Trip>): Set<String> {
        val excluded = rows.filter { it.excludedFromAnalysis }.map { it.id }.toSet()
        return OfferLedger.of(rows, trips).tripOf.filterKeys { it in excluded }.values.map { it.id }.toSet()
    }
}
