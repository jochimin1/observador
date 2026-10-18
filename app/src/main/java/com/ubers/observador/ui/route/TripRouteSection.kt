package com.ubers.observador.ui.route

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ubers.observador.R
import com.ubers.observador.domain.distance.DistanceFormat
import com.ubers.observador.domain.route.RouteCoverage
import com.ubers.observador.domain.route.RoutePhase
import com.ubers.observador.domain.route.RouteStatus
import com.ubers.observador.domain.route.TripSheet
import com.ubers.observador.domain.route.TripSheets
import com.ubers.observador.domain.trips.TripStatus
import com.ubers.observador.ui.components.InfoLine
import com.ubers.observador.ui.components.LegendDot
import com.ubers.observador.ui.components.ReportExportEffects
import java.time.ZoneId

/**
 * Fase 8K, in the detail panels of Inicio and Ganancias: the trip's card (who, when and where it
 * began and ended, offered against real) and the card «Recorrido» with the map, export and delete.
 */
@Composable
fun TripRouteSection(tripId: String) {
    val vm: RouteViewModel = viewModel(key = "route-$tripId")
    LaunchedEffect(tripId) { vm.load(tripId) }
    val state by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    ReportExportEffects(vm.exporter, snackbar)
    val sheet = state.sheet
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (sheet != null) TripSheetCard(sheet)
        RouteCard(state, onExport = { format, share -> if (share) vm.share(format) else vm.save(format) }, onDelete = { vm.deleteRoute() })
        SnackbarHost(snackbar)
    }
}

