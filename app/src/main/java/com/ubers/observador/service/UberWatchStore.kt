package com.ubers.observador.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.ubers.observador.MainActivity
import com.ubers.observador.domain.backup.MaintenanceGate
import com.ubers.observador.domain.health.ReadingEvent
import com.ubers.observador.domain.health.ReadingMark
import com.ubers.observador.domain.health.UberWatch
import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * 1.61.0: the marks of [UberWatch], written as they happen (one line each, only added; in backups
 * and «Exportar todo»), and the two notices: Uber changed version, and reading got worse with it.
 * Each notice is given once per Uber version (the second, at most once a day).
 */
object UberWatchStore {
    private const val TAG = "UberWatchStore"
    private const val PREFS = "uber_watch"
    private const val CHANNEL = "lectura_uber"
    private const val VERSION_TTL_MS = 60_000L

    @Volatile private var app: Context? = null
    @Volatile private var version: Pair<Long, String?>? = null
    private var loaded: MutableList<ReadingMark>? = null

    fun init(context: Context) { app = context.applicationContext }

    /** The Uber installed now, read again at most once a minute. */
    fun uberVersion(context: Context): String? {
        val now = System.currentTimeMillis()
        version?.let { (at, v) -> if (now - at < VERSION_TTL_MS) return v }
        return LearnedXStore.uberVersion(context).also { version = now to it }
    }

    private fun dir(context: Context) = File(context.applicationContext.filesDir, UberWatch.DIR)

    /** Every mark kept on this phone, oldest first. */
    fun all(context: Context): List<ReadingMark> =
        (dir(context).listFiles { f -> f.isFile && f.name.startsWith("lecturas-") }?.sortedBy { it.name }.orEmpty())
            .flatMap { f -> try { UberWatch.parse(f.readText()) } catch (e: Exception) { emptyList() } }

    @Synchronized
    private fun marks(context: Context): MutableList<ReadingMark> = loaded ?: all(context).toMutableList().also { loaded = it }

    /** When the Uber in use was first seen, if it replaced another one on this phone. */
    @Synchronized
    fun updatedAt(context: Context): Long? = UberWatch.updatedAt(marks(context), uberVersion(context))

    @Synchronized
    fun mark(event: ReadingEvent) {
        val c = app ?: return
        try {
            val v = uberVersion(c) ?: "?"
            val now = System.currentTimeMillis()
            val list = marks(c)
            val previous = list.lastOrNull()?.uberVersion
            val m = ReadingMark(now, v, event)
            val written = MaintenanceGate.shared.tryAccess {
                val month = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate().toString().substring(0, 7)
                dir(c).mkdirs()
                File(dir(c), UberWatch.fileName(month)).appendText(UberWatch.encode(m) + "\n")
                true
            } ?: false
            if (!written) return
            list += m
            if (previous != null && previous != v) notifyOnce(c, "update_$v", 5_101, "Uber se actualizó", UberWatch.updateNotice(previous, v))
            if (event != ReadingEvent.MONEY_TOTAL && UberWatch.degraded(list, v)) {
                val day = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
                notifyOnce(c, "degraded_${v}_$day", 5_102, "Observador lee mal las ofertas", UberWatch.degradedNotice(v))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not mark the reading", e)
        }
    }

    private fun notifyOnce(c: Context, key: String, id: Int, title: String, text: String) {
        val prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(key, false)) return
        try {
            val manager = c.getSystemService(NotificationManager::class.java)
            if (!manager.areNotificationsEnabled()) return
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Lectura de Uber", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Cuando Uber se actualiza o Observador deja de leer bien sus ofertas"
            })
            val open = PendingIntent.getActivity(c, 7, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            manager.notify(id, android.app.Notification.Builder(c, CHANNEL).setContentTitle(title).setContentText(text)
                .setStyle(android.app.Notification.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.stat_sys_warning).setContentIntent(open).setAutoCancel(true).build())
            prefs.edit().putBoolean(key, true).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Could not notify", e)
        }
    }
}
