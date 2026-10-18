package com.ubers.observador.ui.reports

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ubers.observador.BuildConfig
import com.ubers.observador.R
import com.ubers.observador.domain.CategoryFilter
import com.ubers.observador.domain.TakenFilter
import com.ubers.observador.domain.earnings.EarningsFormat
import com.ubers.observador.domain.reports.ButtonFilter
import com.ubers.observador.domain.reports.ReportPeriod
import com.ubers.observador.domain.reports.PeriodTotals
import com.ubers.observador.domain.reports.PeriodTotalsText
import com.ubers.observador.ui.components.InfoLine
import com.ubers.observador.ui.components.ReportExportEffects
import com.ubers.observador.ui.components.SectionCard
import com.ubers.observador.ui.components.StatRow
import com.ubers.observador.ui.components.StatTile
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * «Reportes y exportación» (Fase 8J): one place to export a period, with filters, the totals
 * before exporting, and the last exports. Opened from Ajustes, and from Inicio, Ganancias and
 * Gastos with the period they show.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen(onBack: () -> Unit, viewModel: ReportsViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var picking by remember { mutableStateOf(false) }
    var allTotals by remember { mutableStateOf(false) }
    ReportExportEffects(viewModel.exporter, snackbar)

    if (picking) RangeDialog(state.customFrom, state.customTo, onDismiss = { picking = false }) { from, to ->
        picking = false
        viewModel.setCustomRange(from, to)
    }

    Scaffold(

        containerColor = com.ubers.observador.ui.theme.screenBackground(),
        topBar = {
            TopAppBar(
                colors = com.ubers.observador.ui.theme.topBarColors(),
                title = { Text("Reportes y exportación") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Volver") }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                SectionCard(title = "Periodo", icon = R.drawable.ic_calendar_month, subtitle = state.periodLabel.ifEmpty { null }) {
                    Chips {
                        ReportPeriod.entries.forEach { p ->
                            com.ubers.observador.ui.components.AppFilterChip(
                                selected = p == state.period,
                                onClick = {
                                    viewModel.selectPeriod(p)
                                    if (p == ReportPeriod.CUSTOM) picking = true
                                },
                                label = { Text(p.label) }
                            )
                        }
                    }
                    if (state.period == ReportPeriod.CUSTOM) {
                        TextButton(onClick = { picking = true }) { Text(if (state.window == null) "Elegir fechas" else "Cambiar fechas") }
                    }
                    Text(
                        "Cada jornada empieza a tu hora de inicio, y la semana en el día que elegiste en Ajustes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (state.failed) item { InfoLine(R.drawable.ic_info, "No se pudieron leer los datos del periodo. Prueba de nuevo.", tint = MaterialTheme.colorScheme.error) }
            state.totals?.let { t -> item { TotalsCard(t, allTotals, onToggle = { allTotals = !allTotals }) } }
            item { FiltersCard(state, viewModel) }
            item { FilesCard(state, viewModel) }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { viewModel.save() }, enabled = state.ready.isNotEmpty(), modifier = Modifier.weight(1f)) {
                        Icon(painterResource(R.drawable.ic_download), contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Guardar en el teléfono", Modifier.padding(start = 6.dp))
                    }
                    OutlinedButton(onClick = { viewModel.share() }, enabled = state.ready.isNotEmpty(), modifier = Modifier.weight(1f)) {
                        Icon(painterResource(R.drawable.ic_share), contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Compartir", Modifier.padding(start = 6.dp))
                    }
                }
            }
            item {
                Text(
                    // «Recorridos» is two files: GPX and GeoJSON
                    when (val files = state.ready.size + if (ReportKind.ROUTES in state.ready) 1 else 0) {
                        0 -> "Marca al menos un archivo con datos."
                        1 -> "Se exporta 1 archivo."
                        else -> "Se exportan $files archivos juntos en un .zip."
                    } + " Importes exactos con su moneda; coordenadas solo en «Recorridos». El ofertado es referencia, nunca ingreso. " +
                        "El PDF lleva la nota de que no es un documento oficial de Uber.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (state.history.isNotEmpty()) item { HistoryCard(state) }
        }
    }
}

@Composable
private fun Chips(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun TotalsCard(t: PeriodTotals, all: Boolean, onToggle: () -> Unit) {
    val c = t.currencyCode
    SectionCard(title = "Totales con los filtros", icon = R.drawable.ic_summarize, subtitle = "Lo que llevarán los archivos de ofertas y de totales") {
        StatRow { m ->
            StatTile(R.drawable.ic_leaderboard, "Ofertas vistas", t.count.all.toString(), m, detail = "${t.count.offered} sin tomar")
            StatTile(R.drawable.ic_check_circle, "Tomados", t.count.taken.toString(), m, detail = t.takenPercent?.let { "$it % de lo visto" })
        }
        StatRow { m ->
            StatTile(R.drawable.ic_payments, "Real confirmado", EarningsFormat.money(t.realMinor, c), m, detail = "${t.confirmedTrips} de ${t.doneTrips} viajes")
            StatTile(R.drawable.ic_bookmarks, "Ofertado", EarningsFormat.money(t.offeredRefMinor, c), m, detail = "referencia, no ingreso")
        }
        TextButton(onClick = onToggle) { Text(if (all) "Ocultar el detalle de los totales" else "Ver todos los totales") }
        if (all) {
            PeriodTotalsText.sections(t).forEach { s ->
                Text(s.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
                s.lines.forEach { (k, v) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(k, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        Text(v, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
            }
        }
    }
}

@Composable
private fun FiltersCard(state: ReportsUiState, vm: ReportsViewModel) {
    val f = state.filters
    val n = state.filterCounts
    val clear: (@Composable () -> Unit)? = if (f.isDefault) null else {
        { TextButton(onClick = { vm.clearFilters() }) { Text("Quitar") } }
    }
    SectionCard(
        title = "Filtros",
        icon = R.drawable.ic_filter_alt,
        subtitle = "Se aplican a ofertas, totales, viajes y la página de totales del PDF. Gastos y jornadas son del periodo completo.",
        action = clear
    ) {
        Label("Origen")
        Chips {
            TakenFilter.entries.forEach { o ->
                com.ubers.observador.ui.components.AppFilterChip(selected = f.origin == o, onClick = { vm.setOrigin(o) }, label = { Text("${o.label} ${n.origin[o] ?: 0}") })
            }
        }
        Label("Categoría")
        Chips {
            CategoryFilter.entries.forEach { q ->
                com.ubers.observador.ui.components.AppFilterChip(selected = f.quality == q, onClick = { vm.setQuality(q) }, label = { Text("${q.label} ${n.quality[q] ?: 0}") })
            }
        }
        Label("Botón de la tarjeta")
        Chips {
            ButtonFilter.entries.forEach { b ->
                com.ubers.observador.ui.components.AppFilterChip(selected = f.button == b, onClick = { vm.setButton(b) }, label = { Text("${b.label} ${n.button[b] ?: 0}") })
            }
        }
        Label("Modalidad")
        Chips {
            com.ubers.observador.ui.components.AppFilterChip(selected = f.product == null, onClick = { vm.setProduct(null) }, label = { Text("Todas ${n.products.sumOf { it.second }}") })
            n.products.forEach { (p, count) ->
                com.ubers.observador.ui.components.AppFilterChip(selected = f.product == p, onClick = { vm.setProduct(p) }, label = { Text("$p $count") })
            }
        }
    }
}

@Composable
private fun FilesCard(state: ReportsUiState, vm: ReportsViewModel) {
    SectionCard(title = "Qué exportar", icon = R.drawable.ic_download) {
        ReportKind.entries.forEach { kind ->
            val count = state.counts[kind] ?: 0
            val enabled = count > 0
            Row(
                Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Checkbox) { vm.toggle(kind) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = kind in state.selected && enabled, onCheckedChange = { vm.toggle(kind) }, enabled = enabled)
                Column(Modifier.weight(1f)) {
                    Text(kind.label, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        when {
                            !enabled -> "Sin datos en este periodo"
                            kind == ReportKind.TOTALS || kind == ReportKind.SUMMARY_PDF -> "Listo"
                            kind == ReportKind.DAILY -> "$count jornadas"
                            kind == ReportKind.ROUTES -> "$count viajes con recorrido · llevan ubicaciones y el nombre del pasajero"
                            else -> "$count filas"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Label("Formato (ofertas, totales, viajes y gastos)")
        Chips {
            ReportFormat.entries.forEach { fmt ->
                com.ubers.observador.ui.components.AppFilterChip(selected = state.format == fmt, onClick = { vm.setFormat(fmt) }, label = { Text(fmt.label) })
            }
        }
        if (BuildConfig.DEBUG) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Incluir datos de prueba (TEST-)", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(checked = state.includeTest, onCheckedChange = { vm.setIncludeTest(it) })
            }
        }
    }
}

private val WHEN = DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.forLanguageTag("es-DO"))

@Composable
private fun HistoryCard(state: ReportsUiState) {
    SectionCard(title = "Últimas exportaciones", icon = R.drawable.ic_history) {
        state.history.forEach { r ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    Instant.ofEpochMilli(r.atMs).atZone(ZoneId.systemDefault()).format(WHEN).replace(".", "") + " · " + r.period,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text("${r.filters} · ${r.files}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** The Material date range picker; its dates are UTC midnights, read back as plain dates. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RangeDialog(from: LocalDate?, to: LocalDate?, onDismiss: () -> Unit, onPick: (LocalDate, LocalDate) -> Unit) {
    fun ms(d: LocalDate?) = d?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
    val picker = rememberDateRangePickerState(initialSelectedStartDateMillis = ms(from), initialSelectedEndDateMillis = ms(to))
    fun date(m: Long) = Instant.ofEpochMilli(m).atZone(ZoneOffset.UTC).toLocalDate()
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            val start = picker.selectedStartDateMillis
            TextButton(
                enabled = start != null,
                onClick = { start?.let { s -> onPick(date(s), date(picker.selectedEndDateMillis ?: s)) } }
            ) { Text("Listo") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    ) {
        DateRangePicker(state = picker, modifier = Modifier.weight(1f))
    }
}
