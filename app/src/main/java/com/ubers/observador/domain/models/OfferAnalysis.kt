package com.ubers.observador.domain.models

data class OfferAnalysis(
    val offer: UberOffer,
    val totalKm: Double,
    val totalMin: Int,
    val rdpPerKm: Double,
    val rdpPerHour: Double,
    val fuelCost: Double,
    val netProfit: Double,
    val score: Int
)
