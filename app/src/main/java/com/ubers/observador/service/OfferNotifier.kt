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

/**
 * Heads-up alert for an offer on screen, with its verdict. Which offers notify is chosen in
 * Ajustes → «Avisos de ofertas» (Fase 8I); each card of Uber's Radar gets its own notification.
 */
class OfferNotifier(private val context: Context) {

    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Ofertas de Uber",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Aviso de cada oferta que Observador lee, con su veredicto (según «Avisos de ofertas»)"
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)
    }

    /** [id]: the main card uses the fixed id; other cards of the Radar use [idFor] so each keeps its own. */
    fun notifyGoodOffer(analysis: OfferAnalysis, quality: OfferQuality, id: Int = NOTIFICATION_ID) {
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
            .setStyle(
                Notification.BigTextStyle().bigText(
                    listOfNotNull(
                        "RD$%.2f · %.1f km · %d min · RD$%.2f/km".format(
                            analysis.offer.price, analysis.totalKm, analysis.totalMin, analysis.rdpPerKm
                        ),
                        analysis.offer.pickupAddress?.let { "Recoger: $it" },
                        analysis.offer.dropoffAddress?.let { "Destino: $it" }
                    ).joinToString("\n")
                )
            )
            .setCategory(Notification.CATEGORY_RECOMMENDATION)
            .setContentIntent(tapToClose)
            .setAutoCancel(true)
            .setTimeoutAfter(TIMEOUT_MS)
            .build()
        manager.notify(id, notification)
    }

    /** A stable id per offer for the extra cards of the Radar; they close on their own after the timeout. */
    fun idFor(offerHash: String): Int = EXTRA_ID_BASE + (offerHash.hashCode() and 0xFFF)

    fun cancel() {
        manager.cancel(NOTIFICATION_ID)
    }

    private companion object {
        const val TAG = "OfferNotifier"
        const val CHANNEL_ID = "observador_offers"
        const val NOTIFICATION_ID = 2
        const val EXTRA_ID_BASE = 10_000
        const val ACTION_NOOP = "com.ubers.observador.action.NOOP"
        const val TIMEOUT_MS = 30_000L
    }
}
