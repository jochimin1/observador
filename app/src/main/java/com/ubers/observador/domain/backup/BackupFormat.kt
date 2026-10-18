package com.ubers.observador.domain.backup

import java.io.InputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What a backup file is (1.31.0): a .zip with the database (`observador.db`), the settings
 * (`ajustes.json`) and a control sheet (`ficha.json`) that carries the versions, the rows per
 * table and the SHA-256 of the other two. Everything here is pure, so the rules (which settings
 * travel, how the sheet is written and read, what counts as damaged) are tested on the JVM.
 */
data class BackupManifest(
    val format: Int,
    val appVersion: String,
    val appVersionCode: Int,
    /** Room's version of the database inside the file. */
    val dbVersion: Int,
    val createdAtMs: Long,
    /** Rows of every table, by table name. */
    val rows: Map<String, Int>,
    /** SHA-256 (lowercase hex) of [BackupFormat.ENTRY_DB] and [BackupFormat.ENTRY_SETTINGS]. */
    val sha256: Map<String, String>
)

/** The settings read back from a file: typed values, plus the names that could not be read. */
data class DecodedSettings(val values: Map<String, Any>, val rejected: List<String>)

object BackupFormat {

    const val FORMAT_VERSION = 1
    const val ENTRY_DB = "observador.db"
    const val ENTRY_SETTINGS = "ajustes.json"
    const val ENTRY_MANIFEST = "ficha.json"

    /**
     * Settings that describe the moment, not the driver's choices: measuring km, closed with the
     * button, paused, the welcome already seen, notices already sent today and the export history.
     * Restored on another phone they would start it paused or measuring for no reason.
     */
    val VOLATILE_SETTINGS: Set<String> = setOf(
        "distance_manual", "closed_by_user", "observer_enabled", "onboarding_done", "last_backup_at",
        "export_history", "goal_notified_day", "goal_notified_week", "weekly_report_notified"
    )

    /** Settings written once per kind of maintenance («maintenance_notified_aceite»…). */
    val VOLATILE_PREFIXES: List<String> = listOf("maintenance_notified_")

    fun fileName(atMs: Long, zone: ZoneId): String =
        "observador-respaldo-" +
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm").format(Instant.ofEpochMilli(atMs).atZone(zone)) +
            ".zip"

    fun isVolatile(key: String): Boolean = key in VOLATILE_SETTINGS || VOLATILE_PREFIXES.any { key.startsWith(it) }

    private fun supported(v: Any?): Boolean =
        v is Boolean || v is Int || v is Long || v is Float || v is Double || v is String ||
            (v is Set<*> && v.all { it is String })

    /** The settings that go in the backup: everything except the moment's state and unsupported types. */
    fun selectSettings(all: Map<String, Any?>): Map<String, Any> {
        val out = java.util.TreeMap<String, Any>()
        for ((k, v) in all) if (v != null && !isVolatile(k) && supported(v)) out[k] = v
        return out
    }

    /** `{"cost_per_km":{"t":"double","v":4.5}, …}`: every value with its type, so it comes back as it was. */
    fun encodeSettings(values: Map<String, Any>): String {
        val out = LinkedHashMap<String, Any?>()
        for (key in values.keys.sorted()) {
            val entry = LinkedHashMap<String, Any?>()
            when (val v = values.getValue(key)) {
                is Boolean -> { entry["t"] = "bool"; entry["v"] = v }
                is Int -> { entry["t"] = "int"; entry["v"] = v }
                is Long -> { entry["t"] = "long"; entry["v"] = v }
                is Float -> { entry["t"] = "float"; entry["v"] = v.toDouble() }
                is Double -> { entry["t"] = "double"; entry["v"] = v }
                is String -> { entry["t"] = "string"; entry["v"] = v }
                is Set<*> -> { entry["t"] = "set"; entry["v"] = v.map { it as String }.sorted() }
                else -> throw IllegalArgumentException("Ajuste de tipo no admitido: $key")
            }
            out[key] = entry
        }
        return MiniJson.write(out)
    }

