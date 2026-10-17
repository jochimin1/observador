package com.ubers.observador.data.db

import com.ubers.observador.domain.models.OfferAnalysis

/** Row for [OfferAnalysis]. [offerHash] can be overridden to tag rows, e.g. test offers. */
fun OfferAnalysis.toEntity(offerHash: String = offer.offerHash()) = OfferEntity(
    offerHash = offerHash,
    detectedAt = offer.detectedAt,
    price = offer.price,
    pickupKm = offer.pickupKm,
    pickupMin = offer.pickupMin,
    tripKm = offer.tripKm,
    tripMin = offer.tripMin,
    totalKm = totalKm,
    totalMin = totalMin,
    rdpPerKm = rdpPerKm,
    rdpPerHour = rdpPerHour,
    fuelCost = fuelCost,
    netProfit = netProfit,
    score = score,
    pickupAddress = offer.pickupAddress,
    dropoffAddress = offer.dropoffAddress,
    cardAction = offer.cardAction?.storageKey,
    product = offer.product,
    exclusive = offer.exclusive
)

/** Fase 8J: the card's button, read from [OfferEntity.cardAction] (kept out of the entity so Room never sees it). */
val OfferEntity.action: com.ubers.observador.domain.lifecycle.OfferCardAction?
    get() = com.ubers.observador.domain.lifecycle.OfferCardAction.fromStorage(cardAction)
