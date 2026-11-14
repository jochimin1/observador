package com.ubers.observador.domain.expenses

import com.ubers.observador.domain.distance.DistanceSegment
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

const val INITIAL_VEHICLE = "vehicle-initial"

/** One active motorcycle. Replacements keep the previous motorcycle and its entries. */
data class VehicleProfile(
    val id: String = INITIAL_VEHICLE,
    val name: String = "Mi moto",
    val workSharePct: Int = 100,
    val startedAt: Long = 0,
    val retiredAt: Long? = null,
    val updatedAt: Long = 0
) {
    fun contains(at: Long) = at >= startedAt && (retiredAt == null || at < retiredAt)
}

data class ComponentPlan(val vehicleId: String, val type: ExpenseType, val intervalKm: Long = 0, val intervalDays: Int = 0) {
    fun valid() = type in ExpenseType.REMINDABLE && intervalKm in 0..ExpenseLimits.INTERVAL_MAX_KM && intervalDays in 0..3650
}

data class VehicleCareData(val vehicles: List<VehicleProfile> = emptyList(), val plans: List<ComponentPlan> = emptyList()) {
    val active: VehicleProfile get() = vehicles.lastOrNull { it.retiredAt == null } ?: VehicleProfile()
}

/** Evidence must describe the entire tank interval, including personal kilometres. */
data class FuelGpsEvidence(val km: Long?, val coveragePct: Int, val problem: String? = null) {
    val usable: Boolean get() = km != null && km > 0 && coveragePct >= 95 && problem == null
}

object FuelGpsCoverage {
    fun between(segments: List<DistanceSegment>, from: Long, to: Long): FuelGpsEvidence {
        if (to <= from) return FuelGpsEvidence(null, 0, "Fechas de tanqueo no válidas")
        val rows = segments.filter { it.startedAt < to && (it.endedAt ?: it.updatedAt) > from }.sortedBy { it.startedAt }
        var end = from
        var covered = 0L
        var mm = 0L
        for (s in rows) {
            val stop = s.endedAt ?: s.updatedAt // Never extend a stale open segment to now.
            if (s.startedAt < from || stop > to) return FuelGpsEvidence(null, 0, "Un tramo GPS cruza el límite del tanqueo; usa el odómetro")
            if (s.startedAt < end) return FuelGpsEvidence(null, 0, "Tramos GPS solapados")
            val firstFix = s.firstFixAt
            val lastFix = s.lastFixAt
            if (firstFix == null || lastFix == null || firstFix < s.startedAt || lastFix > stop || firstFix > lastFix ||
                firstFix - s.startedAt > 60_000 || stop - lastFix > 60_000)
                return FuelGpsEvidence(null, 0, "GPS sin evidencia de inicio y fin; usa el odómetro")
            if (s.gapMm > 0 || s.secondsWithoutSignal > 0 || s.fixesUsed < 2 || s.fixesDiscarded > 0 || s.measuredMm < 0)
                return FuelGpsEvidence(null, 0, "Hay huecos o señal GPS insuficiente")
            if (s.startedAt - end > 60_000) return FuelGpsEvidence(null, 0, "Falta GPS entre tanqueos, incluido el uso personal")
            covered += (lastFix - firstFix).coerceAtLeast(0)
            mm += s.measuredMm
            end = stop
        }
        val pct = (covered.toBigInteger() * 100.toBigInteger() / (to - from).toBigInteger()).toInt().coerceIn(0, 100)
        if (rows.isEmpty() || to - end > 60_000 || pct < 95)
            return FuelGpsEvidence(null, pct, "Cobertura GPS insuficiente del tanque completo ($pct %)")
        return FuelGpsEvidence(mm / 1_000_000, pct, if (mm < 1_000_000) "Sin distancia suficiente" else null)
    }
}

