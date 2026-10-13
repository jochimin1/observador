package com.ubers.observador.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ubers.observador.data.TripWithEvents
import com.ubers.observador.domain.finance.TripFinancialSummary
import com.ubers.observador.domain.money.Currencies
import com.ubers.observador.domain.money.MoneyAmount
import com.ubers.observador.domain.money.MoneyFormat
import com.ubers.observador.domain.formatNumber
import com.ubers.observador.domain.trips.Trip
import com.ubers.observador.domain.trips.TripTimeline
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TIME = DateTimeFormatter.ofPattern("HH:mm")
private val TIME_SECONDS = DateTimeFormatter.ofPattern("HH:mm:ss")
private const val HASH_HEAD = 6
private const val HASH_TAIL = 3

private fun clock(ms: Long?, format: DateTimeFormatter = TIME): String =
    ms?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(format) } ?: "—"

/**
 * Debug builds only (Fase 7B): what the database holds. Shows states, times, ids and codes; no
 * names or addresses are ever stored. Real trips can be looked at, never edited or deleted here.
 */
@Composable
fun TripsDebugCard(viewModel: TripsDebugViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val selected by viewModel.selected.collectAsState()
    val selectedMoney by viewModel.selectedMoney.collectAsState()
    val selectedDistance by viewModel.selectedDistance.collectAsState()

    selected?.let {
        TimelineDialog(
            it,
            selectedMoney?.takeIf { m -> m.tripId == it.trip.id },
            selectedDistance?.takeIf { d -> d.first == it.trip.id }?.second,
            onDismiss = { viewModel.select(null) }
        )
    }

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Viajes detectados", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            Text(
                text = "Lo que guarda la base. Un viaje nace al aceptar (o al recuperarse a mitad); " +
                    "una oferta sola no es viaje. Toca uno para ver su línea de tiempo.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val active = state.active
            Text(
                text = "Viajes ${state.tripCount} · eventos ${state.eventCount} · activo: " +
                    (active?.let { "${it.status.name} (${shortId(it.id)})" } ?: "ninguno"),
                style = MaterialTheme.typography.bodySmall
            )
            val m = state.metrics
            Text(
                text = "Esta sesión: creados ${m.tripsCreated} · recuperados ${m.recoveredTrips} · completados " +
                    "${m.tripsCompleted} · cancelados ${m.tripsCancelled} · interrumpidos ${m.tripsInterrupted} · " +
                    "eventos ${m.eventsPersisted} · repetidos ${m.duplicateEventsIgnored} · anomalías " +
                    "${m.anomaliesDetected} · errores ${m.persistenceErrors}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { viewModel.loadTestTrips() }) { Text("Cargar prueba") }
                OutlinedButton(onClick = { viewModel.clearTestTrips() }) { Text("Borrar prueba") }
            }
            TextButton(onClick = { viewModel.checkIntegrity() }) { Text("Comprobar integridad") }
            state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (state.trips.isNotEmpty()) HorizontalDivider()
            state.trips.forEach { (trip, events) ->
                TripRow(trip, events, onClick = { viewModel.select(trip.id) })
            }
        }
    }
}

