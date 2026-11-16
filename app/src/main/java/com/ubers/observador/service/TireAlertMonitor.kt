package com.ubers.observador.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.util.Log
import com.ubers.observador.MainActivity
import com.ubers.observador.data.AppSettings
import com.ubers.observador.data.db.AppDatabase
import com.ubers.observador.data.db.TireAlertEntity
import com.ubers.observador.data.db.TireSensorFrameEntity
import com.ubers.observador.data.db.toDomain
import com.ubers.observador.domain.backup.MaintenanceGate
import com.ubers.observador.domain.vehicle.*
import kotlinx.coroutines.flow.first
import java.util.Locale
import java.util.UUID

/**
 * 1.55.0: turns the tyre sensors' frames into alerts — stored with start and end, notified once,
 * with vibration and (if the driver turned it on) a short spoken line. Fed ONLY when the sensor
 * format is validated ([TpmsParser.VALIDATED], 1.60.0: once the driver's gauge confirmed the pressure
 * reference, see [TpmsCheck]); until then it does nothing. An alert never creates
 * an expense and never touches an offer.
 */
object TireAlertMonitor {
    private const val TAG = "TireAlertMonitor"
    const val CHANNEL_ID = "tpms_alerts"
    private const val BASE_ID = 7_300
    /** The same alert is not spoken again within this time, even if it ends and starts again. */
    private const val VOICE_REPEAT_MS = 10 * 60_000L
    private const val PREFS = "tpms"
    private const val KEY_SILENCED = "silenced_until"

    private val tracker = TpmsAlertTracker()
    private val openIds = HashMap<Pair<WheelPosition, TpmsAlertKind>, String>()
    private val spoken = HashMap<Pair<WheelPosition, TpmsAlertKind>, Long>()
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    fun silencedUntil(context: Context): Long? =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_SILENCED, 0L).takeIf { it > System.currentTimeMillis() }

    /** «Silenciar 1 h»: alerts keep being recorded, only notifications and voice keep quiet. */
    fun silence(context: Context, forMs: Long) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_SILENCED, System.currentTimeMillis() + forMs).apply()

    /** Frames just saved by the listener, in time order. */
    suspend fun onFrames(context: Context, frames: List<TireSensorFrameEntity>, listening: Boolean) {
        val app = context.applicationContext
        TpmsCheck.ensure(app)
        if (!TpmsParser.VALIDATED) return
        val db = AppDatabase.getInstance(app)
        val bindings = db.tireSensorDao().sensors().mapNotNull { it.toDomain() }.filter { it.active }
        val tires = db.tireDao().tires().mapNotNull { it.toDomain() }
        val events = ArrayList<Pair<TpmsAlertEvent, SensorBinding?>>()
        synchronized(this) {
            for (f in frames.sortedBy { it.receivedAt }) {
                val b = bindings.firstOrNull { it.sensorId == f.sensorId && f.parsedId == it.sensorId } ?: continue
                val target = TireRules.active(TireData(tires), b.vehicleId, b.position)?.target
                tracker.onSample(TpmsSample(b.position, f.receivedAt, f.pressurePa?.let { TpmsReference.gauge(it) }, f.temperatureCenti, f.batteryPct, f.leak?.let { it == 1 }), target)
                    .forEach { events += it to b }
            }
            tracker.onTick(System.currentTimeMillis(), listening, bindings.map { it.position }.toSet()).forEach { e ->
                events += e to bindings.firstOrNull { it.position == e.position }
            }
        }
        if (events.isEmpty()) return
        val unit = AppSettings(app).tirePressureUnit.first()
        val voice = AppSettings(app).tpmsVoice.first()
        val silenced = silencedUntil(app)
        for ((e, b) in events) {
            store(db, e, b)
            if (TpmsAlertNotice.shouldNotify(e, silenced)) notify(app, e, unit, voice)
            else if (e.endedAt != null) cancel(app, e)
        }
    }

    private suspend fun store(db: AppDatabase, e: TpmsAlertEvent, b: SensorBinding?) {
        val key = e.position to e.kind
        val now = System.currentTimeMillis()
        val id = synchronized(this) { if (e.endedAt == null) UUID.randomUUID().toString().also { openIds[key] = it } else openIds.remove(key) } ?: return
        MaintenanceGate.shared.access {
            db.tireAlertDao().put(TireAlertEntity(id, b?.vehicleId ?: "", e.position.storageKey, e.kind.name, b?.sensorId, e.startedAt, e.endedAt,
                e.value, e.limit, TpmsParser.VERSION, now, now))
        }
    }

    private fun notify(app: Context, e: TpmsAlertEvent, unit: PressureUnit, voice: Boolean) {
        val text = TpmsAlertNotice.text(e, unit)
        try {
            val manager = app.getSystemService(NotificationManager::class.java)
            if (manager.areNotificationsEnabled()) {
                manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Alertas de neumáticos", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Presión, temperatura, batería del sensor y señal de las gomas"
                })
                val open = PendingIntent.getActivity(app, 0, Intent(app, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
                manager.notify(BASE_ID + e.position.ordinal * 10 + e.kind.ordinal, android.app.Notification.Builder(app, CHANNEL_ID)
                    .setContentTitle(e.kind.label).setContentText(text).setStyle(android.app.Notification.BigTextStyle().bigText(text))
                    .setSmallIcon(android.R.drawable.stat_sys_warning).setContentIntent(open).setAutoCancel(true).build())
            }
            app.getSystemService(Vibrator::class.java)?.takeIf { it.hasVibrator() }
                ?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 200, 300), -1))
        } catch (x: Exception) { Log.w(TAG, "Could not notify", x) }
        if (voice) speak(app, e, text)
    }

    private fun cancel(app: Context, e: TpmsAlertEvent) = try {
        app.getSystemService(NotificationManager::class.java).cancel(BASE_ID + e.position.ordinal * 10 + e.kind.ordinal)
    } catch (_: Exception) { }

    @Synchronized
    private fun speak(app: Context, e: TpmsAlertEvent, text: String) {
        val key = e.position to e.kind
        val last = spoken[key]
        if (last != null && e.startedAt - last < VOICE_REPEAT_MS) return
        spoken[key] = e.startedAt
        val line = "Observador. " + text.replace("·", ",")
        val engine = tts
        if (engine != null && ttsReady) { engine.speak(line, TextToSpeech.QUEUE_ADD, null, UUID.randomUUID().toString()); return }
        tts = TextToSpeech(app) { status ->
            synchronized(this) {
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) {
                    tts?.language = Locale.forLanguageTag("es-DO")
                    tts?.speak(line, TextToSpeech.QUEUE_ADD, null, UUID.randomUUID().toString())
                }
            }
        }
    }

    /** History screen: every stored alert. */
    suspend fun all(context: Context): List<TpmsAlertEvent> =
        MaintenanceGate.shared.access { AppDatabase.getInstance(context.applicationContext).tireAlertDao().all().mapNotNull { it.toDomain() } }
}

