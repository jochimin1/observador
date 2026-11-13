package com.ubers.observador.ui.trips

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ubers.observador.R
import com.ubers.observador.domain.StatsPeriod
import com.ubers.observador.domain.distance.DistanceFormat
import com.ubers.observador.domain.earnings.EarningsFormat
import com.ubers.observador.domain.finance.FinancialCompleteness
import com.ubers.observador.domain.finance.FinancialObservationType
import com.ubers.observador.domain.finance.FinancialSource
import com.ubers.observador.domain.money.MoneyAmount
import com.ubers.observador.domain.money.MoneyFormat
import com.ubers.observador.domain.route.TripSheets
import com.ubers.observador.domain.trips.CorrectionField
import com.ubers.observador.domain.trips.CorrectionOrigin
import com.ubers.observador.domain.trips.TripCenter
import com.ubers.observador.domain.trips.TripCorrection
import com.ubers.observador.domain.trips.TripCorrections
import com.ubers.observador.domain.trips.TripDetails
import com.ubers.observador.domain.trips.TripRow
import com.ubers.observador.domain.trips.TripStatus
import com.ubers.observador.domain.trips.TripStatusFilter
import com.ubers.observador.ui.components.InfoLine
import com.ubers.observador.ui.components.SheetGroup
import com.ubers.observador.ui.components.StatRow
import com.ubers.observador.ui.components.StatTile
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.forLanguageTag("es"))

