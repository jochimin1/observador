package com.ubers.observador.domain.earnings

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/**
 * 1.57.0: the day at a glance, for the widget, the quick tile and the service notification. Same
 * rules as Ganancias: only confirmed money counts, pending amounts are named, never added; «neto»
 * is the operating profit only when the cost model can compute it, otherwise it says «ingreso».
 */
data class TodayFigures(
    val incomeMinor: Long,
    val currency: String,
    val confirmedTrips: Int,
    val completedTrips: Int,
    /** Amounts kept but not summed (to review or to link). */
    val pending: Int,
    /** Confirmed time online today. */
    val onlineMs: Long,
    /** Operating profit (income − costs) when computable; null otherwise. */
    val profitMinor: Long?,
    val goalMinor: Long = 0,
    val goalMinutes: Int = 0
) {
    /** Profit when known, otherwise income, per confirmed online hour; null under 30 min online. */
    val perHourMinor: Long?
        get() = if (onlineMs < Goals.MIN_ONLINE_MS) null
        else BigDecimal.valueOf(profitMinor ?: incomeMinor).multiply(BigDecimal(3_600_000)).divide(BigDecimal.valueOf(onlineMs), 0, RoundingMode.HALF_UP).toLong()

    val moneyGoalPermille: Int? get() = if (goalMinor <= 0) null else (incomeMinor * 1000 / goalMinor).coerceIn(0, 1000).toInt()
    val hoursReached: Boolean get() = goalMinutes > 0 && onlineMs >= goalMinutes * 60_000L
}

object Today {
    fun money(minor: Long, currency: String = "DOP"): String {
        val sign = if (minor < 0) "-" else ""
        val units = BigDecimal.valueOf(kotlin.math.abs(minor), 2).setScale(0, RoundingMode.HALF_UP).toLong()
        return sign + (if (currency == "DOP") "RD$" else "$currency ") + String.format(Locale.US, "%,d", units)
    }

    fun duration(ms: Long): String {
        val m = ms / 60_000
        return if (m < 60) "$m min" else "${m / 60} h" + if (m % 60 != 0L) " ${m % 60} min" else ""
    }

    /** «Neto RD$1,234» or «Ingreso RD$1,500 (sin costos)». */
    fun headline(t: TodayFigures): String =
        if (t.profitMinor != null) "Neto ${money(t.profitMinor, t.currency)}" else "Ingreso ${money(t.incomeMinor, t.currency)}"

    /** «3 h 20 min en línea · RD$250/h · 7 viajes (2 sin dinero confirmado)». */
    fun details(t: TodayFigures): String {
        val parts = ArrayList<String>()
        parts += "${duration(t.onlineMs)} en línea"
        t.perHourMinor?.let { parts += "${money(it, t.currency)}/h" }
        val trips = "${t.completedTrips} ${if (t.completedTrips == 1) "viaje" else "viajes"}"
        val missing = t.completedTrips - t.confirmedTrips
        parts += trips + if (missing > 0) " ($missing sin dinero confirmado)" else ""
        if (t.pending > 0) parts += "${t.pending} por revisar"
        return parts.joinToString(" · ")
    }

    /** «Meta 62 % de RD$3,000 · Horas 3 h 20 min de 8 h», or null without goals. */
    fun goals(t: TodayFigures): String? {
        val parts = ArrayList<String>()
        t.moneyGoalPermille?.let { parts += "Meta ${it / 10} % de ${money(t.goalMinor, t.currency)}" }
        if (t.goalMinutes > 0) parts += "Horas ${duration(t.onlineMs)} de ${duration(t.goalMinutes * 60_000L)}"
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    /** The service notification's short line. */
    fun notificationLine(t: TodayFigures): String =
        "Hoy: ${money(if (t.profitMinor != null) t.profitMinor else t.incomeMinor, t.currency)}" + (if (t.profitMinor != null) " neto" else "") +
            " · ${t.completedTrips} ${if (t.completedTrips == 1) "viaje" else "viajes"} · ${duration(t.onlineMs)}"

    /** The quick tile's subtitle: short. */
    fun tileLine(t: TodayFigures): String = money(if (t.profitMinor != null) t.profitMinor else t.incomeMinor, t.currency) + " hoy"

    const val MAX_GOAL_MINUTES = 24 * 60
}
