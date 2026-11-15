package com.ubers.observador.ui.expenses

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
import kotlinx.coroutines.launch
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
    if (state.loading) return
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
            state.wheels.forEach { w ->
                WheelBlock(w, state,
                    onInstall = { install = w.position },
                    onEdit = { edit = it },
                    onEvent = { tire, kind, existing -> eventKind = kind; event = tire to existing },
                    onDelete = { deleting = it })
                HorizontalDivider()
            }
            Text("Revisa la presión en frío, antes de rodar, y compárala con la que indica el fabricante para tu moto. " +
                "Una revisión vale ${TireRules.CHECK_VALID_DAYS} días. Las revisiones son gratis: no crean gastos. Cambios y reparaciones " +
                "pueden registrar su importe en Gastos una sola vez; borrar aquí un registro no borra su gasto.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
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
    Text("${w.position.label} · ${w.level.label}", style = MaterialTheme.typography.titleSmall,
        color = if (w.level.problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
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