/**
 * 1.60.0: the gauge calibrations (TpmsCalibrations), read from the pressure checks and sensor
 * frames already stored, and the reference they confirm, set in [TpmsReference] for the alerts,
 * the charts and the screens. Read again at most every few minutes, or at once after a check.
 */
object TpmsCheck {
    private const val MAX_AGE_MS = 5 * 60_000L
    @Volatile private var loadedAt = 0L

    data class Result(val calibrations: List<TpmsCalibration>, val state: CalibrationState)

    suspend fun refresh(context: Context): Result {
        val db = AppDatabase.getInstance(context.applicationContext)
        val result = MaintenanceGate.shared.access {
            val data = TireData(db.tireDao().tires().mapNotNull { it.toDomain() }, db.tireDao().events().mapNotNull { it.toDomain() })
            val bindings = db.tireSensorDao().sensors().mapNotNull { it.toDomain() }
            val samples = db.tireSensorDao().sensorFrames().mapNotNull { f ->
                val id = f.sensorId ?: return@mapNotNull null
                if (f.parsedId != id) null else f.pressurePa?.let { SensorPressure(id, f.receivedAt, it, f.temperatureCenti) }
            }
            val list = TpmsCalibrations.pair(data, bindings, samples)
            Result(list, TpmsCalibrations.state(list))
        }
        TpmsReference.current = (result.state as? CalibrationState.Confirmed)?.reference
        loadedAt = System.currentTimeMillis()
        return result
    }

    suspend fun ensure(context: Context) {
        if (System.currentTimeMillis() - loadedAt > MAX_AGE_MS) runCatching { refresh(context) }.onFailure { Log.w("TpmsCheck", "Could not read calibrations", it) }
    }
}
