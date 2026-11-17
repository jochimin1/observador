package com.ubers.observador.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ubers.observador.MainActivity
import com.ubers.observador.data.AppSettings
import com.ubers.observador.data.ExpenseRepository
import com.ubers.observador.data.FinancialRepository
import com.ubers.observador.data.MoneyCheckStore
import com.ubers.observador.data.OnlineRepository
import com.ubers.observador.data.OperatingRepository
import com.ubers.observador.data.TireRepository
import com.ubers.observador.data.VehicleRepository
import com.ubers.observador.data.db.AppDatabase
import com.ubers.observador.data.db.toDomain
import com.ubers.observador.domain.WorkdayClock
import com.ubers.observador.domain.earnings.EarningsRange
import com.ubers.observador.domain.earnings.EarningsWindows
import com.ubers.observador.domain.earnings.MoneyChecks
import com.ubers.observador.domain.expenses.Odometer
import com.ubers.observador.domain.reports.WeekReview
import com.ubers.observador.domain.reports.WeekReviewFigures
import com.ubers.observador.domain.sectors.CloseSummaries
import com.ubers.observador.domain.vehicle.FrameSeen
import com.ubers.observador.domain.vehicle.SensorIds
import com.ubers.observador.domain.vehicle.TireRules
import com.ubers.observador.domain.vehicle.TpmsDiagnosis
import com.ubers.observador.domain.vehicle.WheelLevel
import com.ubers.observador.domain.vehicle.WheelPosition
import kotlinx.coroutines.flow.first
import java.time.DayOfWeek
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * 1.61.0: «Revisión de la semana». A daily check (WorkManager, survives restarts) that on the
 * chosen day, from 7 pm, sends one notification with [WeekReview]. On by default, on Sunday; the
 * switch and the day are in Ajustes › Ganancias y metas.
 */
object WeeklyReview {
    private const val TAG = "WeeklyReview"
    private const val PREFS = "revision_semanal"
    private const val WORK = "revision_semanal"
    private const val CHANNEL = "revision_semanal"
    private const val NOTIFICATION_ID = 5_201

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(c: Context): Boolean = prefs(c).getBoolean("enabled", true)
    fun day(c: Context): DayOfWeek = runCatching { DayOfWeek.valueOf(prefs(c).getString("day", null) ?: "SUNDAY") }.getOrDefault(DayOfWeek.SUNDAY)

    fun setEnabled(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean("enabled", on).apply()
        if (on) ensureScheduled(c) else WorkManager.getInstance(c.applicationContext).cancelUniqueWork(WORK)
    }

    fun setDay(c: Context, day: DayOfWeek) = prefs(c).edit().putString("day", day.name).apply()

