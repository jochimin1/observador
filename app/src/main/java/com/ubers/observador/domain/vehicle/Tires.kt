package com.ubers.observador.domain.vehicle

import com.ubers.observador.domain.expenses.ExpenseEntry
import com.ubers.observador.domain.expenses.ExpenseLimits
import com.ubers.observador.domain.expenses.ExpenseType
import com.ubers.observador.domain.expenses.Odometer
import com.ubers.observador.domain.expenses.OdometerPoint
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 1.53.0 «Mi moto y neumáticos»: the two wheels, cared for by hand (no sensors yet). Every record
 * is kept: a tyre that is changed stays in the history, nothing is deleted without the driver
 * asking, and a check never renews itself.
 */
enum class WheelPosition(val storageKey: String, val label: String) {
    FRONT("FRONT", "Delantera"),
    REAR("REAR", "Trasera");

    companion object {
        fun fromStorage(key: String?): WheelPosition? = entries.firstOrNull { it.storageKey == key }
    }
}

/**
 * Pressure units. A pressure is stored twice: exactly as written, in the unit written, and in
 * pascals to compare. Converting for display never changes what was written.
 */
enum class PressureUnit(val storageKey: String, val label: String, val pascals: BigDecimal, val decimals: Int) {
    PSI("PSI", "psi", BigDecimal("6894.757293168"), 1),
    BAR("BAR", "bar", BigDecimal("100000"), 2),
    KPA("KPA", "kPa", BigDecimal("1000"), 0);

    companion object {
        val DEFAULT = PSI
        fun fromStorage(key: String?): PressureUnit = entries.firstOrNull { it.storageKey == key } ?: DEFAULT
    }
}

/** A pressure as the driver wrote it. [entered] is the normalised decimal text («32.5»). */
data class Pressure(val pascals: Int, val entered: String, val unit: PressureUnit) {

    fun shown(unit: PressureUnit = this.unit): String =
        if (unit == this.unit) "$entered ${unit.label}" else TirePressure.format(pascals, unit)

    companion object {
        fun of(pascals: Int?, entered: String?, unit: String?): Pressure? =
            if (pascals == null || entered == null) null else Pressure(pascals, entered, PressureUnit.fromStorage(unit))
    }
}

object TirePressure {
    /** Accepted range: 50–700 kPa (about 7–100 psi), wide enough for any motorcycle tyre. */
    const val MIN_PA = 50_000
    const val MAX_PA = 700_000

    /** «32», «32.5», «2,25» in [unit] → the pressure, or the reason it is not one. */
    fun parse(text: String, unit: PressureUnit): Result<Pressure> {
        val t = text.trim().replace(',', '.')
        if (t.isEmpty()) return Result.failure(IllegalArgumentException("Escribe la presión"))
        if (!Regex("\\d{1,4}(\\.\\d{1,3})?").matches(t)) return Result.failure(IllegalArgumentException("Presión no válida: usa números, por ejemplo 32 o 2.2"))
        val value = BigDecimal(t)
        val pa = value.multiply(unit.pascals).setScale(0, RoundingMode.HALF_UP)
        if (pa < BigDecimal(MIN_PA) || pa > BigDecimal(MAX_PA))
            return Result.failure(IllegalArgumentException("Fuera de rango: entre ${format(MIN_PA, unit)} y ${format(MAX_PA, unit)}"))
        return Result.success(Pressure(pa.toInt(), value.stripTrailingZeros().toPlainString(), unit))
    }

    fun format(pascals: Int, unit: PressureUnit): String =
        BigDecimal(pascals).divide(unit.pascals, unit.decimals, RoundingMode.HALF_UP).stripTrailingZeros()
            .let { if (it.scale() < 0) it.setScale(0) else it }.toPlainString() + " ${unit.label}"
}

enum class TireTemperature(val storageKey: String, val label: String) {
    COLD("COLD", "En frío"),
    HOT("HOT", "En caliente"),
    UNKNOWN("UNKNOWN", "Sin indicar");

    companion object {
        fun fromStorage(key: String?): TireTemperature = entries.firstOrNull { it.storageKey == key } ?: UNKNOWN
    }
}

enum class TireEventKind(val storageKey: String, val label: String, val expenseType: ExpenseType?) {
    /** The tyre was put on (its cost, if any, is a «Gomas» expense). */
    INSTALL("INSTALL", "Instalación", ExpenseType.TIRES),
    /** A manual pressure check: free, never needs an expense. */
    CHECK("CHECK", "Revisión de presión", null),
    /** A puncture or other repair (its cost is «Otro mantenimiento»: it does not reset the tyre change reminder). */
    REPAIR("REPAIR", "Reparación", ExpenseType.MAINTENANCE_OTHER);

    companion object {
        fun fromStorage(key: String?): TireEventKind? = entries.firstOrNull { it.storageKey == key }
    }
}

