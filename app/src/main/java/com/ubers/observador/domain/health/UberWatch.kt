package com.ubers.observador.domain.health

/**
 * 1.61.0: «cuando Uber cambia, Observador se entera». Every offer read, every offer card that could
 * not be read and every trip total read from Uber's screen is marked with the Uber version installed
 * at that moment (uber/lecturas-AAAA-MM.txt, kept for good). From those marks: when Uber changed
 * version, how well each version is read, and whether the reading got worse after an update.
 */
enum class ReadingEvent(val code: String) {
    OFFER_OK("O"),
    OFFER_UNREADABLE("U"),
    MONEY_TOTAL("M");

    companion object {
        fun of(code: String): ReadingEvent? = entries.firstOrNull { it.code == code }
    }
}

data class ReadingMark(val atMs: Long, val uberVersion: String, val event: ReadingEvent)

data class VersionHealth(
    val version: String,
    val sinceMs: Long,
    /** When the next version was first seen; null for the one in use. */
    val untilMs: Long?,
    val offersOk: Int,
    val unreadable: Int,
    val moneyTotals: Int
) {
    val offers: Int get() = offersOk + unreadable
    /** Unreadable offers per hundred, or null with no offers. */
    val unreadablePct: Int? get() = if (offers == 0) null else unreadable * 100 / offers
    /** The first offers of a version are watched closely. */
    val watching: Boolean get() = untilMs == null && offers < UberWatch.WATCH_OFFERS
}

object UberWatch {
    const val DIR = "uber"
    /** After an update, this many offers are «vigiladas». */
    const val WATCH_OFFERS = 20
    /** The window of recent offers that decides whether reading got worse. */
    const val WINDOW = 20
    const val MIN_BAD = 3
    const val BAD_PCT = 30
    private const val TAG = "r1"

    fun fileName(month: String) = "lecturas-$month.txt"

    private fun esc(s: String) = s.replace("%", "%25").replace(";", "%3B").replace("\n", "%0A")
    private fun unesc(s: String) = s.replace("%0A", "\n").replace("%3B", ";").replace("%25", "%")

    fun encode(m: ReadingMark) = "$TAG;${m.atMs};${esc(m.uberVersion)};${m.event.code}"

    fun decode(line: String): ReadingMark? {
        val p = line.trim().split(';')
        if (p.size != 4 || p[0] != TAG) return null
        val at = p[1].toLongOrNull() ?: return null
        val e = ReadingEvent.of(p[3]) ?: return null
        return ReadingMark(at, unesc(p[2]), e)
    }

    fun parse(text: String): List<ReadingMark> = text.lines().mapNotNull(::decode)

    /** Each version as first seen, oldest first (a version seen again after another counts from its first time). */
    fun versions(marks: List<ReadingMark>): List<Pair<String, Long>> =
        marks.sortedBy { it.atMs }.groupBy { it.uberVersion }.map { (v, l) -> v to l.first().atMs }.sortedBy { it.second }

    /** How each version was read, newest first. */
    fun health(marks: List<ReadingMark>): List<VersionHealth> {
        val vs = versions(marks)
        return vs.mapIndexed { i, (v, since) ->
            val mine = marks.filter { it.uberVersion == v }
            VersionHealth(v, since, vs.getOrNull(i + 1)?.second,
                mine.count { it.event == ReadingEvent.OFFER_OK }, mine.count { it.event == ReadingEvent.OFFER_UNREADABLE },
                mine.count { it.event == ReadingEvent.MONEY_TOTAL })
        }.asReversed()
    }

    /** When the version in use started, if Uber changed at least once on this phone; null otherwise. */
    fun updatedAt(marks: List<ReadingMark>, current: String?): Long? {
        val vs = versions(marks)
        if (vs.size < 2 || current == null) return null
        return vs.lastOrNull { it.first == current }?.second?.takeIf { since -> vs.any { it.second < since } }
    }

    /** Whether the last [WINDOW] offers of [version] read clearly worse: at least [MIN_BAD] and [BAD_PCT] % unreadable. */
    fun degraded(marks: List<ReadingMark>, version: String): Boolean {
        val offers = marks.filter { it.uberVersion == version && it.event != ReadingEvent.MONEY_TOTAL }.sortedBy { it.atMs }.takeLast(WINDOW)
        val bad = offers.count { it.event == ReadingEvent.OFFER_UNREADABLE }
        return bad >= MIN_BAD && bad * 100 >= offers.size * BAD_PCT
    }

    fun describe(h: VersionHealth, learnedX: String?): String = buildString {
        append("Uber ${h.version}")
        append(if (h.untilMs == null) " (instalada)" else "")
        append(": ${h.offersOk} ofertas leídas, ${h.unreadable} sin leer")
        h.unreadablePct?.let { append(" ($it %)") }
        append(", ${h.moneyTotals} totales de dinero leídos")
        if (h.watching) append(". Vigilando las primeras $WATCH_OFFERS ofertas")
        learnedX?.let { append(". X: $it") }
        append('.')
    }

    fun updateNotice(from: String?, to: String): String =
        (if (from != null) "Uber pasó de $from a $to. " else "Uber $to. ") +
            "Observador vigila las próximas $WATCH_OFFERS ofertas; la X de Uber y el dinero se vuelven a confirmar con esta versión."

    fun degradedNotice(version: String): String =
        "Desde Uber $version, Observador no está leyendo bien varias ofertas. Revisa Ajustes › Salud de lectura y, si sigue, exporta la evidencia."
}
