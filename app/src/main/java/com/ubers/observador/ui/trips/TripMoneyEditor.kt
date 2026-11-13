package com.ubers.observador.ui.trips

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.ubers.observador.domain.earnings.EarningsFormat
import com.ubers.observador.domain.finance.*
import com.ubers.observador.domain.money.MoneyAmount
import com.ubers.observador.domain.money.MoneyFormat
import com.ubers.observador.domain.trips.Trip
import com.ubers.observador.ui.components.SheetGroup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

@Composable
internal fun TripMoneyEditor(
    trip: Trip, summary: TripFinancialSummary, observations: List<FinancialObservation>,
    onSave: suspend (ManualMoneyRequest) -> MoneyEditResult,
    onVoid: (String) -> Unit, onUndo: (String) -> Unit
) {
    var editing by remember(trip.id) { mutableStateOf(false) }
    var target by remember(trip.id) { mutableStateOf<FinancialObservation?>(null) }
    var voidTarget by remember { mutableStateOf<FinancialObservation?>(null) }
    val history = observations.distinctBy { it.observationId }
    val liveIds = ManualMoney.live(history).map { it.observationId }.toSet()
    SheetGroup {
        Text("Dinero del viaje", style = MaterialTheme.typography.titleMedium)
        Text(MoneyExplanation.reason(summary), style = MaterialTheme.typography.bodySmall)
        Text("Los pagos tardíos se registran cuando los anotas y se atribuyen al periodo en que terminó este viaje. El cierre del viaje y la confirmación del dinero son independientes.", style = MaterialTheme.typography.bodySmall)
        val collected = MoneyExplanation.collections(history)
        Text("Cobros registrados: efectivo ${MoneyFormat.format(MoneyAmount(collected.cash, "DOP"))} · digital ${MoneyFormat.format(MoneyAmount(collected.digital, "DOP"))}", style = MaterialTheme.typography.bodySmall)
        Text("Los cobros no se suman al ingreso. El saldo a transferir por Uber requiere su estado de cuenta; no se deduce de estos cobros.", style = MaterialTheme.typography.bodySmall)
        if (!trip.status.isOpen) {
            OutlinedButton(onClick = { target = null; editing = true }) { Text("Registrar dinero") }
            val undo = history.filter { it.source == FinancialSource.MANUAL &&
                (ManualMoney.ENTRY in it.evidence || ManualMoney.ACTION in it.evidence) &&
                it.evidence.none { code -> code.startsWith(ManualMoney.UNDO) } }
                .sortedByDescending { it.observedAtMs }.firstNotNullOfOrNull { row ->
                    ManualMoney.command(row)?.takeIf { command -> ManualMoney.undoRows(trip, command, history, "preview", System.currentTimeMillis()) != null }
                }
            if (undo != null) TextButton(onClick = { onUndo(undo) }) { Text("Deshacer último cambio de dinero") }
        } else Text("Puedes registrar dinero al cerrar el viaje.", style = MaterialTheme.typography.bodySmall)
        if (history.isNotEmpty()) Text("Historial de importes", style = MaterialTheme.typography.titleSmall)
        val date = DateTimeFormatter.ofPattern("d MMM yyyy · HH:mm").withZone(ZoneId.systemDefault())
        history.sortedByDescending { it.observedAtMs }.forEach { row ->
            val active = row.observationId in liveIds
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("${EarningsFormat.typeLabel(row.type)} · ${MoneyFormat.format(row.amount)}", style = MaterialTheme.typography.bodyMedium)
                Text("${date.format(Instant.ofEpochMilli(row.observedAtMs))} · ${if (row.source == FinancialSource.MANUAL) "introducido por ti" else if (row.source == FinancialSource.TEST) "prueba" else "pantalla de Uber"}" +
                    if (row.type == FinancialObservationType.VOID) " · anulación" else if (!active) " · anulada" else " · vigente", style = MaterialTheme.typography.bodySmall)
                if (row.type != FinancialObservationType.VOID) Text(when {
                    row.type.isCollection -> "Cobro separado del ingreso"
                    row.semantics == AmountSemantics.ABSOLUTE_TOTAL -> "Total confirmado"
                    row.semantics == AmountSemantics.COMPONENT -> "Incluido en el total; no se suma"
                    else -> "Adicional al total anterior; un total posterior puede incluirlo"
                }, style = MaterialTheme.typography.bodySmall)
                row.evidence.firstOrNull { it.startsWith(ManualMoney.RECORDED_AT) }?.removePrefix(ManualMoney.RECORDED_AT)?.toLongOrNull()?.let {
                    Text("Restaurado por ti el ${date.format(Instant.ofEpochMilli(it))}; conserva la fecha y fuente originales.", style = MaterialTheme.typography.bodySmall)
                }
                if (active && !trip.status.isOpen && row.type in ManualMoney.types) Row {
                    TextButton(onClick = { target = row; editing = true }) { Text("Corregir") }
                    TextButton(onClick = { voidTarget = row }) { Text("Anular") }
                }
            }
        }
    }
    if (editing) MoneyEntryDialog(trip, summary, history, target, onSave) { editing = false }
    voidTarget?.let { row -> AlertDialog(onDismissRequest = { voidTarget = null },
        title = { Text("¿Anular este importe?") }, text = { Text("Se conservará en el historial. El dinero del viaje se volverá a calcular sin este importe; podrás deshacer la anulación.") },
        confirmButton = { TextButton(onClick = { onVoid(row.observationId); voidTarget = null }) { Text("Anular importe") } },
        dismissButton = { TextButton(onClick = { voidTarget = null }) { Text("Cancelar") } }) }
}

