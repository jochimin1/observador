package com.ubers.observador.domain.earnings

/**
 * 1.60.0: Uber's money labels confirmed on the phone, trip by trip. After a trip, the driver
 * compares what Observador read (the trip's total) with Uber's own app and answers «Coincide» or
 * corrects it (the correction goes into the ledger as a manual amount, as always). When the last
 * [REQUIRED] trips checked all matched, the reading is confirmed and Ganancias stops warning;
 * a correction starts the count again. Every answer is kept for good (dinero/confirmaciones.txt).
 */
enum class MoneyVerdict(val label: String) { MATCH("Coincide"), CORRECTED("Corregido") }

data class MoneyCheck(
    val atMs: Long,
    val tripId: String,
    /** What Observador had read when the driver checked it. */
    val readMinor: Long,
    val currencyCode: String,
    val verdict: MoneyVerdict,
    /** Uber's figure written by the driver, when it did not match. */
    val correctMinor: Long? = null
)

data class MoneyCheckState(val validated: Boolean, val inARow: Int, val matches: Int, val corrections: Int, val checkedTrips: Set<String>)

object MoneyChecks {
    const val REQUIRED = 3
    const val DIR = "dinero"
    const val FILE = "confirmaciones.txt"
    private const val TAG = "m1"

    private fun esc(s: String) = s.replace("%", "%25").replace(";", "%3B").replace("\n", "%0A")
    private fun unesc(s: String) = s.replace("%0A", "\n").replace("%3B", ";").replace("%25", "%")

    fun encode(c: MoneyCheck): String =
        listOf(TAG, c.atMs.toString(), esc(c.tripId), c.readMinor.toString(), esc(c.currencyCode), c.verdict.name, c.correctMinor?.toString() ?: "")
            .joinToString(";")

    fun decode(line: String): MoneyCheck? {
        val p = line.trim().split(';')
        if (p.size != 7 || p[0] != TAG) return null
        return try {
            MoneyCheck(p[1].toLong(), unesc(p[2]), p[3].toLong(), unesc(p[4]), MoneyVerdict.valueOf(p[5]), p[6].takeIf { it.isNotEmpty() }?.toLong())
        } catch (e: IllegalArgumentException) { null }
    }

    fun parse(text: String): List<MoneyCheck> = text.lines().mapNotNull(::decode)

    /** The last answer for each trip counts; then the run of matches at the end decides. */
    fun state(checks: List<MoneyCheck>): MoneyCheckState {
        val last = checks.sortedBy { it.atMs }.associateBy { it.tripId }.values.sortedBy { it.atMs }
        val inARow = last.takeLastWhile { it.verdict == MoneyVerdict.MATCH }.size
        return MoneyCheckState(inARow >= REQUIRED, inARow, last.count { it.verdict == MoneyVerdict.MATCH },
            last.count { it.verdict == MoneyVerdict.CORRECTED }, last.map { it.tripId }.toSet())
    }

    /**
     * Trips worth checking: a real total read from Uber's screen (never a manual one), not
     * cancelled, not yet checked; newest first.
     */
    fun candidates(rows: List<TripMoneyRow>, readFromUber: Set<String>, state: MoneyCheckState, limit: Int = REQUIRED): List<TripMoneyRow> =
        rows.filter { it.real != null && !it.cancelled && it.tripId in readFromUber && it.tripId !in state.checkedTrips }
            .sortedByDescending { it.completedAt }.take(limit)

    fun describe(s: MoneyCheckState): String = when {
        s.validated -> "Lecturas de dinero confirmadas: los últimos ${s.inARow} viajes que revisaste coinciden con Uber."
        s.corrections > 0 && s.inARow == 0 -> "La última lectura que revisaste no coincidía con Uber y quedó corregida. " +
            "Hacen falta $REQUIRED viajes seguidos que coincidan para confirmar las lecturas."
        else -> "Las cifras que Observador lee de Uber aún no están confirmadas: ${s.inARow} de $REQUIRED viajes revisados coinciden. " +
            "Compara cada viaje con la app de Uber y responde."
    }
}
