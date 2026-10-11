package com.ubers.observador.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.ubers.observador.domain.OfferQuality
import com.ubers.observador.domain.models.OfferAnalysis

/** Heads-up alert for Assisted mode: tells the driver an offer on screen is worth taking. */
class OfferNotifier(private val context: Context) {

    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Ofertas rentables",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Aviso cuando una oferta es BUENA o EXCELENTE (modo Asistido)"
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)
    }

    fun notifyGoodOffer(analysis: OfferAnalysis, quality: OfferQuality) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "POST_NOTIFICATIONS not granted, skipping alert")
            return
        }
        // Android only dismisses a notification on tap when it has a content intent. This one
        // goes nowhere on purpose: opening Observador would pull the driver out of Uber.
        val tapToClose = PendingIntent.getBroadcast(
            context,
            0,
            Intent(ACTION_NOOP).setPackage(context.packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("Viaje ${quality.label} — ${analysis.score}/100")
            .setContentText(
                "RD$%.2f · %.1f km · %d min · RD$%.2f/km".format(
                    analysis.offer.price, analysis.totalKm, analysis.totalMin, analysis.rdpPerKm
                )
            )
            .setCategory(Notification.CATEGORY_RECOMMENDATION)
            .setContentIntent(tapToClose)
            .setAutoCancel(true)
            .setTimeoutAfter(TIMEOUT_MS)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    fun cancel() {
        manager.cancel(NOTIFICATION_ID)
    }

    private companion object {
        const val TAG = "OfferNotifier"
        const val CHANNEL_ID = "observador_offers"
        const val NOTIFICATION_ID = 2
        const val ACTION_NOOP = "com.ubers.observador.action.NOOP"
        const val TIMEOUT_MS = 30_000L
    }
}
