package com.ubers.observador.domain.vehicle

import com.ubers.observador.domain.expenses.ExpenseEntry
import com.ubers.observador.domain.expenses.OdometerPoint
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** 1.55.0: the driver's own limits for the tyres (they were fixed in 1.53.0/1.54.0). */
enum class TemperatureUnit(val storageKey: String, val label: String) {
    C("C", "°C"), F("F", "°F");

    fun format(centi: Int): String {
        val v = if (this == C) BigDecimal.valueOf(centi.toLong(), 2)
        else BigDecimal.valueOf(centi.toLong(), 2).multiply(BigDecimal("1.8")).add(BigDecimal(32))
        return v.setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }.toPlainString() + " $label"
    }

    companion object { fun fromStorage(key: String?) = entries.firstOrNull { it.storageKey == key } ?: C }
}

data class TireSettings(
    val checkDays: Int = TireRules.CHECK_VALID_DAYS.toInt(),
    val tolerancePct: Int = TireRules.TOLERANCE_PCT,
    val temperature: TemperatureUnit = TemperatureUnit.C
) {
    companion object {
        val CHECK_DAYS = 1..90
        val TOLERANCE = 3..30
    }
    fun valid() = checkDays in CHECK_DAYS && tolerancePct in TOLERANCE
}

/** One day of a sensor's recorded frames, summed up (the frames themselves stay in the database). */
data class SensorDay(
    val sensorId: String,
    val dayStartMs: Long,
    val frames: Int,
    val firstAt: Long,
    val lastAt: Long,
    val minPa: Int?,
    val maxPa: Int?,
    val minTempCenti: Int?,
    val maxTempCenti: Int?,
    val battery: Int?
)

/** A line of the wheel's history. */
sealed interface TireHistoryItem {
    val atMs: Long
    val position: WheelPosition
    data class Event(override val position: WheelPosition, val tire: Tire, val event: TireEvent) : TireHistoryItem { override val atMs get() = event.atMs }
    data class Removed(override val position: WheelPosition, val tire: Tire) : TireHistoryItem { override val atMs get() = tire.removedAt!! }
    data class SensorOn(override val position: WheelPosition, val binding: SensorBinding) : TireHistoryItem { override val atMs get() = binding.boundAt }
    data class SensorOff(override val position: WheelPosition, val binding: SensorBinding) : TireHistoryItem { override val atMs get() = binding.unboundAt!! }
    data class Sensor(override val position: WheelPosition, val day: SensorDay) : TireHistoryItem { override val atMs get() = day.lastAt }
    data class Alert(override val position: WheelPosition, val alert: TpmsAlertEvent) : TireHistoryItem { override val atMs get() = alert.startedAt }
    /** A stretch longer than the check period with no manual check (shown as a gap, never as «correct»). */
    data class Gap(override val position: WheelPosition, val fromMs: Long, val toMs: Long, val days: Long) : TireHistoryItem { override val atMs get() = toMs }
}

object TireHistory {

    fun sensorDays(frames: List<Triple<String, Long, Triple<Int?, Int?, Int?>>>, zone: ZoneId): List<SensorDay> =
        frames.groupBy { (id, at, _) -> id to Instant.ofEpochMilli(at).atZone(zone).toLocalDate() }.map { (key, list) ->
            val (id, day) = key
            val p = list.mapNotNull { it.third.first }; val t = list.mapNotNull { it.third.second }
            SensorDay(id, day.atStartOfDay(zone).toInstant().toEpochMilli(), list.size, list.minOf { it.second }, list.maxOf { it.second },
                p.minOrNull(), p.maxOrNull(), t.minOrNull(), t.maxOrNull(), list.maxByOrNull { it.second }?.third?.third)
        }

