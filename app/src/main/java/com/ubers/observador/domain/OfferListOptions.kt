package com.ubers.observador.domain

import com.ubers.observador.data.db.OfferEntity

/** Which offers the list shows. [quality] is null for all of them. */
enum class CategoryFilter(val label: String, val quality: OfferQuality?) {
    ALL("Todas", null),
    EXCELLENT("Excelentes", OfferQuality.EXCELLENT),
    GOOD("Buenas", OfferQuality.GOOD),
    BAD("Malas", OfferQuality.BAD)
}

/**
 * Fase 8H: which of the offers seen the list shows. "Tomados" are the offers the driver accepted
 * (they have a trip); "Ofertados" are the ones that were only offered, not taken.
 */
enum class TakenFilter(val label: String) {
    ALL("Todo"),
    OFFERED("Ofertados"),
    TAKEN("Tomados")
}

data class TakenCounts(val all: Int = 0, val offered: Int = 0, val taken: Int = 0) {
    fun of(filter: TakenFilter): Int = when (filter) {
        TakenFilter.ALL -> all
        TakenFilter.OFFERED -> offered
        TakenFilter.TAKEN -> taken
    }
}

/** Every offer seen, with the status of the trip it became (null = only offered). */
data class OfferLedger(
    val rows: List<OfferEntity>,
    val tripStatus: Map<Long, com.ubers.observador.domain.trips.TripStatus>,
    /** Offer row id -> the trip it became (Fase 8J, for the reports). */
    val tripOf: Map<Long, com.ubers.observador.domain.trips.Trip> = emptyMap()
) {

    val counts: TakenCounts
        get() = TakenCounts(all = rows.size, offered = rows.count { it.id !in tripStatus }, taken = tripStatus.size)

    /** Fase 8J: how many cards had each button (rows older than DB 12 have none and are not counted). */
    fun countOf(action: com.ubers.observador.domain.lifecycle.OfferCardAction): Int =
        rows.count { it.cardAction == action.storageKey }

    fun filtered(filter: TakenFilter): List<OfferEntity> = when (filter) {
        TakenFilter.ALL -> rows
        TakenFilter.OFFERED -> rows.filter { it.id !in tripStatus }
        TakenFilter.TAKEN -> rows.filter { it.id in tripStatus }
    }

    companion object {
        /**
         * All offers seen, plus the trips whose offer row is gone (deleted, or never stored) as
         * rows of their own, so a trip taken never disappears from the list. An offer is taken
         * when a trip points at its hash; with several trips for one hash the newest decides.
         */
        fun of(offers: List<OfferEntity>, trips: List<com.ubers.observador.domain.trips.Trip>): OfferLedger {
            val byHash = trips.filter { it.relatedOfferHash != null }
                .sortedBy { it.updatedAt }
                .associateBy { requireNotNull(it.relatedOfferHash) }
            val status = HashMap<Long, com.ubers.observador.domain.trips.TripStatus>()
            val tripOf = HashMap<Long, com.ubers.observador.domain.trips.Trip>()
            offers.forEach { o -> byHash[o.offerHash]?.let { status[o.id] = it.status; tripOf[o.id] = it } }
            val seen = offers.map { it.offerHash }.toSet()
            val orphan = trips.filter { it.relatedOfferHash == null || it.relatedOfferHash !in seen }
            val extra = orphan.mapNotNull { t -> CompletedTrips.offerRowOf(t)?.let { it to t } }
            val used = offers.map { it.id }.toMutableSet()
            val added = ArrayList<OfferEntity>()
            extra.forEach { (row, t) ->
                if (used.add(row.id)) {
                    added += row
                    status[row.id] = t.status
                    tripOf[row.id] = t
                }
            }
            return OfferLedger((offers + added).sortedByDescending { it.detectedAt }, status, tripOf)
        }
    }
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
