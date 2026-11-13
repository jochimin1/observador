package com.ubers.observador.domain.sectors

import com.ubers.observador.data.db.OfferEntity
import com.ubers.observador.domain.DayGroup

/** How many offers of one sector, by the address it was found in (1.36.0). */
data class SectorCount(val name: String, val pickup: Int, val dropoff: Int) {
    val total: Int get() = pickup + dropoff
}

/**
 * 1.36.0: the care sectors applied to stored offers. Computed from the addresses already saved
 * (DB 11) and the driver's current list: nothing new is stored, and an offer older than its address
 * columns simply has no sector.
 */
object SectorStats {

    fun matchOf(o: OfferEntity, sectors: List<CareSector>): SectorMatch =
        SectorMatcher.match(o.pickupAddress, o.dropoffAddress, sectors)

    fun isFlagged(o: OfferEntity, sectors: List<CareSector>): Boolean =
        sectors.isNotEmpty() && !matchOf(o, sectors).isEmpty

    /** The days with only their flagged offers; days left empty are dropped (headers keep the whole day). */
    fun keepFlagged(days: List<DayGroup>, sectors: List<CareSector>): List<DayGroup> =
        days.mapNotNull { day ->
            val kept = day.offers.filter { isFlagged(it, sectors) }
            if (kept.isEmpty()) null else day.copy(offers = kept)
        }

    fun flaggedCount(days: List<DayGroup>, sectors: List<CareSector>): Int =
        if (sectors.isEmpty()) 0 else days.sumOf { d -> d.offers.count { isFlagged(it, sectors) } }

    /**
     * Per sector, how many offers had it in the pickup and in the drop-off (an offer with the same
     * sector in both counts once in each). Sectors with no offers are left out; most frequent first.
     */
    fun counts(offers: List<OfferEntity>, sectors: List<CareSector>): List<SectorCount> =
        countsOf(offers.filterNot { it.excludedFromAnalysis }.map { it.pickupAddress to it.dropoffAddress }, sectors)

    /** [counts] from (pickup, drop-off) pairs, as read straight from the table. */
    fun countsOf(addresses: List<Pair<String?, String?>>, sectors: List<CareSector>): List<SectorCount> {
        if (sectors.isEmpty()) return emptyList()
        val pickup = HashMap<String, Int>()
        val dropoff = HashMap<String, Int>()
        addresses.forEach { (from, to) ->
            SectorMatcher.match(from, to, sectors).hits.forEach { h ->
                val bucket = if (h.place == OfferPlaceKind.PICKUP) pickup else dropoff
                bucket[h.sector.name] = (bucket[h.sector.name] ?: 0) + 1
            }
        }
        return sectors.map { SectorCount(it.name, pickup[it.name] ?: 0, dropoff[it.name] ?: 0) }
            .filter { it.total > 0 }
            .sortedWith(compareByDescending<SectorCount> { it.total }.thenBy { it.name })
    }

    /** The CSV cell: «Destino: Santa Cruz / Santo Domingo Norte», no commas so it never splits a column. */
    fun csvValue(o: OfferEntity, sectors: List<CareSector>): String {
        val m = matchOf(o, sectors)
        if (m.isEmpty) return ""
        return OfferPlaceKind.entries.mapNotNull { place ->
            val names = m.hits.filter { it.place == place }.map { it.sector.name }.distinct()
            if (names.isEmpty()) null else "${place.label}: ${names.joinToString(" / ")}"
        }.joinToString(" · ").replace(",", " ")
    }
}
