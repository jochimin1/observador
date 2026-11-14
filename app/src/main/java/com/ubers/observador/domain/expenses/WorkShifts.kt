package com.ubers.observador.domain.expenses

import com.ubers.observador.domain.WorkdayClock
import com.ubers.observador.domain.online.OnlineSession
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 1.46.0: a work shift ("jornada") is the odometer written before connecting to Uber and the one
 * written after finishing. Both readings are ordinary [OdometerReading]s, so fuel efficiency and
 * maintenance reminders use them too. The shift only links them.
 */
data class WorkShift(
    val id: String,
    val vehicleId: String,
    val start: OdometerReading,
    val end: OdometerReading? = null,
    val createdAt: Long,
    val updatedAt: Long
) {
    val isOpen: Boolean get() = end == null
    /** Kilometres driven for Uber; null while open. */
    val uberKm: Long? get() = end?.let { it.km - start.km }
}

/** A stored shift, before its readings are attached. */
data class WorkShiftLink(val id: String, val vehicleId: String, val startReadingId: String, val endReadingId: String?,
    val createdAt: Long, val updatedAt: Long)

/** Result of a shift input check. [needsConfirm]: allowed only after the driver confirms [message]. */
sealed interface ShiftCheck {
    data object Ok : ShiftCheck
    data class Confirm(val message: String) : ShiftCheck
    data class Rejected(val message: String) : ShiftCheck
}

/** Measured work share over a period. [pct] is null when the evidence is not enough (then the manual % applies). */
data class WorkShareMeasurement(
    val from: LocalDate,
    val to: LocalDate,
    val closedShifts: Int,
    val uberKm: Long,
    val personalKm: Long,
    /** Gaps between consecutive shifts that could be measured (same motorbike, no reset, not lower). */
    val measuredGaps: Int,
    val pct: Int?
) {
    val measured: Boolean get() = pct != null
}

/** What the driver is offered: use [measuredPct] instead of [manualPct]. Never applied by itself. */
data class WorkShareSuggestion(val measuredPct: Int, val manualPct: Int, val measurement: WorkShareMeasurement)

enum class ShiftReminderKind { START, END }
data class ShiftReminder(val kind: ShiftReminderKind, val key: String)

object WorkShiftRules {
    /** A single shift above this needs confirmation (typo guard, not a limit). */
    const val CONFIRM_KM = 600L
    /** Closed shifts needed in the period before a % is measured. */
    const val MIN_SHIFTS = 2
    /** Days the suggestion looks back, ending today. */
    const val PERIOD_DAYS = 30L
    /** A dismissed suggestion comes back only when the measured % moved at least this much. */
    const val SUGGEST_AGAIN_POINTS = 3
    /** Minutes offline with an open shift before "¿Terminaste la jornada?". */
    const val END_REMINDER_MINUTES = 30L
    /** Clock tolerance for "not in the future". */
    private const val FUTURE_TOLERANCE_MS = 60_000L

    fun attach(links: List<WorkShiftLink>, readings: List<OdometerReading>): List<WorkShift> {
        val byId = readings.associateBy { it.id }
        return links.mapNotNull { l ->
            val start = byId[l.startReadingId] ?: return@mapNotNull null
            WorkShift(l.id, l.vehicleId, start, l.endReadingId?.let { byId[it] }, l.createdAt, l.updatedAt)
        }.sortedBy { it.start.atMs }
    }

    fun checkStart(km: Long, atMs: Long, nowMs: Long, shifts: List<WorkShift>, timeline: List<OdometerPoint>,
        reset: Boolean, editingId: String? = null): ShiftCheck {
        if (km < 0 || km > ExpenseLimits.ODOMETER_MAX_KM) return ShiftCheck.Rejected("Escribe el odómetro en km, sin decimales.")
        if (atMs > nowMs + FUTURE_TOLERANCE_MS) return ShiftCheck.Rejected("La hora de inicio no puede ser futura.")
        val others = shifts.filter { it.id != editingId }
        if (editingId == null && others.any { it.isOpen }) return ShiftCheck.Rejected("Ya hay una jornada abierta. Termínala antes de iniciar otra.")
        others.firstOrNull { overlaps(it, atMs, nowMs) }?.let {
            return ShiftCheck.Rejected("Esa hora cae dentro de otra jornada. Revisa la hora.")
        }
        val excluded = shifts.firstOrNull { it.id == editingId }?.start?.id
        if (!reset) when (val c = Odometer.check(km, atMs, timeline, excludeId = excluded)) {
            is OdometerCheck.Lower -> return ShiftCheck.Rejected(
                "Es menor que la lectura anterior (${ExpenseFormat.km(c.previousKm)} km). Corrígelo, o marca que cambiaste de odómetro o de motor.")
            OdometerCheck.Ok -> Unit
        }
        return ShiftCheck.Ok
    }