/** One tyre on one wheel, from installation until it was changed. */
data class Tire(
    val id: String,
    val vehicleId: String,
    val position: WheelPosition,
    val brand: String? = null,
    val model: String? = null,
    val size: String? = null,
    val target: Pressure? = null,
    val installedAt: Long,
    val installedOdometerKm: Long? = null,
    val removedAt: Long? = null,
    val removedOdometerKm: Long? = null,
    val createdAt: Long,
    val updatedAt: Long
) {
    val active: Boolean get() = removedAt == null
    val title: String get() = listOfNotNull(brand, model).joinToString(" ").ifBlank { "Goma ${position.label.lowercase()}" }
}

/** Something done to a tyre: installation, check or repair. Optional money goes to Gastos, once. */
data class TireEvent(
    val id: String,
    val tireId: String,
    val kind: TireEventKind,
    val atMs: Long,
    val pressure: Pressure? = null,
    val temperature: TireTemperature = TireTemperature.UNKNOWN,
    val odometerKm: Long? = null,
    /** The single expense this event paid, if any. One expense is never linked to two events. */
    val expenseId: String? = null,
    val note: String? = null,
    val createdAt: Long,
    val updatedAt: Long
)

data class TireData(val tires: List<Tire> = emptyList(), val events: List<TireEvent> = emptyList())

enum class WheelLevel(val label: String, val problem: Boolean) {
    NOT_REGISTERED("Sin registrar", false),
    NEEDS_CHECK("Falta revisar", false),
    NO_TARGET("Falta la presión objetivo", false),
    OK("Presión correcta", false),
    LOW("Presión baja", true),
    HIGH("Presión alta", true)
}

data class WheelStatus(
    val position: WheelPosition,
    val tire: Tire?,
    val level: WheelLevel,
    val lastCheck: TireEvent?,
    val daysSinceCheck: Long?,
    /** Km on this tyre from its installation odometer to the latest one; null with the reason. */
    val lifeKm: Long?,
    val lifeProblem: String?,
    val detail: String,
    val history: List<TireEvent>,
    val previous: List<Tire>
)

object TireRules {
    /** A check older than this no longer says how the tyre is: «Falta revisar». Default; 1.55.0 makes it a setting. */
    const val CHECK_VALID_DAYS = 14L
    /** Beyond ±10 % of the target a cold reading is a problem. */
    const val TOLERANCE_PCT = 10
    const val TEXT_MAX = 40
    const val NOTE_MAX = 200

