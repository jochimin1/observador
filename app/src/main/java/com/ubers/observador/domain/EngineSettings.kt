package com.ubers.observador.domain

data class EngineSettings(
    /** Fuel cost per km actually used by the engine. Derived from the fuel fields when both are set. */
    val costPerKm: Double = 1.50,
    /** RD$ per gallon; 0 = not set. */
    val fuelPricePerGallon: Double = 0.0,
    /** Km per gallon of the vehicle; 0 = not set. */
    val kmPerGallon: Double = 0.0,
    val minRdpPerKm: Double = 6.0,
    val minRdpPerHour: Double = 300.0,
    val maxPickupKm: Double = 6.0,
    val maxPickupMin: Int = 12,
    /** Sub-score weights (Fase 7D); defaults are the original 35/30/20/15. */
    val weights: ScoreWeights = ScoreWeights.DEFAULT
)
