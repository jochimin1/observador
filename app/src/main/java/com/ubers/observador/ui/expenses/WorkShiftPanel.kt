package com.ubers.observador.ui.expenses

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ubers.observador.R
import com.ubers.observador.domain.expenses.*
import com.ubers.observador.ui.components.SectionCard
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val WHEN = DateTimeFormatter.ofPattern("EEE d MMM · HH:mm", Locale.forLanguageTag("es"))
private val HOUR = DateTimeFormatter.ofPattern("HH:mm")
private fun shown(at: Long) = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(WHEN)
private fun hourOf(at: Long) = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(HOUR)

/** What a shift dialog is for. */
private sealed interface ShiftForm {
    data object Start : ShiftForm
    data class Finish(val shift: WorkShift) : ShiftForm
    data class EditStart(val shift: WorkShift) : ShiftForm
}

/**
 * 1.46.0: «Jornada» in Gastos → Mi moto. The odometer before connecting to Uber and after
 * finishing; the work share of the last 30 days is measured from them and only suggested.
 */
@Composable
fun WorkShiftPanel(state: ExpensesUiState, viewModel: ExpensesViewModel) {
    var form by remember { mutableStateOf<ShiftForm?>(null) }
    var history by rememberSaveable { mutableStateOf(false) }
    val open = state.openShift
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        state.shareSuggestion?.let { s -> ShareSuggestionCard(s, { viewModel.acceptShare(s) }, { viewModel.dismissShare(s) }) }

        SectionCard(title = "Jornada", icon = R.drawable.ic_schedule,
            subtitle = if (open == null) "Anota el odómetro antes de conectarte a Uber" else "En curso desde ${shown(open.start.atMs)}") {
            if (open == null) {
                Text("Al terminar la jornada anotas el odómetro final. Solo esos km cuentan como uso para Uber; lo recorrido entre jornadas es personal.",
                    style = MaterialTheme.typography.bodySmall)
                Button(onClick = { form = ShiftForm.Start }) { Text("Iniciar jornada") }
            } else {
                Text("Odómetro inicial: ${ExpenseFormat.km(open.start.km)} km.")
                state.openShiftGpsKm?.let {
                    Text("GPS desde el inicio: $it km aprox. Es solo referencia; el odómetro manda.", style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { form = ShiftForm.Finish(open) }) { Text("Terminar jornada") }
                    TextButton(onClick = { form = ShiftForm.EditStart(open) }) { Text("Corregir inicio") }
                }
            }
            val reminders by viewModel.shiftReminders.collectAsState()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Recordarme anotar el odómetro al conectarme y al desconectarme de Uber",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Switch(checked = reminders, onCheckedChange = { viewModel.setShiftReminders(it) })
            }
            HorizontalDivider()
            WorkShareSummary(state)
            if (state.shifts.isNotEmpty()) {
                TextButton(onClick = { history = !history }) {
                    Text(if (history) "Ocultar jornadas" else "Ver jornadas (${state.shifts.size})")
                }
                if (history) state.shifts.take(30).forEach { s ->
                    ShiftRow(s, onEditStart = { form = ShiftForm.EditStart(s) }, onEditEnd = { form = ShiftForm.Finish(s) },
                        onDelete = { viewModel.deleteShift(s.id) })
                    HorizontalDivider()
                }
            }
        }
    }

    when (val f = form) {
        null -> Unit
        ShiftForm.Start -> ShiftDialog("Iniciar jornada", state.lastOdometerKm, null, askReset = true,
            onDismiss = { form = null }) { km, at, reset, _ -> viewModel.startShift(km, at, reset)?.let { ShiftCheck.Rejected(it) } ?: ShiftCheck.Ok }
        is ShiftForm.EditStart -> ShiftDialog("Corregir inicio", f.shift.start.km, f.shift.start.atMs, askReset = true,
            initialReset = f.shift.start.isReset, onDismiss = { form = null }) { km, at, reset, _ ->
            viewModel.editShiftStart(f.shift.id, km, at, reset)?.let { ShiftCheck.Rejected(it) } ?: ShiftCheck.Ok }
        is ShiftForm.Finish -> ShiftDialog(if (f.shift.end == null) "Terminar jornada" else "Corregir final",
            f.shift.end?.km ?: f.shift.start.km, f.shift.end?.atMs, askReset = false,
            note = "Inicio: ${ExpenseFormat.km(f.shift.start.km)} km · ${shown(f.shift.start.atMs)}",
            onDismiss = { form = null }) { km, at, _, confirmed -> viewModel.finishShift(f.shift.id, km, at, confirmed) }
    }
}