@Composable
private fun Title(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Line(label: String, value: String, note: String? = null) {
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
        note?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Who, when and where: the trip's card. */
@Composable
fun TripSheetCard(s: TripSheet) {
    val zone = ZoneId.systemDefault()
    fun h(ms: Long?) = ms?.let { TripSheets.hour(it, zone) } ?: "—"
    com.ubers.observador.ui.components.SheetGroup {
        Title("Ficha del viaje")
        s.riderName?.let { InfoLine(R.drawable.ic_info, "Pasajero: $it") }
        Line("Aceptado", h(s.acceptedAt))
        s.arrivedAt?.let { Line("Llegada al punto", h(it), "Pantalla «Llegué» (aún sin validar con capturas)") }
        Line(
            if (s.startDistinguished) "Inicio del viaje" else "Inicio (sin distinguir)",
            h(s.startAt),
            placeNote(s.startPlace, s.uberPickup, if (s.startDistinguished) null else "Primer punto del recorrido")
        )
        Line(
            when (s.status) {
                TripStatus.CANCELLED -> "Cancelado"
                TripStatus.INTERRUPTED -> "Fin (sin confirmar)"
                else -> "Fin del viaje"
            },
            if (s.endAt == null) "En curso" else h(s.endAt),
            placeNote(s.endPlace, s.uberDropoff, null)
        )
        s.totalMs?.let { Line("Duración", TripSheets.duration(it), "Desde que lo aceptaste hasta el final") }
        if (!s.startDistinguished && s.endAt != null) {
            InfoLine(
                R.drawable.ic_info,
                "«Iniciar viaje» aún no se distingue: la hora de inicio es la de aceptado y el lugar, el primer punto del recorrido."
            )
        }
        if (s.comparisons.isNotEmpty()) {
            HorizontalDivider()
            Title("Ofertado contra real")
            s.comparisons.forEach { c ->
                Column {
                    Text(c.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(c.text, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** The GPS place name, with Uber's address when there is no name yet (or when they differ). */
private fun placeNote(gps: String?, uber: String?, prefix: String?): String? {
    val parts = listOfNotNull(
        prefix,
        gps,
        uber?.let { if (gps == null) "Según Uber: $it" else "Uber: $it" }
    )
    return parts.joinToString(" · ").ifEmpty { null }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RouteCard(state: RouteUi, onExport: (RouteFormat, Boolean) -> Unit, onDelete: () -> Unit) {
    var exporting by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    val summary = state.summary
    val trip = state.trip
    com.ubers.observador.ui.components.SheetGroup {
        Title("Recorrido")
        when {
            state.loading -> Text("Cargando…", style = MaterialTheme.typography.bodySmall)
            summary == null -> InfoLine(
                R.drawable.ic_info,
                when {
                    !state.recordingOn -> "La grabación del recorrido está apagada (Ajustes › Recorrido de los viajes)."
                    trip != null && trip.status.isOpen -> "Se empieza a grabar en cuanto llegue una posición del GPS."
                    else -> "Este viaje no tiene recorrido: es anterior a la versión 1.24.0 o se tomó con la grabación apagada."
                }
            )
            summary.status == RouteStatus.DELETED -> InfoLine(
                R.drawable.ic_info,
                "Recorrido borrado. Se conservan las horas, los lugares, los km y el dinero."
            )
            state.points.isEmpty() -> InfoLine(
                R.drawable.ic_warning,
                if (summary.isOpen) "Grabando: aún no hay puntos (se guardan cada 30 segundos)."
                else "Sin GPS: el viaje se guardó sin recorrido. Activa «Medir km con GPS» y el permiso de ubicación."
            )
            else -> {
                RouteMap(state.points)
                val phases = state.points.map { it.phase }.toSet()
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (RoutePhase.PICKUP in phases) LegendDot(RouteColors.pickup, "Yendo a recoger")
                    if (RoutePhase.PASSENGER in phases) LegendDot(RouteColors.passenger, "Con pasajero")
                    if (RoutePhase.UNKNOWN in phases) LegendDot(RouteColors.unknown, "Sin distinguir")
                    LegendDot(RouteColors.start, "Inicio")
                    LegendDot(RouteColors.end, "Fin")
                }
                val km = state.distance?.let { d ->
                    "${DistanceFormat.km(d.pickupMm)} km a recoger + ${DistanceFormat.km(d.tripMm)} km de viaje"
                }
                Text(
                    listOfNotNull(
                        if (summary.isOpen) "Grabando…" else "Cobertura: ${summary.coverage.label.lowercase()}",
                        "${summary.pointsKept} puntos",
                        km
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (summary.secondsWithoutSignal >= 60) {
                    InfoLine(R.drawable.ic_warning, "${TripSheets.duration(summary.secondsWithoutSignal * 1000)} sin señal del GPS durante el viaje.")
                }
                if (summary.status == RouteStatus.CAPPED) {
                    InfoLine(R.drawable.ic_warning, "Se grabaron solo las primeras 3 horas del viaje.")
                }
                if (summary.coverage == RouteCoverage.PARTIAL && !summary.isOpen) {
                    InfoLine(R.drawable.ic_info, "Parcial: falta algún tramo (el GPS llegó tarde, hubo un hueco o se reinició el teléfono).")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(onClick = { exporting = true }, enabled = !summary.isOpen) {
                        Icon(painterResource(R.drawable.ic_download), contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Exportar", Modifier.padding(start = 6.dp))
                    }
                    TextButton(onClick = { deleting = true }, enabled = !summary.isOpen) {
                        Text("Borrar recorrido", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }

    if (exporting) {
        AlertDialog(
            onDismissRequest = { exporting = false },
            icon = { Icon(painterResource(R.drawable.ic_route), contentDescription = null) },
            title = { Text("Exportar recorrido") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "El archivo lleva las ubicaciones del viaje y el nombre del pasajero. GPX abre en Google Maps (Mis mapas), " +
                            "Google Earth y casi cualquier app de mapas; GeoJSON, en herramientas de mapas y hojas de cálculo.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    RouteFormat.entries.forEach { f ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(f.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            FilledTonalButton(onClick = { exporting = false; onExport(f, false) }) { Text("Guardar") }
                            OutlinedButton(onClick = { exporting = false; onExport(f, true) }) { Text("Compartir") }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { exporting = false }) { Text("Cerrar") } }
        )
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("¿Borrar este recorrido?") },
            text = { Text("Se borran los puntos y el trazo. El viaje, sus horas, los nombres de los lugares, los km y el dinero se quedan.") },
            confirmButton = {
                TextButton(onClick = { deleting = false; onDelete() }) { Text("Borrar", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancelar") } }
        )
    }
}