    /** From the app's start: the daily check exists while the review is on. */
    fun ensureScheduled(c: Context) {
        if (!enabled(c)) return
        try {
            WorkManager.getInstance(c.applicationContext).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<WeeklyReviewWorker>(1, TimeUnit.DAYS, 3, TimeUnit.HOURS).build())
        } catch (e: Exception) {
            Log.w(TAG, "Could not schedule the weekly review", e)
        }
    }

    suspend fun figures(context: Context, now: Long = System.currentTimeMillis()): Pair<String, WeekReviewFigures> {
        val app = context.applicationContext
        val db = AppDatabase.getInstance(app)
        val settings = AppSettings(app)
        val clock = WorkdayClock(settings.workdayStartHour.first(), ZoneId.systemDefault())
        val goals = settings.goals.first()
        val current = EarningsWindows.of(EarningsRange.WEEK, 0, now, clock, goals.weekStart)
        val w = EarningsWindows.of(EarningsRange.WEEK, WeekReview.offset(current.startMs, now), now, clock, goals.weekStart)
        val income = FinancialRepository(db).periodEarnings(w.startMs, w.endMs)
        val online = OnlineRepository(db).let { it.summarize(it.getBetween(w.startMs, w.endMs), w.startMs, w.endMs, minOf(now, w.endMs)).confirmedMs }
        val report = runCatching { OperatingRepository(db).report(w, clock, now, false) }.getOrNull()
        val closes = CloseSummaries.of(CloseLogStore.linesOfDays(app, 7, now))
        // Tyres of the active motorbike, with the same rules as «Mi moto y neumáticos»
        val tires = runCatching {
            val vehicle = VehicleRepository(db).observe().first().active
            val data = TireRepository(db).observe().first()
            val money = ExpenseRepository(db).getAll()
            val timeline = Odometer.timeline(money.fuel.filter { it.vehicleId == vehicle.id }, money.expenses.filter { it.vehicleId == vehicle.id },
                money.readings.filter { it.vehicleId == vehicle.id })
            val limits = settings.tireSettings.first()
            WheelPosition.entries.map {
                TireRules.status(data, vehicle.id, it, timeline, now, ZoneId.systemDefault(), settings.tirePressureUnit.first(), limits.checkDays.toLong(), limits.tolerancePct)
            }.filter { it.level.problem || it.level == WheelLevel.NEEDS_CHECK }.map { "${it.position.label}: ${it.level.label.lowercase()}" }
        }.getOrDefault(emptyList())
        val silent = runCatching {
            val dao = db.tireSensorDao()
            val bindings = dao.sensors().mapNotNull { it.toDomain() }.filter { it.active }
            val day = dao.framesSince(now - TpmsDiagnosis.DAY_MS).map { FrameSeen(it.receivedAt, it.rssi, it.name, it.parsedId, it.sensorId, it.batteryPct) }
            val latest = dao.observeLatest().first().associateBy { it.sensorId }
            bindings.mapNotNull { b ->
                val last = latest[b.sensorId]?.let { FrameSeen(it.receivedAt, it.rssi, it.name, it.parsedId, it.sensorId, it.batteryPct) }
                val r = TpmsDiagnosis.report(b, day, last, now)
                if (r.lastAtMs == null || now - r.lastAtMs > TpmsDiagnosis.DAY_MS) SensorIds.shown(b.sensorId) else null
            }
        }.getOrDefault(emptyList())
        val moneyToConfirm = !MoneyChecks.state(MoneyCheckStore.all(app), UberWatchStore.updatedAt(app)).validated
        val f = WeekReviewFigures(income.totalObservedEarnings, income.currencyCode, report?.profitMinor, report?.perKm, online,
            income.completedTripCount, income.confirmedTripCount, income.pendingReviewCount, closes, tires, silent, moneyToConfirm)
        return java.time.Instant.ofEpochMilli(w.startMs).atZone(ZoneId.systemDefault()).toLocalDate().toString() to f
    }

    /** Called by the worker: notifies once per week on the chosen day. */
    suspend fun runIfDue(context: Context) {
        val app = context.applicationContext
        if (!enabled(app)) return
        val now = System.currentTimeMillis()
        val zoned = java.time.Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
        val (key, f) = figures(app, now)
        if (!WeekReview.due(zoned, day(app), prefs(app).getString("last_week", null), key)) return
        if (notify(app, f)) prefs(app).edit().putString("last_week", key).apply()
    }

    fun notify(app: Context, f: WeekReviewFigures): Boolean = try {
        val manager = app.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) false else {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Revisión de la semana", NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(app, 9, Intent(app, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val lines = WeekReview.lines(f)
            manager.notify(NOTIFICATION_ID, android.app.Notification.Builder(app, CHANNEL)
                .setContentTitle(WeekReview.title(f)).setContentText(lines.first())
                .setStyle(android.app.Notification.BigTextStyle().bigText(lines.joinToString("\n")))
                .setSmallIcon(android.R.drawable.ic_menu_agenda).setContentIntent(open).setAutoCancel(true).build())
            true
        }
    } catch (e: Exception) {
        Log.w(TAG, "Could not notify the weekly review", e)
        false
    }
}

class WeeklyReviewWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (com.ubers.observador.data.backup.DataRecovery.failure != null) return Result.success()
        return try {
            WeeklyReview.runIfDue(applicationContext)
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("WeeklyReviewWorker", "Weekly review failed", e)
            Result.success()
        }
    }
}
