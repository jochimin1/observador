package com.ubers.observador.data.db

/** Per-day aggregate. [day] is the local date as yyyy-MM-dd. */
data class DaySummary(
    val day: String,
    val count: Int,
    val avgRdpPerKm: Double,
    val avgScore: Double
)
