package com.ubers.observador.domain.online

import com.ubers.observador.domain.money.MoneyFormat

/** What the status bar says about Uber, apart from whether Observador itself is reading. */
enum class OnlineStatusKind { ONLINE, NOT_SEEN_LATELY, OFFLINE }

data class OnlineStatusLine(val kind: OnlineStatusKind, val text: String)

/** Texts of Fase 8A, pure so they are tested like the rules. Minutes are cut, never rounded up. */
object OnlineFormat {

    private const val MINUTE = 60_000L

    /** "45 min", "1 h 25 min", "2 h", "menos de 1 min". */
    fun duration(ms: Long): String {
        val minutes = ms.coerceAtLeast(0) / MINUTE
        if (minutes < 1) return "menos de 1 min"
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0L -> "$m min"
            m == 0L -> "$h h"
            else -> "$h h $m min"
        }
    }

    /** "1 h 40 min" or, with unseen time, "1 h 40 min · hasta 2 h 10 min". */
    fun range(summary: OnlineSummary): String =
        if (summary.hasRange) "${duration(summary.confirmedMs)} · hasta ${duration(summary.possibleMs)}" else duration(summary.confirmedMs)

    /** 34 tenths -> "3.4". */
    fun tenths(value: Long): String = "${value / 10}.${value % 10}"

    /** The second line of the status bar. A recovered start says "al menos": Observador came in late. */
    fun status(current: OnlineSession?, nowMs: Long, policy: OnlinePolicy = OnlinePolicy()): OnlineStatusLine = when {
        current == null || !current.isOpen -> OnlineStatusLine(OnlineStatusKind.OFFLINE, "Desconectado de Uber")
        current.isStale(nowMs, policy) -> OnlineStatusLine(
            OnlineStatusKind.NOT_SEEN_LATELY,
            "Uber sin ver desde hace ${duration(nowMs - current.lastOnlineSeenAt)}"
        )
        else -> {
            val minimum = if (current.startKind == OnlineStartKind.RECOVERED) "al menos " else ""
            OnlineStatusLine(OnlineStatusKind.ONLINE, "En línea en Uber · $minimum${duration(current.confirmedMs(nowMs, policy))}")
        }
    }

    /** "RD$450.00/h", or why there is no figure. */
    fun earningsPerHour(earnings: OnlineEarnings?): String = when {
        earnings == null -> "Sin viajes"
        earnings.perHour == null -> "—"
        else -> MoneyFormat.format(earnings.perHour) + "/h"
    }

    /** "3 de 5 viajes con dinero confirmado" when partial; null when all are confirmed. */
    fun coverage(earnings: OnlineEarnings?): String? = when {
        earnings == null -> null
        earnings.confirmedTrips == 0 -> "Sin dinero confirmado todavía"
        earnings.partial -> "Parcial: ${earnings.confirmedTrips} de ${earnings.completedTrips} viajes con dinero"
        else -> "${earnings.completedTrips} viajes con dinero confirmado"
    }
}
