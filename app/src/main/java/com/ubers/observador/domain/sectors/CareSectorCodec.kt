package com.ubers.observador.domain.sectors

import com.ubers.observador.domain.backup.MiniJson

/**
 * The care sectors as stored in DataStore (key `care_sectors`, 1.35.0):
 * `[{"n":"Engombe","w":"BOTH","l":"AVOID"}, …]`. Strict, like every restored setting: a list that
 * does not decode whole is rejected (null) and goes back to empty.
 */
object CareSectorCodec {

    /**
     * 1.47.0 adds optional keys, written only when used, so a list without them is byte for byte the
     * 1.35.0 format: `a` aliases, `x` exceptions, `d` ISO days, `f`/`t` minutes, `p` modality and
     * `e:false` for a disabled sector.
     */
    fun encode(sectors: List<CareSector>): String =
        MiniJson.write(sectors.map { s ->
            val m = linkedMapOf<String, Any?>("n" to s.name, "w" to s.where.name, "l" to s.level.name)
            if (s.aliases.isNotEmpty()) m["a"] = s.aliases
            if (s.exceptions.isNotEmpty()) m["x"] = s.exceptions
            s.schedule?.let { m["d"] = it.days.sorted().map { d -> d.toLong() }; m["f"] = it.fromMin.toLong(); m["t"] = it.toMin.toLong() }
            s.product?.let { m["p"] = it }
            if (!s.enabled) m["e"] = false
            m
        })

    private fun names(v: Any?, max: Int): List<String>? {
        if (v == null) return emptyList()
        val list = v as? List<*> ?: return null
        if (list.size > max) return null
        return list.map { item ->
            val n = (item as? String)?.let(::cleanName) ?: return null
            if (n.isEmpty() || n.length > CareSector.MAX_NAME) return null
            n
        }
    }

    private fun minute(v: Any?): Int? = (v as? Long)?.takeIf { it in 0L..1439L }?.toInt()

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
            val aliases = names(m["a"], CareSector.MAX_ALIASES) ?: return null
            val exceptions = names(m["x"], CareSector.MAX_EXCEPTIONS) ?: return null
            val schedule = if (m.containsKey("d") || m.containsKey("f") || m.containsKey("t")) {
                val days = (m["d"] as? List<*>)?.map { d -> (d as? Long)?.takeIf { it in 1L..7L }?.toInt() ?: return null } ?: return null
                SectorSchedule(days.toSet(), minute(m["f"]) ?: return null, minute(m["t"]) ?: return null)
            } else null
            val product = if (m.containsKey("p")) (m["p"] as? String)?.trim()?.takeIf { it.isNotEmpty() && it.length <= 30 } ?: return null else null
            val enabled = if (m.containsKey("e")) m["e"] as? Boolean ?: return null else true
            out += CareSector(name, where, level, aliases, exceptions, schedule, product, enabled)
        }
        return out
    }

    /** «Alcarrizos; Los Alcarrizos» → each one tidied, empty and repeated ones dropped. */
    fun splitNames(text: String, max: Int): List<String> =
        text.split(';').map(::cleanName).map { it.take(CareSector.MAX_NAME) }.filter { it.isNotEmpty() }
            .distinctBy { SectorMatcher.words(it) }.take(max)

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
            val text = buildString {
                append(if (avoid) "⛔ Evitar · " else "⚠ ")
                if (card != null && card > 1) append("Tarjeta $card · ")
                append(places(match))
            }
            return SectorBanner(text, avoid)
        }

        /** «Recogida: Hato Nuevo · Destino: Engombe». */
        fun places(match: SectorMatch): String =
            OfferPlaceKind.entries.mapNotNull { place ->
                val names = match.hits.filter { it.place == place }.map { it.sector.name }.distinct()
                if (names.isEmpty()) null else "${place.label}: ${names.joinToString(", ")}"
            }.joinToString(" · ")
    }
}
