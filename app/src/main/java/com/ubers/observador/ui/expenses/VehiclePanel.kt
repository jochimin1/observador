package com.ubers.observador.ui.expenses

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ubers.observador.R
import com.ubers.observador.domain.WorkdayClock
import com.ubers.observador.domain.expenses.*
import com.ubers.observador.domain.operating.*
import com.ubers.observador.ui.components.SectionCard
import com.ubers.observador.ui.earnings.OperatingModelDialog
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId

private fun date(at: Long) = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate().toString()
private fun cash(minor: Long) = "RD$" + BigDecimal.valueOf(minor, 2).toPlainString()

@Composable
fun VehiclePanel(state: ExpensesUiState, onVehicle: suspend (String, Int, Boolean) -> String?,
    onPlan: suspend (ComponentPlan) -> String?, onModel: suspend (OperatingCostInput) -> String?) {
    var edit by rememberSaveable { mutableStateOf(false) }
    var replace by rememberSaveable { mutableStateOf(false) }
    var component by remember { mutableStateOf<ComponentPlan?>(null) }
    var costs by rememberSaveable { mutableStateOf(false) }
    val active = state.care.active
    val efficiency = state.overview.efficiency
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionCard(title = "Mi moto", icon = R.drawable.ic_two_wheeler, subtitle = active.name) {
            Text("Uso laboral de referencia: ${active.workSharePct} % · personal: ${100 - active.workSharePct} %.")
            Text(state.overview.currentOdometer?.let { "Último odómetro: ${ExpenseFormat.km(it.km)} km · ${date(it.atMs)}" }
                ?: "Sin lectura de odómetro para esta moto.", style = MaterialTheme.typography.bodySmall)
            Text("Esta ficha separa consumo y componentes por moto. El resumen mensual de gastos reúne todas las motos; los porcentajes de Ajustes y los modelos de costos conservan su configuración explícita.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { edit = true; replace = false }) { Text("Editar ficha") }
            TextButton(onClick = { edit = true; replace = true }) { Text("Cambiar de moto") }
            state.care.vehicles.filter { it.retiredAt != null }.forEach {
                Text("Historial conservado: ${it.name} · hasta ${date(it.retiredAt!!)}. Sus registros siguen en la lista inferior.", style = MaterialTheme.typography.bodySmall)
            }
        }
        SectionCard(title = "Componentes y próximos cambios", icon = R.drawable.ic_notifications_active,
            subtitle = "Se avisa por el primer límite alcanzado: km o fecha") {
            state.components.forEach { status ->
                ComponentRow(status, state.plans.first { it.type == status.plan.type }) { component = it }
                HorizontalDivider()
            }
            Text("Los km del GPS posteriores al último odómetro son un mínimo observado: pueden faltar recorridos personales o sin señal. Registra otra lectura para precisar cuánto falta.", style = MaterialTheme.typography.bodySmall)
        }
        if (efficiency.sample >= FuelRules.SUGGEST_FROM) {
            SectionCard(title = "Datos para tu modelo de costos", icon = R.drawable.ic_lightbulb,
                subtitle = "${efficiency.sample} intervalos completos · ${efficiency.confidence}") {
                Text("Rendimiento: ${efficiency.medianKmPerGallonTenths?.let(ExpenseFormat::tenths)} km/galón. Precio observado: ${efficiency.medianPricePerGallonMinor?.let(::cash)}/galón.")
                Text("Se usa la mediana de hasta cinco intervalos válidos. Es evidencia histórica, no una garantía del próximo consumo. Puedes revisar estos valores y crear una versión de costos con vigencia explícita.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { costs = true }) { Text("Revisar para modelo de costos") }
            }
        }
    }
    if (edit) VehicleDialog(active, replace, onVehicle) { edit = false }
    component?.let { plan -> ComponentDialog(plan, onPlan) { component = null } }
    if (costs) OperatingModelDialog(state.models, state.clock, onModel, { costs = false },
        suggestedFuel = efficiency.medianPricePerGallonMinor to efficiency.medianKmPerGallonTenths?.times(100),
        suggestedShare = active.workSharePct)
}

@Composable
private fun ComponentRow(status: ComponentStatus, configured: ComponentPlan, onEdit: (ComponentPlan) -> Unit) {
    var history by rememberSaveable(status.plan.vehicleId, status.plan.type.storageKey) { mutableStateOf(false) }
    val enabled = status.plan.intervalKm > 0 || status.plan.intervalDays > 0
    val label = if (!enabled) "Sin intervalo" else when (status.level) {
        ReminderLevel.NO_DATA -> "Falta registrar el cambio"
        ReminderLevel.OK -> "Dentro del intervalo"
        ReminderLevel.SOON -> "Próximo cambio"
        ReminderLevel.OVERDUE -> "Cambio pendiente"
    }
    Text("${status.plan.type.label} · $label", style = MaterialTheme.typography.titleSmall,
        color = if (enabled && status.level == ReminderLevel.OVERDUE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    if (status.plan.intervalKm > 0) Text(status.remainingKm?.let { km ->
        if (km <= 0) "Límite alcanzado${if (km < 0) ": ${ExpenseFormat.km(-km)} km de exceso conocido" else ""}"
        else "Faltan ${ExpenseFormat.km(km)} km${if (status.estimatedKm) " como máximo según lo observado" else " según el último odómetro"}"
    } ?: status.kmProblem ?: "Sin kilometraje de referencia", style = MaterialTheme.typography.bodySmall)
    status.remainingDays?.let { Text(if (it == 0L) "Vence hoy" else if (it < 0) "Fecha alcanzada: ${-it} días de exceso" else "Faltan $it días", style = MaterialTheme.typography.bodySmall) }
    Text("Intervalo: ${status.plan.intervalKm.takeIf { it > 0 }?.let { "$it km" } ?: "sin límite de km"} · ${status.plan.intervalDays.takeIf { it > 0 }?.let { "$it días" } ?: "sin límite de días"}.", style = MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick = { onEdit(configured) }) { Text("Configurar ${status.plan.type.label.lowercase()}") }
    if (status.history.isNotEmpty()) {
        TextButton(onClick = { history = !history }) { Text(if (history) "Ocultar historial" else "Ver ${status.history.size} ${if (status.history.size == 1) "cambio" else "cambios"}") }
        if (history) status.history.forEach { entry ->
            Text("${date(entry.atMs)} · ${cash(entry.amountMinor)} · ${entry.odometerKm?.let { "$it km" } ?: "sin odómetro"}" +
                (entry.expectedLifeKm?.let { " · vida prevista $it km" } ?: "") +
                (entry.expectedLifeDays?.let { " · plazo $it días" } ?: ""), style = MaterialTheme.typography.bodySmall)
        }
    } else Text("Registra el cambio desde + → Gasto.", style = MaterialTheme.typography.bodySmall)
}

@Composable
internal fun VehicleDialog(vehicle: VehicleProfile, replace: Boolean,
    onSave: suspend (String, Int, Boolean) -> String?, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(if (replace) "" else vehicle.name) }
    var share by rememberSaveable { mutableStateOf(vehicle.workSharePct.toString()) }
    CareDialog(if (replace) "Cambiar de moto" else "Ficha de moto", onDismiss,
        save = { onSave(name, share.toIntOrNull() ?: -1, replace) }) {
        if (replace) Text("La nueva moto empieza ahora. Se conserva el historial anterior y no se trasladan odómetro, rendimiento ni intervalos. Cierra cualquier viaje activo antes de continuar.")
        OutlinedTextField(name, { name = it }, label = { Text("Nombre o modelo de la moto") }, singleLine = true)
        OutlinedTextField(share, { share = it }, label = { Text("Uso laboral de referencia (%)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        Text("Este porcentaje sirve de referencia al revisar un modelo nuevo; guardar la ficha no modifica costos históricos.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun ComponentDialog(plan: ComponentPlan, onSave: suspend (ComponentPlan) -> String?, onDismiss: () -> Unit) {
    var km by rememberSaveable { mutableStateOf(plan.intervalKm.toString()) }
    var days by rememberSaveable { mutableStateOf(plan.intervalDays.toString()) }
    CareDialog("Intervalo: ${plan.type.label}", onDismiss, save = {
        onSave(plan.copy(intervalKm = km.toLongOrNull() ?: -1, intervalDays = days.toIntOrNull() ?: -1))
    }) {
        OutlinedTextField(km, { km = it }, label = { Text("Cada cuántos km (0 desactiva)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        OutlinedTextField(days, { days = it }, label = { Text("Cada cuántos días (0 desactiva)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        Text("Sigue el intervalo indicado para tu moto y componente. Se aplica cuando el cambio no tiene una vida esperada propia; los valores escritos en ese cambio se conservan.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun CareDialog(title: String, onDismiss: () -> Unit, save: suspend () -> String?, fields: @Composable ColumnScope.() -> Unit) {
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(title) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            fields()
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(enabled = !busy, onClick = { scope.launch {
            busy = true
            try { error = save(); if (error == null) onDismiss() }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { error = "No se pudo guardar. Inténtalo otra vez." }
            finally { busy = false }
        } }) { Text("Guardar") } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancelar") } })
}
