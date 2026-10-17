package com.ubers.observador.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.ubers.observador.MainActivity
import com.ubers.observador.data.AppSettings
import com.ubers.observador.data.DistanceRepository
import com.ubers.observador.data.db.AppDatabase
import com.ubers.observador.domain.WorkdayClock
import com.ubers.observador.domain.distance.DayDistance
import com.ubers.observador.domain.distance.DistanceConditions
import com.ubers.observador.domain.distance.DistanceFormat
import com.ubers.observador.domain.distance.DistancePolicy
import com.ubers.observador.domain.distance.LocationFix
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * Keeps Observador alive and shows its state. The notification has a "Pausar" / "Reanudar"
 * button, so the switch is one tap away even while Uber is open.
 *
 * Fase 8E: it also runs the GPS while kilometres are measured (connected to Uber, or the
 * "Iniciar recorrido" button), with the "location" service type so positions keep coming with
 * the screen off. Positions go straight to DistanceMonitor and are never kept.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ObservadorForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "observador_service"
        const val NOTIFICATION_ID = 1
        private const val TAG = "ObservadorService"
        private const val ACTION_PAUSE = "com.ubers.observador.action.PAUSE"
        private const val ACTION_RESUME = "com.ubers.observador.action.RESUME"
        private const val ACTION_REFRESH = "com.ubers.observador.action.REFRESH"
        private const val ACTION_CLOSE = "com.ubers.observador.action.CLOSE"

        /** Re-checks staleness of the online session and the permission this often. */
        private const val TICK_MS = 30_000L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ObservadorForegroundService::class.java))
        }

        /** After the location permission is granted (app in front): takes the "location" type. */
        fun refresh(context: Context) {
            context.startForegroundService(Intent(context, ObservadorForegroundService::class.java).setAction(ACTION_REFRESH))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ObservadorForegroundService::class.java))
        }

        fun hasLocationPermission(context: Context): Boolean =
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var settings: AppSettings
    private lateinit var distances: DistanceRepository
    private lateinit var locationClient: FusedLocationProviderClient
    private var gpsOn = false
    private var notification: Notification? = null

    private val policy = DistancePolicy()

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach { loc ->
                // Only the numbers needed for one step; the object is dropped right after
                DistanceMonitor.onFix(
                    LocationFix(loc.time, loc.latitude, loc.longitude, if (loc.hasAccuracy()) loc.accuracy else Float.NaN)
                )
            }
        }
    }

    private val ticker: Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(TICK_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(applicationContext)
        distances = DistanceRepository(AppDatabase.getInstance(applicationContext))
        locationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
        val first = buildNotification(active = true, measuringText = null)
        notification = first
        startForegroundTyped(first)
        DistanceMonitor.start(applicationContext)

        scope.launch {
            settings.workdayStartHour.collect { DistanceMonitor.setClock(WorkdayClock(it, ZoneId.systemDefault())) }
        }
        // Which trip is under way, and whether Uber is online (8A)
        scope.launch {
            combine(RideLifecycleMonitor.state, RideLifecycleMonitor.online, ticker) { s, o, now -> Triple(s.context, o.current, now) }
                .collect { (ride, online, now) -> DistanceMonitor.onRide(ride, online, now) }
        }
        // Whether to measure: switch, permission, pause, mode, online, button
        scope.launch {
            val switches = combine(settings.distanceEnabled, settings.distanceMode, settings.distanceManual, settings.observerEnabled) { e, m, b, o ->
                arrayOf<Any>(e, m, b, o)
            }
            combine(switches, RideLifecycleMonitor.online, ticker) { sw, online, now ->
                val session = online.current
                DistanceConditions(
                    enabled = sw[0] as Boolean,
                    permitted = hasLocationPermission(this@ObservadorForegroundService),
                    observerOn = sw[3] as Boolean,
                    mode = sw[1] as com.ubers.observador.domain.distance.DistanceMode,
                    online = session != null && session.isOpen && !session.isStale(now),
                    manualRequested = sw[2] as Boolean
                ) to now
            }.collect { (conditions, now) -> DistanceMonitor.apply(conditions, now) }
        }
        // The GPS follows the tracker: on while a stretch is open, off otherwise
        scope.launch {
            DistanceMonitor.state.map { it.measuring != null }.distinctUntilChanged().collect { setGps(it) }
        }
        // Notification: paused / active, and "Midiendo km · 23.4 km hoy" while measuring
        scope.launch {
            val today = settings.workdayStartHour.flatMapLatest { hour ->
                val clock = WorkdayClock(hour, ZoneId.systemDefault())
                ticker.map { clock.workdayOf(it) }.distinctUntilChanged().flatMapLatest { distances.observeDay(it) }
            }
            combine(settings.observerEnabled, DistanceMonitor.state, today) { active, live, day ->
                Triple(active, live.measuring != null, day)
            }.distinctUntilChanged().collect { (active, measuring, day) ->
                val built = buildNotification(active, if (measuring) measuringText(day) else null)
                notification = built
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, built)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> scope.launch { settings.updateObserverEnabled(false) }
            ACTION_RESUME -> scope.launch { settings.updateObserverEnabled(true) }
            ACTION_REFRESH -> notification?.let { startForegroundTyped(it) }
            // Fase 8L: «Apagar» in the notification
            ACTION_CLOSE -> AppCloser.closeAsync(applicationContext)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        setGps(false)
        scope.cancel()
        super.onDestroy()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    /**
     * specialUse always; plus location when the permission is granted. Android refuses the
     * location type when the service is (re)started with the app in the background: then it
     * runs without it and the screens ask to open the app once.
     */
    private fun startForegroundTyped(n: Notification) {
        val located = hasLocationPermission(this)
        val special = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        val location = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        try {
            when {
                located -> startForeground(NOTIFICATION_ID, n, special or location)
                special != 0 -> startForeground(NOTIFICATION_ID, n, special)
                else -> startForeground(NOTIFICATION_ID, n)
            }
            DistanceMonitor.setBackgroundLimited(false)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Location type refused; running without it", e)
            if (special != 0) startForeground(NOTIFICATION_ID, n, special) else startForeground(NOTIFICATION_ID, n)
            DistanceMonitor.setBackgroundLimited(located)
        }
    }

    /** The GPS is on only while measuring. */
    private fun setGps(on: Boolean) {
        if (on == gpsOn) return
        if (on) {
            try {
                val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, policy.fixIntervalMs)
                    .setMinUpdateIntervalMillis(policy.fixIntervalMs / 2)
                    .build()
                locationClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
                gpsOn = true
            } catch (e: SecurityException) {
                Log.w(TAG, "No location permission", e)
            }
        } else {
            locationClient.removeLocationUpdates(callback)
            gpsOn = false
        }
    }

    private fun measuringText(day: DayDistance): String = "Midiendo km · ${DistanceFormat.km(day.measuredMm)} km hoy"

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Servicio Observador",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Estado de Observador (activo o en pausa) y medición de km"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(active: Boolean, measuringText: String?): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val toggle = PendingIntent.getService(
            this, if (active) 1 else 2,
            Intent(this, ObservadorForegroundService::class.java).setAction(if (active) ACTION_PAUSE else ACTION_RESUME),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val action = Notification.Action.Builder(
            Icon.createWithResource(this, if (active) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play),
            if (active) "Pausar" else "Reanudar",
            toggle
        ).build()
        val close = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
            "Apagar",
            PendingIntent.getService(
                this, 3,
                Intent(this, ObservadorForegroundService::class.java).setAction(ACTION_CLOSE),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        ).build()
        val text = when {
            !active -> "No se leen ofertas ni viajes"
            measuringText != null -> measuringText
            else -> "Monitoreando ofertas Uber Moto"
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(if (active) "Observador activo" else "Observador en pausa")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(openIntent)
            .addAction(action)
            .addAction(close)
            .setOngoing(true)
            .build()
    }
}
