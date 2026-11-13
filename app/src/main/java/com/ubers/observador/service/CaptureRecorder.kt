package com.ubers.observador.service

import android.content.Context
import android.util.Log
import com.ubers.observador.domain.lifecycle.CaptureDeduper
import com.ubers.observador.domain.lifecycle.CaptureLog
import com.ubers.observador.domain.lifecycle.ScreenClassification
import com.ubers.observador.domain.lifecycle.ScreenFingerprint
import com.ubers.observador.domain.lifecycle.ScreenSignatures
import com.ubers.observador.domain.lifecycle.ScreenSnapshot
import com.ubers.observador.domain.lifecycle.SnapshotSanitizer
import java.io.File
import java.io.OutputStream
import java.time.ZoneId
import com.ubers.observador.domain.backup.MaintenanceGate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 1.33.2, debug builds only: records every different Uber screen during real trips (names and
 * addresses hidden, amounts kept) into filesDir/capturas, one file per day. The switch lives in the
 * app's private preferences, not in DataStore, so it never travels in a backup. Files stay on the
 * phone until the driver exports them.
 */
object CaptureRecorder {

    private const val TAG = "CaptureRecorder"
    private const val PREFS = "debug_tools"
    private const val KEY_ON = "capture_recording"

    @Volatile var enabled: Boolean = false
        private set

    private var dir: File? = null
    private val deduper = CaptureDeduper()

    fun init(context: Context) {
        val app = context.applicationContext
        dir = File(app.filesDir, "capturas")
        enabled = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, false)
    }

    fun setEnabled(context: Context, on: Boolean) = MaintenanceGate.shared.tryAccess {
        init(context)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, on).apply()
        enabled = on
        synchronized(deduper) { deduper.reset() }
    }

    /** Called for every Uber snapshot; cheap when off. */
    fun record(
        snapshot: ScreenSnapshot,
        offerHash: String?,
        classification: ScreenClassification?,
        state: String,
        signatures: ScreenSignatures
    ) {
        if (!enabled) return
        val target = dir ?: return
        val fingerprint = ScreenFingerprint.of(snapshot, offerHash)
        if (!synchronized(deduper) { deduper.isNew(fingerprint) }) return
        // The listing is built here, on the reading thread, so no Android node outlives this call
        val zone = ZoneId.systemDefault()
        val body = SnapshotSanitizer.describe(snapshot, signatures, keepMeasureDigits = true)
        val text = CaptureLog.entry(snapshot.timestampMs, zone, classification, state, body)
        val name = CaptureLog.fileName(snapshot.timestampMs, zone)
        PersistenceQueue.shared().enqueue {
            try {
                target.mkdirs()
                val file = File(target, name)
                if (file.length() + text.length > CaptureLog.MAX_BYTES_PER_DAY) return@enqueue
                file.appendText(text, Charsets.UTF_8)
            } catch (e: Exception) {
                Log.w(TAG, "Could not write a capture", e)
            }
        }
    }

    fun files(context: Context): List<File> {
        init(context)
        return dir?.listFiles { f -> f.isFile && f.name.startsWith("capturas-") }?.sortedBy { it.name }.orEmpty()
    }

    fun summary(context: Context): String {
        val list = files(context)
        val entries = list.sumOf { f -> runCatching { CaptureLog.countEntries(f.readText(Charsets.UTF_8)) }.getOrDefault(0) }
        return CaptureLog.summary(list.size, entries, list.sumOf { it.length() })
    }

    /** One .zip with every day's file, written to [out] (closed afterwards). */
    fun export(context: Context, out: OutputStream) {
        val list = files(context)
        ZipOutputStream(out.buffered()).use { zip ->
            list.forEach { f ->
                zip.putNextEntry(ZipEntry(f.name))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    fun clear(context: Context) = MaintenanceGate.shared.tryAccess {
        files(context).forEach { it.delete() }
        synchronized(deduper) { deduper.reset() }
    }
}
