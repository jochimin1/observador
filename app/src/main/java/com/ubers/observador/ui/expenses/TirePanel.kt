package com.ubers.observador.ui.expenses

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ubers.observador.R
import com.ubers.observador.data.TireMoney
import com.ubers.observador.domain.expenses.ExpenseEntry
import com.ubers.observador.domain.expenses.ExpenseFormat
import com.ubers.observador.domain.expenses.FieldResult
import com.ubers.observador.domain.expenses.ExpenseInput
import com.ubers.observador.domain.vehicle.*
import com.ubers.observador.ui.components.AppFilterChip
import com.ubers.observador.ui.components.SectionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.ubers.observador.service.TireSensorScanner
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

private val zone: ZoneId get() = ZoneId.systemDefault()
private fun day(at: Long) = Instant.ofEpochMilli(at).atZone(zone).toLocalDate().toString()
private fun cash(minor: Long) = "RD$" + BigDecimal.valueOf(minor, 2).toPlainString()

/** «AAAA-MM-DD» → today now, another day at noon; null when not a date or in the future. */
private fun parseDay(text: String): Long? {
    val d = runCatching { LocalDate.parse(text.trim()) }.getOrNull() ?: return null
    val today = LocalDate.now(zone)
    if (d.isAfter(today)) return null
    return if (d == today) System.currentTimeMillis() else d.atTime(LocalTime.NOON).atZone(zone).toInstant().toEpochMilli()
}

private fun parseKm(text: String): Result<Long?> {
    val t = text.trim().replace(",", "").replace(".", "")
    if (t.isEmpty()) return Result.success(null)
    val km = t.toLongOrNull() ?: return Result.failure(IllegalArgumentException("Odómetro: solo números, en km"))
    return if (km in 0..9_999_999) Result.success(km) else Result.failure(IllegalArgumentException("Odómetro no válido"))
}

/**
 * 1.53.0 «Mi moto y neumáticos»: the two wheels under «Mi moto». Works without any sensor: the
 * driver writes the tyre installed, each pressure check and each repair. A missing check is shown
 * as pending, never as «correct».
 */
