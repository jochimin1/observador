package com.ubers.observador.data.export

import com.ubers.observador.data.db.OfferEntity
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class ExportFormat(val extension: String, val mimeType: String, val label: String) {
    CSV("csv", "text/csv", "CSV (Excel / Sheets)"),
    JSON("json", "application/json", "JSON")
}

enum class ExportRange(val label: String, val fileTag: String) {
    TODAY("Hoy", "hoy"),
    LAST_7_DAYS("Últimos 7 días", "7dias"),
    ALL("Todo el historial", "todo");

    /** Epoch millis of the first instant included in this range. */
    fun sinceMillis(zone: ZoneId = ZoneId.systemDefault()): Long {
        val today = LocalDate.now(zone)
        return when (this) {
            TODAY -> today.atStartOfDay(zone).toInstant().toEpochMilli()
            LAST_7_DAYS -> today.minusDays(6).atStartOfDay(zone).toInstant().toEpochMilli()
            ALL -> 0L
        }
    }
}

object OfferExporter {

    private const val CSV_HEADER =
        "fecha,hora,precio,km_recogida,min_recogida,km_viaje,min_viaje,km_total,min_total," +
        "rd_por_km,rd_por_hora,costo_combustible,ganancia_neta,score"

    private val DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE
    private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss")

    fun fileName(format: ExportFormat, range: ExportRange): String =
        "observador_${range.fileTag}_${LocalDate.now()}.${format.extension}"

    fun export(offers: List<OfferEntity>, format: ExportFormat): String = when (format) {
        ExportFormat.CSV -> toCsv(offers)
        ExportFormat.JSON -> toJson(offers)
    }

    fun toCsv(offers: List<OfferEntity>, zone: ZoneId = ZoneId.systemDefault()): String {
        // BOM so Excel detects UTF-8; Locale.US keeps '.' as decimal separator
        val sb = StringBuilder("﻿").append(CSV_HEADER).append("\r\n")
        for (o in offers) {
            val detected = Instant.ofEpochMilli(o.detectedAt).atZone(zone)
            sb.append(
                String.format(
                    Locale.US,
                    "%s,%s,%.2f,%.1f,%d,%.1f,%d,%.1f,%d,%.2f,%.2f,%.2f,%.2f,%d",
                    detected.format(DATE_FORMAT),
                    detected.format(TIME_FORMAT),
                    o.price,
                    o.pickupKm,
                    o.pickupMin,
                    o.tripKm,
                    o.tripMin,
                    o.totalKm,
                    o.totalMin,
                    o.rdpPerKm,
                    o.rdpPerHour,
                    o.fuelCost,
                    o.netProfit,
                    o.score
                )
            ).append("\r\n")
        }
        return sb.toString()
    }

    fun toJson(offers: List<OfferEntity>, zone: ZoneId = ZoneId.systemDefault()): String {
        val array = JSONArray()
        for (o in offers) {
            val detected = Instant.ofEpochMilli(o.detectedAt).atZone(zone)
            array.put(
                JSONObject()
                    .put("detectedAt", o.detectedAt)
                    .put("detectedAtIso", detected.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                    .put("price", o.price)
                    .put("pickupKm", o.pickupKm)
                    .put("pickupMin", o.pickupMin)
                    .put("tripKm", o.tripKm)
                    .put("tripMin", o.tripMin)
                    .put("totalKm", o.totalKm)
                    .put("totalMin", o.totalMin)
                    .put("rdpPerKm", o.rdpPerKm)
                    .put("rdpPerHour", o.rdpPerHour)
                    .put("fuelCost", o.fuelCost)
                    .put("netProfit", o.netProfit)
                    .put("score", o.score)
            )
        }
        return JSONObject()
            .put("exportedAt", Instant.now().atZone(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
            .put("count", offers.size)
            .put("offers", array)
            .toString(2)
    }
}
