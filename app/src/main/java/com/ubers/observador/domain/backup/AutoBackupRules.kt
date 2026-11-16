package com.ubers.observador.domain.backup

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 1.56.0: the weekly automatic backup and the list of backups in its folder. */
object AutoBackupRules {
    const val PERIOD_DAYS = 7L
    private val NAME = Regex("observador-respaldo-(auto-)?(\\d{4}-\\d{2}-\\d{2}-\\d{4})(-\\d+)?\\.zip(\\.cifrado)?")

    fun fileName(atMs: Long, zone: ZoneId): String =
        "observador-respaldo-auto-" + DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm").format(Instant.ofEpochMilli(atMs).atZone(zone)) + ".zip"

    data class Entry(val name: String, val bytes: Long, val modifiedAt: Long) {
        val automatic: Boolean get() = name.startsWith("observador-respaldo-auto-")
        val encrypted: Boolean get() = name.endsWith(".cifrado")
    }

    fun isBackup(name: String): Boolean = NAME.matches(name)

    /** Observador's backups in the folder, newest first; nothing is ever removed from the list on its own. */
    fun list(files: List<Entry>): List<Entry> = files.filter { isBackup(it.name) }.sortedByDescending { it.modifiedAt }

    fun totalBytes(list: List<Entry>): Long = list.sumOf { it.bytes }

    /** «Último automático hace 3 días · próximo en 4 días aprox.» */
    fun status(lastOkAt: Long?, lastError: String?, lastErrorAt: Long?, now: Long): String {
        val day = 86_400_000L
        val ok = lastOkAt?.let { val d = (now - it) / day; "Último respaldo automático " + if (d == 0L) "hoy" else if (d == 1L) "ayer" else "hace $d días" }
        val next = lastOkAt?.let { val left = PERIOD_DAYS - (now - it) / day; if (left <= 0) "toca en cuanto haya batería" else "próximo en $left días aprox." }
        val err = if (lastError != null && (lastOkAt == null || (lastErrorAt ?: 0) > lastOkAt)) "Falló el último intento: $lastError" else null
        return listOfNotNull(ok, next, err).joinToString(" · ").ifEmpty { "Todavía no se ha hecho ningún respaldo automático." }
    }
}