/** 1.40.0: every trip, whatever happened to it, with filters and a local search. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripCenterScreen(onBack: () -> Unit, onOpenTrip: (String) -> Unit, vm: TripCenterViewModel = viewModel()) {
    val state by vm.list.collectAsState()
    val hide by vm.hideNames.collectAsState()
    val q = state.query
    Scaffold(
        containerColor = com.ubers.observador.ui.theme.screenBackground(),
        topBar = {
            TopAppBar(
                colors = com.ubers.observador.ui.theme.topBarColors(),
                title = { Text("Centro de Viajes") },
                navigationIcon = { TextButton(onClick = onBack) { Text("← Volver") } }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Chips(StatsPeriod.entries.map { it.label to (it == state.period) }) { vm.setPeriod(StatsPeriod.entries[it]) }
                Chips(TripStatusFilter.entries.map { f ->
                    "${f.label} ${state.all.count { f.accepts(it.trip) }}" to (f == q.status)
                }) { vm.setQuery(q.copy(status = TripStatusFilter.entries[it])) }
                Chips(listOf("Dinero pendiente ${state.all.count { it.moneyPending }}" to q.moneyPendingOnly)) {
                    vm.setQuery(q.copy(moneyPendingOnly = !q.moneyPendingOnly))
                }
                if (state.products.size > 1) Chips(state.products.map { it to it.equals(q.product, true) }) { i ->
                    val p = state.products[i]; vm.setQuery(q.copy(product = if (p.equals(q.product, true)) null else p))
                }
                if (state.sectors.isNotEmpty()) Chips(state.sectors.map { it.name to (it.name == q.sector) }) { i ->
                    val s = state.sectors[i].name; vm.setQuery(q.copy(sector = if (s == q.sector) null else s))
                }
                OutlinedTextField(
                    value = q.text,
                    onValueChange = { vm.setQuery(q.copy(text = it.take(60))) },
                    label = { Text(if (hide) "Buscar por dirección o modalidad" else "Buscar por pasajero, dirección o modalidad") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Ocultar nombres de pasajeros", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(checked = hide, onCheckedChange = { vm.setHideNames(it) })
                }
                Text(
                    "La búsqueda se hace en el teléfono. Con los nombres ocultos tampoco se buscan.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            when {
                state.loading -> item { Text("Cargando…") }
                state.error -> item { Text("No se pudieron cargar los viajes.", color = MaterialTheme.colorScheme.error) }
                state.shown.isEmpty() -> item {
                    Text(
                        if (state.all.isEmpty()) "No hay viajes en este periodo." else "Ningún viaje coincide con los filtros.",
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 16.dp)
                    )
                }
                else -> items(state.shown, key = { it.id }) { row -> TripRowCard(row, hide, state.zone) { onOpenTrip(row.id) } }
            }
        }
    }
}

@Composable
private fun Chips(chips: List<Pair<String, Boolean>>, onClick: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        chips.forEachIndexed { i, (label, selected) -> FilterChip(selected = selected, onClick = { onClick(i) }, label = { Text(label) }) }
    }
}

private fun day(ms: Long, zone: ZoneId) = Instant.ofEpochMilli(ms).atZone(zone).format(DAY)

private fun money(a: MoneyAmount?) = a?.let { MoneyFormat.format(it) } ?: "—"

@Composable
private fun TripRowCard(row: TripRow, hide: Boolean, zone: ZoneId, onClick: () -> Unit) {
    val t = row.trip
    OutlinedCard(Modifier.fillMaxWidth().clickable(onClickLabel = "Ver viaje", onClick = onClick)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(TripCenter.statusLabel(t), style = MaterialTheme.typography.labelMedium, color = statusColor(t.status), modifier = Modifier.weight(1f))
                Text(
                    "${day(row.startAt, zone)} · ${TripSheets.hourShort(row.startAt, zone)}" + (row.endAt?.let { " → ${TripSheets.hourShort(it, zone)}" } ?: ""),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            val places = listOfNotNull(
                com.ubers.observador.domain.route.PlaceNames.short(t.offer?.pickupAddress),
                com.ubers.observador.domain.route.PlaceNames.short(t.offer?.dropoffAddress)
            ).joinToString(" → ")
            Text(
                listOfNotNull(TripCenter.riderShown(t, hide), t.offer?.product, places.ifEmpty { null }).joinToString(" · ").ifEmpty { "Sin datos de la oferta" },
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium
            )
            Text(
                "Ofertado ${money(row.offered)} · Real ${money(row.real)}" +
                    (if (row.cancellationFeeMinor > 0) " · cargo por cancelación" else "") +
                    (if (row.moneyPending) " · dinero pendiente" else ""),
                style = MaterialTheme.typography.bodySmall
            )
            val badges = listOfNotNull(
                "recuperado".takeIf { t.recovered }, "sin oferta".takeIf { t.offer == null },
                "corregido".takeIf { row.corrected }, "anomalías".takeIf { row.hasAnomalies }
            )
            if (badges.isNotEmpty()) Text(badges.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
        }
    }
}

@Composable
private fun statusColor(s: TripStatus) = when (s) {
    TripStatus.COMPLETED -> MaterialTheme.colorScheme.primary
    TripStatus.CANCELLED -> MaterialTheme.colorScheme.error
    TripStatus.INTERRUPTED -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.secondary
}

/** The full detail, loaded when the trip is opened. Every part copes with missing data. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripDetailScreen(tripId: String, onBack: () -> Unit, vm: TripCenterViewModel = viewModel(key = "trip-$tripId")) {
    LaunchedEffect(tripId) { vm.open(tripId) }
    val d by vm.detail.collectAsState()
    val operating by vm.operating.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    var editName by remember { mutableStateOf(false) }
    var editStatus by remember { mutableStateOf(false) }
    val zone = ZoneId.systemDefault()

    Scaffold(
        containerColor = com.ubers.observador.ui.theme.screenBackground(),
        topBar = {
            TopAppBar(
                colors = com.ubers.observador.ui.theme.topBarColors(),
                title = { Text("Viaje") },
                navigationIcon = { TextButton(onClick = onBack) { Text("← Volver") } }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        val s = d
        if (s == null) {
            Text("Cargando…", Modifier.padding(padding).padding(16.dp))
            return@Scaffold
        }
        val t = s.trip
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(4.dp))
            Text(TripCenter.statusLabel(t), style = MaterialTheme.typography.titleLarge, color = statusColor(t.status))
            val start = t.acceptedAt ?: t.pickupNavigationAt ?: t.createdAt
            Text("${day(start, zone)} · ${TripSheets.hour(start, zone)}", style = MaterialTheme.typography.bodyMedium)
            TripCenter.riderShown(t, s.hideNames)?.let { InfoLine(R.drawable.ic_info, "Pasajero: $it") }
            t.offer?.product?.let { InfoLine(R.drawable.ic_two_wheeler, "Modalidad: $it" + if (t.offer?.exclusive == true) " · Exclusivo" else "") }
            if (t.recovered) InfoLine(R.drawable.ic_history, "Recuperado: Observador se unió al viaje ya empezado o lo retomó tras perderlo.")
            if (t.offer == null) InfoLine(R.drawable.ic_info, "No se capturó la oferta inicial: no hay precio ofertado ni puntaje.")

            // Money: the same summary Ganancias uses
            val sum = s.summary
            StatRow { m ->
                StatTile(R.drawable.ic_bookmarks, "Ofertado", money(sum.offeredAmount ?: t.offer?.offeredAmount?.let { MoneyAmount.fromLegacyDouble(it, com.ubers.observador.domain.money.Currencies.DOP) }), m, detail = "referencia")
                StatTile(
                    R.drawable.ic_payments, "Real",
                    money(sum.effectiveEarnings.takeIf { sum.completeness == FinancialCompleteness.CONFIRMED }), m,
                    detail = when (sum.completeness) {
                        FinancialCompleteness.CONFIRMED -> "confirmado"
                        FinancialCompleteness.PARTIAL -> "parcial: sin total"
                        FinancialCompleteness.UNKNOWN -> "sin dinero visto"
                    }
                )
            }
            if (sum.tip > 0) InfoLine(R.drawable.ic_payments, "Propina: ${MoneyFormat.format(MoneyAmount(sum.tip, sum.currencyCode ?: com.ubers.observador.domain.money.Currencies.DOP))}")
            TripMoneyEditor(t, sum, s.observations, vm::saveMoney, vm::voidMoney, vm::undoMoney)
            com.ubers.observador.ui.earnings.TripOperatingPanel(operating?.takeIf { it.tripId == t.id })

            // Cancelled: what it cost
            TripDetails.cancelled(t, s.distance?.let { it.pickupMm + it.tripMm }, sum)?.let { c ->
                SheetGroup {
                    Text("Lo que costó la cancelación", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text("Tiempo perdido: ${c.lostMs?.let { TripSheets.duration(it) } ?: "sin datos"}", style = MaterialTheme.typography.bodySmall)
                    Text("Km medidos: ${c.measuredMm?.let { "${DistanceFormat.km(it)} km" } ?: "sin medir"}", style = MaterialTheme.typography.bodySmall)
                    Text(
                        when {
                            c.feeConfirmed -> "Cargo por cancelación confirmado: ${MoneyFormat.format(MoneyAmount(c.feeMinor, sum.currencyCode ?: com.ubers.observador.domain.money.Currencies.DOP))}"
                            c.feeMinor > 0 -> "Se vio un cargo por cancelación, sin total confirmado."
                            else -> "No se vio cargo por cancelación."
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // Map, places, offered vs real distance and time (Fase 8K, reused)
            com.ubers.observador.ui.route.TripRouteSection(t.id)
            s.distance?.let { InfoLine(R.drawable.ic_route, "Distancia medida: ${DistanceFormat.km(it.pickupMm)} km de recogida + ${DistanceFormat.km(it.tripMm)} km de viaje") }
                ?: InfoLine(R.drawable.ic_route, "Sin distancia medida con GPS.")
            TripDetails.waitAtPickup(t)?.let { w ->
                InfoLine(R.drawable.ic_hourglass_top, "Espera en el punto: ${TripSheets.duration(w.ms)}" + if (w.endedByCancellation) " (hasta la cancelación)" else "")
            }

            // Scores
            TripDetails.scores(t, s.engine)?.let { sc ->
                SheetGroup {
                    Text("Puntaje", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    StatRow { m ->
                        StatTile(R.drawable.ic_speed, "Al detectar", "${sc.atDetection}", m, detail = "con los ajustes de ese día")
                        StatTile(R.drawable.ic_tune, "Recalculado hoy", "${sc.today}", m, detail = "con tus ajustes actuales")
                    }
                    sc.detectionSettings?.let { ds ->
                        Text(
                            "Ese día: mín. RD$ ${fmt(ds.minRdpPerKm)}/km, RD$ ${fmt(ds.minRdpPerHour)}/h, recogida máx. ${fmt(ds.maxPickupKm)} km o ${ds.maxPickupMinutes} min; bueno desde ${ds.goodFrom}, excelente desde ${ds.excellentFrom}.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    } ?: Text("Los ajustes de ese día no se guardaron (viaje anterior a la Fase 7B).", style = MaterialTheme.typography.bodySmall)
                    val e = sc.todaySettings
                    Text(
                        "Hoy: costo RD$ ${fmt(e.costPerKm)}/km, mín. RD$ ${fmt(e.minRdpPerKm)}/km, RD$ ${fmt(e.minRdpPerHour)}/h, recogida máx. ${fmt(e.maxPickupKm)} km o ${e.maxPickupMin} min.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // Timeline and stored events
            SheetGroup {
                Text("Línea de tiempo", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                val line = TripDetails.timeline(t)
                if (line.isEmpty()) Text("Sin horas registradas.", style = MaterialTheme.typography.bodySmall)
                line.forEach { (label, at) -> Text("${TripSheets.hourShort(at, zone)} · $label", style = MaterialTheme.typography.bodySmall) }
            }
            if (s.events.isNotEmpty()) SheetGroup {
                Text("Eventos guardados (${s.events.size})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                s.events.forEach { ev ->
                    Text(
                        "${ev.sequence}. ${TripSheets.hourShort(ev.timestampMs, zone)} · ${TripDetails.eventLabel(ev.type)}" +
                            (if (ev.recovered) " · recuperado" else "") + (ev.anomaly?.let { " · ${TripDetails.anomalyLabel(it)}" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (ev.anomaly != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            val anomalies = s.events.mapNotNull { it.anomaly }.distinct().map(TripDetails::anomalyLabel) + sum.anomalies.map { "Dinero: ${it.name}" }
            if (anomalies.isNotEmpty() || t.hasAnomalies) SheetGroup {
                Text("Anomalías", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                (anomalies.ifEmpty { listOf("El viaje se marcó con anomalías.") }).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }

            // Corrections: every change leaves a record
            SheetGroup {
                Text("Correcciones", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                if (s.corrections.isEmpty()) Text("Sin correcciones.", style = MaterialTheme.typography.bodySmall)
                s.corrections.forEach { c -> Text(correctionLine(c, s.hideNames, zone), style = MaterialTheme.typography.bodySmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { editName = true }) { Text("Corregir nombre") }
                    if (t.status in TripCorrections.CORRECTABLE) OutlinedButton(onClick = { editStatus = true }) { Text("Corregir estado") }
                }
                val lastStatus = s.corrections.lastOrNull { it.field == CorrectionField.STATUS }
                if (lastStatus?.origin == CorrectionOrigin.DRIVER) TextButton(onClick = { vm.undoStatus() }) { Text("Deshacer la última corrección de estado") }
                Text(
                    "Cada cambio guarda la fecha, quién lo hizo y el valor anterior. Si corriges el nombre, el que lea Uber después no lo reemplaza.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(24.dp))
        }

        if (editName) NameDialog(if (s.hideNames) "" else t.riderName.orEmpty(), onDismiss = { editName = false }) { vm.correctName(it); editName = false }
        if (editStatus) StatusDialog(t.status, onDismiss = { editStatus = false }) { vm.correctStatus(it); editStatus = false }
    }
}

private fun fmt(v: Double) = if (v % 1.0 == 0.0) "%.0f".format(java.util.Locale.US, v) else "%.2f".format(java.util.Locale.US, v)

private fun statusName(s: TripStatus) = when (s) {
    TripStatus.COMPLETED -> "Completado"
    TripStatus.CANCELLED -> "Cancelado"
    TripStatus.INTERRUPTED -> "Interrumpido"
    else -> s.storageKey
}

private fun correctionLine(c: TripCorrection, hide: Boolean, zone: ZoneId): String {
    val (from, to) = when (c.field) {
        CorrectionField.STATUS -> (TripCorrections.decodeStatus(c.oldValue)?.status?.let(::statusName) ?: "—") to
            (TripCorrections.decodeStatus(c.newValue)?.status?.let(::statusName) ?: "—")
        CorrectionField.RIDER_NAME -> if (hide) "oculto" to "oculto" else (c.oldValue ?: "sin nombre") to (c.newValue ?: "sin nombre")
    }
    return "${day(c.atMs, zone)} ${TripSheets.hourShort(c.atMs, zone)} · ${c.field.label}: $from → $to · ${c.origin.label}"
}

@Composable
private fun NameDialog(initial: String, onDismiss: () -> Unit, onSave: (String?) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nombre del pasajero") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = text, onValueChange = { text = it.take(60) }, singleLine = true, label = { Text("Nombre") })
                Text("Déjalo vacío para quitarlo. El cambio queda registrado.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable
private fun StatusDialog(current: TripStatus, onDismiss: () -> Unit, onPick: (TripStatus) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Corregir estado") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Ahora: ${statusName(current)}. Un viaje completado cuenta en Ganancias; uno cancelado o interrumpido sin recogida, no. " +
                        "Se puede deshacer y el cambio queda registrado.",
                    style = MaterialTheme.typography.bodySmall
                )
                TripCorrections.CORRECTABLE.filter { it != current }.forEach { to ->
                    OutlinedButton(onClick = { onPick(to) }, modifier = Modifier.fillMaxWidth()) { Text("Marcar como ${statusName(to).lowercase()}") }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}
