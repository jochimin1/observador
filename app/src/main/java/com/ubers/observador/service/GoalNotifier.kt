package com.ubers.observador.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.ubers.observador.MainActivity
import com.ubers.observador.data.AppSettings
import com.ubers.observador.data.FinancialRepository
import com.ubers.observador.data.db.AppDatabase
import com.ubers.observador.domain.WorkdayClock
import com.ubers.observador.domain.earnings.EarningsFormat
import com.ubers.observador.domain.earnings.EarningsRange
import com.ubers.observador.domain.earnings.EarningsWindows
import com.ubers.observador.domain.earnings.Goals
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId

/**
 * Fase 8C: "meta alcanzada" as a notification, off until the driver turns it on (decision 10).
 * Checked after an amount is stored and when the app opens; each goal is notified once per day
 * or week. Real confirmed income only, like the progress bar.
 */
object GoalNotifier {

    private const val CHANNEL_ID = "metas"
    private const val DAILY_ID = 5_001
    private const val WEEKLY_ID = 5_002
    private val lock = Mutex()

    suspend fun check(context: Context) = lock.withLock {
        try {
            val app = context.applicationContext
            val settings = AppSettings(app)
            val goals = settings.goals.first()
            if (!goals.notify || !goals.hasAny) return@withLock
            val manager = app.getSystemService(NotificationManager::class.java)
            if (!manager.areNotificationsEnabled()) return@withLock
            val clock = WorkdayClock(settings.workdayStartHour.first(), ZoneId.systemDefault())
            val repository = FinancialRepository(AppDatabase.getInstance(app))
            val now = System.currentTimeMillis()
            val (notifiedDay, notifiedWeek) = settings.goalNotified.first()
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Metas", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Aviso cuando llegas a tu meta del día o de la semana"
                }
            )
            val open = PendingIntent.getActivity(app, 1, Intent(app, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            fun notify(id: Int, title: String, text: String) = manager.notify(
                id,
                android.app.Notification.Builder(app, CHANNEL_ID)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setSmallIcon(android.R.drawable.star_on)
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build()
            )
            if (goals.dailyMinor > 0) {
                val day = EarningsWindows.of(EarningsRange.DAY, 0, now, clock)
                val key = day.firstDay.toString()
                if (notifiedDay != key) {
                    val income = repository.periodEarnings(day.startMs, day.endMs)
                    if (Goals.progress(goals.dailyMinor, income.totalObservedEarnings)?.reached == true) {
                        notify(
                            DAILY_ID, "¡Llegaste a tu meta de hoy!",
                            "Llevas ${EarningsFormat.money(income.totalObservedEarnings, income.currencyCode)} de " +
                                "${EarningsFormat.money(goals.dailyMinor, income.currencyCode)} con dinero confirmado."
                        )
                        settings.markGoalNotified(key, null)
                    }
                }
            }
            if (goals.weeklyMinor > 0) {
                val week = EarningsWindows.of(EarningsRange.WEEK, 0, now, clock, goals.weekStart)
                val key = week.firstDay.toString()
                if (notifiedWeek != key) {
                    val income = repository.periodEarnings(week.startMs, week.endMs)
                    if (Goals.progress(goals.weeklyMinor, income.totalObservedEarnings)?.reached == true) {
                        notify(
                            WEEKLY_ID, "¡Llegaste a tu meta de la semana!",
                            "Llevas ${EarningsFormat.money(income.totalObservedEarnings, income.currencyCode)} de " +
                                "${EarningsFormat.money(goals.weeklyMinor, income.currencyCode)} con dinero confirmado."
                        )
                        settings.markGoalNotified(null, key)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("Goals", "Goal check failed", e)
        }
    }
}