    fun checkEnd(shift: WorkShift, km: Long, atMs: Long, nowMs: Long, shifts: List<WorkShift>): ShiftCheck {
        if (km < 0 || km > ExpenseLimits.ODOMETER_MAX_KM) return ShiftCheck.Rejected("Escribe el odómetro en km, sin decimales.")
        if (km < shift.start.km) return ShiftCheck.Rejected("El odómetro final no puede ser menor que el inicial (${ExpenseFormat.km(shift.start.km)} km).")
        if (atMs <= shift.start.atMs) return ShiftCheck.Rejected("La hora de fin debe ser posterior al inicio.")
        if (atMs > nowMs + FUTURE_TOLERANCE_MS) return ShiftCheck.Rejected("La hora de fin no puede ser futura.")
        shifts.firstOrNull { it.id != shift.id && it.start.atMs > shift.start.atMs && it.start.atMs < atMs }?.let {
            return ShiftCheck.Rejected("Hay otra jornada que empieza antes de esa hora de fin.")
        }
        val driven = km - shift.start.km
        if (driven > CONFIRM_KM) return ShiftCheck.Confirm("Son ${ExpenseFormat.km(driven)} km en una sola jornada. ¿Es correcto?")
        return ShiftCheck.Ok
    }

    private fun overlaps(shift: WorkShift, atMs: Long, nowMs: Long): Boolean {
        val end = shift.end?.atMs ?: nowMs
        return atMs > shift.start.atMs && atMs < end
    }

    /**
     * Shifts belong to the working day of their start. Uber km = Σ(end − start) of the closed shifts
     * of the period. Personal km = Σ(start − previous end) for consecutive closed shifts of the same
     * motorbike, without a reset between them; a gap that would be negative is left out.
     */
    fun measure(shifts: List<WorkShift>, timeline: List<OdometerPoint>, clock: WorkdayClock, from: LocalDate, to: LocalDate): WorkShareMeasurement {
        val ordered = shifts.filter { !it.isOpen }.sortedBy { it.start.atMs }
        fun inPeriod(s: WorkShift) = clock.workdayOf(s.start.atMs).let { !it.isBefore(from) && !it.isAfter(to) }
        var uber = 0L
        var personal = 0L
        var gaps = 0
        var count = 0
        ordered.forEachIndexed { i, s ->
            if (!inPeriod(s)) return@forEachIndexed
            count++
            uber += s.uberKm!!
            val prev = ordered.getOrNull(i - 1) ?: return@forEachIndexed
            if (prev.vehicleId != s.vehicleId) return@forEachIndexed
            val prevEnd = prev.end!!
            val resetBetween = s.start.isReset || timeline.any { it.isReset && it.atMs > prevEnd.atMs && it.atMs <= s.start.atMs }
            val gap = s.start.km - prevEnd.km
            if (resetBetween || gap < 0) return@forEachIndexed
            personal += gap
            gaps++
        }
        val total = uber + personal
        val pct = if (count >= MIN_SHIFTS && gaps >= 1 && total > 0) (uber * 100.0 / total).roundToInt().coerceIn(0, 100) else null
        return WorkShareMeasurement(from, to, count, uber, personal, gaps, pct)
    }

    fun lastPeriod(clock: WorkdayClock, nowMs: Long): Pair<LocalDate, LocalDate> {
        val today = clock.workdayOf(nowMs)
        return today.minusDays(PERIOD_DAYS - 1) to today
    }

    /** Null when not measured, equal to the manual %, or dismissed and not moved enough since. */
    fun suggestion(m: WorkShareMeasurement, manualPct: Int, dismissedPct: Int?): WorkShareSuggestion? {
        val pct = m.pct ?: return null
        if (pct == manualPct) return null
        if (dismissedPct != null && abs(pct - dismissedPct) < SUGGEST_AGAIN_POINTS) return null
        return WorkShareSuggestion(pct, manualPct, m)
    }

    /**
     * START: Uber is online and no shift is open (once per online session).
     * END: a shift is open and Uber has been offline for [END_REMINDER_MINUTES] since after the shift
     * started (once per offline period). Never opens or closes a shift.
     */
    fun reminder(latest: OnlineSession?, open: WorkShift?, nowMs: Long, onlineNow: Boolean): ShiftReminder? {
        if (latest == null) return null
        if (onlineNow && open == null) return ShiftReminder(ShiftReminderKind.START, "start:${latest.id}")
        if (open == null || onlineNow) return null
        val offlineSince = latest.endedAt ?: latest.lastOnlineSeenAt
        if (offlineSince <= open.start.atMs) return null
        if (nowMs - offlineSince < END_REMINDER_MINUTES * 60_000) return null
        return ShiftReminder(ShiftReminderKind.END, "end:${open.id}:${latest.id}")
    }
}
