package com.ubers.observador.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.IBinder
import com.ubers.observador.MainActivity
import com.ubers.observador.data.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Keeps Observador alive and shows its state. The notification has a "Pausar" / "Reanudar"
 * button, so the switch is one tap away even while Uber is open.
 */
class ObservadorForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "observador_service"
        const val NOTIFICATION_ID = 1
        private const val ACTION_PAUSE = "com.ubers.observador.action.PAUSE"
        private const val ACTION_RESUME = "com.ubers.observador.action.RESUME"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ObservadorForegroundService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ObservadorForegroundService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var settings: AppSettings

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(applicationContext)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(active = true))
        scope.launch {
            settings.observerEnabled.distinctUntilChanged().collect { active ->
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(active))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> scope.launch { settings.updateObserverEnabled(false) }
            ACTION_RESUME -> scope.launch { settings.updateObserverEnabled(true) }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Servicio Observador",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Estado de Observador (activo o en pausa)"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(active: Boolean): Notification {
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
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(if (active) "Observador activo" else "Observador en pausa")
            .setContentText(if (active) "Monitoreando ofertas Uber Moto" else "No se leen ofertas ni viajes")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(openIntent)
            .addAction(action)
            .setOngoing(true)
            .build()
    }
}
