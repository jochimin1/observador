package com.ubers.observador.domain.sectors

import com.ubers.observador.domain.lifecycle.TextNormalizer

/**
 * Finds the driver's care sectors in the two addresses of an offer card (1.34.0). Uber writes
 * «street, sector, municipality» separated by commas: «C. Bernabe Boza 2, Santa Cruz, Santo Domingo
 * Norte», sometimes without the municipality («Calle Antena, Hato Nuevo»).
 *
 * Rules (checked against 20 real cards of 11/11/2026):
 *  1. The address is split by commas; what is between brackets is one more part.
 *  2. Parts and sectors are compared normalized: no accents, lower case, single spaces.
 *  3. A sector matches when its words appear in a row and whole inside one part, so «Santo Domingo
 *     Este» never matches «Santo Domingo Oeste» and «Santo Domingo Norte» never matches
 *     «Universidad Católica Santo Domingo».
 *  4. The first part is skipped when it is a street (starts with C., Calle, Av., Avenida, Carr., Cta.,
 *     P.º, Autopista… or holds a number) and there are more parts: «Av. Abraham Lincoln» never matches
 *     a sector called «Lincoln», but «Alameda, Santo Domingo Oeste» is read whole.
 */
object SectorMatcher {

    private val WORD_SPLIT = Regex("[^a-z0-9]+")
    private val BRACKETS = Regex("""\(([^)]*)\)""")
    private val DIGIT = Regex("\\d")

    /** First words that make a part a street name (normalized, without the dot). */
    private val STREET_WORDS = setOf(
        "c", "calle", "av", "ave", "avenida", "carr", "carretera", "cta", "cuesta", "p", "paseo", "autopista",
        "aut", "prol", "prolongacion", "pasaje", "callejon", "cl", "calle", "res", "manzana", "mz", "km"
    )

    fun words(text: String): List<String> =
        TextNormalizer.normalize(text).split(WORD_SPLIT).filter { it.isNotEmpty() }

    /** The parts of [address] as Uber wrote them, the street skipped (rules 1 and 4). */
    fun displayParts(address: String?): List<String> {
        if (address.isNullOrBlank()) return emptyList()
        val parts = mutableListOf<String>()
        address.split(',').forEach { raw ->
            val inside = BRACKETS.findAll(raw).map { it.groupValues[1].trim() }.toList()
            val outside = BRACKETS.replace(raw, " ").replace(Regex("\\s+"), " ").trim()
            if (outside.isNotEmpty()) parts += outside
            inside.filter { it.isNotEmpty() }.forEach { parts += it }
        }
        if (parts.size > 1 && isStreet(parts.first())) parts.removeAt(0)
        return parts
    }

    fun isStreet(part: String): Boolean {
        if (DIGIT.containsMatchIn(part)) return true
        val first = words(part).firstOrNull() ?: return false
        return first in STREET_WORDS
    }

    /** True when [sector]'s words appear in a row and whole in one part of [address] (rule 3). */
    fun matches(address: String?, sector: String): Boolean {
        val needle = words(sector)
        if (needle.isEmpty()) return false
        return displayParts(address).any { part -> containsRun(words(part), needle) }
    }

    private fun containsRun(hay: List<String>, needle: List<String>): Boolean {
        if (needle.size > hay.size) return false
        for (i in 0..hay.size - needle.size) {
            if (needle.indices.all { hay[i + it] == needle[it] }) return true
        }
        return false
    }

    /** The name or alias of [sector] found in [address], or null (rule 3, any name counts). */
    fun nameFound(address: String?, sector: CareSector): String? = sector.names.firstOrNull { matches(address, it) }

    /** The exception of [sector] found anywhere in [address], or null: it cancels the sector (1.47.0). */
    fun exceptionFound(address: String?, sector: CareSector): String? = sector.exceptions.firstOrNull { matches(address, it) }

    /** Whether [sector] is in [address]: some name found and no exception found. */
    fun matchesSector(address: String?, sector: CareSector): Boolean =
        nameFound(address, sector) != null && exceptionFound(address, sector) == null

