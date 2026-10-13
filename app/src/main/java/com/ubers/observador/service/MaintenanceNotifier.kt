package com.ubers.observador.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.ubers.observador.MainActivity
import com.ubers.observador.data.AppSettings
import com.ubers.observador.data.DistanceRepository
import com.ubers.observador.data.ExpenseRepository
import com.ubers.observador.data.db.AppDatabase
import com.ubers.observador.domain.expenses.ExpenseFormat
import com.ubers.observador.domain.expenses.MaintenanceReminders
import com.ubers.observador.domain.expenses.Odometer
import com.ubers.observador.domain.expenses.ReminderLevel
import com.ubers.observador.domain.distance.DistanceTotals
import kotlinx.coroutines.flow.first

/**
 * Fase 8B: "cambio de aceite" and the other reminders as a notification (decision 10: on by
 * default). Checked when the app opens and after something is written in Gastos. Each warning
 * is sent once per change and level (soon, then overdue), never repeated.
 */
object MaintenanceNotifier {

    private const val CHANNEL_ID = "mantenimiento"
    private const val BASE_ID = 4_000

    suspend fun check(context: Context) {
        try {
            val app = context.applicationContext
            val settings = AppSettings(app)
            if (!settings.maintenanceNotification.first()) return
            val db = AppDatabase.getInstance(app)
            val data = ExpenseRepository(db).getAll()
            val segments = DistanceRepository(db).getAll()
            val now = System.currentTimeMillis()
            val timeline = Odometer.timeline(data.fuel, data.expenses, data.readings)
            val reminders = MaintenanceReminders.of(settings.maintenanceIntervals.first(), data.expenses, timeline) { since ->
                if (segments.isEmpty()) null else DistanceTotals.kmBetween(segments, since, now, now)
            }
            val notified = settings.maintenanceNotified.first()
            val manager = app.getSystemService(NotificationManager::class.java)
            if (!manager.areNotificationsEnabled()) return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Mantenimiento", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Avisos de cambio de aceite y otros mantenimientos"
                }
            )
            reminders.filter { it.level == ReminderLevel.SOON || it.level == ReminderLevel.OVERDUE }.forEach { r ->
                val key = "${r.lastChangeAt}:${r.level}"
                if (notified[r.type] == key) return@forEach
                val remaining = r.remainingKm ?: return@forEach
                val text = if (r.level == ReminderLevel.OVERDUE) {
                    "Te pasaste por ${ExpenseFormat.km(-remaining)} km" + if (r.estimated) " (estimado con GPS)." else "."
                } else {
                    "Faltan ${ExpenseFormat.km(remaining)} km" + if (r.estimated) " (estimado con GPS)." else " según tu último odómetro."
                }
                val open = PendingIntent.getActivity(app, 0, Intent(app, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
                manager.notify(
                    BASE_ID + r.type.ordinal,
                    android.app.Notification.Builder(app, CHANNEL_ID)
                        .setContentTitle("${r.type.label}: ${if (r.level == ReminderLevel.OVERDUE) "toca cambiarlo" else "pronto"}")
                        .setContentText(text)
                        .setSmallIcon(android.R.drawable.ic_menu_compass)
                        .setContentIntent(open)
                        .setAutoCancel(true)
                        .build()
                )
                settings.markMaintenanceNotified(r.type, key)
            }
        } catch (e: Exception) {
            Log.e("Maintenance", "Reminder check failed", e)
        }
    }
}