@Composable
private fun WorkShareSummary(state: ExpensesUiState) {
    val m = state.workShare ?: return
    if (m.pct != null) {
        Text("Uso laboral medido (últimos ${WorkShiftRules.PERIOD_DAYS} días): ${m.pct} %", style = MaterialTheme.typography.titleSmall)
        Text("${m.closedShifts} jornadas · ${ExpenseFormat.km(m.uberKm)} km Uber · ${ExpenseFormat.km(m.personalKm)} km personales. " +
            "En uso ahora: ${state.manualShare} %.", style = MaterialTheme.typography.bodySmall)
    } else {
        Text("Uso laboral: ${state.manualShare} % (manual, no medido)", style = MaterialTheme.typography.titleSmall)
        Text(if (m.closedShifts == 0) "Registra jornadas para medirlo con el odómetro."
            else "Hacen falta al menos ${WorkShiftRules.MIN_SHIFTS} jornadas seguidas de la misma moto para medirlo (van ${m.closedShifts}" +
                (if (m.uberKm > 0) ", ${ExpenseFormat.km(m.uberKm)} km Uber" else "") + ").",
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ShareSuggestionCard(s: WorkShareSuggestion, onAccept: () -> Unit, onDismiss: () -> Unit) {
    SectionCard(title = "Uso laboral medido: ${s.measuredPct} %", icon = R.drawable.ic_lightbulb,
        subtitle = "Ahora usas ${s.manualPct} %") {
        Text("Según ${s.measurement.closedShifts} jornadas de los últimos ${WorkShiftRules.PERIOD_DAYS} días: " +
            "${ExpenseFormat.km(s.measurement.uberKm)} km Uber y ${ExpenseFormat.km(s.measurement.personalKm)} km personales.")
        Text("Si lo usas, cambia el % de Ajustes y de la ficha de la moto: Gastos de trabajo, Ganancias y reportes. " +
            "Los modelos de costos guardados no cambian; crea una versión nueva si quieres.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onAccept) { Text("Usar ${s.measuredPct} %") }
            TextButton(onClick = onDismiss) { Text("Ahora no") }
        }
    }
}

@Composable
private fun ShiftRow(s: WorkShift, onEditStart: () -> Unit, onEditEnd: () -> Unit, onDelete: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Text("${shown(s.start.atMs)} – ${s.end?.let { hourOf(it.atMs) } ?: "abierta"}", style = MaterialTheme.typography.titleSmall)
    Text("${ExpenseFormat.km(s.start.km)} → ${s.end?.let { ExpenseFormat.km(it.km) } ?: "…"} km" +
        (s.uberKm?.let { " · ${ExpenseFormat.km(it)} km Uber" } ?: "") + (if (s.start.isReset) " · odómetro nuevo" else ""),
        style = MaterialTheme.typography.bodySmall)
    Row {
        TextButton(onClick = onEditStart) { Text("Inicio") }
        TextButton(onClick = onEditEnd) { Text("Final") }
        TextButton(onClick = { confirm = true }) { Text("Borrar") }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false },
        title = { Text("¿Borrar esta jornada?") },
        text = { Text("Se borran sus dos lecturas de odómetro. Podrás deshacerlo justo después.") },
        confirmButton = { TextButton(onClick = { confirm = false; onDelete() }) { Text("Borrar") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancelar") } })
}

/**
 * Odometer, date and time. [save] gets (km, at, reset, confirmed) and answers Ok, Rejected (shown
 * as an error) or Confirm (asked once; a second «Guardar» sends confirmed = true).
 */
@Composable
private fun ShiftDialog(title: String, initialKm: Long?, initialAt: Long?, askReset: Boolean, onDismiss: () -> Unit,
    initialReset: Boolean = false, note: String? = null, save: suspend (Long, Long, Boolean, Boolean) -> ShiftCheck) {
    val zone = ZoneId.systemDefault()
    val start = initialAt?.let { Instant.ofEpochMilli(it).atZone(zone) }
    var km by rememberSaveable { mutableStateOf(initialKm?.toString() ?: "") }
    var date by rememberSaveable { mutableStateOf(start?.toLocalDate() ?: LocalDate.now(zone)) }
    var time by rememberSaveable { mutableStateOf((start?.toLocalTime() ?: LocalTime.now(zone)).format(HOUR)) }
    var reset by rememberSaveable { mutableStateOf(initialReset) }
    var confirmText by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val kmValue = km.trim().replace(",", "").toLongOrNull()?.takeIf { it in 0..ExpenseLimits.ODOMETER_MAX_KM }
    val timeValue = runCatching { LocalTime.parse(time.trim(), HOUR) }.getOrNull()
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                NumberField("Odómetro", km, { km = it; confirmText = null; error = null },
                    if (kmValue == null) "Escribe los km sin decimales." else null, "km", decimal = false)
                DateField("Fecha", date) { date = it; confirmText = null }
                OutlinedTextField(time, { time = it; confirmText = null }, label = { Text("Hora (HH:mm)") }, singleLine = true,
                    isError = timeValue == null, modifier = Modifier.fillMaxWidth())
                if (askReset) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = reset, onCheckedChange = { reset = it; error = null })
                    Text("Cambié de odómetro o de motor (empieza una cuenta nueva)", style = MaterialTheme.typography.bodySmall)
                }
                confirmText?.let { Text("$it Pulsa Guardar otra vez para confirmarlo.", color = MaterialTheme.colorScheme.tertiary) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && kmValue != null && timeValue != null, onClick = {
                val at = date.atTime(timeValue!!).atZone(zone).toInstant().toEpochMilli()
                scope.launch {
                    busy = true
                    try {
                        when (val r = save(kmValue!!, at, reset, confirmText != null)) {
                            ShiftCheck.Ok -> onDismiss()
                            is ShiftCheck.Confirm -> confirmText = r.message
                            is ShiftCheck.Rejected -> { error = r.message; confirmText = null }
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) { error = "No se pudo guardar. Inténtalo otra vez." }
                    finally { busy = false }
                }
            }) { Text("Guardar") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancelar") } })
}
