package com.ubers.observador.service

import android.content.Context
import android.util.Log
import com.ubers.observador.data.AppSettings
import com.ubers.observador.domain.health.HealthIncident
import com.ubers.observador.domain.health.HealthReport
import com.ubers.observador.domain.health.HealthRules
import com.ubers.observador.domain.health.HealthSignals
import com.ubers.observador.domain.health.IncidentLog
import com.ubers.observador.domain.health.IncidentTracker
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import java.io.File

/**
 * 1.39.0: collects the evidence that Observador is working (memory only: times, counts and
 * error kinds; no screen text, places or positions) and keeps the short incident history in
 * noBackupFilesDir/salud, outside Android's backups.
 */
object HealthMonitor {

    private const val TAG = "Health"
    private const val TICK_MS = 15_000L
    /** Uber sends many events a second; the time is refreshed at most this often. */
    private const val EVENT_RESOLUTION_MS = 5_000L

    /** What the services report; the permission, switch and queue parts are added in [reports]. */
    private val live = MutableStateFlow(HealthSignals(watchingSince = System.currentTimeMillis()))

    private var file: File? = null
    private val tracker = IncidentTracker()
    private val _incidents = MutableStateFlow<List<HealthIncident>>(emptyList())
    val incidents: StateFlow<List<HealthIncident>> = _incidents.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val f = File(File(context.applicationContext.noBackupFilesDir, "salud"), "incidentes.txt")
        file = f
        _incidents.value = runCatching { if (f.exists()) IncidentLog.decode(f.readText()) else emptyList() }
            .onFailure { Log.w(TAG, "Incident history unreadable; starting empty", it) }
            .getOrDefault(emptyList())
    }

    // ---- Accessibility service ----

    fun onServiceConnected(now: Long) = live.update {
        it.copy(serviceConnectedAt = now, lastUberEventAt = null, lastReadingAt = null, lastRecognizedAt = null,
            unrecognizedRun = 0, unrecognizedSince = null, lastOfferAt = null, lastUnreadableAt = null, lastUnreadableReason = null)
    }

    fun onServiceDisconnected() = live.update { it.copy(serviceConnectedAt = null) }

    fun onUberEvent(now: Long) {
        val last = live.value.lastUberEventAt
        if (last == null || now - last >= EVENT_RESOLUTION_MS) live.update { it.copy(lastUberEventAt = now) }
    }

    /** A new Uber screen was read (repaints of the same content are not counted twice). */
    fun onReading(now: Long, recognized: Boolean) = live.update {
        if (recognized) it.copy(lastReadingAt = now, lastRecognizedAt = now, unrecognizedRun = 0, unrecognizedSince = null)
        else it.copy(lastReadingAt = now, unrecognizedRun = it.unrecognizedRun + 1, unrecognizedSince = it.unrecognizedSince ?: now)
    }

    fun onOffer(now: Long) = live.update { it.copy(lastOfferAt = now) }

    /** [reason] is one of our own labels (ReadingProblem), never screen text. */
    fun onUnreadable(now: Long, reason: String) = live.update { it.copy(lastUnreadableAt = now, lastUnreadableReason = reason) }

    // ---- GPS (foreground service) ----

    fun onGpsRequested(now: Long?) = live.update { it.copy(gpsRequestedAt = now) }

    fun onFix(atMs: Long) {
        val last = live.value.lastFixAt
        if (last == null || atMs - last >= 1_000L) live.update { it.copy(lastFixAt = atMs) }
    }

    fun onBackgroundLimited(limited: Boolean) = live.update { it.copy(gpsBackgroundLimited = limited) }

    // ---- Reports ----

    private val ticker: Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(TICK_MS)
        }
    }

    /**
     * The current report, every [TICK_MS] and on every change. The accessibility setting and the
     * location permission are re-read on each tick: Android does not announce their changes.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun reports(context: Context): Flow<HealthReport> {
        val app = context.applicationContext
        init(app)
        val settings = AppSettings(app)
        return combine(live, settings.observerEnabled, PersistenceQueue.sharedHealth(), ticker) { signals, on, queue, _ ->
            val now = System.currentTimeMillis()
            val s = signals.copy(
                observerOn = on,
                accessibilityEnabled = UberAccessibilityService.isEnabled(app),
                locationPermitted = ObservadorForegroundService.hasLocationPermission(app),
                queue = queue
            )
            HealthRules.evaluate(s, now).also { record(it) }
        }
    }

    /** Writes the incidents a report produces; repeated reports of the same state add nothing. */
    @Synchronized
    fun record(report: HealthReport) {
        val new = tracker.update(report)
        if (new.isEmpty()) return
        val all = IncidentLog.add(_incidents.value, new)
        _incidents.value = all
        file?.let { f ->
            runCatching {
                f.parentFile?.mkdirs()
                val tmp = File(f.parentFile, f.name + ".tmp")
                tmp.writeText(IncidentLog.encode(all))
                if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            }.onFailure { Log.w(TAG, "Incident history not written", it) }
        }
    }

    @Synchronized
    fun clearIncidents() {
        _incidents.value = emptyList()
        file?.let { runCatching { it.delete() } }
    }

    /** Laboratorio: a write that fails on purpose, to see «Guardado» react. Writes nothing. */
    fun simulateWriteFailure(): Boolean = PersistenceQueue.shared().enqueue {
        throw IllegalStateException("Fallo simulado desde Laboratorio")
    }
}