@Composable
private fun MoneyEntryDialog(trip: Trip, before: TripFinancialSummary, history: List<FinancialObservation>, original: FinancialObservation?,
    onSave: suspend (ManualMoneyRequest) -> MoneyEditResult, onDismiss: () -> Unit) {
    val id = remember { UUID.randomUUID().toString() }
    var type by remember { mutableStateOf(original?.type ?: FinancialObservationType.TRIP_TOTAL) }
    var semantics by remember { mutableStateOf(original?.semantics ?: AmountSemantics.ABSOLUTE_TOTAL) }
    var amount by remember { mutableStateOf(original?.amount?.toDecimal()?.let { if (type == FinancialObservationType.COMMISSION) it.abs() else it }?.toPlainString().orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val request = ManualMoneyRequest(id, type, semantics, amount, original?.observationId)
    val validation = ManualMoney.validate(trip, request)
    val current = original ?: if (type == FinancialObservationType.TRIP_TOTAL) ManualMoney.live(history)
        .filter { it.semantics == AmountSemantics.ABSOLUTE_TOTAL }
        .maxWithOrNull(compareBy<FinancialObservation> { it.source == FinancialSource.MANUAL && ManualMoney.ENTRY in it.evidence }.thenBy { it.observedAtMs }) else null
    val after = if (validation == null) TripFinancialAggregator.aggregate(trip.id, before.offeredAmount,
        history + ManualMoney.rows(trip, request, current, maxOf(System.currentTimeMillis(), (history.maxOfOrNull { it.observedAtMs } ?: 0) + 1))) else null
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(dismissOnBackPress = !busy, dismissOnClickOutside = !busy),
        title = { Text(if (original == null) "Registrar dinero" else "Corregir importe") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (original == null) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ManualMoney.types.forEach { option -> FilterChip(selected = type == option, enabled = !busy, onClick = {
                    type = option; semantics = if (option == FinancialObservationType.TRIP_TOTAL) AmountSemantics.ABSOLUTE_TOTAL else AmountSemantics.COMPONENT
                }, label = { Text(EarningsFormat.typeLabel(option)) }) }
            } else Text(EarningsFormat.typeLabel(type))
            OutlinedTextField(value = amount, onValueChange = { amount = it; error = null }, enabled = !busy, singleLine = true,
                label = { Text("Importe en RD$") }, supportingText = { Text(if (type == FinancialObservationType.COMMISSION) "La comisión se descuenta. Escribe su valor positivo." else "Sin separadores de miles. Hasta dos decimales.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            if (type != FinancialObservationType.TRIP_TOTAL && !type.isCollection) {
                Text("¿Cómo se relaciona con el total?", style = MaterialTheme.typography.titleSmall)
                FilterChip(selected = semantics == AmountSemantics.COMPONENT, enabled = !busy, onClick = { semantics = AmountSemantics.COMPONENT }, label = { Text("Ya incluido en el total") })
                FilterChip(selected = semantics == AmountSemantics.DELTA, enabled = !busy, onClick = { semantics = AmountSemantics.DELTA }, label = { Text("Adicional al total anterior") })
            }
            if (type == FinancialObservationType.TRIP_TOTAL) Text("El total incluye todo lo cobrado como ingreso. Sustituye el anterior y absorbe los pagos adicionales ya registrados; no vuelvas a sumar una propina incluida. Un total introducido por ti prevalece sobre nuevas lecturas de Uber hasta que lo anules o corrijas.", style = MaterialTheme.typography.bodySmall)
            if (type.isCollection) Text("Solo registra la forma de cobro. No confirma ni incrementa el ingreso.", style = MaterialTheme.typography.bodySmall)
            fun total(s: TripFinancialSummary) = s.effectiveEarnings?.let { MoneyFormat.format(it) } ?: "pendiente de total"
            Text("Ingreso antes: ${total(before)}\nIngreso después: ${after?.let { total(it) } ?: "revisa el importe"}", style = MaterialTheme.typography.bodyMedium)
            if (after != null) Text(MoneyExplanation.reason(after), style = MaterialTheme.typography.bodySmall)
            (error ?: validation?.takeIf { amount.isNotBlank() })?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        } },
        confirmButton = { TextButton(enabled = validation == null && !busy, onClick = { scope.launch {
            busy = true
            try { when (val result = onSave(request)) {
                is MoneyEditResult.Saved -> onDismiss()
                is MoneyEditResult.Rejected -> error = result.reason
            } } catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "No se pudo guardar. Vuelve a intentarlo." }
            finally { busy = false }
        } }) { Text("Confirmar y guardar") } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancelar") } })
}