    fun cleanText(text: String?, max: Int = TEXT_MAX): String? =
        text?.trim()?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotEmpty() }?.let { t ->
            require(t.length <= max && t.none { it.isISOControl() }) { "Máximo $max caracteres" }
            t
        }

    fun validOdometer(km: Long?): Boolean = km == null || km in 0..ExpenseLimits.ODOMETER_MAX_KM

    /** The active tyre on [position] of [vehicleId] (the newest when the data is odd). */
    fun active(data: TireData, vehicleId: String, position: WheelPosition): Tire? =
        data.tires.filter { it.vehicleId == vehicleId && it.position == position && it.active }.maxByOrNull { it.installedAt }

    /**
     * Installing on a wheel that already has a tyre: the old one is closed at the new one's
     * installation (and odometer) and kept. Returns what must be written.
     */
    fun install(data: TireData, new: Tire): List<Tire> {
        val old = active(data, new.vehicleId, new.position)
        require(new.active) { "Una goma nueva empieza activa" }
        if (old == null) return listOf(new)
        require(new.installedAt > old.installedAt) { "La goma nueva debe instalarse después de la actual (${old.title})." }
        val closed = old.copy(removedAt = new.installedAt, removedOdometerKm = new.installedOdometerKm, updatedAt = new.updatedAt)
        return listOf(closed, new)
    }

    /** Why an event cannot be saved on [tire], or null. */
    fun eventProblem(tire: Tire, event: TireEvent, now: Long): String? = when {
        event.tireId != tire.id -> "El registro no pertenece a esta goma."
        event.atMs > now + 60_000 -> "La fecha no puede ser futura."
        event.atMs < tire.installedAt && event.kind != TireEventKind.INSTALL -> "La fecha es anterior a la instalación de esta goma."
        tire.removedAt != null && event.atMs > tire.removedAt -> "Esta goma ya se cambió; la fecha es posterior al cambio."
        event.kind == TireEventKind.CHECK && event.pressure == null -> "Una revisión necesita la presión medida."
        !validOdometer(event.odometerKm) -> "Odómetro no válido."
        else -> null
    }

    /**
     * Expenses that an event may link to: same motorbike, the event's kind of expense, within a
     * week of it and not already linked to another event (no money is ever counted twice).
     */
    fun linkable(expenses: List<ExpenseEntry>, data: TireData, vehicleId: String, kind: TireEventKind, atMs: Long, editingEventId: String? = null): List<ExpenseEntry> {
        val type = kind.expenseType ?: return emptyList()
        val taken = data.events.filter { it.id != editingEventId }.mapNotNull { it.expenseId }.toSet()
        val week = 7L * 86_400_000L
        return expenses.filter { it.vehicleId == vehicleId && it.type == type && it.id !in taken && kotlin.math.abs(it.atMs - atMs) <= week }
            .sortedBy { kotlin.math.abs(it.atMs - atMs) }
    }

    /** Whether [expenseId] is already linked to an event other than [eventId]. */
    fun alreadyLinked(data: TireData, expenseId: String, eventId: String): Boolean =
        data.events.any { it.expenseId == expenseId && it.id != eventId }

    fun lifeKm(tire: Tire, timeline: List<OdometerPoint>): Pair<Long?, String?> {
        val start = tire.installedOdometerKm ?: return null to "Sin odómetro de instalación"
        val until = tire.removedAt ?: Long.MAX_VALUE
        val end = tire.removedOdometerKm?.let { km -> OdometerPoint(tire.removedAt!!, km, false, Odometer.epochAt(tire.removedAt, timeline), "removed") }
            ?: timeline.lastOrNull { it.atMs in tire.installedAt..until }
            ?: return null to "Sin lectura de odómetro posterior a la instalación"
        val epochStart = Odometer.epochAt(tire.installedAt, timeline)
        if (end.epoch != epochStart || timeline.any { it.isReset && it.atMs > tire.installedAt && it.atMs <= end.atMs })
            return null to "El odómetro se reinició desde la instalación"
        if (end.km < start) return null to "El odómetro actual es menor que el de instalación"
        return (end.km - start) to null
    }

    fun status(data: TireData, vehicleId: String, position: WheelPosition, timeline: List<OdometerPoint>, now: Long, zone: ZoneId, unit: PressureUnit,
        checkDays: Long = CHECK_VALID_DAYS, tolerancePct: Int = TOLERANCE_PCT): WheelStatus {
        val tire = active(data, vehicleId, position)
        val previous = data.tires.filter { it.vehicleId == vehicleId && it.position == position && it.id != tire?.id }.sortedByDescending { it.installedAt }
        if (tire == null) return WheelStatus(position, null, WheelLevel.NOT_REGISTERED, null, null, null, null,
            "Registra la goma instalada para llevar sus revisiones.", emptyList(), previous)
        val history = data.events.filter { it.tireId == tire.id }.sortedWith(compareByDescending<TireEvent> { it.atMs }.thenByDescending { it.createdAt })
        val check = history.firstOrNull { it.kind == TireEventKind.CHECK && it.pressure != null && it.atMs <= now }
        val days = check?.let {
            ChronoUnit.DAYS.between(Instant.ofEpochMilli(it.atMs).atZone(zone).toLocalDate(), Instant.ofEpochMilli(now).atZone(zone).toLocalDate())
        }
        val (life, lifeProblem) = lifeKm(tire, timeline)
        val target = tire.target
        val reading = check?.pressure
        val (level, detail) = when {
            check == null || reading == null -> WheelLevel.NEEDS_CHECK to "Sin revisión de presión registrada."
            days!! > checkDays -> WheelLevel.NEEDS_CHECK to "Última revisión hace $days días: ${reading.shown(unit)}. Revísala de nuevo."
            target == null -> WheelLevel.NO_TARGET to "Medida ${reading.shown(unit)} ${ago(days)}. Indica la presión objetivo del fabricante para compararla."
            else -> {
                val low = target.pascals.toLong() * (100 - tolerancePct) / 100
                val high = target.pascals.toLong() * (100 + tolerancePct) / 100
                val hot = check.temperature == TireTemperature.HOT
                val base = "${reading.shown(unit)} ${ago(days)} · objetivo ${target.shown(unit)}"
                when {
                    reading.pascals < low -> WheelLevel.LOW to "$base. Por debajo del objetivo${if (hot) " incluso en caliente" else ""}."
                    reading.pascals > high && hot -> WheelLevel.NEEDS_CHECK to "$base. Medida en caliente: la presión sube al rodar; revísala en frío antes de ajustarla."
                    reading.pascals > high -> WheelLevel.HIGH to "$base. Por encima del objetivo."
                    else -> WheelLevel.OK to "$base${if (check.temperature == TireTemperature.UNKNOWN) " (sin indicar frío/caliente)" else ""}."
                }
            }
        }
        return WheelStatus(position, tire, level, check, days, life, lifeProblem, detail, history, previous)
    }

    private fun ago(days: Long): String = when (days) { 0L -> "hoy"; 1L -> "ayer"; else -> "hace $days días" }

    /** One line for the whole motorbike: a missing check is not the same as a problem. */
    fun summary(wheels: List<WheelStatus>): String {
        val problems = wheels.filter { it.level.problem }
        val pending = wheels.filter { !it.level.problem && it.level != WheelLevel.OK }
        return when {
            problems.isNotEmpty() -> "Problema detectado: " + problems.joinToString(", ") { "${it.position.label.lowercase()} ${it.level.label.lowercase()}" }
            pending.isNotEmpty() -> "Pendiente: " + pending.joinToString(", ") { "${it.position.label.lowercase()} · ${it.level.label.lowercase()}" }
            else -> "Las dos ruedas revisadas y dentro del objetivo"
        }
    }
}
