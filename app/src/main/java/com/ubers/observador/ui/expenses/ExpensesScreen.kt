package com.ubers.observador.ui.expenses

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ubers.observador.R
import com.ubers.observador.data.DeletedExpense
import com.ubers.observador.domain.expenses.ExpenseFormat
import com.ubers.observador.domain.expenses.ExpenseGroup
import com.ubers.observador.domain.expenses.ExpenseSummary
import com.ubers.observador.domain.expenses.FuelEfficiency
import com.ubers.observador.domain.expenses.DistanceSource
import com.ubers.observador.domain.expenses.NetResult
import com.ubers.observador.domain.expenses.RecurringCost
import com.ubers.observador.domain.expenses.SettingsSuggestion
import com.ubers.observador.domain.money.MoneyAmount
import com.ubers.observador.domain.money.MoneyFormat
import com.ubers.observador.ui.components.InfoLine
import com.ubers.observador.ui.components.LegendDot
import com.ubers.observador.ui.components.SectionCard
import com.ubers.observador.ui.components.SplitBar
import com.ubers.observador.ui.components.StackedBar
import com.ubers.observador.ui.components.StatTile
import com.ubers.observador.ui.components.SwipeToDelete
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.forLanguageTag("es"))
private val MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.forLanguageTag("es"))

private fun rd(minor: Long) = MoneyFormat.format(MoneyAmount(minor, "DOP"))
private fun day(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DAY)

/** What the "+" opens, or an entry being edited. */
private sealed interface Form {
    data class Fuel(val item: ExpenseItem.Fuel?) : Form
    data class Expense(val item: ExpenseItem.Expense?) : Form
    data class Recurring(val item: RecurringCost?) : Form
    data class Odometer(val item: ExpenseItem.Odometer?) : Form
}

