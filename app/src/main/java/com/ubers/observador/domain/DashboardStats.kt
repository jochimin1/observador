package com.ubers.observador.domain

import com.ubers.observador.data.db.DaySummary
import com.ubers.observador.data.db.OfferEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Offers of one local day. Count and averages cover the whole day, not only the loaded [offers]. */
data class DayGroup(
    val date: LocalDate,
    val count: Int,
    val avgRdpPerKm: Double,
    val avgScore: Double,
    val offers: List<OfferEntity>
)

/** Calculations behind the dashboard, free of Android so they can be unit tested. */
object DashboardStats {

    /**
     * Groups [offers] (newest first) by local day. A day's count and averages come from its
     * [summaries] row when there is one, so a day cut short by the list limit still shows its
     * real totals.
     */
    fun groupByDay(
        offers: List<OfferEntity>,
        summaries: List<DaySummary>,
        zone: ZoneId = ZoneId.systemDefault()
    ): List<DayGroup> {
        val summaryByDay = summaries.associateBy { it.day }
        // Offers arrive newest first, so groups keep that order
        return offers
            .groupBy { Instant.ofEpochMilli(it.detectedAt).atZone(zone).toLocalDate() }
            .map { (date, dayOffers) ->
                // LocalDate.toString() is yyyy-MM-dd, same format as DaySummary.day
                val summary = summaryByDay[date.toString()]
                DayGroup(
                    date = date,
                    count = summary?.count ?: dayOffers.size,
                    avgRdpPerKm = summary?.avgRdpPerKm ?: dayOffers.map { it.rdpPerKm }.average(),
                    avgScore = summary?.avgScore ?: dayOffers.map { it.score }.average(),
                    offers = dayOffers
                )
            }
    }
}