    /**
     * Every sector of [sectors] found in the pickup and the drop-off, each address checked only where
     * the sector says. 1.47.0: disabled sectors never match; with a [context] the schedule and the
     * modality of each sector apply too.
     */
    fun match(pickup: String?, dropoff: String?, sectors: List<CareSector>, context: SectorContext? = null): SectorMatch {
        if (sectors.isEmpty()) return SectorMatch.NONE
        val hits = mutableListOf<SectorHit>()
        sectors.forEach { s ->
            if (!s.enabled || !s.appliesTo(context)) return@forEach
            if (s.where.covers(OfferPlaceKind.PICKUP) && matchesSector(pickup, s)) hits += SectorHit(s, OfferPlaceKind.PICKUP)
            if (s.where.covers(OfferPlaceKind.DROPOFF) && matchesSector(dropoff, s)) hits += SectorHit(s, OfferPlaceKind.DROPOFF)
        }
        return SectorMatch(hits)
    }

    /** Why one sector did or did not match one address (Ajustes › Probar reglas, 1.47.0). */
    sealed interface Verdict {
        data class Matched(val name: String, val part: String) : Verdict
        data class Excepted(val name: String, val exception: String) : Verdict
        data object Disabled : Verdict
        data object OtherPlace : Verdict
        data class OutOfSchedule(val schedule: SectorSchedule) : Verdict
        data class OtherProduct(val product: String) : Verdict
        data object NotFound : Verdict
    }

    data class Explanation(val sector: CareSector, val verdict: Verdict) {
        val warns: Boolean get() = verdict is Verdict.Matched
    }

    /** The part of [address] in which [name] was found, as Uber wrote it. */
    private fun partOf(address: String?, name: String): String? {
        val needle = words(name)
        return displayParts(address).firstOrNull { containsRun(words(it), needle) }
    }

    /**
     * Every sector checked against [address] read as [place], with the reason of each result; the
     * ones that say something (found, cancelled, out of schedule…) first. The order of the checks is
     * the one [match] uses, so the tester never says «avisa» when the card would not.
     */
    fun explain(address: String?, place: OfferPlaceKind, sectors: List<CareSector>, context: SectorContext?): List<Explanation> =
        sectors.map { s ->
            val found = nameFound(address, s)
            val verdict = when {
                found == null -> Verdict.NotFound
                !s.where.covers(place) -> Verdict.OtherPlace
                exceptionFound(address, s) != null -> Verdict.Excepted(found, exceptionFound(address, s)!!)
                !s.enabled -> Verdict.Disabled
                context != null && s.schedule != null &&
                    !s.schedule.isActive(java.time.Instant.ofEpochMilli(context.atMs).atZone(context.zone)) -> Verdict.OutOfSchedule(s.schedule)
                !s.appliesTo(context) -> Verdict.OtherProduct(s.product.orEmpty())
                else -> Verdict.Matched(found, partOf(address, found) ?: found)
            }
            Explanation(s, verdict)
        }.sortedBy { if (it.verdict is Verdict.NotFound) 1 else 0 }

    /**
     * Sectors seen in past addresses, most frequent first, as Uber wrote them: the suggestions of
     * Ajustes › Sectores de cuidado. Municipalities and sectors both count; streets do not.
     */
    fun suggestions(addresses: List<String?>, already: List<CareSector>, limit: Int = 12): List<String> {
        val taken = already.flatMap { it.names }.map { words(it) }.toSet()
        val counts = LinkedHashMap<List<String>, Pair<String, Int>>()
        addresses.forEach { a ->
            displayParts(a).filterNot { isStreet(it) }.forEach { part ->
                val key = words(part)
                if (key.isEmpty() || key in taken) return@forEach
                val prev = counts[key]
                counts[key] = (prev?.first ?: part) to ((prev?.second ?: 0) + 1)
            }
        }
        return counts.values.sortedByDescending { it.second }.take(limit).map { it.first }
    }
}