/**
 * Gastos (Fase 8B): the month's expenses and net, real km per gallon and cost per km, the
 * maintenance reminders, fixed costs and every entry (tap to edit, swipe to delete with undo).
 * The "+" opens the four forms; the fill-up form comes first.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ExpensesScreen(
    onOpenReports: ((thisMonth: Boolean) -> Unit)? = null,
    viewModel: ExpensesViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var form by remember { mutableStateOf<Form?>(null) }
    var menu by remember { mutableStateOf(false) }
    var confirmSuggestion by remember { mutableStateOf<SettingsSuggestion?>(null) }
    var exporting by remember { mutableStateOf(false) }
    com.ubers.observador.ui.components.ReportExportEffects(viewModel.exporter, snackbar)
    if (exporting) {
        com.ubers.observador.ui.components.ReportExportDialog(
            periodLabel = "Gastos de ${state.from?.format(MONTH) ?: state.period.label.lowercase()}",
            files = listOf(com.ubers.observador.data.export.ReportFile.EXPENSES_CSV, com.ubers.observador.data.export.ReportFile.EXPENSES_JSON),
            onSave = { viewModel.saveFile(it); exporting = false },
            onShare = { viewModel.shareFile(it); exporting = false },
            onDismiss = { exporting = false }
        )
    }
    val timeline = state.overview.timeline

    LaunchedEffect(Unit) {
        viewModel.deletedEvents.collect { deleted ->
            scope.launch {
                snackbar.currentSnackbarData?.dismiss()
                val r = snackbar.showSnackbar("Registro borrado", actionLabel = "Deshacer", duration = SnackbarDuration.Long)
                if (r == SnackbarResult.ActionPerformed) viewModel.undo(deleted)
            }
        }
    }

    LaunchedEffect(Unit) { viewModel.errors.collect { snackbar.showSnackbar(it) } }
    val activeVehicle = state.care.active.id
    fun timelineFor(id: String?) = id?.let { state.entryTimelines[it] } ?: timeline
    when (val f = form) {
        null -> Unit
        is Form.Fuel -> FuelDialog(f.item?.entry, timelineFor(f.item?.entry?.vehicleId), { form = null }) { e, reset -> viewModel.saveFuel(if (f.item == null) e.copy(vehicleId = activeVehicle) else e, reset); form = null }
        is Form.Expense -> ExpenseDialog(f.item?.entry, timelineFor(f.item?.entry?.vehicleId), { form = null }) { e, reset -> viewModel.saveExpense(if (f.item == null) e.copy(vehicleId = activeVehicle) else e, reset); form = null }
        is Form.Recurring -> RecurringDialog(f.item, { form = null }) { viewModel.saveRecurring(it); form = null }
        is Form.Odometer -> OdometerDialog(f.item?.entry, timelineFor(f.item?.entry?.vehicleId), { form = null }) { viewModel.saveOdometer(if (f.item == null) it.copy(vehicleId = activeVehicle) else it); form = null }
    }

    confirmSuggestion?.let { s ->
        AlertDialog(
            onDismissRequest = { confirmSuggestion = null },
            title = { Text("¿Usar tus valores reales en Ajustes?") },
            text = {
                Text(
                    "Rendimiento ${ExpenseFormat.tenths(s.kmPerGallonTenths)} km/galón y precio ${rd(s.pricePerGallonMinor)} por galón. " +
                        "Solo cambia el costo de combustible que se resta a cada oferta; el score no cambia."
                )
            },
            confirmButton = { TextButton(onClick = { viewModel.applySuggestion(s); confirmSuggestion = null }) { Text("Usar") } },
            dismissButton = { TextButton(onClick = { confirmSuggestion = null }) { Text("Cancelar") } }
        )
    }

    Scaffold(

        containerColor = com.ubers.observador.ui.theme.screenBackground(),
        topBar = {
            TopAppBar(
                colors = com.ubers.observador.ui.theme.topBarColors(),
                title = { Text("Gastos") },
                actions = {
                    IconButton(onClick = {
                        if (onOpenReports != null) onOpenReports(state.period == ExpensePeriod.THIS_MONTH) else exporting = true
                    }) {
                        Icon(painterResource(R.drawable.ic_download), contentDescription = "Exportar gastos del mes")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            Box {
                FloatingActionButton(onClick = { menu = true }) {
                    Icon(painterResource(R.drawable.ic_add), contentDescription = "Añadir")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    listOf(
                        Triple("Tanqueo", R.drawable.ic_local_gas_station, Form.Fuel(null)),
                        Triple("Gasto", R.drawable.ic_payments, Form.Expense(null)),
                        Triple("Gasto fijo", R.drawable.ic_calendar_month, Form.Recurring(null)),
                        Triple("Odómetro", R.drawable.ic_speed, Form.Odometer(null))
                    ).forEach { (label, icon, target) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            leadingIcon = { Icon(painterResource(icon), contentDescription = null) },
                            onClick = { menu = false; form = target }
                        )
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExpensePeriod.entries.forEach { p ->
                        com.ubers.observador.ui.components.AppFilterChip(selected = state.period == p, onClick = { viewModel.selectPeriod(p) }, label = { Text(p.label) })
                    }
                }
            }
            if (!state.loading) item { VehiclePanel(state, viewModel::saveVehicle, viewModel::savePlan, viewModel::saveModel) }
            state.summary?.let { s -> item { SummaryCard(s, state.net, state.from?.format(MONTH) ?: "") } }
            item { EfficiencyCard(state.overview.efficiency, state.overview.suggestion) { confirmSuggestion = it } }
            item { RecurringCard(state.recurring, onEdit = { form = Form.Recurring(it) }, onDelete = { viewModel.delete(DeletedExpense.Recurring(it)) }) }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Registros", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ExpenseFilter.entries.forEach { f ->
                            com.ubers.observador.ui.components.AppFilterChip(selected = state.filter == f, onClick = { viewModel.selectFilter(f) }, label = { Text(f.label) })
                        }
                    }
                }
            }
            if (!state.loading && state.items.isEmpty()) {
                item {
                    Text(
                        "Aún no hay registros. Toca «+» y empieza por tu próximo tanqueo: galones, total y odómetro.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(state.items, key = { it.id }) { item ->
                SwipeToDelete(onDelete = {
                    viewModel.delete(
                        when (item) {
                            is ExpenseItem.Fuel -> DeletedExpense.Fuel(item.entry)
                            is ExpenseItem.Expense -> DeletedExpense.Expense(item.entry)
                            is ExpenseItem.Odometer -> DeletedExpense.Odometer(item.entry)
                        }
                    )
                }) {
                    Column {
                        val vehicleId = when (item) {
                            is ExpenseItem.Fuel -> item.entry.vehicleId
                            is ExpenseItem.Expense -> item.entry.vehicleId
                            is ExpenseItem.Odometer -> item.entry.vehicleId
                        }
                        Text(state.care.vehicles.firstOrNull { it.id == vehicleId }?.name ?: "Mi moto", style = MaterialTheme.typography.labelSmall)
                        EntryRow(item) {
                            form = when (item) {
                                is ExpenseItem.Fuel -> Form.Fuel(item)
                                is ExpenseItem.Expense -> Form.Expense(item)
                                is ExpenseItem.Odometer -> Form.Odometer(item)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun groupColor(g: ExpenseGroup): Color = when (g) {
    ExpenseGroup.FUEL -> MaterialTheme.colorScheme.primary
    ExpenseGroup.MAINTENANCE -> MaterialTheme.colorScheme.tertiary
    ExpenseGroup.FIXED -> MaterialTheme.colorScheme.secondary
    ExpenseGroup.LOOSE -> MaterialTheme.colorScheme.outline
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SummaryCard(s: ExpenseSummary, net: NetResult?, month: String) {
    SectionCard(
        title = "Resumen del mes",
        icon = R.drawable.ic_receipt_long,
        subtitle = month.replaceFirstChar { it.uppercase() }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.semantics(mergeDescendants = true) {}) {
                Text(rd(s.totalMinor), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("gastado en total", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val groups = ExpenseGroup.entries
            val colors = groups.associateWith { groupColor(it) }
            StackedBar(groups.map { s.of(it) to colors.getValue(it) }, groups.joinToString(", ") { "${it.label} ${rd(s.of(it))}" })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                groups.forEach { g -> LegendDot(colors.getValue(g), "${g.label} ${rd(s.of(g))}") }
            }
            if (s.workSharePct < 100) {
                InfoLine(R.drawable.ic_two_wheeler, "Parte de trabajo ${s.workSharePct} %: ${rd(s.workMinor)} de ${rd(s.totalMinor)}.")
            }
            HorizontalDivider()
            if (net == null) {
                InfoLine(R.drawable.ic_info, "Sin ingreso real confirmado este mes: todavía no hay ganancia neta.")
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile(R.drawable.ic_payments, "Ingreso real", rd(net.incomeMinor), Modifier.weight(1f),
                        detail = "${net.confirmedTrips} de ${net.completedTrips} viajes con dinero")
                    StatTile(
                        if (net.netMinor >= 0) R.drawable.ic_trending_up else R.drawable.ic_trending_down,
                        "Ganancia neta", rd(net.netMinor), Modifier.weight(1f),
                        detail = if (net.partial) "Parcial: faltan viajes con dinero" else "ingreso − gastos de trabajo",
                        accent = if (net.netMinor >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        detailColor = if (net.partial) MaterialTheme.colorScheme.error else Color.Unspecified
                    )
                }
            }
        }
    }
}

@Composable
private fun EfficiencyCard(e: FuelEfficiency, suggestion: SettingsSuggestion?, onUse: (SettingsSuggestion) -> Unit) {
    SectionCard(
        title = "Rendimiento real",
        icon = R.drawable.ic_local_gas_station,
        subtitle = "Entre tanques llenos",
        badgeContainer = MaterialTheme.colorScheme.tertiaryContainer,
        badgeContent = MaterialTheme.colorScheme.onTertiaryContainer
    ) {
        e.excludedReasons.forEach { Text("Intervalo descartado: $it", style = MaterialTheme.typography.bodySmall) }
        Text("${e.sample} intervalos usados · ${e.confidence}", style = MaterialTheme.typography.bodySmall)
        val last = e.last
        if (last == null) {
            Text(
                "Hacen falta dos tanqueos con «tanque lleno» y odómetro, o GPS verificable del intervalo completo, incluido el uso personal.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile(R.drawable.ic_speed, "km por galón", e.medianKmPerGallonTenths?.let(ExpenseFormat::tenths) ?: "—", Modifier.weight(1f),
                        detail = "mediana de ${e.sample} · último ${ExpenseFormat.tenths(last.kmPerGallonTenths)}")
                    StatTile(R.drawable.ic_route, "Combustible por km", e.medianCostPerKmMinor?.let(::rd) ?: "—", Modifier.weight(1f),
                        detail = "último ${rd(last.costPerKmMinor)}", accent = MaterialTheme.colorScheme.tertiary)
                }
                Text("Último intervalo válido: ${day(last.fromAt)} a ${day(last.toAt)}", style = MaterialTheme.typography.bodySmall)
                if (e.stretches.any { it.source == DistanceSource.GPS }) {
                    InfoLine(R.drawable.ic_route, "Algunos intervalos usan GPS con cobertura verificada de al menos el 95 % del tiempo, sin huecos de señal registrados. El rendimiento sigue siendo una estimación.")
                }
                suggestion?.let { s ->
                    InfoLine(
                        R.drawable.ic_lightbulb,
                        "Tu rendimiento real es ${ExpenseFormat.tenths(s.kmPerGallonTenths)} km/galón y pagaste ${rd(s.pricePerGallonMinor)} " +
                            "por galón (${s.stretches} tramos); Ajustes usa otros valores."
                    )
                    FilledTonalButton(onClick = { onUse(s) }) { Text("Usar en Ajustes") }
                }
            }
        }
    }
}

@Composable
private fun RecurringCard(items: List<RecurringCost>, onEdit: (RecurringCost) -> Unit, onDelete: (RecurringCost) -> Unit) {
    SectionCard(title = "Gastos fijos", icon = R.drawable.ic_calendar_month, subtitle = "Se reparten por día") {
        if (items.isEmpty()) {
            Text("Ninguno. Cuota del motor, plan de datos o seguro van aquí.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Column {
                items.forEach { c ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable(onClickLabel = "Editar") { onEdit(c) }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(c.type.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${c.frequency.label} · desde ${c.startsOn}" + (c.endsOn?.let { " hasta $it" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(rd(c.amountMinor), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        IconButton(onClick = { onDelete(c) }) {
                            Icon(painterResource(R.drawable.ic_delete), contentDescription = "Borrar ${c.type.label}", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EntryRow(item: ExpenseItem, onClick: () -> Unit) {
    val (icon, title, detail, amount) = when (item) {
        is ExpenseItem.Fuel -> Quad(
            R.drawable.ic_local_gas_station,
            "Tanqueo" + if (item.entry.fullTank) " · lleno" else "",
            "${ExpenseFormat.gallons(item.entry.gallonsMilli)} gal · ${rd(item.entry.pricePerGallonMinor)}/gal" +
                (item.entry.odometerKm?.let { " · ${ExpenseFormat.km(it)} km" } ?: ""),
            rd(item.entry.amountMinor)
        )
        is ExpenseItem.Expense -> Quad(
            if (item.entry.type.group == ExpenseGroup.MAINTENANCE) R.drawable.ic_tune else R.drawable.ic_payments,
            item.entry.type.label,
            item.entry.odometerKm?.let { "${ExpenseFormat.km(it)} km" } ?: item.entry.type.group.label,
            rd(item.entry.amountMinor)
        )
        is ExpenseItem.Odometer -> Quad(
            R.drawable.ic_speed,
            if (item.entry.isReset) "Odómetro nuevo" else "Odómetro",
            if (item.entry.isReset) "Empieza una cuenta nueva" else "Lectura",
            "${ExpenseFormat.km(item.entry.km)} km"
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .clickable(onClickLabel = "Editar", onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text("${day(item.atMs)} · $detail", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(amount, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
    }
}

private data class Quad(val icon: Int, val title: String, val detail: String, val amount: String)
