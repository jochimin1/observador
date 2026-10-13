package com.ubers.observador.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ubers.observador.R
import com.ubers.observador.domain.StatsPeriod
import com.ubers.observador.domain.online.OnlineFormat
import com.ubers.observador.domain.online.OnlineSummary
import com.ubers.observador.ui.components.InfoLine
import com.ubers.observador.ui.components.LegendDot
import com.ubers.observador.ui.components.SectionCard
import com.ubers.observador.ui.components.SplitBar
import com.ubers.observador.ui.components.StatTile

/**
 * Fase 8A: "Tiempo en línea" on the home screen, for the period of the summary chips. Confirmed
 * time leads; possible time only appears as "hasta …" when Uber went unseen for a while. Busy,
 * waiting and the rates use confirmed time only, and money per hour always says its coverage.
 */
@Composable
fun OnlineCard(state: OnlineUiState, modifier: Modifier = Modifier) {
    val s = state.summary
    SectionCard(
        title = "Tiempo en línea",
        icon = R.drawable.ic_schedule,
        subtitle = "${periodText(state.period)} · conectado a Uber",
        badgeContainer = MaterialTheme.colorScheme.secondaryContainer,
        badgeContent = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier
    ) {
        when {
            state.loading -> Unit
            !s.hasData -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Aún no hay tiempo en línea en este periodo.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                InfoLine(
                    R.drawable.ic_info,
                    "Se empieza a medir cuando Uber muestra «Estás conectado» con Observador activo."
                )
            }
            else -> OnlineContent(s)
        }
    }
}

@Composable
private fun OnlineContent(s: OnlineSummary) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Hero(s)
        BusyBar(s)
        val tiles = tilesOf(s)
        if (LocalDensity.current.fontScale > LARGE_FONT_SCALE) {
            tiles.forEach { it(Modifier.fillMaxWidth()) }
        } else {
            tiles.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    pair.forEach { tile -> tile(Modifier.weight(1f)) }
                }
            }
        }
        s.waitBetweenTripsAvgMs?.let { wait ->
            InfoLine(
                R.drawable.ic_swap_horiz,
                "Entre un viaje y el siguiente esperas ${OnlineFormat.duration(wait)} de media " +
                    "(${s.waitBetweenTripsSamples} ${if (s.waitBetweenTripsSamples == 1) "vez" else "veces"})."
            )
        }
        if (s.recoveredSessionCount > 0) {
            InfoLine(
                R.drawable.ic_history,
                if (s.recoveredSessionCount == 1) "Una sesión ya estaba empezada cuando Observador la vio: su tiempo es un mínimo."
                else "${s.recoveredSessionCount} sesiones ya estaban empezadas cuando Observador las vio: su tiempo es un mínimo."
            )
        }
        if (s.hasRange) {
            InfoLine(
                R.drawable.ic_info,
                "Hubo ratos de más de 30 min sin ver Uber: no cuentan como confirmados, solo en «hasta»."
            )
        }
    }
}

/** The big number: confirmed time, and the ceiling when some time went unseen. */
@Composable
private fun Hero(s: OnlineSummary) {
    Column(Modifier.semantics(mergeDescendants = true) {}) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                OnlineFormat.duration(s.confirmedMs),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.alignByBaseline()
            )
            if (s.hasRange) {
                Text(
                    "hasta ${OnlineFormat.duration(s.possibleMs)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.alignByBaseline()
                )
            }
        }
        val sessions = if (s.sessionCount == 1) "1 sesión" else "${s.sessionCount} sesiones"
        Text(
            if (s.openSession) "$sessions · una sigue abierta" else sessions,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Busy versus waiting, as a bar and its legend. */
@Composable
private fun BusyBar(s: OnlineSummary) {
    val share = if (s.confirmedMs > 0) s.busyMs.toFloat() / s.confirmedMs else null
    val busy = OnlineFormat.duration(s.busyMs) + if (s.busyIsMinimum) " (mínimo)" else ""
    val waiting = OnlineFormat.duration(s.waitingMs)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SplitBar(first = share, description = "Ocupado $busy, esperando $waiting")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            LegendDot(MaterialTheme.colorScheme.primary, "Ocupado $busy")
            LegendDot(MaterialTheme.colorScheme.secondaryContainer, "Esperando $waiting")
        }
    }
}

private fun tilesOf(s: OnlineSummary): List<@Composable (Modifier) -> Unit> = listOf<@Composable (Modifier) -> Unit>(
    { m ->
        StatTile(
            icon = R.drawable.ic_two_wheeler,
            label = "Ocupación",
            value = s.utilizationPercent?.let { "$it %" } ?: "—",
            detail = if (s.utilizationPercent == null) "Poco tiempo aún" else "del tiempo en línea con viaje",
            modifier = m
        )
    },
    { m ->
        StatTile(
            icon = R.drawable.ic_bolt,
            label = "Ofertas por hora",
            value = s.offersPerHourTenths?.let(OnlineFormat::tenths) ?: "—",
            detail = if (s.offersSeen == 1) "1 oferta vista" else "${s.offersSeen} ofertas vistas",
            modifier = m,
            accent = MaterialTheme.colorScheme.tertiary
        )
    },
    { m ->
        StatTile(
            icon = R.drawable.ic_payments,
            label = "Ganancia por hora",
            value = OnlineFormat.earningsPerHour(s.earnings),
            detail = OnlineFormat.coverage(s.earnings) ?: "Sin viajes terminados",
            modifier = m,
            accent = MaterialTheme.colorScheme.tertiary,
            detailColor = if (s.earnings?.partial == true) MaterialTheme.colorScheme.error else Color.Unspecified
        )
    },
    { m ->
        StatTile(
            icon = R.drawable.ic_timer,
            label = "Hasta la 1.ª oferta",
            value = s.firstOfferAvgMs?.let(OnlineFormat::duration) ?: "—",
            detail = if (s.firstOfferSamples == 0) "Solo con inicio exacto" else "promedio de ${s.firstOfferSamples}",
            modifier = m
        )
    }
)

private fun periodText(period: StatsPeriod): String = when (period) {
    StatsPeriod.TODAY -> "Jornada de hoy"
    StatsPeriod.WEEK -> "Últimos 7 días"
    StatsPeriod.MONTH -> "Últimos 30 días"
    StatsPeriod.ALL -> "Todo el historial"
}
