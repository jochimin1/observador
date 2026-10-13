package com.ubers.observador.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ubers.observador.R
import com.ubers.observador.domain.distance.DayDistance
import com.ubers.observador.domain.distance.DistanceFormat
import com.ubers.observador.domain.distance.DistanceSegment
import com.ubers.observador.domain.distance.SegmentOrigin
import com.ubers.observador.ui.components.InfoLine
import com.ubers.observador.ui.components.LegendDot
import com.ubers.observador.ui.components.SectionCard
import com.ubers.observador.ui.components.StackedBar
import com.ubers.observador.ui.components.rememberLocationPermissionRequest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")

private fun hhmm(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(CLOCK)

/**
 * Fase 8E: "Km de hoy". The day's total (the next working day starts at 0), split into with
 * passenger, going to pick up, without passenger and offline; the stretches of the day; and the
 * "Iniciar / Detener recorrido" button. Only kilometres and hours: never where you were.
 */
@Composable
fun DistanceCard(
    state: DistanceUiState,
    onEnable: () -> Unit,
    onManual: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val measuring = state.live.measuring
    SectionCard(
        title = "Km de hoy",
        icon = R.drawable.ic_route,
        subtitle = "Kilómetros reales con GPS · la jornada empieza en 0",
        badgeContainer = MaterialTheme.colorScheme.tertiaryContainer,
        badgeContent = MaterialTheme.colorScheme.onTertiaryContainer,
        action = if (measuring != null) { { MeasuringPill() } } else null,
        modifier = modifier
    ) {
        when {
            state.loading -> Unit
            !state.enabled || !state.permitted -> Setup(state, onEnable)
            else -> Content(state, onManual)
        }
    }
}

@Composable
private fun MeasuringPill() {
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.tertiary) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(painterResource(R.drawable.ic_sensors), contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onTertiary)
            Text("Midiendo", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onTertiary)
        }
    }
}

/** Off, or no permission yet: what it does, the privacy promise, and one button. */
@Composable
private fun Setup(state: DistanceUiState, onEnable: () -> Unit) {
    val request = rememberLocationPermissionRequest { granted -> if (granted) onEnable() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Cuenta los km que de verdad recorres: sola mientras estás conectado a Uber, o con el botón.",
            style = MaterialTheme.typography.bodyMedium
        )
        InfoLine(R.drawable.ic_info, "Solo se guardan km y horas. Nunca dónde estuviste ni por dónde pasaste.")
        if (state.enabled && !state.permitted) {
            InfoLine(R.drawable.ic_warning, "Falta el permiso de ubicación precisa.", tint = MaterialTheme.colorScheme.error)
        }
        Button(onClick = { if (state.permitted) onEnable() else request() }) {
            Icon(painterResource(R.drawable.ic_route), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (state.enabled) "Dar permiso de ubicación" else "Medir km con GPS")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Content(state: DistanceUiState, onManual: (Boolean) -> Unit) {
    val day = state.day ?: return
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Hero(day)
        val parts = listOf(
            Triple(day.passengerMm, scheme.primary, "Con pasajero"),
            Triple(day.pickupMm, scheme.tertiary, "A recoger"),
            Triple(day.emptyMm, scheme.secondary, "Sin pasajero"),
            Triple(day.offlineMm, scheme.outline, "Fuera de línea")
        )
        if (day.measuredMm > 0) {
            StackedBar(
                parts = parts.map { it.first to it.second },
                description = parts.joinToString(", ") { "${it.third} ${DistanceFormat.km(it.first)} km" }
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                parts.filter { it.first > 0 }.forEach { (mm, color, label) ->
                    LegendDot(color, "$label ${DistanceFormat.km(mm)} km")
                }
            }
            DistanceFormat.percent(day.emptyMm, day.measuredMm - day.offlineMm)?.let { pct ->
                InfoLine(R.drawable.ic_two_wheeler, "$pct % de los km en línea fueron sin pasajero.")
            }
        }
        if (day.segments.isNotEmpty()) Segments(day.segments)
        Controls(state, onManual)
        if (state.live.backgroundLimited) {
            InfoLine(
                R.drawable.ic_warning,
                "Android limitó el GPS en segundo plano. Abre Observador una vez para que siga midiendo con la pantalla apagada.",
                tint = scheme.error
            )
        }
        InfoLine(R.drawable.ic_info, "Solo se guardan km y horas. Nunca dónde estuviste.")
    }
}

@Composable
private fun Hero(day: DayDistance) {
    Column(Modifier.semantics(mergeDescendants = true) {}) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "${DistanceFormat.km(day.measuredMm)} km",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.alignByBaseline()
            )
            if (day.gapMm >= 50_000) {
                Text(
                    "+ ${DistanceFormat.km(day.gapMm)} km aprox. sin señal",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.alignByBaseline()
                )
            }
        }
        val n = day.segments.size
        Text(
            when (n) {
                0 -> "Aún no has recorrido nada hoy"
                1 -> "1 tramo hoy"
                else -> "$n tramos hoy"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** One line per stretch: hours, km, how it was measured. */
@Composable
private fun Segments(segments: List<DistanceSegment>) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            segments.forEach { s ->
                Row(
                    modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        painterResource(if (s.origin == SegmentOrigin.AUTO_ONLINE) R.drawable.ic_two_wheeler else R.drawable.ic_route),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (s.isOpen) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "${hhmm(s.startedAt)} – ${s.endedAt?.let(::hhmm) ?: "ahora"}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        if (s.origin == SegmentOrigin.AUTO_ONLINE) "Conectado" else "Manual",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text("${DistanceFormat.km(s.measuredMm)} km", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/** The button follows the state: start, stop, or "it measures on its own while connected". */
@Composable
private fun Controls(state: DistanceUiState, onManual: (Boolean) -> Unit) {
    val measuring = state.live.measuring
    when {
        !state.observerOn -> InfoLine(R.drawable.ic_pause_circle, "Observador está en pausa: no se miden km.")
        measuring == SegmentOrigin.MANUAL -> OutlinedButton(onClick = { onManual(false) }) {
            Icon(painterResource(R.drawable.ic_pause_circle), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Detener recorrido")
        }
        measuring == SegmentOrigin.AUTO_ONLINE ->
            InfoLine(R.drawable.ic_two_wheeler, "Mide sola mientras estás conectado a Uber y se guarda al desconectarte.")
        state.mode.allowsManual -> FilledTonalButton(onClick = { onManual(true) }) {
            Icon(painterResource(R.drawable.ic_play_circle), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Iniciar recorrido")
        }
        else -> InfoLine(R.drawable.ic_two_wheeler, "Empieza a medir sola al conectarte a Uber.")
    }
}
