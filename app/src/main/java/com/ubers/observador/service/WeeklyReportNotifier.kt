package com.ubers.observador.service

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.ubers.observador.MainActivity
import com.ubers.observador.data.AppSettings
import com.ubers.observador.data.db.AppDatabase
import com.ubers.observador.data.export.ReportData
import com.ubers.observador.domain.WorkdayClock
import com.ubers.observador.domain.reports.WeeklyReports
import com.ubers.observador.domain.reports.WeeklySchedule
import com.ubers.observador.ui.AppNavigation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * Fase 8D: "Tu resumen de la semana está listo", shortly after the new week starts (Monday at
 * the workday start hour, or Sunday). An inexact Android alarm, no new libraries; set again
 * after every firing, when the phone restarts, when the app is updated and when the app opens.
 * Off until the driver turns it on (decision 10). Each week is notified once.
 */
object WeeklyReportNotifier {

    const val ACTION_FIRE = "com.ubers.observador.action.WEEKLY_REPORT"
    private const val CHANNEL_ID = "resumen_semanal"
    private const val NOTIFICATION_ID = 6_001

    private fun alarmIntent(app: Context): PendingIntent = PendingIntent.getBroadcast(
        app, 0, Intent(app, WeeklyReportReceiver::class.java).setAction(ACTION_FIRE),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /** Sets (or cancels) the next alarm according to the settings. */
    suspend fun reschedule(context: Context) {
        try {
            val app = context.applicationContext
            val settings = AppSettings(app)
            val alarms = app.getSystemService(AlarmManager::class.java)
            val pending = alarmIntent(app)
            if (!settings.weeklyReportNotification.first()) {
                alarms.cancel(pending)
                return
            }
            val clock = WorkdayClock(settings.workdayStartHour.first(), ZoneId.systemDefault())
            val at = WeeklySchedule.nextTrigger(System.currentTimeMillis(), clock, settings.goals.first().weekStart)
            // Inexact on purpose: no exact-alarm permission, and a few minutes late is fine
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        } catch (e: Exception) {
            Log.e(TAG, "Scheduling the weekly summary failed", e)
        }
    }

    /** The alarm went off: notify the week that ended (once), then set the next alarm. */
    suspend fun fire(context: Context) {
        val app = context.applicationContext
        try {
            val settings = AppSettings(app)
            if (!settings.weeklyReportNotification.first()) return
            val manager = app.getSystemService(NotificationManager::class.java)
            val now = System.currentTimeMillis()
            val clock = WorkdayClock(settings.workdayStartHour.first(), ZoneId.systemDefault())
            val goals = settings.goals.first()
            val week = ReportData.lastWeek(now, clock, goals.weekStart)
            val key = week.firstDay.toString()
            if (settings.weeklyReportNotified.first() != key && manager.areNotificationsEnabled()) {
                val report = ReportData(AppDatabase.getInstance(app)).weekly(week, clock, now, goals.weeklyMinor, settings.workSharePct.first())
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Resumen semanal", NotificationManager.IMPORTANCE_DEFAULT).apply {
                        description = "Aviso cuando el resumen de la semana que terminó está listo"
                    }
                )
                val open = PendingIntent.getActivity(
                    app, 2,
                    Intent(app, MainActivity::class.java)
                        .putExtra(AppNavigation.EXTRA_OPEN, AppNavigation.OPEN_LAST_WEEK)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                manager.notify(
                    NOTIFICATION_ID,
                    android.app.Notification.Builder(app, CHANNEL_ID)
                        .setContentTitle("Tu resumen de la semana está listo")
                        .setContentText(WeeklyReports.notificationText(report))
                        .setSmallIcon(android.R.drawable.ic_menu_agenda)
                        .setContentIntent(open)
                        .setAutoCancel(true)
                        .build()
                )
                settings.markWeeklyReportNotified(key)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Weekly summary notification failed", e)
        } finally {
            reschedule(app)
        }
    }

    private const val TAG = "WeeklyReport"
}

/** The alarm, a restart, an update or a clock change: notify or set the next alarm. */
class WeeklyReportReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                if (intent.action == WeeklyReportNotifier.ACTION_FIRE) WeeklyReportNotifier.fire(context)
                else WeeklyReportNotifier.reschedule(context)
            } finally {
                pending.finish()
            }
        }
    }
}