@Composable
fun TirePanel(viewModel: TiresViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val sensors by viewModel.sensors.collectAsState()
    val tpms by viewModel.tpms.collectAsState()
    if (state.loading) return
    var binding by remember { mutableStateOf<WheelPosition?>(null) }
    var showHistory by remember { mutableStateOf(false) }
    var showLimits by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exportMessage by remember { mutableStateOf<String?>(null) }
    val exportAll = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            exportMessage = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri, "w")?.use { viewModel.exportAll(it) }; "Neumáticos exportados." }
                    .getOrElse { "No se pudo exportar: ${it.message}" }
            }
        }
    }
    var install by remember { mutableStateOf<WheelPosition?>(null) }
    var edit by remember { mutableStateOf<Tire?>(null) }
    var event by remember { mutableStateOf<Pair<Tire, TireEvent?>?>(null) }
    var eventKind by remember { mutableStateOf(TireEventKind.CHECK) }
    var deleting by remember { mutableStateOf<Any?>(null) }
    val problem = state.wheels.any { it.level.problem }
    SectionCard(title = "Neumáticos", icon = R.drawable.ic_two_wheeler, subtitle = state.summary,
        badgeContainer = if (problem) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
        badgeContent = if (problem) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("Mostrar en", style = MaterialTheme.typography.bodySmall)
                PressureUnit.entries.forEach { u -> AppFilterChip(selected = state.unit == u, onClick = { viewModel.setUnit(u) }, label = { Text(u.label) }) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showHistory = true }) { Text("Historial y costos") }
                TextButton(onClick = { exportAll.launch("observador-neumaticos-${LocalDate.now()}.zip") }) { Text("Exportar neumáticos") }
                TextButton(onClick = { showLimits = true }) { Text("Ajustes") }
            }
            exportMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            SensorRecorder(viewModel, sensors, tpms.state is CalibrationState.Confirmed)
            CalibrationBlock(tpms, state.unit)
            state.wheels.forEach { w ->
                SensorLine(w.position, state, sensors, tpms, onBind = { binding = w.position }, onUnbind = { viewModel.unbind(w.position) },
                    onCalibrate = w.tire?.let { t -> { eventKind = TireEventKind.CHECK; event = t to null } })
                WheelBlock(w, state,
                    onInstall = { install = w.position },
                    onEdit = { edit = it },
                    onEvent = { tire, kind, existing -> eventKind = kind; event = tire to existing },
                    onDelete = { deleting = it })
                HorizontalDivider()
            }
            Text("Revisa la presión en frío, antes de rodar, y compárala con la que indica el fabricante para tu moto. " +
                "Una revisión vale ${state.limits.checkDays} días y el margen frente al objetivo es ±${state.limits.tolerancePct} %. Las revisiones son gratis: no crean gastos. Cambios y reparaciones " +
                "pueden registrar su importe en Gastos una sola vez; borrar aquí un registro no borra su gasto.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
    if (showHistory) TireHistoryDialog(viewModel, state) { showHistory = false }
    if (showLimits) TireLimitsDialog(state.limits, onSave = { viewModel.saveLimits(it) }) { showLimits = false }
    binding?.let { pos -> BindSensorDialog(pos, onSave = { viewModel.bindCard(pos, it) }) { binding = null } }
    install?.let { pos ->
        InstallDialog(pos, state.unit, current = state.wheels.first { it.position == pos }.tire,
            linkable = { at -> viewModel.linkable(TireEventKind.INSTALL, at) },
            onSave = { b, m, s, target, at, km, pay, note -> viewModel.install(pos, b, m, s, target, at, km, pay, note) },
            onDismiss = { install = null })
    }
    edit?.let { t -> EditTireDialog(t, state.unit, onSave = { b, m, s, target -> viewModel.editTire(t, b, m, s, target) }) { edit = null } }
    event?.let { (tire, existing) ->
        val kind = existing?.kind ?: eventKind
        EventDialog(tire, kind, existing, state.unit, state.expenses,
            linkable = { at -> viewModel.linkable(kind, at, existing?.id) },
            onSave = { e, pay -> viewModel.saveEvent(e, pay) }) { event = null }
    }
    deleting?.let { target ->
        val scope = rememberCoroutineScope()
        var error by remember(target) { mutableStateOf<String?>(null) }
        AlertDialog(onDismissRequest = { deleting = null },
            title = { Text(if (target is Tire) "Borrar goma" else "Borrar registro") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (target is Tire) "Se borra «${target.title}» con sus revisiones y reparaciones. Úsalo solo si se registró por error. " +
                    "Si había reemplazado a otra goma, esa vuelve a quedar instalada. Los gastos enlazados se quedan en Gastos."
                else "Se borra este registro. Si tenía un gasto, se queda en Gastos.")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = { TextButton(onClick = { scope.launch {
                error = when (target) { is Tire -> viewModel.deleteTire(target); is TireEvent -> viewModel.deleteEvent(target); else -> null }
                if (error == null) deleting = null
            } }) { Text("Borrar", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancelar") } })
    }
}

@Composable
private fun WheelBlock(w: WheelStatus, state: TiresUiState, onInstall: () -> Unit, onEdit: (Tire) -> Unit,
    onEvent: (Tire, TireEventKind, TireEvent?) -> Unit, onDelete: (Any) -> Unit) {
    var history by rememberSaveable(w.position.storageKey) { mutableStateOf(false) }
    val tire = w.tire
    // 1.55.0: a heading for TalkBack, and the state said in words (colour is never the only signal)
    Text("${w.position.label} · ${w.level.label}", style = MaterialTheme.typography.titleSmall,
        color = if (w.level.problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.semantics { heading(); contentDescription = "Rueda ${w.position.label.lowercase()}: ${w.level.label}" + if (w.level.problem) ", problema detectado" else "" })
    if (tire != null) {
        Text(listOfNotNull(tire.title, tire.size).joinToString(" · ") + " · instalada ${day(tire.installedAt)}", style = MaterialTheme.typography.bodyMedium)
        tire.target?.let { Text("Presión objetivo: ${it.shown(state.unit)}", style = MaterialTheme.typography.bodySmall) }
    }
    Text(w.detail, style = MaterialTheme.typography.bodySmall)
    if (tire != null) Text(w.lifeKm?.let { "Recorrido con esta goma: ${ExpenseFormat.km(it)} km según el odómetro" } ?: (w.lifeProblem ?: ""),
        style = MaterialTheme.typography.bodySmall)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (tire != null) {
            Button(onClick = { onEvent(tire, TireEventKind.CHECK, null) }) { Text("Revisar presión") }
            OutlinedButton(onClick = { onEvent(tire, TireEventKind.REPAIR, null) }) { Text("Reparación") }
            OutlinedButton(onClick = onInstall) { Text("Cambiar goma") }
            TextButton(onClick = { onEdit(tire) }) { Text("Editar") }
        } else Button(onClick = onInstall) { Text("Registrar goma") }
    }
    val count = w.history.size + w.previous.size
    if (count > 0) {
        TextButton(onClick = { history = !history }) { Text(if (history) "Ocultar historial" else "Ver historial ($count)") }
        if (history) {
            w.history.forEach { e -> EventLine(e, state, onEdit = { if (tire != null && e.kind != TireEventKind.INSTALL) onEvent(tire, e.kind, e) }, onDelete = { onDelete(e) }) }
            tire?.let { TextButton(onClick = { onDelete(it) }) { Text("Borrar esta goma (registrada por error)", color = MaterialTheme.colorScheme.error) } }
            w.previous.forEach { p ->
                Text("Anterior: ${p.title}${p.size?.let { " · $it" } ?: ""} · ${day(p.installedAt)} → ${p.removedAt?.let(::day) ?: "?"}" +
                    (TireRules.lifeKm(p, emptyList()).first?.let { " · ${ExpenseFormat.km(it)} km" } ?: ""),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun EventLine(e: TireEvent, state: TiresUiState, onEdit: () -> Unit, onDelete: () -> Unit) {
    val money = e.expenseId?.let { id -> state.expenses.firstOrNull { it.id == id }?.let { " · gasto ${cash(it.amountMinor)}" } ?: " · su gasto se borró en Gastos" } ?: ""
    val parts = listOfNotNull(day(e.atMs), e.kind.label, e.pressure?.let { p -> p.shown() + (if (p.unit != state.unit) " (${p.shown(state.unit)})" else "") },
        e.temperature.takeIf { e.kind == TireEventKind.CHECK && it != TireTemperature.UNKNOWN }?.label?.lowercase(),
        e.odometerKm?.let { "${ExpenseFormat.km(it)} km" }, e.note)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(parts.joinToString(" · ") + money, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        if (e.kind != TireEventKind.INSTALL) {
            TextButton(onClick = onEdit) { Text("Editar") }
            TextButton(onClick = onDelete) { Text("Borrar") }
        }
    }
}

/** The pressure field with its unit; [unit] is the one chosen in the card, the driver may switch it. */
@Composable
private fun PressureField(label: String, text: String, onText: (String) -> Unit, unit: PressureUnit, onUnit: (PressureUnit) -> Unit) {
    OutlinedTextField(text, onText, label = { Text("$label (${unit.label})") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PressureUnit.entries.forEach { u -> AppFilterChip(selected = unit == u, onClick = { onUnit(u) }, label = { Text(u.label) }) }
    }
}

/** What the money fields currently say; read once, when saving (not a state: writing it never recomposes). */
private class MoneyRef(initial: TireMoney) { var read: () -> Result<TireMoney> = { Result.success(initial) } }

/** None / new amount / link an existing expense. [ref] tells the dialog what to write, or the error. */
@Composable
private fun MoneyFields(kind: TireEventKind, atMs: Long?, initial: TireMoney, linkable: (Long) -> List<ExpenseEntry>, ref: MoneyRef) {
    var mode by rememberSaveable { mutableIntStateOf(when (initial) { TireMoney.None -> 0; is TireMoney.New -> 1; is TireMoney.Link -> 2 }) }
    var amount by rememberSaveable { mutableStateOf((initial as? TireMoney.New)?.amountMinor?.let { BigDecimal.valueOf(it, 2).stripTrailingZeros().toPlainString() } ?: "") }
    var linked by rememberSaveable { mutableStateOf((initial as? TireMoney.Link)?.expenseId) }
    val options = remember(atMs) { atMs?.let(linkable).orEmpty() }
    val expenseLabel = kind.expenseType?.label ?: return
    Text("Gasto ($expenseLabel)", style = MaterialTheme.typography.titleSmall)
    listOf("Sin gasto", "Registrar importe", "Enlazar un gasto ya anotado").forEachIndexed { i, label ->
        if (i == 2 && options.isEmpty() && mode != 2) return@forEachIndexed
        Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = mode == i, onClick = { mode = i }); Text(label) }
    }
    if (mode == 1) OutlinedTextField(amount, { amount = it }, label = { Text("Importe (RD$)") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
    if (mode == 2) options.forEach { e ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = linked == e.id, onClick = { linked = e.id })
            Text("${day(e.atMs)} · ${cash(e.amountMinor)}${e.odometerKm?.let { " · $it km" } ?: ""}", style = MaterialTheme.typography.bodySmall)
        }
    }
    ref.read = {
        when (mode) {
            1 -> when (val r = ExpenseInput.amount(amount)) {
                is FieldResult.Ok -> Result.success(TireMoney.New(r.value))
                is FieldResult.Error -> Result.failure(IllegalArgumentException(r.message))
            }
            2 -> linked?.let { Result.success(TireMoney.Link(it)) } ?: Result.failure(IllegalArgumentException("Elige el gasto a enlazar"))
            else -> Result.success(TireMoney.None)
        }
    }
}

@Composable
private fun InstallDialog(position: WheelPosition, unit: PressureUnit, current: Tire?, linkable: (Long) -> List<ExpenseEntry>,
    onSave: suspend (String, String, String, Pressure?, Long, Long?, TireMoney, String) -> String?, onDismiss: () -> Unit) {
    var brand by rememberSaveable { mutableStateOf(current?.brand ?: "") }
    var model by rememberSaveable { mutableStateOf(current?.model ?: "") }
    var size by rememberSaveable { mutableStateOf(current?.size ?: "") }
    var targetUnit by rememberSaveable { mutableStateOf(current?.target?.unit ?: unit) }
    var target by rememberSaveable { mutableStateOf(current?.target?.takeIf { it.unit == targetUnit }?.entered ?: "") }
    var date by rememberSaveable { mutableStateOf(LocalDate.now(zone).toString()) }
    var km by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    val money = remember { MoneyRef(TireMoney.None) }
    TireDialog(if (current == null) "Registrar goma ${position.label.lowercase()}" else "Cambiar goma ${position.label.lowercase()}", onDismiss, save = {
        val at = parseDay(date) ?: return@TireDialog "Fecha AAAA-MM-DD, hoy o anterior"
        val t = if (target.isBlank()) null else TirePressure.parse(target, targetUnit).getOrElse { return@TireDialog it.message }
        val k = parseKm(km).getOrElse { return@TireDialog it.message }
        val pay = money.read().getOrElse { return@TireDialog it.message }
        onSave(brand, model, size, t, at, k, pay, note)
    }) {
        if (current != null) Text("«${current.title}» queda en el historial, cerrada en esta fecha. La nueva goma empieza su propia cuenta.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(brand, { brand = it }, label = { Text("Marca (opcional)") }, singleLine = true)
        OutlinedTextField(model, { model = it }, label = { Text("Modelo (opcional)") }, singleLine = true)
        OutlinedTextField(size, { size = it }, label = { Text("Medida, ej. 90/90-18 (opcional)") }, singleLine = true)
        PressureField("Presión objetivo (opcional)", target, { target = it }, targetUnit) { targetUnit = it }
        OutlinedTextField(date, { date = it }, label = { Text("Fecha de instalación (AAAA-MM-DD)") }, singleLine = true)
        OutlinedTextField(km, { km = it }, label = { Text("Odómetro al instalar, km (opcional)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        OutlinedTextField(note, { note = it }, label = { Text("Nota (opcional)") })
        MoneyFields(TireEventKind.INSTALL, parseDay(date), TireMoney.None, linkable, money)
    }
}

@Composable
private fun EditTireDialog(tire: Tire, unit: PressureUnit, onSave: suspend (String, String, String, Pressure?) -> String?, onDismiss: () -> Unit) {
    var brand by rememberSaveable { mutableStateOf(tire.brand ?: "") }
    var model by rememberSaveable { mutableStateOf(tire.model ?: "") }
    var size by rememberSaveable { mutableStateOf(tire.size ?: "") }
    var targetUnit by rememberSaveable { mutableStateOf(tire.target?.unit ?: unit) }
    var target by rememberSaveable { mutableStateOf(tire.target?.entered ?: "") }
    TireDialog("Editar goma ${tire.position.label.lowercase()}", onDismiss, save = {
        val t = if (target.isBlank()) null else TirePressure.parse(target, targetUnit).getOrElse { return@TireDialog it.message }
        onSave(brand, model, size, t)
    }) {
        OutlinedTextField(brand, { brand = it }, label = { Text("Marca") }, singleLine = true)
        OutlinedTextField(model, { model = it }, label = { Text("Modelo") }, singleLine = true)
        OutlinedTextField(size, { size = it }, label = { Text("Medida") }, singleLine = true)
        PressureField("Presión objetivo", target, { target = it }, targetUnit) { targetUnit = it }
        Text("La fecha y el odómetro de instalación no se editan: si están mal, borra la goma desde su historial y regístrala de nuevo.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun EventDialog(tire: Tire, kind: TireEventKind, existing: TireEvent?, unit: PressureUnit, expenses: List<ExpenseEntry>,
    linkable: (Long) -> List<ExpenseEntry>, onSave: suspend (TireEvent, TireMoney) -> String?, onDismiss: () -> Unit) {
    var pUnit by rememberSaveable { mutableStateOf(existing?.pressure?.unit ?: unit) }
    var pressure by rememberSaveable { mutableStateOf(existing?.pressure?.entered ?: "") }
    var temp by rememberSaveable { mutableStateOf(existing?.temperature ?: TireTemperature.COLD) }
    var date by rememberSaveable { mutableStateOf(existing?.let { day(it.atMs) } ?: LocalDate.now(zone).toString()) }
    var km by rememberSaveable { mutableStateOf(existing?.odometerKm?.toString() ?: "") }
    var note by rememberSaveable { mutableStateOf(existing?.note ?: "") }
    val initialMoney: TireMoney = existing?.expenseId?.let { id -> expenses.firstOrNull { it.id == id }?.let { TireMoney.New(it.amountMinor) } } ?: TireMoney.None
    val money = remember { MoneyRef(initialMoney) }
    TireDialog("${kind.label} · ${tire.position.label.lowercase()}", onDismiss, save = {
        val at = if (existing != null && date == day(existing.atMs)) existing.atMs else parseDay(date) ?: return@TireDialog "Fecha AAAA-MM-DD, hoy o anterior"
        val p = if (pressure.isBlank()) null else TirePressure.parse(pressure, pUnit).getOrElse { return@TireDialog it.message }
        if (kind == TireEventKind.CHECK && p == null) return@TireDialog "Escribe la presión medida"
        val k = parseKm(km).getOrElse { return@TireDialog it.message }
        val pay = if (kind == TireEventKind.CHECK) TireMoney.None else money.read().getOrElse { return@TireDialog it.message }
        val now = System.currentTimeMillis()
        val event = existing?.copy(atMs = at, pressure = p, temperature = temp, odometerKm = k, note = note)
            ?: TireEvent(java.util.UUID.randomUUID().toString(), tire.id, kind, at, p, temp, k, null, note, now, now)
        onSave(event, pay)
    }) {
        if (kind == TireEventKind.CHECK) {
            PressureField("Presión medida", pressure, { pressure = it }, pUnit) { pUnit = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TireTemperature.entries.forEach { t -> AppFilterChip(selected = temp == t, onClick = { temp = t }, label = { Text(t.label) }) }
            }
        }
        OutlinedTextField(date, { date = it }, label = { Text("Fecha (AAAA-MM-DD)") }, singleLine = true)
        OutlinedTextField(km, { km = it }, label = { Text("Odómetro, km (opcional)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        OutlinedTextField(note, { note = it }, label = { Text(if (kind == TireEventKind.REPAIR) "Qué se reparó (opcional)" else "Nota (opcional)") })
        if (kind != TireEventKind.CHECK) MoneyFields(kind, parseDay(date), initialMoney, linkable, money)
        else Text("Una revisión no crea ningún gasto.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun TireDialog(title: String, onDismiss: () -> Unit, save: suspend () -> String?, fields: @Composable ColumnScope.() -> Unit) {
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


/** 1.54.0: the sensor of one wheel: its ID, when its last advertisement arrived and what it seems to say. */
@Composable
private fun SensorLine(position: WheelPosition, state: TiresUiState, sensors: SensorUi, tpms: TpmsUi, onBind: () -> Unit, onUnbind: suspend () -> Unit,
    onCalibrate: (() -> Unit)?) {
    val scope = rememberCoroutineScope()
    val b = SensorBindings.active(sensors.bindings, state.vehicle.id, position)
    val frame = b?.let { sensors.latest[it.sensorId] }?.takeIf { b != null && it.receivedAt >= b.boundAt }
    val now = System.currentTimeMillis()
    val st = SensorFreshness.state(b, frame?.receivedAt, sensors.scan.listening, now)
    if (b == null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${position.label}: sin sensor Bluetooth", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onBind) { Text("Asociar sensor") }
        }
        return
    }
    Text("${position.label} · sensor ${SensorIds.shown(b.sensorId)} · ${st.label}" +
        (frame?.let { " · recibido ${SensorFreshness.ago(now, it.receivedAt)}" } ?: ""), style = MaterialTheme.typography.bodySmall)
    if (frame?.pressurePa != null && frame.parsedId == b.sensorId) {
        val raw = frame.pressurePa
        val t = frame.temperatureCenti?.let { " · " + state.limits.temperature.format(it) } ?: ""
        val bat = frame.batteryPct?.let { " · batería del sensor $it %" } ?: ""
        Text("Temperatura y batería: ${t.removePrefix(" · ")}$bat.", style = MaterialTheme.typography.bodySmall)
        val confirmed = (tpms.state as? CalibrationState.Confirmed)?.reference
        // 1.61.0: hot, it also says the pressure it would have cold (what the alerts compare)
        if (confirmed != null) Text("Presión: ${TireWarmth.shown(TpmsReference.gauge(raw, confirmed), frame.temperatureCenti, state.unit, state.limits.temperature)}" +
            if (TireWarmth.isWarm(frame.temperatureCenti)) ". Las alertas usan la presión en frío." else " (como la marcaría el manómetro)",
            style = MaterialTheme.typography.bodySmall)
        else Text("Presión por confirmar: el sensor envía ${TirePressure.format(raw, PressureUnit.KPA)}. Si es absoluta, el manómetro marcaría " +
            "${TirePressure.format(TpmsParser.gaugeIfAbsolute(raw), state.unit)}; si ya es manométrica, ${TirePressure.format(raw, state.unit)}. " +
            "Calíbralo con un manómetro. Mientras tanto no decide nada.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    // 1.60.0: why it is or is not received
    tpms.reports[position]?.let { r -> Text(r.verdict, style = MaterialTheme.typography.bodySmall,
        color = if (r.lastAtMs == null || now - r.lastAtMs > TpmsDiagnosis.DAY_MS) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
    val fresh = frame != null && frame.parsedId == b.sensorId && frame.pressurePa != null && now - frame.receivedAt <= TpmsCalibrations.WINDOW_MS
    if (onCalibrate != null) {
        if (fresh) Button(onClick = onCalibrate) { Text("Calibrar con manómetro") }
        else Text("Para calibrar, el sensor debe haberse recibido en los últimos ${TpmsCalibrations.WINDOW_MS / 60_000} min: escucha los sensores, " +
            "mide con el manómetro y toca «Calibrar con manómetro».", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Row {
        TextButton(onClick = onBind) { Text("Cambiar sensor") }
        TextButton(onClick = { scope.launch { onUnbind() } }) { Text("Desvincular") }
    }
}

/** 1.54.0: listen for a few minutes and keep every advertisement, to validate the sensors' format. */
@Composable
private fun SensorRecorder(viewModel: TiresViewModel, sensors: SensorUi, confirmed: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    val scan = sensors.scan
    var startAfterGrant by remember { mutableStateOf(false) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        message = when {
            !granted.values.all { it } -> "Sin el permiso no se pueden escuchar los sensores."
            startAfterGrant -> viewModel.startListening()
            else -> null
        }
        startAfterGrant = false
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch {
            message = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri, "w")?.use { viewModel.exportFrames(it) }; "Anuncios exportados." }
                    .getOrElse { "No se pudo exportar: ${it.message}" }
            }
        }
    }
    val background by viewModel.tpmsBackground.collectAsState()
    val backgroundOn by com.ubers.observador.service.TireSensorBackground.listening.collectAsState()
    val backgroundProblem by com.ubers.observador.service.TireSensorBackground.problem.collectAsState()
    Text("Sensores Bluetooth (en prueba)", style = MaterialTheme.typography.titleSmall)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Escuchar sensores mientras trabajas", style = MaterialTheme.typography.bodyMedium)
            Text(when {
                !background -> "Apagado. Escucha con poco consumo y la pantalla apagada mientras Observador está activo; guarda solo tus sensores. Su consumo de batería aún no está medido."
                backgroundOn -> "Escuchando en segundo plano."
                else -> backgroundProblem ?: "Se activa cuando Observador está en marcha y hay un sensor asociado."
            }, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = background, onCheckedChange = { on ->
            val missing = TireSensorScanner.missingPermissions(context)
            if (on && missing.isNotEmpty()) permissions.launch(missing.toTypedArray())
            viewModel.setTpmsBackground(on)
        })
    }
    Text(if (scan.listening) "Escuchando: ${scan.devices} equipos cerca · ${scan.frames} anuncios · ${scan.mine} de tus sensores" +
            (scan.lastMineAt?.let { " (último ${SensorFreshness.ago(System.currentTimeMillis(), it)})" } ?: "") +
            ". Se detiene sola a los ${TireSensorScanner.MAX_MS / 60_000} min."
        else "Pon la moto cerca y escucha unos minutos: se guardan todos los anuncios recibidos (${sensors.frameCount} hasta ahora) para validar el formato de tus sensores. Los sensores pueden tardar en emitir; rodar o mover la rueda ayuda.",
        style = MaterialTheme.typography.bodySmall)
    // 1.55.0: alert options (alerts start once the sensor pressure is confirmed with a gauge)
    val voice by viewModel.tpmsVoice.collectAsState()
    var silenced by remember { mutableStateOf(viewModel.silencedUntil()) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Decir las alertas en voz alta", style = MaterialTheme.typography.bodyMedium)
            Text(if (confirmed) "Notificación y vibración siempre; la voz, solo si la activas."
                else "Las alertas del sensor se activan cuando confirmes su presión con un manómetro (${TpmsCalibrations.REQUIRED} mediciones).", style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = voice, onCheckedChange = { viewModel.setTpmsVoice(it) })
    }
    if (confirmed) Row(verticalAlignment = Alignment.CenterVertically) {
        Text(silenced?.let { "Alertas en silencio hasta ${java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalTime().withNano(0)}; se siguen registrando." }
            ?: "Alertas con sonido.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        TextButton(onClick = { viewModel.silenceAlerts(1); silenced = viewModel.silencedUntil() }) { Text("Silenciar 1 h") }
    }
    (message ?: scan.problem)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (scan.listening) OutlinedButton(onClick = { viewModel.stopListening() }) { Text("Detener") }
        else Button(onClick = {
            message = null
            val missing = TireSensorScanner.missingPermissions(context)
            if (missing.isNotEmpty()) { startAfterGrant = true; permissions.launch(missing.toTypedArray()) } else { message = viewModel.startListening() }
        }) { Text("Escuchar sensores") }
        if (sensors.frameCount > 0) TextButton(onClick = { exporter.launch("observador-sensores-${java.time.LocalDate.now()}.csv") }) { Text("Exportar anuncios") }
    }
}

/** 1.60.0: the sensor pressure confirmed (or not) with the driver's gauge, and sensors heard but not associated. */
@Composable
private fun CalibrationBlock(tpms: TpmsUi, unit: PressureUnit) {
    if (tpms.reports.isEmpty() && tpms.calibrations.isEmpty()) return
    val bad = tpms.state is CalibrationState.Conflict
    Text(TpmsCalibrations.describe(tpms.state, unit), style = MaterialTheme.typography.bodySmall,
        color = if (bad) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    var all by rememberSaveable { mutableStateOf(false) }
    if (tpms.calibrations.isNotEmpty()) {
        TextButton(onClick = { all = !all }) { Text(if (all) "Ocultar mediciones" else "Ver mediciones con manómetro (${tpms.calibrations.size})") }
        if (all) tpms.calibrations.asReversed().forEach { c ->
            Text("${day(c.atMs)} · " + TpmsCalibrations.line(c, unit), style = MaterialTheme.typography.bodySmall)
        }
    }
    val now = System.currentTimeMillis()
    tpms.strangers.take(3).forEach { s ->
        Text("Se oye otro sensor: ${SensorIds.shown(s.id)}" + (s.wheel?.let { " (TPMS$it)" } ?: "") +
            ", ${s.count} anuncios, último ${SensorFreshness.ago(now, s.lastAtMs)}, señal ${TpmsDiagnosis.signal(s.rssi)}. " +
            "Si es uno de los tuyos, el ID asociado está mal escrito: usa «Cambiar sensor».",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun BindSensorDialog(position: WheelPosition, onSave: suspend (String) -> String?, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    TireDialog("Sensor de la rueda ${position.label.lowercase()}", onDismiss, save = { onSave(text) }) {
        Text("Escribe el ID impreso en la tarjeta del kit (6 caracteres, por ejemplo 13AE32). " +
            "Si escribes el código doble de la tarjeta (13AE32&23A434), el primero va a la delantera y el segundo a la trasera.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(text, { text = it }, label = { Text("ID del sensor o código doble") }, singleLine = true)
        val scanner = rememberLauncherForActivityResult(com.journeyapps.barcodescanner.ScanContract()) { result ->
            result.contents?.let { text = it.trim() }
        }
        OutlinedButton(onClick = {
            scanner.launch(com.journeyapps.barcodescanner.ScanOptions()
                .setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE, com.journeyapps.barcodescanner.ScanOptions.CODE_128)
                .setPrompt("Apunta al código de un sensor o al QR doble de la tarjeta")
                .setBeepEnabled(false)
                .setOrientationLocked(false))
        }) { Text("Escanear la tarjeta con la cámara") }
        Text("Comprueba que el sensor esté puesto en esta rueda: un sensor cambiado de rueda daría datos de la otra.", style = MaterialTheme.typography.bodySmall)
    }
}


/** 1.55.0: check period, pressure margin and temperature unit. */
@Composable
private fun TireLimitsDialog(limits: TireSettings, onSave: suspend (TireSettings) -> String?, onDismiss: () -> Unit) {
    var days by rememberSaveable { mutableStateOf(limits.checkDays.toString()) }
    var margin by rememberSaveable { mutableStateOf(limits.tolerancePct.toString()) }
    var temp by rememberSaveable { mutableStateOf(limits.temperature) }
    TireDialog("Ajustes de neumáticos", onDismiss, save = {
        onSave(TireSettings(days.trim().toIntOrNull() ?: -1, margin.trim().toIntOrNull() ?: -1, temp))
    }) {
        OutlinedTextField(days, { days = it }, label = { Text("Una revisión vale (días, 1–90)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        OutlinedTextField(margin, { margin = it }, label = { Text("Margen frente a la presión objetivo (%, 3–30)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TemperatureUnit.entries.forEach { u -> AppFilterChip(selected = temp == u, onClick = { temp = u }, label = { Text(u.label) }) }
        }
        Text("Usa la presión y la tolerancia que indica el fabricante de tu moto o de tus gomas. Cambiar estos valores no modifica lo ya anotado: solo cómo se evalúa.",
            style = MaterialTheme.typography.bodySmall)
    }
}

private enum class HistoryRange(val label: String, val days: Long?) { WEEK("7 días", 7), MONTH("30 días", 30), QUARTER("90 días", 90), ALL("Todo", null) }

/** 1.55.0: everything that happened to the wheels, with gaps and costs, filtered by wheel and period. */
@Composable
private fun TireHistoryDialog(viewModel: TiresViewModel, state: TiresUiState, onDismiss: () -> Unit) {
    var wheel by rememberSaveable { mutableStateOf<WheelPosition?>(null) }
    var range by rememberSaveable { mutableStateOf(HistoryRange.MONTH) }
    var items by remember { mutableStateOf<List<TireHistoryItem>?>(null) }
    LaunchedEffect(wheel, range, state.data) {
        val now = System.currentTimeMillis()
        items = withContext(Dispatchers.IO) { viewModel.history(wheel, range.days?.let { now - it * 86_400_000L } ?: Long.MIN_VALUE, Long.MAX_VALUE) }
    }
    val unit = state.unit
    val temp = state.limits.temperature
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Historial de neumáticos") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppFilterChip(selected = wheel == null, onClick = { wheel = null }, label = { Text("Las dos") })
                WheelPosition.entries.forEach { w -> AppFilterChip(selected = wheel == w, onClick = { wheel = w }, label = { Text(w.label) }) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HistoryRange.entries.forEach { r -> AppFilterChip(selected = range == r, onClick = { range = r }, label = { Text(r.label) }) }
            }
            // Charts need one wheel: with «Las dos» each wheel gets its own
            (wheel?.let { listOf(it) } ?: WheelPosition.entries).forEach { w ->
                var charts by remember(w, range, state.data) { mutableStateOf<Pair<ChartSeries, ChartSeries>?>(null) }
                LaunchedEffect(w, range, state.data) {
                    val now = System.currentTimeMillis()
                    val from = range.days?.let { now - it * 86_400_000L }
                        ?: (state.data.tires.filter { it.position == w }.minOfOrNull { it.installedAt } ?: (now - 30 * 86_400_000L))
                    charts = withContext(Dispatchers.IO) { viewModel.charts(w, from, now) }
                }
                charts?.let { (p, t) ->
                    TireChartView(p, "Presión ${w.label.lowercase()}")
                    if (!t.empty) TireChartView(t, "Temperatura ${w.label.lowercase()} (sensor)")
                }
            }
            Text("Costo por km", style = MaterialTheme.typography.titleSmall)
            val costs = state.costs.filter { wheel == null || it.tire.position == wheel }
            if (costs.isEmpty()) Text("Sin gomas registradas.", style = MaterialTheme.typography.bodySmall)
            costs.forEach { c -> Text("${c.tire.position.label}: " + TireCosts.line(c), style = MaterialTheme.typography.bodySmall) }
            Text("Solo cuenta gastos enlazados a la goma (instalación y reparaciones) y km del odómetro; desde ${TireCosts.MIN_KM} km.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider()
            when (val list = items) {
                null -> Text("Cargando…", style = MaterialTheme.typography.bodySmall)
                else -> if (list.isEmpty()) Text("Nada en este periodo.", style = MaterialTheme.typography.bodySmall)
                else list.forEach { item -> Text(historyLine(item, unit, temp), style = MaterialTheme.typography.bodySmall,
                    color = if (item is TireHistoryItem.Gap || item is TireHistoryItem.Alert) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) }
            }
        } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } })
}

private fun historyLine(item: TireHistoryItem, unit: PressureUnit, temp: TemperatureUnit): String {
    val w = item.position.label
    return when (item) {
        is TireHistoryItem.Event -> {
            val e = item.event
            listOfNotNull("${day(e.atMs)} · $w · ${e.kind.label}", if (e.kind == TireEventKind.INSTALL) item.tire.title else null,
                e.pressure?.let { p -> p.shown() + if (p.unit != unit) " (${p.shown(unit)})" else "" },
                e.temperature.takeIf { e.kind == TireEventKind.CHECK && it != TireTemperature.UNKNOWN }?.label?.lowercase(),
                e.odometerKm?.let { "${ExpenseFormat.km(it)} km" }, e.note).joinToString(" · ")
        }
        is TireHistoryItem.Removed -> "${day(item.atMs)} · $w · Cambio: sale ${item.tire.title}" + (item.tire.removedOdometerKm?.let { " a ${ExpenseFormat.km(it)} km" } ?: "")
        is TireHistoryItem.SensorOn -> "${day(item.atMs)} · $w · Sensor ${SensorIds.shown(item.binding.sensorId)} asociado"
        is TireHistoryItem.SensorOff -> "${day(item.atMs)} · $w · Sensor ${SensorIds.shown(item.binding.sensorId)} desvinculado"
        is TireHistoryItem.Sensor -> {
            val d = item.day
            val p = if (d.minPa != null && d.maxPa != null) " · envió ${TirePressure.format(d.minPa, PressureUnit.KPA)}–${TirePressure.format(d.maxPa, PressureUnit.KPA)} (por confirmar)" else ""
            val t = if (d.minTempCenti != null && d.maxTempCenti != null) " · ${temp.format(d.minTempCenti)}–${temp.format(d.maxTempCenti)}" else ""
            "${day(d.dayStartMs)} · $w · Sensor ${SensorIds.shown(d.sensorId)}: ${d.frames} anuncios$p$t" + (d.battery?.let { " · batería $it %" } ?: "")
        }
        is TireHistoryItem.Gap -> "${day(item.fromMs)} → ${day(item.toMs)} · $w · ${item.days} días sin revisión de presión"
        is TireHistoryItem.Alert -> "${day(item.atMs)} · $w · Alerta: " + TpmsAlertNotice.text(item.alert, unit).substringAfter(": ") +
            " (${item.alert.kind.label.lowercase()}" + (item.alert.endedAt?.let { ", terminó ${SensorFreshness.ago(it, item.alert.startedAt).removePrefix("hace ")} después" } ?: ", sigue activa") + ")"
    }
}


/**
 * 1.55.0: a small line chart. Manual checks are dots, sensor readings a line that breaks at gaps,
 * the target a dashed line with its margin shaded. TalkBack reads [TireChart.description] instead.
 */
@Composable
private fun TireChartView(series: ChartSeries, title: String) {
    val manualColor = MaterialTheme.colorScheme.primary
    val sensorColor = MaterialTheme.colorScheme.tertiary
    val band = MaterialTheme.colorScheme.secondaryContainer
    val targetColor = MaterialTheme.colorScheme.secondary
    val description = TireChart.description(series, title)
    Text(title + if (series.sensorProvisional && series.sensorRuns.any { it.isNotEmpty() }) " · sensor por confirmar" else "",
        style = MaterialTheme.typography.labelLarge)
    if (series.empty) { Text("Sin datos en este periodo.", style = MaterialTheme.typography.bodySmall); return }
    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(140.dp)
        .androidx_semantics(description)) {
        val w = size.width; val h = size.height
        fun px(at: Long) = TireChart.x(series, at) * w
        fun py(v: Double) = h - TireChart.y(series, v) * h
        if (series.low != null && series.high != null) {
            val top = py(series.high); val bottom = py(series.low)
            drawRect(band, topLeft = androidx.compose.ui.geometry.Offset(0f, top), size = androidx.compose.ui.geometry.Size(w, bottom - top))
        }
        series.target?.let { t ->
            drawLine(targetColor, androidx.compose.ui.geometry.Offset(0f, py(t)), androidx.compose.ui.geometry.Offset(w, py(t)), strokeWidth = 2f,
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(12f, 8f)))
        }
        series.sensorRuns.forEach { run ->
            run.zipWithNext().forEach { (a, b) ->
                drawLine(sensorColor, androidx.compose.ui.geometry.Offset(px(a.atMs), py(a.value)), androidx.compose.ui.geometry.Offset(px(b.atMs), py(b.value)), strokeWidth = 4f)
            }
            if (run.size == 1) drawCircle(sensorColor, 4f, androidx.compose.ui.geometry.Offset(px(run[0].atMs), py(run[0].value)))
        }
        series.manual.forEach { p -> drawCircle(manualColor, 8f, androidx.compose.ui.geometry.Offset(px(p.atMs), py(p.value))) }
    }
    Text("${TireChart.fmt(series.minY)}–${TireChart.fmt(series.maxY)} ${series.unitLabel} · ${day(series.fromMs)} → ${day(series.toMs)}" +
        (if (series.manual.isNotEmpty()) " · puntos: revisiones" else "") + (if (series.sensorRuns.any { it.isNotEmpty() }) " · línea: sensor" else ""),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun Modifier.androidx_semantics(description: String): Modifier =
    this.then(Modifier.semantics { contentDescription = description })
