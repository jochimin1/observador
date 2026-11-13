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
         * rows of their own. New logical ids refer to one appearance. A legacy numerical hash
         * needs the snapshot's exact detection time; ambiguous links are left unresolved.
         */
        fun of(offers: List<OfferEntity>, trips: List<com.ubers.observador.domain.trips.Trip>): OfferLedger {
            val byHash = offers.groupBy { it.offerHash }
            val candidates = trips.distinctBy { it.id }.mapNotNull { t ->
                val hash = t.relatedOfferHash ?: return@mapNotNull null
                val matching = byHash[hash].orEmpty().filter { o ->
                    com.ubers.observador.domain.models.UberOffer.isAppearanceId(hash) ||
                        t.offer?.offerSeenAt == o.detectedAt
                }
                matching.singleOrNull()?.let { it.id to t }
            }.groupBy({ it.first }, { it.second })
            val status = HashMap<Long, com.ubers.observador.domain.trips.TripStatus>()
            val tripOf = HashMap<Long, com.ubers.observador.domain.trips.Trip>()
            candidates.forEach { (id, matches) -> matches.singleOrNull()?.let { status[id] = it.status; tripOf[id] = it } }
            val linked = tripOf.values.map { it.id }.toSet()
            val orphan = trips.distinctBy { it.id }.filter { it.id !in linked }.sortedBy { it.id }
            val used = offers.map { it.id }.toMutableSet()
            val added = ArrayList<OfferEntity>()
            orphan.forEach { t ->
                val row = CompletedTrips.offerRowOf(t) ?: return@forEach
                // Synthetic rows occupy a negative namespace; equal completion times never drop a trip.
                var id = t.id.hashCode().toLong() or Long.MIN_VALUE
                while (!used.add(id)) id = if (id == Long.MIN_VALUE) -1L else id - 1L
                added += row.copy(id = id)
                status[id] = t.status
                tripOf[id] = t
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
