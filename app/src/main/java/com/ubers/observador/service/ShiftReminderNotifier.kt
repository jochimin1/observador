package com.ubers.observador.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.ubers.observador.MainActivity
import com.ubers.observador.data.AppSettings
import com.ubers.observador.data.WorkShiftRepository
import com.ubers.observador.data.db.AppDatabase
import com.ubers.observador.domain.TestData
import com.ubers.observador.domain.expenses.ShiftReminderKind
import com.ubers.observador.domain.expenses.WorkShiftRules
import com.ubers.observador.domain.online.OnlineSession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 1.46.0: «Anota el odómetro para iniciar la jornada» when Uber goes online without an open shift,
 * and «¿Terminaste la jornada?» after a long offline period with one open. Each is sent once per
 * online session / offline period. Reminders never open or close a shift.
 */
object ShiftReminderNotifier {
    private const val CHANNEL_ID = "jornada"
    private const val NOTIFICATION_ID = 4_500
    private val mutex = Mutex()

    suspend fun check(context: Context, latest: OnlineSession?, nowMs: Long) = mutex.withLock {
        try {
            val app = context.applicationContext
            val settings = AppSettings(app)
            if (!settings.shiftReminders.first()) return@withLock
            val session = latest?.takeIf { !TestData.isTest(it.id) }
            val onlineNow = session != null && session.isOpen && !session.isStale(nowMs)
            val open = WorkShiftRepository(AppDatabase.getInstance(app)).all().lastOrNull { it.isOpen }
            val manager = app.getSystemService(NotificationManager::class.java)
            // A shift started or finished: the pending reminder no longer applies
            if ((onlineNow && open != null) || (!onlineNow && open == null)) manager.cancel(NOTIFICATION_ID)
            val reminder = WorkShiftRules.reminder(session, open, nowMs, onlineNow) ?: return@withLock
            if (settings.shiftReminderSent(reminder.kind) == reminder.key) return@withLock
            if (!manager.areNotificationsEnabled()) return@withLock
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Jornada", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Recordatorios para anotar el odómetro al iniciar y terminar la jornada"
            })
            val (title, text) = when (reminder.kind) {
                ShiftReminderKind.START -> "Anota el odómetro" to "Te conectaste a Uber. Inicia la jornada en Gastos para medir los km de trabajo."
                ShiftReminderKind.END -> "¿Terminaste la jornada?" to "Uber lleva más de ${WorkShiftRules.END_REMINDER_MINUTES} min desconectado. Anota el odómetro final en Gastos."
            }
            val openApp = PendingIntent.getActivity(app, 0, Intent(app, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            manager.notify(NOTIFICATION_ID, Notification.Builder(app, CHANNEL_ID)
                .setContentTitle(title).setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.ic_menu_edit)
                .setContentIntent(openApp).setAutoCancel(true).build())
            settings.markShiftReminderSent(reminder.kind, reminder.key)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("ShiftReminder", "Reminder check failed", e)
        }
    }
}
