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
import com.ubers.observador.domain.expenses.Odometer
import com.ubers.observador.domain.expenses.ReminderLevel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.ubers.observador.data.VehicleRepository
import com.ubers.observador.domain.expenses.VehicleCare
import com.ubers.observador.domain.TestData
import java.time.ZoneId

/**
 * Fase 8B: "cambio de aceite" and the other reminders as a notification (decision 10: on by
 * default). Checked on app open, after Gastos writes and at most every five minutes during GPS persistence. Each warning
 * is sent once per change and level (soon, then overdue), never repeated.
 */
object MaintenanceNotifier {

    private const val CHANNEL_ID = "mantenimiento"
    private const val BASE_ID = 4_000

    private val mutex = Mutex()

    suspend fun check(context: Context) = mutex.withLock {
        try {
            val app = context.applicationContext
            val settings = AppSettings(app)
            if (!settings.maintenanceNotification.first()) return@withLock
            val db = AppDatabase.getInstance(app)
            val raw = ExpenseRepository(db).getAll()
            // Notifications always exclude laboratory data, even while it is visible on screen.
            val real = raw.copy(fuel = TestData.filter(raw.fuel, false) { it.id },
                expenses = TestData.filter(raw.expenses, false) { it.id }, readings = TestData.filter(raw.readings, false) { it.id })
            val care = VehicleRepository(db).get()
            val data = VehicleCare.scope(real, care.active)
            val now = System.currentTimeMillis()
            val baseline = Odometer.timeline(data.fuel.filter { it.atMs <= now }, data.expenses.filter { it.atMs <= now },
                data.readings.filter { it.atMs <= now }).lastOrNull()
            val segments = if (baseline == null) emptyList() else TestData.filter(
                DistanceRepository(db).getSince(maxOf(baseline.atMs, care.active.startedAt)), false) { it.id }
            val plans = VehicleCare.plansFor(care, settings.maintenanceIntervals.first())
            val reminders = VehicleCare.components(data, plans, segments, now, ZoneId.systemDefault())
            val notified = settings.maintenanceNotified.first()
            val manager = app.getSystemService(NotificationManager::class.java)
            if (!manager.areNotificationsEnabled()) return@withLock
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Mantenimiento", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Avisos de cambio de aceite y otros mantenimientos"
                }
            )
            reminders.filter { it.level != ReminderLevel.SOON && it.level != ReminderLevel.OVERDUE }.forEach { manager.cancel(BASE_ID + it.plan.type.ordinal) }
            reminders.filter { it.level == ReminderLevel.SOON || it.level == ReminderLevel.OVERDUE }.forEach { r ->
                val key = r.notificationKey
                if (notified[r.plan.type] == key) return@forEach
                val parts = mutableListOf<String>()
                r.remainingKm?.let { remaining ->
                    parts += if (remaining <= 0) "Límite de km alcanzado (${ExpenseFormat.km(-remaining)} km de exceso conocido)"
                        else "Faltan ${ExpenseFormat.km(remaining)} km según lo observado"
                }
                r.remainingDays?.let { days -> parts += if (days == 0L) "Vence hoy" else if (days < 0) "Fecha alcanzada (${-days} días de exceso)" else "Faltan $days días" }
                if (r.estimatedKm) parts += "GPS: mínimo conocido, pueden faltar recorridos"
                val text = parts.joinToString(" · ")
                val open = PendingIntent.getActivity(app, 0, Intent(app, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
                manager.notify(
                    BASE_ID + r.plan.type.ordinal,
                    android.app.Notification.Builder(app, CHANNEL_ID)
                        .setContentTitle("${care.active.name} · ${r.plan.type.label}: ${if (r.level == ReminderLevel.OVERDUE) "toca cambiarlo" else "pronto"}")
                        .setContentText(text)
                        .setSmallIcon(android.R.drawable.ic_menu_compass)
                        .setContentIntent(open)
                        .setAutoCancel(true)
                        .build()
                )
                settings.markMaintenanceNotified(r.plan.type, key)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("Maintenance", "Reminder check failed", e)
        }
    }
}
