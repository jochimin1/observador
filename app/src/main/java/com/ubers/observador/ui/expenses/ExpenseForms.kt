package com.ubers.observador.ui.expenses

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ubers.observador.R
import com.ubers.observador.domain.expenses.ExpenseEntry
import com.ubers.observador.domain.expenses.ExpenseFormat
import com.ubers.observador.domain.expenses.ExpenseGroup
import com.ubers.observador.domain.expenses.ExpenseInput
import com.ubers.observador.domain.expenses.ExpenseType
import com.ubers.observador.domain.expenses.FieldResult
import com.ubers.observador.domain.expenses.Frequency
import com.ubers.observador.domain.expenses.FuelEntry
import com.ubers.observador.domain.expenses.Odometer
import com.ubers.observador.domain.expenses.OdometerCheck
import com.ubers.observador.domain.expenses.OdometerPoint
import com.ubers.observador.domain.expenses.OdometerReading
import com.ubers.observador.domain.expenses.RecurringCost
import com.ubers.observador.domain.expenses.RecurringType
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

private val DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.forLanguageTag("es"))

/** The entry's instant: the chosen date at the original time of day (or now for a new one). */
private fun atOf(date: LocalDate, initialMs: Long?): Long {
    val zone = ZoneId.systemDefault()
    val time = initialMs?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime() } ?: LocalTime.now(zone)
    return date.atTime(time).atZone(zone).toInstant().toEpochMilli()
}

private fun dateOf(ms: Long?): LocalDate = ms?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() } ?: LocalDate.now()

private fun minorText(minor: Long): String = BigDecimal.valueOf(minor, 2).stripTrailingZeros().toPlainString()

private fun <T> FieldResult<T>.error(): String? = (this as? FieldResult.Error)?.message
private fun <T> FieldResult<T>.value(): T? = (this as? FieldResult.Ok)?.value

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(label: String, date: LocalDate, onDate: (LocalDate) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
        Icon(painterResource(R.drawable.ic_calendar_month), contentDescription = null, modifier = Modifier.size(18.dp))
        Text("  $label: ${date.format(DATE)}")
    }
    if (open) {
        val state = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    open = false
                }) { Text("Listo") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancelar") } }
        ) { DatePicker(state = state) }
    }
}

