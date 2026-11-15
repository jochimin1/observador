package com.ubers.observador.ui.reports

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ubers.observador.data.ClosureRepository
import com.ubers.observador.domain.earnings.EarningsWindow
import com.ubers.observador.domain.reports.ClosureText
import com.ubers.observador.ui.components.ReportExportEffects
import java.time.Instant
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClosureScreen(window: EarningsWindow?, includeTest: Boolean, onBack: () -> Unit,
    onTrip: (String) -> Unit, onMoney: () -> Unit, vm: ClosureViewModel = viewModel()) {
    val history by vm.history.collectAsState()
    val selected by vm.selected.collectAsState()
    val busy by vm.busy.collectAsState()
    val error by vm.error.collectAsState()
    var profit by rememberSaveable { mutableStateOf("") }
    var hours by rememberSaveable { mutableStateOf("") }
    var detail by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val lines = remember(selected) { selected?.let(ClosureRepository::lines).orEmpty() }
    BackHandler(onBack = onBack)
    ReportExportEffects(vm.exporter, snackbar)
    Scaffold(topBar = { TopAppBar(title = { Text("Cierre de jornada") }, navigationIcon = {
        TextButton(onClick = onBack) { Text("Volver") }
    }) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("El cierre conserva una copia. Las correcciones posteriores se incorporan al crear una revisión; nunca sobrescriben las anteriores.")
                Text(window?.let { "Periodo seleccionado: ${it.firstDay} a ${it.lastDay}. Se guarda hasta el momento del cierre o el fin del periodo." }
                    ?: "Selecciona un periodo en Reportes para crear un cierre. Puedes consultar los anteriores.")
                Text(if (includeTest) "Incluye datos TEST." else "Excluye datos TEST.")
            }
            item {
                OutlinedTextField(profit, { profit = it }, label = { Text("Meta de beneficio (RD$, opcional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(hours, { hours = it }, label = { Text("Máximo de horas en línea (opcional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Text("Estas metas pertenecen al cierre y sus revisiones. No cambian tus metas de ingresos.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { window?.let { vm.close(it, includeTest, profit, hours) } }, enabled = window != null && !busy) {
                    Text(if (busy) "Guardando…" else "Guardar nuevo cierre")
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            selected?.let { entry ->
                item {
                    Text("Revisión ${entry.revision} · ${Instant.ofEpochMilli(entry.createdAt).atZone(ZoneId.of(entry.zone))}", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { vm.save(entry) }) { Text("Guardar CSV, JSON y PDF del mismo cierre") }
                    TextButton(onClick = { vm.close(window, entry.includeTest, "", "", entry) }, enabled = !busy) { Text("Crear revisión con las correcciones actuales") }
                    Text("La revisión conserva el intervalo y las metas del cierre seleccionado.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { detail = !detail }) { Text(if (detail) "Ocultar detalle" else "Ver todos los detalles y referencias") }
                }
                items(lines.filter { detail || it.section in setOf("Resumen", "Periodo", "Metas", "Incidencias", "Pendientes") }) { line ->
                    Column {
                        Text("${line.section} · ${line.label}", style = MaterialTheme.typography.labelLarge)
                        Text(ClosureText.display(line))
                        if (line.reference.isNotEmpty()) {
                            Text(line.reference, style = MaterialTheme.typography.bodySmall)
                            if (line.section in setOf("Viajes", "Costos por viaje", "Incidencias"))
                                TextButton(onClick = { onTrip(line.reference) }) { Text("Abrir viaje") }
                        }
                        if (line.section == "Pendientes") TextButton(onClick = onMoney) { Text("Revisar en Ganancias") }
                        HorizontalDivider()
                    }
                }
            }
            item { Text("Cierres conservados (${history.size})", style = MaterialTheme.typography.titleMedium) }
            if (history.isEmpty()) item { Text("Todavía no has guardado un cierre.") }
            items(history, key = { it.id }) { entry ->
                OutlinedButton(onClick = { vm.selected.value = entry }, modifier = Modifier.fillMaxWidth()) {
                    Text("${Instant.ofEpochMilli(entry.startMs).atZone(ZoneId.of(entry.zone)).toLocalDate()} · revisión ${entry.revision}\nGuardado ${Instant.ofEpochMilli(entry.createdAt).atZone(ZoneId.of(entry.zone))}")
                }
            }
        }
    }
}
