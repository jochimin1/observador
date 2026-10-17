package com.ubers.observador.ui.earnings

import android.app.Application
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ubers.observador.R
import com.ubers.observador.data.AppSettings
import com.ubers.observador.data.FinancialRepository
import com.ubers.observador.data.OnlineRepository
import com.ubers.observador.data.TripRepository
import com.ubers.observador.data.db.AppDatabase
import com.ubers.observador.domain.WorkdayClock
import com.ubers.observador.domain.earnings.EarningsFormat
import com.ubers.observador.domain.earnings.EarningsRange
import com.ubers.observador.domain.earnings.EarningsWindows
import com.ubers.observador.domain.earnings.GoalProgress
import com.ubers.observador.domain.earnings.Goals
import com.ubers.observador.domain.money.Currencies
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.ZoneId

/** What the home strip shows: today's goal, or the week's when there is no daily one. */
data class GoalStripState(val title: String, val progress: GoalProgress)

/** The small goal strip of Inicio (Fase 8C), with the same rules as Ganancias. */
@OptIn(ExperimentalCoroutinesApi::class)
class GoalStripViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.getInstance(app)
    private val money = FinancialRepository(db)
    private val online = OnlineRepository(db)
    private val trips = TripRepository(db)
    private val settings = AppSettings(app)

    private val ticker = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(60_000)
        }
    }

    val state: StateFlow<GoalStripState?> = combine(
        settings.goals, settings.workdayStartHour, money.observeRecentObservations(1), trips.observeCounts(), ticker,
        money.observeLatestPlatformSnapshot(com.ubers.observador.domain.finance.PlatformPeriodType.DAY)
    ) { values ->
        arrayOf<Any?>(values[0], values[1], values[4], values[5])
    }
        .mapLatest { v ->
            val goals = v[0] as com.ubers.observador.domain.earnings.GoalSettings
            val hour = v[1] as Int
            val now = v[2] as Long
            val uber = v[3] as com.ubers.observador.domain.finance.PlatformEarningsSnapshot?
            try {
                val clock = WorkdayClock(hour, ZoneId.systemDefault())
                when {
                    goals.dailyMinor > 0 -> {
                        val w = EarningsWindows.of(EarningsRange.DAY, 0, now, clock)
                        val income = money.periodEarnings(w.startMs, w.endMs)
                        val onlineMs = online.summarize(online.getBetween(w.startMs, w.endMs), w.startMs, w.endMs, now).confirmedMs
                        val figure = uber?.takeIf {
                            it.capturedAtMs in w.startMs until w.endMs && it.amount.currencyCode == Currencies.DOP && !it.amount.isNegative
                        }?.amount?.minorUnits
                        Goals.withUberFigure(Goals.daily(goals.dailyMinor, income, onlineMs), figure)?.let { GoalStripState("Meta de hoy", it) }
                    }
                    goals.weeklyMinor > 0 -> {
                        val w = EarningsWindows.of(EarningsRange.WEEK, 0, now, clock, goals.weekStart)
                        Goals.weekly(goals.weeklyMinor, money.periodEarnings(w.startMs, w.endMs), EarningsWindows.daysLeft(w, now, clock))
                            ?.let { GoalStripState("Meta de la semana", it) }
                    }
                    else -> null
                }
            } catch (e: Exception) {
                Log.e("GoalStrip", "Goal strip failed", e)
                null
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

/** Nothing is drawn without a goal. Tapping opens Ganancias. */
@Composable
fun GoalStrip(onOpen: (() -> Unit)?, modifier: Modifier = Modifier, viewModel: GoalStripViewModel = viewModel()) {
    val strip by viewModel.state.collectAsState()
    val s = strip ?: return
    val p = s.progress
    val money = { minor: Long -> EarningsFormat.money(minor, Currencies.DOP) }
    val line = (if (p.reached) "¡Meta alcanzada! ${money(p.earnedMinor)}" else "Te faltan ${money(p.remainingMinor)}") +
        (if (p.source == com.ubers.observador.domain.earnings.GoalSource.UBER) " · según la cifra HOY de Uber" else "")
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = modifier.fillMaxWidth()
            .then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier)
            .clearAndSetSemantics {
                contentDescription = "${s.title}: ${money(p.earnedMinor)} de ${money(p.goalMinor)}. $line"
                if (onOpen != null) role = Role.Button
            }
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(painterResource(R.drawable.ic_flag), contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(18.dp))
                Text(s.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.weight(1f))
                Text(
                    "${money(p.earnedMinor)} / ${money(p.goalMinor)}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
            LinearProgressIndicator(
                progress = { p.permille / 1000f },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)),
                color = MaterialTheme.colorScheme.tertiary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                drawStopIndicator = {}
            )
            Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
        }
    }
}
