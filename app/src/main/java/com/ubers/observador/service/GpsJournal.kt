package com.ubers.observador.service

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.util.Log
import com.ubers.observador.domain.backup.MaintenanceGate
import com.ubers.observador.domain.distance.DistanceBucket
import com.ubers.observador.domain.distance.LocationFix
import com.ubers.observador.domain.gps.BatteryCodec
import com.ubers.observador.domain.gps.BatterySample
import com.ubers.observador.domain.gps.GpsFrequency
import com.ubers.observador.domain.gps.MovementDetector
import com.ubers.observador.domain.gps.SignalCodec
import com.ubers.observador.domain.gps.SignalRecord
import com.ubers.observador.domain.gps.SignalTracker
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 1.45.0: what the GPS and the battery did, kept for good in the app's own folders (they travel in
 * every backup and in «Exportar todo»; nothing here is deleted on its own):
 *
 * - filesDir/gps/senal-AAAA-MM-DD.txt: signal per phase, once a minute (SignalCodec).
 * - filesDir/bateria/AAAA-MM-DD.txt: one battery reading a minute while the service runs (BatteryCodec).
 * - filesDir/lugares/consultas-AAAA-MM.txt: every place-name lookup and its answer.
 *
 * Called from the foreground service on the main thread; file writes are small appends that wait
 * for no one and are skipped while a restore or an erase holds the data.
 */
object GpsJournal {

    private const val TAG = "GpsJournal"
    const val SIGNAL_DIR = "gps"
    const val BATTERY_DIR = "bateria"
    const val PLACES_DIR = "lugares"

    private val signal = SignalTracker()
    private val movement = MovementDetector()
    private var files: File? = null

    @Synchronized
    fun start(context: Context) {
        if (files == null) files = context.applicationContext.filesDir
    }

    val moving: Boolean @Synchronized get() = movement.moving

    @Synchronized
    fun onRequest(on: Boolean, nowMs: Long) {
        signal.onRequest(on, nowMs)
        if (!on) movement.reset()
    }

    @Synchronized
    fun onPhase(phase: DistanceBucket, nowMs: Long) = signal.onPhase(phase, nowMs)

    /** Returns whether moving/stopped changed (the GPS request may need a new interval). */
    @Synchronized
    fun onFix(fix: LocationFix): Boolean {
        signal.onFix(fix, System.currentTimeMillis())
        return movement.onFix(fix)
    }

    /** Once a minute from the service: writes the signal of the last minute and a battery reading. */
    fun tick(context: Context, nowMs: Long, gpsOn: Boolean, frequency: GpsFrequency, phase: DistanceBucket) {
        val records = synchronized(this) { signal.flush(nowMs) }
        val day = Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault()).toLocalDate()
        if (records.isNotEmpty()) append(SIGNAL_DIR, "senal-$day.txt", records.joinToString("\n") { SignalCodec.encode(it) })
        batterySample(context, nowMs, gpsOn, frequency, phase)?.let { append(BATTERY_DIR, "$day.txt", BatteryCodec.encode(it)) }
    }

    /** The service is going away: what the last minute had is written now. */
    fun stop(nowMs: Long) {
        val records = synchronized(this) { signal.onRequest(false, nowMs); signal.flush(nowMs) }
        val day = Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault()).toLocalDate()
        if (records.isNotEmpty()) append(SIGNAL_DIR, "senal-$day.txt", records.joinToString("\n") { SignalCodec.encode(it) })
    }

    /** «Lugares»: each lookup of a name for a GPS point, answered or not (no internet). */
    fun onPlaceLookup(context: Context, nowMs: Long, latE7: Int, lngE7: Int, answer: String?) {
        if (files == null) start(context)
        val month = Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault()).toString().toLocalDate().substring(0, 7)
        val text = answer?.replace('\n', ' ')?.replace(';', ',') ?: "sin respuesta"
        append(PLACES_DIR, "consultas-$month.txt", "l1;$nowMs;$latE7;$lngE7;$text")
    }

    private fun append(dir: String, name: String, lines: String) {
        val root = files ?: return
        MaintenanceGate.shared.tryAccess {
            try {
                val d = File(root, dir)
                d.mkdirs()
                File(d, name).appendText(lines + "\n")
            } catch (e: Exception) {
                Log.w(TAG, "Could not write $dir", e)
            }
        }
    }

    private fun batterySample(context: Context, nowMs: Long, gpsOn: Boolean, frequency: GpsFrequency, phase: DistanceBucket): BatterySample? = try {
        val app = context.applicationContext
        val status: Intent? = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = status?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = status?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level < 0 || scale <= 0) null else {
            val plugged = (status?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
            val counter = app.getSystemService(BatteryManager::class.java)
                ?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
                ?.takeIf { it > 0 && it != Long.MIN_VALUE }
            BatterySample(
                atMs = nowMs,
                levelPct = (level * 100 / scale).coerceIn(0, 100),
                chargeUah = counter,
                charging = plugged,
                screenOn = app.getSystemService(PowerManager::class.java)?.isInteractive ?: true,
                gpsOn = gpsOn,
                frequency = frequency,
                phase = phase
            )
        }
    } catch (e: Exception) {
        Log.w(TAG, "Battery not readable", e)
        null
    }

    // ---- Reading back, for the screens ----

    fun signalRecords(context: Context, fromMs: Long, toMs: Long): List<SignalRecord> =
        daysBetween(fromMs, toMs).flatMap { day ->
            read(context, SIGNAL_DIR, "senal-$day.txt")?.let { SignalCodec.decodeAll(it) }.orEmpty()
        }.filter { it.atMs in fromMs..toMs }

    fun batterySamples(context: Context, days: Int = 30, nowMs: Long = System.currentTimeMillis()): List<BatterySample> =
        daysBetween(nowMs - days * 86_400_000L, nowMs).flatMap { day ->
            read(context, BATTERY_DIR, "$day.txt")?.let { BatteryCodec.decodeAll(it) }.orEmpty()
        }

    private fun daysBetween(fromMs: Long, toMs: Long): List<LocalDate> {
        val zone = ZoneId.systemDefault()
        var d = Instant.ofEpochMilli(fromMs).atZone(zone).toLocalDate()
        val end = Instant.ofEpochMilli(toMs).atZone(zone).toLocalDate()
        val out = ArrayList<LocalDate>()
        while (!d.isAfter(end) && out.size < 400) { out += d; d = d.plusDays(1) }
        return out
    }

    private fun read(context: Context, dir: String, name: String): String? = try {
        File(File(context.applicationContext.filesDir, dir), name).takeIf { it.isFile }?.readText()
    } catch (e: Exception) { null }
}
