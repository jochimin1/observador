package com.ubers.observador.domain.sectors

import com.ubers.observador.domain.backup.MiniJson

/**
 * The care sectors as stored in DataStore (key `care_sectors`, 1.35.0):
 * `[{"n":"Engombe","w":"BOTH","l":"AVOID"}, …]`. Strict, like every restored setting: a list that
 * does not decode whole is rejected (null) and goes back to empty.
 */
object CareSectorCodec {

    fun encode(sectors: List<CareSector>): String =
        MiniJson.write(sectors.map { mapOf("n" to it.name, "w" to it.where.name, "l" to it.level.name) })

    fun decode(text: String?): List<CareSector>? {
        if (text.isNullOrBlank()) return emptyList()
        val list = MiniJson.parseOrNull(text) as? List<*> ?: return null
        if (list.size > CareSector.MAX_SECTORS) return null
        val out = ArrayList<CareSector>(list.size)
        for (item in list) {
            val m = item as? Map<*, *> ?: return null
            val name = (m["n"] as? String)?.let(::cleanName) ?: return null
            if (name.isEmpty() || name.length > CareSector.MAX_NAME) return null
            val where = (m["w"] as? String)?.let { w -> SectorWhere.entries.firstOrNull { it.name == w } } ?: return null
            val level = (m["l"] as? String)?.let { l -> SectorLevel.entries.firstOrNull { it.name == l } } ?: return null
            out += CareSector(name, where, level)
        }
        return out
    }

    /** What the driver typed, tidied: single spaces, no commas (they split addresses), no brackets. */
    fun cleanName(raw: String): String =
        raw.replace(Regex("[,()]"), " ").replace(Regex("\\s+"), " ").trim()

    /** Adds or replaces (same words, accents and case ignored) and keeps the list in the order added. */
    fun upsert(list: List<CareSector>, sector: CareSector): List<CareSector> {
        val key = SectorMatcher.words(sector.name)
        val i = list.indexOfFirst { SectorMatcher.words(it.name) == key }
        return if (i >= 0) list.toMutableList().also { it[i] = sector } else (list + sector).take(CareSector.MAX_SECTORS)
    }
}

/** What the floating card, the notification and the vibration say about a [SectorMatch] (1.35.0). */
data class SectorBanner(val text: String, val avoid: Boolean) {

    companion object {
        /**
         * «⚠ Destino: Cristo Rey» or «⛔ Evitar · Recogida: Hato Nuevo · Destino: Engombe». [CLOSE] counts
         * as «Evitar» here: closing only happens in the «Automático» mode (v1.37.0). [card] names the
         * Radar card when it is not the first one.
         */
        fun of(match: SectorMatch, card: Int? = null): SectorBanner? {
            if (match.isEmpty) return null
            val avoid = (match.level ?: SectorLevel.WARN) >= SectorLevel.AVOID
            val places = OfferPlaceKind.entries.mapNotNull { place ->
                val names = match.hits.filter { it.place == place }.map { it.sector.name }.distinct()
                if (names.isEmpty()) null else "${place.label}: ${names.joinToString(", ")}"
            }
            val text = buildString {
                append(if (avoid) "⛔ Evitar · " else "⚠ ")
                if (card != null && card > 1) append("Tarjeta $card · ")
                append(places.joinToString(" · "))
            }
            return SectorBanner(text, avoid)
        }
    }
}
