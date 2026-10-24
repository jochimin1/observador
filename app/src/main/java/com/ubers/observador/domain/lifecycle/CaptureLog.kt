package com.ubers.observador.domain.lifecycle

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 1.33.2: the capture recorder's rules. While it is on (debug builds), every DIFFERENT Uber screen
 * is written to a daily text file with what Observador understood of it, so a whole real trip can
 * be reviewed afterwards without touching the phone while driving. Pure, tested on the JVM.
 */
object CaptureLog {

    /** A day of driving is a few hundred screens; this keeps a runaway day from filling the phone. */
    const val MAX_BYTES_PER_DAY = 3L * 1024 * 1024
    const val ENTRY_MARK = "=== "

    private val DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun fileName(atMs: Long, zone: ZoneId): String =
        "capturas-" + DAY.format(Instant.ofEpochMilli(atMs).atZone(zone)) + ".txt"

    /**
     * «=== 2026-10-24 14:03:12 · pantalla=ARRIVED_OR_WAITING 0.72 provisional · estado=TO_PICKUP ·
     * señales=ARRIVED_ACTION("llegue")» followed by the sanitized node listing and a blank line.
     */
    fun entry(atMs: Long, zone: ZoneId, classification: ScreenClassification?, state: String, body: String): String =
        buildString {
            append(ENTRY_MARK).append(STAMP.format(Instant.ofEpochMilli(atMs).atZone(zone)))
            append(" · pantalla=")
            if (classification == null) {
                append("—")
            } else {
                append(classification.type.name).append(' ')
                append(String.format(Locale.US, "%.2f", classification.confidence))
                if (classification.provisional) append(" provisional")
            }
            append(" · estado=").append(state)
            val signals = classification?.evidence.orEmpty()
            append(" · señales=")
            append(if (signals.isEmpty()) "—" else signals.joinToString(",") { "${it.code.name}(\"${it.matchedSignature}\")" })
            append('\n').append(body).append("\n\n")
        }

    /** «3 días · 412 pantallas · 1.2 MB» for the debug panel. */
    fun summary(days: Int, entries: Int, bytes: Long): String {
        if (days == 0) return "Sin capturas guardadas."
        val size = when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
            else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        }
        return "$days " + (if (days == 1) "día" else "días") + " · $entries " +
            (if (entries == 1) "pantalla" else "pantallas") + " · $size"
    }

    fun countEntries(text: String): Int = text.lineSequence().count { it.startsWith(ENTRY_MARK) }
}

/** Writes a screen only when it differs from the last one written (Uber repaints the same screen often). */
class CaptureDeduper {
    private var last: String? = null

    fun isNew(fingerprint: String): Boolean {
        if (fingerprint == last) return false
        last = fingerprint
        return true
    }

    fun reset() { last = null }
}
