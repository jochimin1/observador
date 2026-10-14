package com.ubers.observador.domain.reports

import com.ubers.observador.domain.WorkdayClock
import com.ubers.observador.domain.earnings.DayBar
import com.ubers.observador.domain.expenses.ExpenseEntry
import com.ubers.observador.domain.expenses.ExpenseGroup
import com.ubers.observador.domain.expenses.FuelEntry
import com.ubers.observador.domain.finance.FinancialCompleteness
import com.ubers.observador.domain.finance.TripFinancialSummary
import com.ubers.observador.domain.money.Currencies
import com.ubers.observador.domain.money.MoneyAmount
import com.ubers.observador.domain.trips.Trip
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/*
 * Fase 8D: the files for the driver's own accounts. Pure text from the same figures as
 * Ganancias (no formulas of their own). Amounts are written as exact decimals from the stored
 * minor units, always with a currency column. Never coordinates. Since Fase 8F (decision of
 * 14/10) the trips file also carries the rider's name and the addresses Uber showed.
 */

/** "1978.38" from 197838 centavos; exact, never through a Double. */
object Decimals {
    fun of(minor: Long, currencyCode: String): String =
        BigDecimal.valueOf(minor, Currencies.minorDigits(currencyCode) ?: Currencies.DEFAULT_MINOR_DIGITS).toPlainString()

    fun of(amount: MoneyAmount?): String = amount?.let { of(it.minorUnits, it.currencyCode) } ?: ""

    /** Gallons from thousandths: 1250 -> "1.250". */
    fun gallons(milli: Long): String = BigDecimal.valueOf(milli, 3).toPlainString()
}

/** CSV with a BOM (so Excel reads the accents) and CRLF lines, like the offers export. */
object Csv {
    const val BOM = "﻿"

