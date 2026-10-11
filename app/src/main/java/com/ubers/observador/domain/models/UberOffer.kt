package com.ubers.observador.domain.models

data class UberOffer(
    val price: Double,
    val pickupKm: Double,
    val pickupMin: Int,
    val tripKm: Double,
    val tripMin: Int,
    val detectedAt: Long = System.currentTimeMillis()
) {
    fun offerHash(): String =
        "%.2f-%.1f-%d-%.1f-%d".format(price, pickupKm, pickupMin, tripKm, tripMin)
}
