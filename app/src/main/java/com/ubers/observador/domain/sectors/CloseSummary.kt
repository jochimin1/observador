package com.ubers.observador.domain.sectors

/**
 * 1.60.0: what the «Cerrar» sectors did, counted from the daily close logs (CloseLog lines):
 * real closes, simulations, the ones the driver stopped by touching the card, the ones that could
 * not be done and why, and the X closes the driver made by hand that taught Observador.
 */
data class CloseSummary(
    val closed: Int = 0,
    val simulated: Int = 0,
    val stoppedByDriver: Int = 0,
    /** Reason → how many. */
    val notClosed: Map<String, Int> = emptyMap(),
    val byHand: Int = 0
) {
    val notClosedTotal: Int get() = notClosed.values.sum()
    val isEmpty: Boolean get() = closed + simulated + stoppedByDriver + notClosedTotal + byHand == 0
}

object CloseSummaries {
    const val BY_HAND = "X DE UBER TOCADA A MANO"
    const val STOPPED = "Cancelado por ti"

    fun of(lines: List<String>): CloseSummary {
        var closed = 0; var simulated = 0; var stopped = 0; var hand = 0
        val reasons = LinkedHashMap<String, Int>()
        for (line in lines) {
            val result = line.split(" · ").getOrNull(1)?.trim() ?: continue
            when {
                result == CloseLog.CLOSED -> closed++
                result.startsWith(CloseLog.SIMULATED) -> simulated++
                result.startsWith(BY_HAND) -> hand++
                result.startsWith(CloseLog.NOT_CLOSED) -> {
                    val reason = result.removePrefix(CloseLog.NOT_CLOSED).trim().removeSurrounding("(", ")").ifBlank { "sin motivo" }
                    if (reason == STOPPED) stopped++ else reasons[reason] = (reasons[reason] ?: 0) + 1
                }
            }
        }
        return CloseSummary(closed, simulated, stopped, reasons.entries.sortedByDescending { it.value }.associate { it.key to it.value }, hand)
    }

    fun line(title: String, s: CloseSummary): String = if (s.isEmpty) "$title: sin ofertas de sectores «Cerrar»." else buildString {
        append("$title: ${s.closed} cerradas")
        if (s.simulated > 0) append(", ${s.simulated} en simulación")
        append(", ${s.stoppedByDriver} impedidas por ti, ${s.notClosedTotal} sin cerrar")
        if (s.byHand > 0) append(", ${s.byHand} cerradas a mano")
        append('.')
    }

    fun reasons(s: CloseSummary, max: Int = 4): String? =
        s.notClosed.entries.take(max).takeIf { it.isNotEmpty() }?.joinToString("; ", prefix = "Sin cerrar: ") { "${it.key} (${it.value})" }
}
