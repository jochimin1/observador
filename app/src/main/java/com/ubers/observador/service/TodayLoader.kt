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
import com.ubers.observador.data.OnlineRepository
import com.ubers.observador.data.OperatingRepository
import com.ubers.observador.data.db.AppDatabase
import com.ubers.observador.domain.WorkdayClock
import com.ubers.observador.domain.earnings.EarningsRange
import com.ubers.observador.domain.earnings.EarningsWindows
import com.ubers.observador.domain.earnings.Today
import com.ubers.observador.domain.earnings.TodayFigures
import kotlinx.coroutines.flow.first
import java.time.ZoneId

/**
 * 1.57.0: today's figures with the same sources as Ganancias (confirmed money of the working day,
 * confirmed time online, operating profit when the cost model computes it).
 */
object TodayLoader {
    private const val TAG = "TodayLoader"

    suspend fun load(context: Context): TodayFigures? = try {
        val app = context.applicationContext
        val db = AppDatabase.getInstance(app)
        val settings = AppSettings(app)
        val clock = WorkdayClock(settings.workdayStartHour.first(), ZoneId.systemDefault())
        val now = System.currentTimeMillis()
        val w = EarningsWindows.of(EarningsRange.DAY, 0, now, clock)
        val income = FinancialRepository(db).periodEarnings(w.startMs, w.endMs)
        val online = OnlineRepository(db).let { it.summarize(it.getBetween(w.startMs, w.endMs), w.startMs, w.endMs, now).confirmedMs }
        val profit = runCatching { OperatingRepository(db).report(w, clock, now, false).profitMinor }.getOrNull()
        val goals = settings.goals.first()
        TodayFigures(income.totalObservedEarnings, income.currencyCode, income.confirmedTripCount, income.completedTripCount,
            income.pendingReviewCount, online, profit, goals.dailyMinor, goals.dailyOnlineMinutes)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Today's figures not available", e)
        null
    }

    /** 1.57.0: «meta de horas alcanzada», once per working day, only with goal notifications on. */
    suspend fun checkHoursGoal(context: Context, t: TodayFigures) {
        if (!t.hoursReached) return
        val app = context.applicationContext
        val settings = AppSettings(app)
        if (!settings.goals.first().notify) return
        val clock = WorkdayClock(settings.workdayStartHour.first(), ZoneId.systemDefault())
        val key = clock.workdayOf(System.currentTimeMillis()).toString()
        val prefs = app.getSharedPreferences("metas_horas", Context.MODE_PRIVATE)
        if (prefs.getString("notified_day", null) == key) return
        val manager = app.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return
        manager.createNotificationChannel(NotificationChannel("metas", "Metas", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(app, 1, Intent(app, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        manager.notify(5_003, android.app.Notification.Builder(app, "metas")
            .setContentTitle("¡Llegaste a tus horas de hoy!")
            .setContentText("${Today.duration(t.onlineMs)} en línea confirmadas · ${Today.headline(t)}")
            .setSmallIcon(android.R.drawable.star_on).setContentIntent(open).setAutoCancel(true).build())
        prefs.edit().putString("notified_day", key).apply()
    }
}