data class ComponentStatus(
    val plan: ComponentPlan,
    val history: List<ExpenseEntry>,
    val remainingKm: Long?,
    val remainingDays: Long?,
    val estimatedKm: Boolean,
    val level: ReminderLevel,
    val kmProblem: String? = null
) {
    val last get() = history.firstOrNull()
    val notificationKey get() = "${plan.vehicleId}:${last?.id}:${last?.updatedAt}:${plan.intervalKm}:${plan.intervalDays}:$level"
}

object VehicleCare {
    fun plansFor(care: VehicleCareData, legacy: Map<ExpenseType, Long>): List<ComponentPlan> = ExpenseType.REMINDABLE.map { type ->
        care.plans.firstOrNull { it.vehicleId == care.active.id && it.type == type }
            ?: ComponentPlan(care.active.id, type, if (care.active.id == INITIAL_VEHICLE) legacy[type] ?: 0 else 0)
    }

    fun scope(data: ExpenseData, vehicle: VehicleProfile) = data.copy(
        fuel = data.fuel.filter { it.vehicleId == vehicle.id },
        expenses = data.expenses.filter { it.vehicleId == vehicle.id },
        readings = data.readings.filter { it.vehicleId == vehicle.id }
    )

    fun components(data: ExpenseData, plans: List<ComponentPlan>, segments: List<DistanceSegment>, now: Long, zone: ZoneId): List<ComponentStatus> {
        val timeline = Odometer.timeline(data.fuel.filter { it.atMs <= now }, data.expenses.filter { it.atMs <= now }, data.readings.filter { it.atMs <= now })
        val current = timeline.lastOrNull()
        return plans.map { configured ->
            val history = data.expenses.filter { it.vehicleId == configured.vehicleId && it.type == configured.type && it.atMs <= now }.sortedByDescending { it.atMs }
            val change = history.firstOrNull()
            val plan = configured.copy(intervalKm = change?.expectedLifeKm ?: configured.intervalKm,
                intervalDays = change?.expectedLifeDays ?: configured.intervalDays)
            var problem: String? = null
            val since = current?.let { c -> segments.filter { it.startedAt >= c.atMs && (it.endedAt ?: it.updatedAt) <= now } }
            // A partial GPS sum is a lower bound: useful for warning, never a complete odometer.
            var lastEnd = current?.atMs ?: 0L
            var measuredMm = 0L
            since.orEmpty().sortedBy { it.startedAt }.forEach { s ->
                val end = s.endedAt ?: s.updatedAt
                if (s.startedAt >= lastEnd && end >= s.startedAt) {
                    measuredMm += s.measuredMm.coerceAtLeast(0)
                    lastEnd = end
                }
            }
            val measuredKm = measuredMm / 1_000_000
            val remainingKm = if (plan.intervalKm <= 0 || change?.odometerKm == null || current == null) {
                if (plan.intervalKm > 0) problem = "Anota el cambio y una lectura de odómetro."
                null
            } else if (Odometer.epochAt(change.atMs, timeline) != current.epoch || current.km < change.odometerKm ||
                timeline.filter { it.atMs >= change.atMs }.zipWithNext().any { (a,b) -> b.km < a.km }) {
                problem = "Odómetro reiniciado o incoherente; falta una referencia del componente en esta cuenta."
                null
            } else change.odometerKm + plan.intervalKm - current.km - measuredKm
            val remainingDays = if (plan.intervalDays > 0 && change != null) {
                val changed = Instant.ofEpochMilli(change.atMs).atZone(zone).toLocalDate()
                val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
                ChronoUnit.DAYS.between(today, changed.plusDays(plan.intervalDays.toLong()))
            } else null
            val level = when {
                remainingKm != null && remainingKm <= 0 || remainingDays != null && remainingDays <= 0 -> ReminderLevel.OVERDUE
                remainingKm != null && remainingKm * 10 <= plan.intervalKm || remainingDays != null && remainingDays <= maxOf(1, plan.intervalDays / 10) -> ReminderLevel.SOON
                remainingKm == null && remainingDays == null -> ReminderLevel.NO_DATA
                else -> ReminderLevel.OK
            }
            ComponentStatus(plan, history, remainingKm, remainingDays, measuredKm > 0, level, problem)
        }
    }
}
