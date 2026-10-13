package com.ubers.observador.ui.settings

import android.app.Application
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ubers.observador.R
import com.ubers.observador.data.AppSettings
import com.ubers.observador.data.DistanceRepository
import com.ubers.observador.data.db.AppDatabase
import com.ubers.observador.data.debug.DistanceTestSeeder
import com.ubers.observador.domain.WorkdayClock
import com.ubers.observador.domain.distance.DistanceFormat
import com.ubers.observador.domain.distance.DistanceSegment
import com.ubers.observador.service.DistanceLiveState
import com.ubers.observador.service.DistanceMonitor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class DistanceDebugUiState(
    val live: DistanceLiveState = DistanceLiveState(),
    val recent: List<DistanceSegment> = emptyList(),
    val message: String? = null
)

/** Debug builds only (Fase 8E): the tracker's counters and TEST stretches. Never positions. */
class DistanceDebugViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = DistanceRepository(AppDatabase.getInstance(app))
    private val settings = AppSettings(app)
    private val message = MutableStateFlow<String?>(null)

    val state: StateFlow<DistanceDebugUiState> = combine(DistanceMonitor.state, repository.observeRecent(8), message) { live, recent, text ->
        DistanceDebugUiState(live, recent, text)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DistanceDebugUiState())

    fun load() = viewModelScope.launch {
        message.value = try {
            val clock = WorkdayClock(settings.workdayStartHour.first(), ZoneId.systemDefault())
            val segments = DistanceTestSeeder.generate(System.currentTimeMillis(), clock)
            repository.replaceTestSegments(DistanceTestSeeder.PREFIX, segments)
            "${segments.size} tramos TEST cargados (uno es de ayer y no cuenta hoy)."
        } catch (e: Exception) {
            Log.e("DistanceDebug", "Could not load TEST stretches", e)
            "No se pudieron cargar los tramos TEST."
        }
    }

    fun clear() = viewModelScope.launch {
        message.value = try {
            "${repository.deleteTestSegments(DistanceTestSeeder.PREFIX)} tramos TEST borrados. Los reales no se tocan."
        } catch (e: Exception) {
            Log.e("DistanceDebug", "Could not delete TEST stretches", e)
            "No se pudieron borrar los tramos TEST."
        }
    }
}

private val CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss")
private fun at(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(CLOCK)

@Composable
fun DistanceDebugCard(viewModel: DistanceDebugViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val live = state.live
    val d = live.diagnostics
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_route), contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Text("  Kilómetros (diagnóstico)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            }
            Text(
                "Solo contadores: ninguna posición se guarda ni se muestra.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Midiendo: ${live.measuring?.name ?: "no"} · última posición: ${live.lastFixAt?.let(::at) ?: "—"}" +
                    if (live.backgroundLimited) " · GPS limitado en segundo plano" else "",
                style = MaterialTheme.typography.bodySmall
            )
            live.open?.let { s ->
                Text(
                    "Tramo abierto: ${DistanceFormat.km(s.measuredMm)} km · sin señal ${DistanceFormat.km(s.gapMm)} km · " +
                        "usadas ${s.fixesUsed} · descartadas ${s.fixesDiscarded} · ${s.secondsWithoutSignal} s sin señal",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Text(
                "Posiciones ${d.fixesReceived} · imprecisas ${d.discardedAccuracy} · saltos ${d.discardedJump} · " +
                    "temblor ${d.jitterIgnored} · huecos estimados ${d.gapsEstimated} · huecos largos ${d.gapsTooLong}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text("Últimos tramos", style = MaterialTheme.typography.labelLarge)
            if (state.recent.isEmpty()) Text("Ninguno todavía.", style = MaterialTheme.typography.bodySmall)
            state.recent.forEach { s ->
                Text(
                    "${s.workday} ${at(s.startedAt)}–${s.endedAt?.let { at(it) } ?: "abierto"} · ${DistanceFormat.km(s.measuredMm)} km · " +
                        "${s.origin.name} · ${s.endReason?.name ?: ""}" + if (s.id.startsWith("TEST-")) " · TEST" else "",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { viewModel.load() }) { Text("Cargar TEST") }
                OutlinedButton(onClick = { viewModel.clear() }) { Text("Borrar TEST") }
            }
            state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
