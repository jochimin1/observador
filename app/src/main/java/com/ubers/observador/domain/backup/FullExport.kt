package com.ubers.observador.domain.backup

import java.io.Writer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale

/**
 * 1.45.0, «Exportar todo»: one .zip with every stored datum, explicitly and with nothing left out
 * (the driver's rule): every table of the database as CSV with every column (names, addresses,
 * coordinates, sectors, money…), every route as GPX and GeoJSON, every file of the app's own
 * folders, every setting and a LEEME.txt that lists what went in. Unlike the backup it is meant to
 * be read with a spreadsheet or a map; it is not restored.
 */
object FullExport {

    const val README = "LEEME.txt"
    const val SETTINGS = "ajustes.json"
    const val ROUTES_GPX = "recorridos/recorridos.gpx"
    const val ROUTES_GEOJSON = "recorridos/recorridos.geojson"
    private val TABLE = Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")

    /** Tables that are SQLite's or Room's own bookkeeping, not the driver's data. */
    fun isDataTable(name: String): Boolean =
        TABLE.matches(name) && !name.startsWith("sqlite_") && name != "android_metadata" && name != "room_master_table"

    fun tableEntry(table: String): String {
        require(isDataTable(table)) { "not a data table: $table" }
        return "datos/$table.csv"
    }

    fun fileName(atMs: Long, zone: ZoneId, encrypted: Boolean): String =
        "observador-todo-" + DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm").format(Instant.ofEpochMilli(atMs).atZone(zone)) +
            if (encrypted) ".zip.cifrado" else ".zip"

    /** A CSV cell: text as is, numbers in full, blobs in Base64, null empty. */
    fun cell(value: Any?): String = when (value) {
        null -> ""
        is ByteArray -> Base64.getEncoder().encodeToString(value)
        is Double -> if (value.isNaN() || value.isInfinite()) value.toString() else java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString().let { if ('.' in it) it else "$it.0" }
        is Float -> cell(value.toDouble())
        else -> value.toString()
    }

    fun escape(v: String): String =
        if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + v.replace("\"", "\"\"") + "\"" else v

    /** Writes one table: a BOM (Excel reads the accents), the header, then each row; CRLF lines. */
    class TableWriter(private val out: Writer, private val columns: List<String>) {
        var rows = 0
            private set

        init {
            require(columns.isNotEmpty())
            out.write("﻿")
            out.write(columns.joinToString(",") { escape(it) })
            out.write("\r\n")
        }

        fun row(values: List<Any?>) {
            require(values.size == columns.size) { "row has ${values.size} cells, header ${columns.size}" }
            out.write(values.joinToString(",") { escape(cell(it)) })
            out.write("\r\n")
            rows++
        }
    }

    data class Content(
        val createdAtMs: Long,
        val appVersion: String,
        val dbVersion: Int,
        /** Rows written per table. */
        val tables: Map<String, Int>,
        val routes: Int,
        val routePoints: Int,
        /** Entry name → bytes, of the app's own files. */
        val files: Map<String, Long>,
        val settings: Int,
        val encrypted: Boolean
    )

    fun readme(c: Content, zone: ZoneId): String {
        val date = DateTimeFormatter.ofPattern("d/M/yyyy h:mm a", Locale.forLanguageTag("es-DO"))
            .format(Instant.ofEpochMilli(c.createdAtMs).atZone(zone))
        val sb = StringBuilder()
        sb.append("Observador · Exportación completa\r\n")
        sb.append("Creada: $date · app ${c.appVersion} · base de datos ${c.dbVersion}\r\n\r\n")
        sb.append("Contiene TODOS los datos guardados, sin filtrar ni ocultar nada: nombres de pasajeros,\r\n")
        sb.append("direcciones, lugares, coordenadas y rutas GPS, dinero, sectores, lecturas y ajustes.\r\n")
        sb.append(if (c.encrypted) "El archivo está cifrado con tu contraseña.\r\n" else "El archivo NO está cifrado: guárdalo en un lugar seguro.\r\n")
        sb.append("\r\nTablas (datos/*.csv, una fila por registro, todas las columnas):\r\n")
        c.tables.toSortedMap().forEach { (t, n) -> sb.append("  $t: ${String.format(Locale.US, "%,d", n)} filas\r\n") }
        sb.append("\r\nRecorridos: ${c.routes} viajes con ruta, ${String.format(Locale.US, "%,d", c.routePoints)} puntos GPS\r\n")
        sb.append("  $ROUTES_GPX (Google Earth, My Maps) y $ROUTES_GEOJSON\r\n")
        sb.append("\r\nArchivos de la app (archivos/): ${c.files.size}\r\n")
        c.files.toSortedMap().forEach { (name, bytes) -> sb.append("  $name (${BackupFormat.sizeText(bytes)})\r\n") }
        sb.append("\r\nAjustes ($SETTINGS): ${c.settings}, incluidos los del momento.\r\n")
        sb.append("\r\nMontos: unidades menores (centavos) con su moneda, como en la app.\r\n")
        sb.append("Coordenadas: grados × 10.000.000 (lat_e7, lng_e7) en las tablas; en grados en GPX/GeoJSON.\r\n")
        sb.append("Fechas: milisegundos desde 1970 (UTC) en las tablas.\r\n")
        sb.append("Para volver a cargar datos en Observador usa un respaldo, no esta exportación.\r\n")
        return sb.toString()
    }
}
