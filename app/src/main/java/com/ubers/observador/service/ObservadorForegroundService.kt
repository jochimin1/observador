package com.ubers.observador.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.ubers.observador.MainActivity

class ObservadorForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "observador_service"
        const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ObservadorForegroundService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ObservadorForegroundService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Servicio Observador",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Observador activo — monitoreando ofertas Uber"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Observador activo")
            .setContentText("Monitoreando ofertas Uber Moto")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }
}
