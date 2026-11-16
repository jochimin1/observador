package com.ubers.observador.service

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.RemoteViews
import com.ubers.observador.MainActivity
import com.ubers.observador.R
import com.ubers.observador.data.AppSettings
import com.ubers.observador.data.TireRepository
import com.ubers.observador.data.VehicleRepository
import com.ubers.observador.data.db.AppDatabase
import com.ubers.observador.domain.vehicle.TireRules
import com.ubers.observador.domain.vehicle.WheelPosition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/** 1.56.0: what the tile and the widget show, read from the same places as the app. */
internal object QuickState {
    data class State(val reading: Boolean, val mode: String, val tyres: String?, val lastBackup: Long?,
        /** 1.57.0 */
        val today: com.ubers.observador.domain.earnings.TodayFigures? = null)

    suspend fun read(context: Context): State {
        val app = context.applicationContext
        val s = AppSettings(app)
        val tyres = runCatching {
            val db = AppDatabase.getInstance(app)
            val data = TireRepository(db).observe().first()
            val vehicle = VehicleRepository(db).get().active
            if (data.tires.none { it.vehicleId == vehicle.id }) null
            else TireRules.summary(WheelPosition.entries.map {
                TireRules.status(data, vehicle.id, it, emptyList(), System.currentTimeMillis(), ZoneId.systemDefault(), s.tirePressureUnit.first())
            })
        }.getOrNull()
        return State(s.observerEnabled.first(), s.operatingMode.first(), tyres, s.lastBackupAt.first(), TodayLoader.load(app))
    }

    fun modeLabel(mode: String) = when (mode) {
        AppSettings.MODE_AUTO -> "Automático"
        AppSettings.MODE_ASSISTED -> "Asistido"
        else -> "Observador"
    }
}

/**
 * 1.56.0: «Observador» in Android's quick settings: one touch pauses or resumes the reading, the
 * same as the notification's buttons. It never changes the operating mode.
 */
class ObservadorTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onStartListening() {
        super.onStartListening()
        scope.launch { show(QuickState.read(this@ObservadorTileService)) }
    }

    override fun onClick() {
        super.onClick()
        scope.launch {
            val settings = AppSettings(applicationContext)
            val now = !settings.observerEnabled.first()
            settings.updateObserverEnabled(now)
            show(QuickState.read(this@ObservadorTileService))
            ObservadorWidget.refresh(applicationContext)
        }
    }

    private fun show(st: QuickState.State) {
        val tile = qsTile ?: return
        tile.state = if (st.reading) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Observador"
        // 1.57.0: today's money under the state
        tile.subtitle = (if (st.reading) "Leyendo" else "En pausa") +
            (st.today?.let { " · " + com.ubers.observador.domain.earnings.Today.tileLine(it) } ?: if (st.reading) " · ${QuickState.modeLabel(st.mode)}" else "")
        tile.contentDescription = "Observador ${if (st.reading) "leyendo, toca para pausar" else "en pausa, toca para reanudar"}"
        tile.icon = Icon.createWithResource(this, R.drawable.ic_two_wheeler)
        tile.updateTile()
    }

    override fun onDestroy() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        super.onDestroy()
    }
}

/** 1.56.0: home screen widget: reading or paused, mode, the tyres and the last backup. Opens the app. */
class ObservadorWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { render(context, manager, ids) } finally { pending.finish() }
        }
    }

    companion object {
        private suspend fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val st = QuickState.read(context)
            val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val backup = st.lastBackup?.let { "Último respaldo: " + Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() } ?: "Sin respaldo todavía"
            ids.forEach { id ->
                val v = RemoteViews(context.packageName, R.layout.widget_observador)
                v.setTextViewText(R.id.widget_status, if (st.reading) "Leyendo · ${QuickState.modeLabel(st.mode)}" else "En pausa")
                // 1.57.0: the day at a glance
                val t = st.today
                v.setTextViewText(R.id.widget_today, t?.let { com.ubers.observador.domain.earnings.Today.headline(it) } ?: "Hoy: sin datos todavía")
                v.setTextViewText(R.id.widget_today_details, t?.let { com.ubers.observador.domain.earnings.Today.details(it) } ?: "")
                v.setTextViewText(R.id.widget_goals, t?.let { com.ubers.observador.domain.earnings.Today.goals(it) } ?: "")
                v.setViewVisibility(R.id.widget_goals, if (t?.let { com.ubers.observador.domain.earnings.Today.goals(it) } == null) android.view.View.GONE else android.view.View.VISIBLE)
                v.setTextViewText(R.id.widget_tyres, st.tyres ?: "Neumáticos sin registrar")
                v.setTextViewText(R.id.widget_backup, backup)
                v.setOnClickPendingIntent(R.id.widget_root, open)
                manager.updateAppWidget(id, v)
            }
        }

        /** After a change made elsewhere (the tile, the app). */
        fun refresh(context: Context) {
            val app = context.applicationContext
            val manager = AppWidgetManager.getInstance(app)
            val ids = manager.getAppWidgetIds(ComponentName(app, ObservadorWidget::class.java))
            if (ids.isEmpty()) return
            CoroutineScope(Dispatchers.IO).launch { runCatching { render(app, manager, ids) } }
        }
    }
}