@Composable
private fun TripRow(trip: Trip, eventCount: Int, onClick: () -> Unit) {
    val start = trip.acceptedAt ?: trip.pickupNavigationAt ?: trip.arrivedAt ?: trip.tripStartedAt ?: trip.createdAt
    val end = trip.completedAt ?: trip.cancelledAt ?: trip.interruptedAt
    val flags = listOfNotNull(
        "recuperado".takeIf { trip.recovered },
        "⚠ anomalía".takeIf { trip.hasAnomalies },
        "prueba".takeIf { trip.id.startsWith(com.ubers.observador.data.debug.TripTestSeeder.PREFIX) }
    ).ifEmpty { listOf("normal") }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClickLabel = "Ver línea de tiempo", role = Role.Button, onClick = onClick)
            .padding(vertical = 4.dp)
    ) {
        Text(trip.status.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Text(
            text = "${clock(start)} → ${clock(end)} · $eventCount eventos · oferta " +
                (trip.relatedOfferHash?.let(::shortHash) ?: "—") + " · " + flags.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TimelineDialog(
    data: TripWithEvents,
    money: TripFinancialSummary?,
    distance: com.ubers.observador.domain.distance.TripDistance?,
    onDismiss: () -> Unit
) {
    val trip = data.trip
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${trip.status.name} · ${shortId(trip.id)}") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("Línea de tiempo: ${TripTimeline.validate(trip).name}", style = MaterialTheme.typography.bodySmall)
                Text(
                    "Sesión ${shortId(trip.lifecycleSessionId)} · oferta ${trip.relatedOfferHash?.let(::shortHash) ?: "—"}" +
                        (trip.offer?.let { " · score original ${it.scoreAtDetection}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall
                )
                MoneyBlock(trip, money)
                DistanceBlock(trip, distance)
                HorizontalDivider()
                data.events.forEach { e ->
                    Text(
                        text = "${e.sequence}. ${clock(e.timestampMs, TIME_SECONDS)} ${e.type.name} → " +
                            (e.resultingStatus?.name ?: "—") + (e.anomaly?.let { " ⚠ ${it.name}" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } }
    )
}

/** Offered vs real (Fase 7C). The offer is a reference; real money only from what Uber showed later. */
@Composable
private fun MoneyBlock(trip: Trip, money: TripFinancialSummary?) {
    HorizontalDivider()
    val offered = money?.offeredAmount
        ?: trip.offer?.offeredAmount?.let { MoneyAmount.fromLegacyDouble(it, Currencies.DOP) }
    val lines = ArrayList<String>()
    lines += "Ofertado: " + (offered?.let { MoneyFormat.format(it) } ?: "—")
    lines += "Real: " + (money?.effectiveEarnings?.let { MoneyFormat.format(it) } ?: "no observado")
    money?.differenceFromOffer()?.let { (diff, pct) ->
        lines += "Diferencia: ${MoneyFormat.format(diff, signed = true)} (${if (pct.signum() > 0) "+" else ""}$pct%)"
    }
    if (money != null) {
        val cur = money.currencyCode ?: Currencies.DOP
        fun part(label: String, minor: Long) = if (minor != 0L) "$label ${MoneyFormat.format(MoneyAmount(minor, cur))}" else null
        listOfNotNull(
            part("tarifa", money.fare), part("propina", money.tip), part("promoción", money.promotion),
            part("ajuste", money.adjustment), part("cancelación", money.cancellationFee), part("otros", money.other)
        ).takeIf { it.isNotEmpty() }?.let { lines += "Desglose: " + it.joinToString(" · ") }
        lines += "Estado: ${money.completeness.name} · ${money.observationCount} cantidades" +
            (if (money.pendingReviewCount > 0) " · ${money.pendingReviewCount} por revisar" else "") +
            (if (money.anomalies.isNotEmpty()) " · ⚠ " + money.anomalies.joinToString { it.name } else "")
    } else {
        lines += "Estado: UNKNOWN · ningún importe visto"
    }
    lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
}

/** GPS kilometres (Fase 8E) against what the offer estimated; with real money, the real RD$/km. */
@Composable
private fun DistanceBlock(trip: Trip, distance: com.ubers.observador.domain.distance.TripDistance?) {
    HorizontalDivider()
    fun km(mm: Long) = com.ubers.observador.domain.distance.DistanceFormat.km(mm)
    val offer = trip.offer
    val estimated = offer?.let { " (oferta: ${formatNumber(it.pickupKm)} + ${formatNumber(it.tripKm)} km)" } ?: ""
    if (distance == null) {
        Text("Km GPS: no medidos$estimated", style = MaterialTheme.typography.bodySmall)
        return
    }
    Text(
        "Km GPS: recogida ${km(distance.pickupMm)} + viaje ${km(distance.tripMm)} km$estimated · ${distance.coverage.name}" +
            if (distance.gapMm > 0) " · ${km(distance.gapMm)} km aprox. sin señal" else "",
        style = MaterialTheme.typography.bodySmall
    )
}

private fun shortId(id: String): String = if (id.length <= HASH_HEAD + 2) id else id.take(HASH_HEAD + 2) + "…"

private fun shortHash(hash: String): String =
    if (hash.length <= HASH_HEAD + HASH_TAIL) hash else hash.take(HASH_HEAD) + "…" + hash.takeLast(HASH_TAIL)
