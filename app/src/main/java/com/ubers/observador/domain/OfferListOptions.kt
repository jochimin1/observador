package com.ubers.observador.domain

import com.ubers.observador.data.db.OfferEntity

/** Which offers the list shows. [quality] is null for all of them. */
enum class CategoryFilter(val label: String, val quality: OfferQuality?) {
    ALL("Todas", null),
    EXCELLENT("Excelentes", OfferQuality.EXCELLENT),
    GOOD("Buenas", OfferQuality.GOOD),
    BAD("Malas", OfferQuality.BAD)
}

/** How offers are ordered inside each working day. All orders put the best first. */
enum class OfferSort(val label: String) {
    TIME("Hora"),
    SCORE("Score"),
    PRICE("Precio"),
    PER_KM("RD$/km")
}

object OfferListOptions {

    /**
     * Keeps only the offers that pass [filter] and orders each day by [sort]. A day's count,
     * median and average still describe the whole day, so the header can say "3 de 12".
     * Days left with no offers are dropped.
     */
    fun apply(
        days: List<DayGroup>,
        filter: CategoryFilter,
        sort: OfferSort,
        cuts: QualityThresholds
    ): List<DayGroup> = days.mapNotNull { day ->
        val kept = if (filter.quality == null) day.offers
        else day.offers.filter { cuts.classify(it.score) == filter.quality }
        if (kept.isEmpty()) null else day.copy(offers = kept.sortedWith(comparatorFor(sort)))
    }

    /** Newest first breaks every tie, so equal offers keep a stable, predictable order. */
    private fun comparatorFor(sort: OfferSort): Comparator<OfferEntity> {
        val newestFirst = compareByDescending<OfferEntity> { it.detectedAt }
        return when (sort) {
            OfferSort.TIME -> newestFirst
            OfferSort.SCORE -> compareByDescending<OfferEntity> { it.score }.then(newestFirst)
            OfferSort.PRICE -> compareByDescending<OfferEntity> { it.price }.then(newestFirst)
            OfferSort.PER_KM -> compareByDescending<OfferEntity> { it.rdpPerKm }.then(newestFirst)
        }
    }

    /** How many offers each chip would show, from the period summary. */
    fun countFor(filter: CategoryFilter, summary: PeriodSummary): Int = when (filter) {
        CategoryFilter.ALL -> summary.count
        CategoryFilter.EXCELLENT -> summary.excellent
        CategoryFilter.GOOD -> summary.good
        CategoryFilter.BAD -> summary.bad
    }
}