@Composable
private fun NumberField(label: String, value: String, onValue: (String) -> Unit, error: String?, suffix: String? = null, decimal: Boolean = true, supporting: String? = null) {
    val shown = if (value.isNotBlank() && error != null) error else supporting
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        suffix = if (suffix != null) { { Text(suffix) } } else null,
        isError = value.isNotBlank() && error != null,
        supportingText = if (shown != null) { { Text(shown) } } else null,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

/** Shown when the odometer is lower than the last one written: saving needs "cambié de odómetro o de motor". */
@Composable
private fun ResetQuestion(check: OdometerCheck, confirmed: Boolean, onConfirmed: (Boolean) -> Unit) {
    if (check !is OdometerCheck.Lower) return
    Column {
        Text(
            "Es menor que el último odómetro (${ExpenseFormat.km(check.previousKm)} km, ${dateOf(check.previousAt).format(DATE)}).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = confirmed, onCheckedChange = onConfirmed)
            Text("Cambié de odómetro o de motor", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun FormDialog(title: String, canSave: Boolean, onDismiss: () -> Unit, onSave: () -> Unit, content: @Composable () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() } },
        confirmButton = { TextButton(onClick = onSave, enabled = canSave) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

/** Fill-up: the quickest form, gallons, total and odometer first. */
@Composable
fun FuelDialog(initial: FuelEntry?, timeline: List<OdometerPoint>, onDismiss: () -> Unit, onSave: (FuelEntry, Long?) -> Unit) {
    var date by rememberSaveable { mutableStateOf(dateOf(initial?.atMs)) }
    var gallons by rememberSaveable { mutableStateOf(initial?.let { ExpenseFormat.gallons(it.gallonsMilli) } ?: "") }
    var total by rememberSaveable { mutableStateOf(initial?.let { minorText(it.amountMinor) } ?: "") }
    var byPrice by rememberSaveable { mutableStateOf(false) }
    var price by rememberSaveable { mutableStateOf("") }
    var odometer by rememberSaveable { mutableStateOf(initial?.odometerKm?.toString() ?: "") }
    var full by rememberSaveable { mutableStateOf(initial?.fullTank ?: true) }
    var reset by rememberSaveable { mutableStateOf(false) }

    val totalR = ExpenseInput.amount(total)
    val priceR = ExpenseInput.amount(price)
    val gallonsR = if (byPrice) {
        val t = totalR.value(); val p = priceR.value()
        if (t != null && p != null) ExpenseInput.gallonsFromPrice(t, p) else FieldResult.Error("Escribe precio y total")
    } else ExpenseInput.gallons(gallons)
    val odoR = ExpenseInput.odometer(odometer, required = false)
    val at = atOf(date, initial?.atMs)
    val check = odoR.value()?.let { Odometer.check(it, at, timeline, excludeId = initial?.id) } ?: OdometerCheck.Ok
    val ok = totalR.value() != null && gallonsR.value() != null && odoR !is FieldResult.Error &&
        (check == OdometerCheck.Ok || reset)

    FormDialog(if (initial == null) "Tanqueo" else "Editar tanqueo", ok, onDismiss, {
        val now = System.currentTimeMillis()
        val entry = FuelEntry(
            id = initial?.id ?: UUID.randomUUID().toString(),
            atMs = at,
            gallonsMilli = gallonsR.value()!!,
            amountMinor = totalR.value()!!,
            odometerKm = odoR.value(),
            fullTank = full,
            createdAt = initial?.createdAt ?: now,
            updatedAt = now
        )
        onSave(entry, if (check is OdometerCheck.Lower && reset) entry.odometerKm else null)
    }) {
        if (!byPrice) {
            NumberField("Galones", gallons, { gallons = it }, gallonsR.error(), "gal")
        } else {
            NumberField("Precio por galón", price, { price = it }, priceR.error(), "RD$")
        }
        NumberField("Total pagado", total, { total = it }, totalR.error(), "RD$",
            supporting = gallonsR.value()?.let { g -> totalR.value()?.let { t -> "RD$" + minorText(t * 1000 / g) + " por galón · " + ExpenseFormat.gallons(g) + " gal" } })
        TextButton(onClick = { byPrice = !byPrice }) { Text(if (byPrice) "Escribir los galones" else "Prefiero escribir el precio por galón") }
        NumberField("Odómetro (opcional)", odometer, { odometer = it; reset = false }, odoR.error(), "km", decimal = false,
            supporting = "Con tanque lleno y odómetro sale tu km/galón real")
        ResetQuestion(check, reset) { reset = it }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Tanque lleno", modifier = Modifier.weight(1f))
            Switch(checked = full, onCheckedChange = { full = it })
        }
        DateField("Fecha", date) { date = it }
    }
}

/** Maintenance or a loose expense; maintenance asks for the odometer (reminders need it). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExpenseDialog(initial: ExpenseEntry?, timeline: List<OdometerPoint>, onDismiss: () -> Unit, onSave: (ExpenseEntry, Long?) -> Unit) {
    var type by rememberSaveable { mutableStateOf(initial?.type ?: ExpenseType.OIL) }
    var date by rememberSaveable { mutableStateOf(dateOf(initial?.atMs)) }
    var amount by rememberSaveable { mutableStateOf(initial?.let { minorText(it.amountMinor) } ?: "") }
    var odometer by rememberSaveable { mutableStateOf(initial?.odometerKm?.toString() ?: "") }
    var reset by rememberSaveable { mutableStateOf(false) }

    val amountR = ExpenseInput.amount(amount)
    val maintenance = type.group == ExpenseGroup.MAINTENANCE
    val odoR = if (maintenance) ExpenseInput.odometer(odometer, required = false) else FieldResult.Ok(null)
    val at = atOf(date, initial?.atMs)
    val check = odoR.value()?.let { Odometer.check(it, at, timeline, excludeId = initial?.id) } ?: OdometerCheck.Ok
    val ok = amountR.value() != null && odoR !is FieldResult.Error && (check == OdometerCheck.Ok || reset)

    FormDialog(if (initial == null) "Gasto" else "Editar gasto", ok, onDismiss, {
        val now = System.currentTimeMillis()
        val entry = ExpenseEntry(
            id = initial?.id ?: UUID.randomUUID().toString(),
            atMs = at,
            type = type,
            amountMinor = amountR.value()!!,
            odometerKm = odoR.value(),
            createdAt = initial?.createdAt ?: now,
            updatedAt = now
        )
        onSave(entry, if (check is OdometerCheck.Lower && reset) entry.odometerKm else null)
    }) {
        listOf(ExpenseGroup.MAINTENANCE, ExpenseGroup.LOOSE).forEach { group ->
            Text(group.label, style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ExpenseType.entries.filter { it.group == group }.forEach { t ->
                    FilterChip(selected = type == t, onClick = { type = t }, label = { Text(t.label) })
                }
            }
        }
        NumberField("Importe", amount, { amount = it }, amountR.error(), "RD$")
        if (maintenance) {
            NumberField("Odómetro", odometer, { odometer = it; reset = false }, odoR.error(), "km", decimal = false,
                supporting = "Sin odómetro no hay recordatorio")
            ResetQuestion(check, reset) { reset = it }
        }
        DateField("Fecha", date) { date = it }
    }
}

/** A fixed cost, spread per day. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecurringDialog(initial: RecurringCost?, onDismiss: () -> Unit, onSave: (RecurringCost) -> Unit) {
    var type by rememberSaveable { mutableStateOf(initial?.type ?: RecurringType.BIKE_PAYMENT) }
    var amount by rememberSaveable { mutableStateOf(initial?.let { minorText(it.amountMinor) } ?: "") }
    var frequency by rememberSaveable { mutableStateOf(initial?.frequency ?: Frequency.MONTHLY) }
    var starts by rememberSaveable { mutableStateOf(initial?.startsOn ?: LocalDate.now().withDayOfMonth(1)) }
    var ends by rememberSaveable { mutableStateOf(initial?.endsOn) }

    val amountR = ExpenseInput.amount(amount)
    val endsOk = ends?.let { !it.isBefore(starts) } ?: true
    FormDialog(if (initial == null) "Gasto fijo" else "Editar gasto fijo", amountR.value() != null && endsOk, onDismiss, {
        val now = System.currentTimeMillis()
        onSave(
            RecurringCost(
                id = initial?.id ?: UUID.randomUUID().toString(),
                type = type,
                amountMinor = amountR.value()!!,
                frequency = frequency,
                startsOn = starts,
                endsOn = ends,
                createdAt = initial?.createdAt ?: now,
                updatedAt = now
            )
        )
    }) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RecurringType.entries.forEach { t -> FilterChip(selected = type == t, onClick = { type = t }, label = { Text(t.label) }) }
        }
        NumberField("Importe", amount, { amount = it }, amountR.error(), "RD$")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Frequency.entries.forEach { f -> FilterChip(selected = frequency == f, onClick = { frequency = f }, label = { Text(f.label) }) }
        }
        Text(
            when (frequency) {
                Frequency.MONTHLY -> "Se reparte entre los días de cada mes."
                Frequency.BIWEEKLY -> "Se reparte entre 15 días."
                Frequency.WEEKLY -> "Se reparte entre 7 días."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        DateField("Desde", starts) { starts = it }
        val end = ends
        if (end == null) {
            TextButton(onClick = { ends = starts.plusMonths(1) }) { Text("Ponerle fecha de fin") }
        } else {
            DateField("Hasta", end) { ends = it }
            if (!endsOk) Text("La fecha de fin es antes del inicio.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { ends = null }) { Text("Sin fecha de fin") }
        }
    }
}

/** An odometer reading on its own, to keep reminders up to date without fueling. */
@Composable
fun OdometerDialog(initial: OdometerReading?, timeline: List<OdometerPoint>, onDismiss: () -> Unit, onSave: (OdometerReading) -> Unit) {
    var date by rememberSaveable { mutableStateOf(dateOf(initial?.atMs)) }
    var km by rememberSaveable { mutableStateOf(initial?.km?.toString() ?: "") }
    var reset by rememberSaveable { mutableStateOf(initial?.isReset ?: false) }
    val kmR = ExpenseInput.odometer(km, required = true)
    val at = atOf(date, initial?.atMs)
    val check = kmR.value()?.let { Odometer.check(it, at, timeline, excludeId = initial?.id) } ?: OdometerCheck.Ok
    val ok = kmR.value() != null && (check == OdometerCheck.Ok || reset)
    FormDialog(if (initial == null) "Odómetro" else "Editar odómetro", ok, onDismiss, {
        val now = System.currentTimeMillis()
        onSave(OdometerReading(initial?.id ?: UUID.randomUUID().toString(), at, kmR.value()!!, reset, initial?.createdAt ?: now, now))
    }) {
        NumberField("Odómetro", km, { km = it }, kmR.error(), "km", decimal = false)
        if (check is OdometerCheck.Lower) {
            ResetQuestion(check, reset) { reset = it }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = reset, onCheckedChange = { reset = it })
                Text("Cambié de odómetro o de motor (empieza una cuenta nueva)", style = MaterialTheme.typography.bodySmall)
            }
        }
        DateField("Fecha", date) { date = it }
    }
}
