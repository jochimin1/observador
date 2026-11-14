package com.ubers.observador.domain.sectors

/** Which address of the offer a sector is looked for in. */
enum class SectorWhere(val label: String) {
    PICKUP("Recogida"), DROPOFF("Destino"), BOTH("Ambos");

    fun covers(place: OfferPlaceKind): Boolean = this == BOTH || (this == PICKUP) == (place == OfferPlaceKind.PICKUP)
}

/**
 * How loud the warning is. [CLOSE] (v1.37.0) only closes the card in the «Automático» operating mode;
 * in «Observador» and «Asistido» it behaves as [AVOID].
 */
enum class SectorLevel(val label: String) { WARN("Aviso"), AVOID("Evitar"), CLOSE("Cerrar") }

enum class OfferPlaceKind(val label: String) { PICKUP("Recogida"), DROPOFF("Destino") }

/**
 * 1.47.0: when a sector applies. [days] are ISO days (1 = Monday … 7 = Sunday), empty = every day.
 * [fromMin] and [toMin] are minutes of the day; when [fromMin] > [toMin] the range crosses midnight
 * and the hours after midnight belong to the day it began on (Friday 20:00–05:00 includes Saturday
 * 02:00). Equal values mean the whole day.
 */
data class SectorSchedule(val days: Set<Int> = emptySet(), val fromMin: Int = 0, val toMin: Int = 0) {

    fun isActive(at: java.time.ZonedDateTime): Boolean {
        val minute = at.hour * 60 + at.minute
        val day = at.dayOfWeek.value
        val previous = if (day == 1) 7 else day - 1
        fun on(d: Int) = days.isEmpty() || d in days
        return when {
            fromMin == toMin -> on(day)
            fromMin < toMin -> on(day) && minute >= fromMin && minute < toMin
            else -> (minute >= fromMin && on(day)) || (minute < toMin && on(previous))
        }
    }

    /** «L M X · 20:00–05:00», «Todos los días · 06:00–10:00». */
    val label: String get() {
        val d = if (days.isEmpty() || days.size == 7) "Todos los días" else (1..7).filter { it in days }.joinToString(" ") { DAY_LETTERS[it - 1] }
        val h = if (fromMin == toMin) "todo el día" else "${hhmm(fromMin)}–${hhmm(toMin)}"
        return "$d · $h"
    }

    companion object {
        val DAY_LETTERS = listOf("L", "M", "X", "J", "V", "S", "D")
        fun hhmm(min: Int): String = "%02d:%02d".format(min / 60, min % 60)

        /** «20:00» or «8:30» to minutes of the day, or null. */
        fun parseTime(text: String): Int? {
            val m = Regex("""^\s*(\d{1,2})(?::(\d{2}))?\s*$""").find(text) ?: return null
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].ifEmpty { "0" }.toInt()
            return if (h in 0..23 && min in 0..59) h * 60 + min else null
        }
    }
}

/**
 * What a sector is checked against besides the address (1.47.0): the moment of the offer and its
 * modality as Uber wrote it («Moto»). Without it (searches) the schedule and modality are ignored.
 */
data class SectorContext(val atMs: Long, val zone: java.time.ZoneId, val product: String?) {
    companion object {
        /** The moment the card was seen and its modality, in the phone's time zone. */
        fun of(offer: com.ubers.observador.domain.models.UberOffer, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()) =
            SectorContext(offer.detectedAt, zone, offer.product)
    }
}

/**
 * A sector the driver wants to be warned about, written as Uber writes it («Los Alcarrizos»).
 * 1.47.0: other names that count the same ([aliases]), parts of the address that cancel it
 * ([exceptions], «Santo Domingo Oeste» except «Herrera»), an optional [schedule], one [product]
 * (null = every modality) and [enabled] to keep it listed without warning.
 */
data class CareSector(
    val name: String,
    val where: SectorWhere = SectorWhere.BOTH,
    val level: SectorLevel = SectorLevel.WARN,
    val aliases: List<String> = emptyList(),
    val exceptions: List<String> = emptyList(),
    val schedule: SectorSchedule? = null,
    val product: String? = null,
    val enabled: Boolean = true
) {
    /** The name and its aliases: any of them matches. */
    val names: List<String> get() = listOf(name) + aliases

    /** Whether the moment and modality of [context] are inside this sector's rule. */
    fun appliesTo(context: SectorContext?): Boolean {
        if (context == null) return true
        if (schedule != null && !schedule.isActive(java.time.Instant.ofEpochMilli(context.atMs).atZone(context.zone))) return false
        if (product != null) {
            val p = context.product ?: return false
            if (SectorMatcher.words(p) != SectorMatcher.words(product)) return false
        }
        return true
    }

    companion object {
        const val MAX_NAME = 40
        const val MAX_SECTORS = 50
        const val MAX_ALIASES = 5
        const val MAX_EXCEPTIONS = 5
    }
}

/** One sector found in one address of the card. */
data class SectorHit(val sector: CareSector, val place: OfferPlaceKind)

/** Every sector found on one card; empty when none. */
data class SectorMatch(val hits: List<SectorHit>) {
    val isEmpty: Boolean get() = hits.isEmpty()

    /** The loudest level among the hits, or null. */
    val level: SectorLevel? get() = hits.maxOfOrNull { it.sector.level }

    companion object {
        val NONE = SectorMatch(emptyList())
    }
}