    fun escape(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + value.replace("\"", "\"\"") + "\"" else value

    fun build(header: List<String>, rows: List<List<String>>): String {
        val sb = StringBuilder(BOM)
        sb.append(header.joinToString(",") { escape(it) }).append("\r\n")
        rows.forEach { row ->
            require(row.size == header.size) { "row has ${row.size} cells, header ${header.size}" }
            sb.append(row.joinToString(",") { escape(it) }).append("\r\n")
        }
        return sb.toString()
    }
}

/** A minimal JSON writer: no Android classes, so the files can be tested on the JVM. */
object Json {
    fun str(value: String?): String {
        if (value == null) return "null"
        val sb = StringBuilder("\"")
        value.forEach { c ->
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append("\\u%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    /** A decimal written as a JSON number ("1978.38"), or null when empty. */
    fun num(decimal: String): String = decimal.ifEmpty { "null" }

    fun num(value: Long?): String = value?.toString() ?: "null"

    fun bool(value: Boolean?): String = value?.toString() ?: "null"

    fun obj(vararg fields: Pair<String, String>): String = fields.joinToString(",", "{", "}") { str(it.first) + ":" + it.second }

    fun arr(items: List<String>): String = if (items.isEmpty()) "[]" else items.joinToString(",\n  ", "[\n  ", "\n]")
}

private val DATE = DateTimeFormatter.ISO_LOCAL_DATE
private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss")
private val DATE_TIME = DateTimeFormatter.ISO_OFFSET_DATE_TIME

/** One trip of the "Viajes y ganancias" file. */
data class TripExportRow(
    val tripId: String,
    val workday: LocalDate,
    val acceptedAt: Long?,
    val endedAt: Long?,
    val status: String,
    val offered: MoneyAmount?,
    /** Null unless the money is CONFIRMED. Never the offer. */
    val real: MoneyAmount?,
    val fareMinor: Long,
    val tipMinor: Long,
    val promotionMinor: Long,
    val adjustmentMinor: Long,
    val cancellationFeeMinor: Long,
    val otherMinor: Long,
    val completeness: FinancialCompleteness,
    val anomalies: List<String>,
    val currencyCode: String,
    val riderName: String? = null,
    val pickupAddress: String? = null,
    val dropoffAddress: String? = null
)

object TripExport {

    val HEADER = listOf(
        "viaje_id", "jornada", "fecha", "hora_aceptado", "hora_fin", "estado", "ofertado", "real", "tarifa", "propina",
        "promocion", "ajuste", "cargo_cancelacion", "otros", "completitud", "anomalias", "moneda",
        "pasajero", "origen", "destino"
    )

    fun rows(trips: List<Trip>, summaries: Map<String, TripFinancialSummary>, clock: WorkdayClock, currencyCode: String = Currencies.DOP): List<TripExportRow> =
        trips.sortedBy { it.acceptedAt ?: it.createdAt }.map { t ->
            val s = summaries[t.id]
            val currency = s?.currencyCode ?: currencyCode
            TripExportRow(
                tripId = t.id,
                workday = clock.workdayOf(t.acceptedAt ?: t.createdAt),
                acceptedAt = t.acceptedAt,
                endedAt = t.completedAt ?: t.cancelledAt ?: t.interruptedAt,
                status = t.status.storageKey,
                offered = s?.offeredAmount ?: t.offer?.offeredAmount?.let { MoneyAmount.fromLegacyDouble(it, currencyCode) },
                real = s?.effectiveEarnings?.takeIf { s.completeness == FinancialCompleteness.CONFIRMED },
                fareMinor = s?.fare ?: 0,
                tipMinor = s?.tip ?: 0,
                promotionMinor = s?.promotion ?: 0,
                adjustmentMinor = s?.adjustment ?: 0,
                cancellationFeeMinor = s?.cancellationFee ?: 0,
                otherMinor = s?.other ?: 0,
                completeness = s?.completeness ?: FinancialCompleteness.UNKNOWN,
                anomalies = s?.anomalies?.map { it.storageKey }?.sorted() ?: emptyList(),
                currencyCode = currency,
                riderName = t.riderName,
                pickupAddress = t.offer?.pickupAddress,
                dropoffAddress = t.offer?.dropoffAddress
            )
        }

    fun csv(rows: List<TripExportRow>, zone: ZoneId): String = Csv.build(HEADER, rows.map { r ->
        val start = r.acceptedAt ?: r.endedAt
        listOf(
            r.tripId,
            r.workday.format(DATE),
            start?.let { Instant.ofEpochMilli(it).atZone(zone).format(DATE) } ?: "",
            r.acceptedAt?.let { Instant.ofEpochMilli(it).atZone(zone).format(TIME) } ?: "",
            r.endedAt?.let { Instant.ofEpochMilli(it).atZone(zone).format(TIME) } ?: "",
            r.status,
            Decimals.of(r.offered),
            Decimals.of(r.real),
            Decimals.of(r.fareMinor, r.currencyCode),
            Decimals.of(r.tipMinor, r.currencyCode),
            Decimals.of(r.promotionMinor, r.currencyCode),
            Decimals.of(r.adjustmentMinor, r.currencyCode),
            Decimals.of(r.cancellationFeeMinor, r.currencyCode),
            Decimals.of(r.otherMinor, r.currencyCode),
            r.completeness.storageKey,
            r.anomalies.joinToString("|"),
            r.currencyCode,
            r.riderName ?: "",
            r.pickupAddress ?: "",
            r.dropoffAddress ?: ""
        )
    })

    fun json(rows: List<TripExportRow>, zone: ZoneId, exportedAtMs: Long, from: LocalDate, to: LocalDate): String = Json.obj(
        "exportedAt" to Json.str(Instant.ofEpochMilli(exportedAtMs).atZone(zone).format(DATE_TIME)),
        "from" to Json.str(from.format(DATE)),
        "to" to Json.str(to.format(DATE)),
        "count" to rows.size.toString(),
        "note" to Json.str(PeriodSummaryText.NOTE),
        "trips" to Json.arr(rows.map { r ->
            Json.obj(
                "tripId" to Json.str(r.tripId),
                "workday" to Json.str(r.workday.format(DATE)),
                "acceptedAt" to Json.str(r.acceptedAt?.let { Instant.ofEpochMilli(it).atZone(zone).format(DATE_TIME) }),
                "endedAt" to Json.str(r.endedAt?.let { Instant.ofEpochMilli(it).atZone(zone).format(DATE_TIME) }),
                "status" to Json.str(r.status),
                "offered" to Json.num(Decimals.of(r.offered)),
                "real" to Json.num(Decimals.of(r.real)),
                "fare" to Json.num(Decimals.of(r.fareMinor, r.currencyCode)),
                "tip" to Json.num(Decimals.of(r.tipMinor, r.currencyCode)),
                "promotion" to Json.num(Decimals.of(r.promotionMinor, r.currencyCode)),
                "adjustment" to Json.num(Decimals.of(r.adjustmentMinor, r.currencyCode)),
                "cancellationFee" to Json.num(Decimals.of(r.cancellationFeeMinor, r.currencyCode)),
                "other" to Json.num(Decimals.of(r.otherMinor, r.currencyCode)),
                "completeness" to Json.str(r.completeness.storageKey),
                "anomalies" to r.anomalies.joinToString(",", "[", "]") { Json.str(it) },
                "currency" to Json.str(r.currencyCode),
                "riderName" to Json.str(r.riderName),
                "pickupAddress" to Json.str(r.pickupAddress),
                "dropoffAddress" to Json.str(r.dropoffAddress)
            )
        })
    )
}

/** One fill-up or expense of the "Gastos" file. */
data class ExpenseExportRow(
    val id: String,
    val atMs: Long,
    val workday: LocalDate,
    val group: String,
    val type: String,
    val amountMinor: Long,
    val currencyCode: String,
    val gallonsMilli: Long?,
    val fullTank: Boolean?,
    val odometerKm: Long?
)

object ExpenseExport {

    val HEADER = listOf("id", "jornada", "fecha", "hora", "grupo", "tipo", "importe", "moneda", "galones", "tanque_lleno", "odometro_km")

    /** Fill-ups and expenses whose working day is in [from]..[to], oldest first. Fixed costs are in the summary, not here. */
    fun rows(fuel: List<FuelEntry>, expenses: List<ExpenseEntry>, from: LocalDate, to: LocalDate, clock: WorkdayClock): List<ExpenseExportRow> {
        fun inside(at: Long) = clock.workdayOf(at).let { !it.isBefore(from) && !it.isAfter(to) }
        val f = fuel.filter { inside(it.atMs) }.map {
            ExpenseExportRow(it.id, it.atMs, clock.workdayOf(it.atMs), ExpenseGroup.FUEL.name, "FUEL", it.amountMinor, it.currencyCode, it.gallonsMilli, it.fullTank, it.odometerKm)
        }
        val e = expenses.filter { inside(it.atMs) }.map {
            ExpenseExportRow(it.id, it.atMs, clock.workdayOf(it.atMs), it.type.group.name, it.type.storageKey, it.amountMinor, it.currencyCode, null, null, it.odometerKm)
        }
        return (f + e).sortedWith(compareBy<ExpenseExportRow> { it.atMs }.thenBy { it.id })
    }

    fun csv(rows: List<ExpenseExportRow>, zone: ZoneId): String = Csv.build(HEADER, rows.map { r ->
        val at = Instant.ofEpochMilli(r.atMs).atZone(zone)
        listOf(
            r.id, r.workday.format(DATE), at.format(DATE), at.format(TIME), r.group, r.type,
            Decimals.of(r.amountMinor, r.currencyCode), r.currencyCode,
            r.gallonsMilli?.let { Decimals.gallons(it) } ?: "",
            r.fullTank?.let { if (it) "si" else "no" } ?: "",
            r.odometerKm?.toString() ?: ""
        )
    })

    fun json(rows: List<ExpenseExportRow>, zone: ZoneId, exportedAtMs: Long, from: LocalDate, to: LocalDate): String = Json.obj(
        "exportedAt" to Json.str(Instant.ofEpochMilli(exportedAtMs).atZone(zone).format(DATE_TIME)),
        "from" to Json.str(from.format(DATE)),
        "to" to Json.str(to.format(DATE)),
        "count" to rows.size.toString(),
        "expenses" to Json.arr(rows.map { r ->
            Json.obj(
                "id" to Json.str(r.id),
                "workday" to Json.str(r.workday.format(DATE)),
                "at" to Json.str(Instant.ofEpochMilli(r.atMs).atZone(zone).format(DATE_TIME)),
                "group" to Json.str(r.group),
                "type" to Json.str(r.type),
                "amount" to Json.num(Decimals.of(r.amountMinor, r.currencyCode)),
                "currency" to Json.str(r.currencyCode),
                "gallons" to Json.num(r.gallonsMilli?.let { Decimals.gallons(it) } ?: ""),
                "fullTank" to Json.bool(r.fullTank),
                "odometerKm" to Json.num(r.odometerKm)
            )
        })
    )
}

/** One working day of the "Resumen por jornada" file. */
data class DayExportRow(
    val day: LocalDate,
    val incomeMinor: Long,
    val completedTrips: Int,
    val confirmedTrips: Int,
    val onlineMs: Long?,
    val expensesWorkMinor: Long,
    val currencyCode: String
) {
    val coveragePct: Int? get() = if (completedTrips == 0) null else (confirmedTrips * 100 + completedTrips / 2) / completedTrips
    val netMinor: Long get() = incomeMinor - expensesWorkMinor

    companion object {
        fun of(bar: DayBar, onlineMs: Long?, expensesWorkMinor: Long, currencyCode: String) =
            DayExportRow(bar.day, bar.incomeMinor, bar.completedTrips, bar.confirmedTrips, onlineMs, expensesWorkMinor, currencyCode)
    }
}

object DailyExport {
    val HEADER = listOf(
        "jornada", "ingreso_real", "viajes", "viajes_con_dinero", "cobertura_pct", "minutos_en_linea", "gastos_trabajo", "neto", "moneda"
    )

    fun csv(rows: List<DayExportRow>): String = Csv.build(HEADER, rows.map { r ->
        listOf(
            r.day.format(DATE),
            Decimals.of(r.incomeMinor, r.currencyCode),
            r.completedTrips.toString(),
            r.confirmedTrips.toString(),
            r.coveragePct?.toString() ?: "",
            r.onlineMs?.let { (it / 60_000).toString() } ?: "",
            Decimals.of(r.expensesWorkMinor, r.currencyCode),
            Decimals.of(r.netMinor, r.currencyCode),
            r.currencyCode
        )
    })
}

/** File names: "observador_viajes_2026-10-05_2026-10-11.csv". */
object ExportNames {
    fun of(kind: String, from: LocalDate, to: LocalDate, extension: String): String =
        if (from == to) "observador_${kind}_${from.format(DATE)}.$extension"
        else "observador_${kind}_${from.format(DATE)}_${to.format(DATE)}.$extension"
}