    /** Null when the text is not a settings file at all; unreadable single entries go in `rejected`. */
    fun decodeSettings(text: String): DecodedSettings? {
        val root = MiniJson.parseOrNull(text) as? Map<*, *> ?: return null
        val values = LinkedHashMap<String, Any>()
        val rejected = ArrayList<String>()
        for ((k, raw) in root) {
            val key = k as? String ?: return null
            val entry = raw as? Map<*, *>
            val decoded = entry?.let { decodeValue(it["t"], it["v"]) }
            if (decoded == null) rejected += key else values[key] = decoded
        }
        return DecodedSettings(values, rejected)
    }

    private fun decodeValue(type: Any?, v: Any?): Any? = when (type) {
        "bool" -> v as? Boolean
        "int" -> (v as? Long)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
        "long" -> v as? Long
        "float" -> (v as? Number)?.toFloat()
        "double" -> (v as? Number)?.toDouble()
        "string" -> v as? String
        "set" -> (v as? List<*>)?.takeIf { l -> l.all { it is String } }?.map { it as String }?.toSet()
        else -> null
    }

    fun encodeManifest(m: BackupManifest): String = MiniJson.write(
        linkedMapOf<String, Any?>(
            "formato" to m.format,
            "app" to "observador",
            "version" to m.appVersion,
            "codigo" to m.appVersionCode,
            "baseDeDatos" to m.dbVersion,
            "creado" to m.createdAtMs,
            "filas" to m.rows.toSortedMap(),
            "sha256" to m.sha256.toSortedMap()
        )
    )

    /** Null when the sheet is missing a field, has the wrong type, or is not from Observador. */
    fun decodeManifest(text: String): BackupManifest? {
        val root = MiniJson.parseOrNull(text) as? Map<*, *> ?: return null
        if (root["app"] != "observador") return null
        fun int(name: String): Int? = (root[name] as? Long)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
        val rows = (root["filas"] as? Map<*, *>)?.let { m ->
            m.entries.associate { (k, v) ->
                val name = k as? String ?: return null
                val n = (v as? Long)?.takeIf { it in 0..Int.MAX_VALUE }?.toInt() ?: return null
                name to n
            }
        } ?: return null
        val sha = (root["sha256"] as? Map<*, *>)?.let { m ->
            m.entries.associate { (k, v) -> (k as? String ?: return null) to (v as? String ?: return null) }
        } ?: return null
        return BackupManifest(
            format = int("formato") ?: return null,
            appVersion = root["version"] as? String ?: return null,
            appVersionCode = int("codigo") ?: return null,
            dbVersion = int("baseDeDatos") ?: return null,
            createdAtMs = root["creado"] as? Long ?: return null,
            rows = rows,
            sha256 = sha
        )
    }

    /** Entries whose SHA-256 is missing from the sheet or differs from the one computed on the file. */
    fun mismatches(m: BackupManifest, actual: Map<String, String>): List<String> =
        listOf(ENTRY_DB, ENTRY_SETTINGS).filter { name ->
            val expected = m.sha256[name]
            val found = actual[name]
            expected == null || found == null || !expected.equals(found, ignoreCase = true)
        }

    fun sha256Hex(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun sha256Hex(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return hex(md.digest())
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    // --- the line shown when a backup is made (and, later, when one is restored) ---

    private fun count(n: Int, one: String, many: String): String =
        String.format(Locale.US, "%,d", n) + " " + if (n == 1) one else many

    fun sizeText(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    }

    /** «1,284 ofertas, 96 viajes, 12 gastos». */
    fun contentText(rows: Map<String, Int>): String = listOf(
        count(rows["detected_offers"] ?: 0, "oferta", "ofertas"),
        count(rows["trips"] ?: 0, "viaje", "viajes"),
        count((rows["expense_entries"] ?: 0) + (rows["fuel_entries"] ?: 0), "gasto", "gastos")
    ).joinToString(", ")

    fun createdText(m: BackupManifest, bytes: Long): String = "Respaldo creado: ${contentText(m.rows)} · ${sizeText(bytes)}."
}
