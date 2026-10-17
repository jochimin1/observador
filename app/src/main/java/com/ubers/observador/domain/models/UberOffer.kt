package com.ubers.observador.domain.models

data class UberOffer(
    val price: Double,
    val pickupKm: Double,
    val pickupMin: Int,
    val tripKm: Double,
    val tripMin: Int,
    val detectedAt: Long = System.currentTimeMillis(),
    /** As Uber wrote it on the card (Fase 8F); not part of the hash. */
    val pickupAddress: String? = null,
    val dropoffAddress: String? = null,
    /** Fase 8J: «Me interesa» or «Aceptar», as on the card; not part of the hash. */
    val cardAction: com.ubers.observador.domain.lifecycle.OfferCardAction? = null,
    /** Fase 8J: the modality above the price ("Moto") and the "Exclusivo" label; not part of the hash. */
    val product: String? = null,
    val exclusive: Boolean? = null
) {
    fun offerHash(): String =
        "%.2f-%.1f-%d-%.1f-%d".format(price, pickupKm, pickupMin, tripKm, tripMin)
}