    /**
     * Everything that happened to the wheels of [vehicleId], newest first, filtered by [position]
     * (null = both) and the window [fromMs, toMs].
     */
    fun build(data: TireData, bindings: List<SensorBinding>, days: List<SensorDay>, vehicleId: String, position: WheelPosition?,
        fromMs: Long, toMs: Long, settings: TireSettings, now: Long, alerts: List<TpmsAlertEvent> = emptyList()): List<TireHistoryItem> {
        val out = ArrayList<TireHistoryItem>()
        val wheels = position?.let { listOf(it) } ?: WheelPosition.entries
        for (w in wheels) {
            val tires = data.tires.filter { it.vehicleId == vehicleId && it.position == w }
            for (t in tires) {
                val events = data.events.filter { it.tireId == t.id }
                events.forEach { out += TireHistoryItem.Event(w, t, it) }
                t.removedAt?.let { out += TireHistoryItem.Removed(w, t) }
                // Gaps between checks over the tyre's life (or until now)
                val checks = (listOf(t.installedAt) + events.filter { it.kind == TireEventKind.CHECK }.map { it.atMs }).sorted()
                val end = t.removedAt ?: now
                (checks + end).zipWithNext().forEach { (a, b) ->
                    val d = (b - a) / 86_400_000L
                    if (d > settings.checkDays) out += TireHistoryItem.Gap(w, a, b, d)
                }
            }
            val mine = bindings.filter { it.vehicleId == vehicleId && it.position == w }
            mine.forEach { b ->
                out += TireHistoryItem.SensorOn(w, b)
                b.unboundAt?.let { out += TireHistoryItem.SensorOff(w, b) }
                days.filter { it.sensorId == b.sensorId && it.lastAt >= b.boundAt && (b.unboundAt == null || it.firstAt <= b.unboundAt) }
                    .forEach { out += TireHistoryItem.Sensor(w, it) }
            }
        }
        alerts.filter { position == null || it.position == position }.forEach { out += TireHistoryItem.Alert(it.position, it) }
        return out.filter { it.atMs in fromMs..toMs }.sortedByDescending { it.atMs }
    }
}

/** 1.55.0: what a tyre cost per kilometre, only with real money and a real odometer. */
data class TireCost(
    val tire: Tire,
    /** Sum of the expenses linked to its installation and repairs (existing ones only). */
    val totalMinor: Long,
    val expenses: Int,
    val km: Long?,
    /** Cents per km, rounded; null with [reason]. */
    val perKmMinor: Long?,
    val reason: String?,
    val fromMs: Long,
    val toMs: Long?
)

object TireCosts {
    /** Under this distance a cost per km says more about the start than about the tyre. */
    const val MIN_KM = 500L

    fun of(tire: Tire, data: TireData, expenses: List<ExpenseEntry>, timeline: List<OdometerPoint>): TireCost {
        val byId = expenses.associateBy { it.id }
        val linked = data.events.filter { it.tireId == tire.id }.mapNotNull { it.expenseId?.let(byId::get) }
        val total = linked.sumOf { it.amountMinor }
        val (km, why) = TireRules.lifeKm(tire, timeline)
        val reason = when {
            linked.isEmpty() -> "Sin gastos enlazados a esta goma"
            km == null -> why
            km < MIN_KM -> "Solo ${km} km recorridos: se calcula a partir de $MIN_KM km"
            else -> null
        }
        val perKm = if (reason == null && km != null) BigDecimal(total).divide(BigDecimal(km), 0, RoundingMode.HALF_UP).toLong() else null
        return TireCost(tire, total, linked.size, km, perKm, reason, tire.installedAt, tire.removedAt)
    }

    fun line(c: TireCost): String {
        val money = "RD$" + BigDecimal.valueOf(c.totalMinor, 2).toPlainString()
        return if (c.perKmMinor != null)
            "${c.tire.title}: RD$" + BigDecimal.valueOf(c.perKmMinor, 2).toPlainString() + "/km · $money en ${c.expenses} " +
                (if (c.expenses == 1) "gasto" else "gastos") + " · ${c.km} km" + (if (c.tire.active) " hasta ahora" else "")
        else "${c.tire.title}: ${c.reason}" + if (c.totalMinor > 0) " ($money registrados)" else ""
    }
}

/** 1.55.0: «Exportar neumáticos»: every row of the section, all columns, units as written and converted. */
object TireExport {
    private fun esc(v: Any?): String = (v?.toString() ?: "").let { t ->
        if (t.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + t.replace("\"", "\"\"") + "\"" else t
    }
    private fun row(vararg v: Any?) = v.joinToString(",") { esc(it) } + "\r\n"
    private fun iso(at: Long?, zone: ZoneId) = at?.let { Instant.ofEpochMilli(it).atZone(zone).toOffsetDateTime().toString() }
    private fun psi(pa: Int?) = pa?.let { TirePressure.format(it, PressureUnit.PSI).removeSuffix(" psi") }
    private fun kpa(pa: Int?) = pa?.let { BigDecimal(it).divide(BigDecimal(1000), 3, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() }

    fun tires(data: TireData, zone: ZoneId): String = "﻿" + row("id", "vehicle_id", "position", "brand", "model", "size",
        "target_entered", "target_unit", "target_pa", "target_psi", "target_kpa", "installed_at_ms", "installed_at", "installed_odometer_km",
        "removed_at_ms", "removed_at", "removed_odometer_km", "zone", "created_at_ms", "updated_at_ms") +
        data.tires.sortedBy { it.installedAt }.joinToString("") { t ->
            row(t.id, t.vehicleId, t.position.storageKey, t.brand, t.model, t.size, t.target?.entered, t.target?.unit?.storageKey, t.target?.pascals,
                psi(t.target?.pascals), kpa(t.target?.pascals), t.installedAt, iso(t.installedAt, zone), t.installedOdometerKm,
                t.removedAt, iso(t.removedAt, zone), t.removedOdometerKm, zone.id, t.createdAt, t.updatedAt)
        }

    fun events(data: TireData, expenses: List<ExpenseEntry>, zone: ZoneId): String {
        val byId = expenses.associateBy { it.id }
        val tires = data.tires.associateBy { it.id }
        return "﻿" + row("id", "tire_id", "position", "kind", "source", "at_ms", "at", "zone", "pressure_entered", "pressure_unit", "pressure_pa",
            "pressure_psi", "pressure_kpa", "temperature", "odometer_km", "expense_id", "expense_amount_minor", "expense_currency", "note",
            "created_at_ms", "updated_at_ms") +
            data.events.sortedBy { it.atMs }.joinToString("") { e ->
                val x = e.expenseId?.let(byId::get)
                row(e.id, e.tireId, tires[e.tireId]?.position?.storageKey, e.kind.storageKey, "MANUAL", e.atMs, iso(e.atMs, zone), zone.id,
                    e.pressure?.entered, e.pressure?.unit?.storageKey, e.pressure?.pascals, psi(e.pressure?.pascals), kpa(e.pressure?.pascals),
                    e.temperature.storageKey, e.odometerKm, e.expenseId, x?.amountMinor, x?.currencyCode, e.note, e.createdAt, e.updatedAt)
            }
    }

    fun sensors(bindings: List<SensorBinding>, zone: ZoneId): String = "﻿" + row("id", "vehicle_id", "position", "sensor_id", "family",
        "bound_at_ms", "bound_at", "unbound_at_ms", "unbound_at", "zone") +
        bindings.sortedBy { it.boundAt }.joinToString("") { b ->
            row(b.id, b.vehicleId, b.position.storageKey, SensorIds.shown(b.sensorId), b.family, b.boundAt, iso(b.boundAt, zone), b.unboundAt, iso(b.unboundAt, zone), zone.id)
        }

    fun readme(zone: ZoneId, now: Long, tires: Int, events: Int, sensors: Int, frames: Int): String =
        "Observador · Neumáticos y sensores\r\nCreado: ${iso(now, zone)} (zona ${zone.id})\r\n\r\n" +
        "Contiene todo, sin recortar: gomas ($tires), revisiones, instalaciones y reparaciones ($events), sensores asociados con su ID completo ($sensors)\r\n" +
        "y todos los anuncios Bluetooth grabados ($frames, con el anuncio crudo).\r\n\r\n" +
        "Presión: el valor tal como se escribió (pressure_entered + pressure_unit) y sus conversiones (pa, psi, kpa).\r\n" +
        "Sensores: pressure_pa es lo que envía el sensor; aún no se ha confirmado si es absoluta o manométrica.\r\n" +
        "Temperatura del sensor en centésimas de °C. Montos en centavos (amount_minor). Fechas en ms desde 1970 (UTC) y en ISO con la zona.\r\n" +
        "Origen: MANUAL = anotado por ti; los anuncios grabados son del sensor.\r\n"
}
