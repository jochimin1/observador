package com.ubers.observador.ui.earnings

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
import com.ubers.observador.R
import com.ubers.observador.domain.WorkdayClock
import com.ubers.observador.domain.distance.DistanceFormat
import com.ubers.observador.domain.money.MoneyAmount
import com.ubers.observador.domain.money.MoneyFormat
import com.ubers.observador.domain.operating.*
import com.ubers.observador.ui.components.SectionCard
import com.ubers.observador.ui.components.SheetGroup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.LocalDate

private fun rd(n: Long) = MoneyFormat.format(MoneyAmount(n, "DOP"))
private fun decimal(n: Long, places: Int = 2) = BigDecimal.valueOf(n, places).toPlainString()

@Composable
fun OperatingPanel(report: OperatingReport?, models: List<OperatingCostModel>, clock: WorkdayClock,
    onSave: suspend (OperatingCostInput) -> String?) {
    var editing by remember { mutableStateOf(false) }
    SectionCard(title = "Rentabilidad operativa", icon = R.drawable.ic_payments,
        subtitle = "Estimación por consumo y uso · distinta del neto por gastos registrados") {
        if (report == null) Text("No se pudo calcular la rentabilidad de este periodo.")
        else {
            Text("Beneficio estimado: ${report.profitMinor?.let(::rd) ?: "no calculable"}", style = MaterialTheme.typography.titleLarge)
            Text("Ingreso confirmado: ${rd(report.incomeMinor)}")
            Text("${if (report.issues.isEmpty()) "Costos operativos" else "Subtotal de costos calculables"}: ${rd(report.costs.total)}")
            OperatingBreakdown(report.costs)
            Text("De esos costos, ${rd(report.costs.total - report.unallocated.total)} se asignan a viajes y ${rd(report.unallocated.total)} quedan sin asignar a un viaje.", style = MaterialTheme.typography.bodySmall)
            Text("GPS: ${report.gpsCoveragePct?.let { "$it % del tiempo confirmado" } ?: "sin cobertura verificable"}. Km operativos atribuidos: ${DistanceFormat.km(report.distanceMm)}.", style = MaterialTheme.typography.bodySmall)
            if (report.profitMinor != null) {
                Text("Por hora en línea: ${report.perOnlineHour?.let(::rd) ?: "sin tiempo suficiente"}")
                Text("Por hora comprometida: ${report.perCommittedHour?.let(::rd) ?: "sin tiempo suficiente"}")
                Text("Por km operativo: ${report.perKm?.let(::rd) ?: "sin km suficientes"}")
            }
            report.issues.forEach { Text("• ${it.label}", style = MaterialTheme.typography.bodySmall) }
            if (report.revisions.isNotEmpty()) Text("Versiones de costos usadas: ${report.revisions.sorted().joinToString { "#$it" }}", style = MaterialTheme.typography.bodySmall)
        }
        Text("Distancias y tiempos medidos; parámetros introducidos por ti; costos y beneficio estimados. Los costos usan los km completos de viajes cerrados y los km vacíos medidos del periodo. Los huecos de GPS no se convierten en una distancia completa.", style = MaterialTheme.typography.bodySmall)
        Text("Los fijos se reparten por duración de los viajes cerrados y el resto del tiempo confirmado. Se conservan aparte los costos sin viaje. Combustible, mantenimiento pagado y recurrentes de Gastos no se descuentan otra vez aquí; los gastos sueltos sí, con el porcentaje laboral de su versión de costos.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { editing = true }) { Text("Modelo de costos") }
    }
    if (editing) OperatingModelDialog(models, clock, onSave) { editing = false }
}

@Composable
fun TripOperatingPanel(result: OperatingTripResult?) {
    SheetGroup {
        Text("Rentabilidad operativa estimada", style = MaterialTheme.typography.titleMedium)
        if (result == null) Text("Disponible al cerrar el viaje y disponer de datos para calcularlo.")
        else {
            Text("Beneficio asignado: ${result.profitMinor?.let(::rd) ?: "no calculable"}", style = MaterialTheme.typography.titleMedium)
            Text("Ingreso: ${result.incomeMinor?.let(::rd) ?: "sin confirmar"}")
            OperatingBreakdown(result.cost)
            Text("Parámetros de distancia: ${result.modelRevision?.let { "versión #$it" } ?: "sin modelo vigente"}. Fijos de la jornada de cierre: ${result.fixedRevision?.let { "versión #$it" } ?: "sin modelo"}.", style = MaterialTheme.typography.bodySmall)
            result.issues.forEach { Text("• ${it.label}", style = MaterialTheme.typography.bodySmall) }
            Text("Incluye recogida y viaje medidos. Los costos vacíos y gastos sueltos sin vínculo se muestran en Ganancias; no se añaden de nuevo al total del periodo.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun OperatingBreakdown(p: OperatingCostParts) {
    Text("Combustible consumido: ${rd(p.fuel)}\nReserva de mantenimiento: ${rd(p.maintenance)}\nDepreciación: ${rd(p.depreciation)}\nFijos asignados: ${rd(p.fixed)}\nGastos sueltos laborales: ${rd(p.loose)}", style = MaterialTheme.typography.bodySmall)
}

@Composable
internal fun OperatingModelDialog(models: List<OperatingCostModel>, clock: WorkdayClock,
    onSave: suspend (OperatingCostInput) -> String?, onDismiss: () -> Unit) {
    val last = models.maxByOrNull { it.effectiveAtMs }
    var price by remember { mutableStateOf(last?.fuelPriceMinor?.let { decimal(it) }.orEmpty()) }
    var efficiency by remember { mutableStateOf(last?.kmPerGallonMilli?.let { decimal(it, 3) }.orEmpty()) }
    var wear by remember { mutableStateOf(decimal(last?.maintenancePerKmMinor ?: 0)) }
    var depreciation by remember { mutableStateOf(decimal(last?.depreciationPerKmMinor ?: 0)) }
    var fixed by remember { mutableStateOf(decimal(last?.fixedMinor ?: 0)) }
    var basis by remember { mutableStateOf(last?.fixedBasis ?: FixedCostBasis.WORKDAY) }
    var share by remember { mutableStateOf((last?.looseWorkSharePct ?: 100).toString()) }
    val today = clock.workdayOf(System.currentTimeMillis())
    var date by remember { mutableStateOf(if (last == null) today.toString() else maxOf(today.plusDays(1), runCatching { LocalDate.parse(last.effectiveDay).plusDays(1) }.getOrDefault(today.plusDays(1))).toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val input = OperatingCostInput(price, efficiency, wear, depreciation, fixed, basis, share, date)
    val scroll = rememberScrollState()
    LaunchedEffect(error) {
        if (error != null) { withFrameNanos { }; scroll.animateScrollTo(scroll.maxValue) }
    }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(dismissOnBackPress = !busy, dismissOnClickOutside = !busy),
        title = { Text("Modelo de costos") },
        text = { Column(Modifier.verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Sin separadores de miles. Estos parámetros son estimaciones introducidas por ti; no se aplican valores sugeridos automáticamente.")
            OutlinedTextField(price, { price = it }, enabled = !busy, label = { Text("Precio RD$/galón") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OutlinedTextField(efficiency, { efficiency = it }, enabled = !busy, label = { Text("Rendimiento km/galón") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OutlinedTextField(wear, { wear = it }, enabled = !busy, label = { Text("Reserva mantenimiento RD$/km") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OutlinedTextField(depreciation, { depreciation = it }, enabled = !busy, label = { Text("Depreciación RD$/km (0 = no incluir)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OutlinedTextField(fixed, { fixed = it }, enabled = !busy, label = { Text("Costo fijo laboral RD$") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            FixedCostBasis.entries.filter { it != FixedCostBasis.UNKNOWN }.forEach { option -> FilterChip(selected = basis == option, enabled = !busy, onClick = { basis = option }, label = { Text(option.label) }) }
            Text("Un fijo por jornada se carga una vez cuando hay actividad registrada. Por hora se carga sobre el tiempo en línea confirmado. Introduce la parte laboral; evita incluir costos que ya indicaste por km.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(share, { share = it }, enabled = !busy, label = { Text("Uso laboral de gastos sueltos (%)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(date, { date = it }, enabled = !busy, label = { Text("Vigente desde AAAA-MM-DD") }, singleLine = true)
            Text(if (last == null) "La configuración inicial puede aplicarse desde el inicio de la jornada de hoy. Al guardar confirmas esa vigencia; las jornadas anteriores quedan sin modelo." else "Cada cambio crea otra versión para una jornada futura. No modifica las anteriores ni permite reescribir su vigencia.", style = MaterialTheme.typography.bodySmall)
            if (models.isNotEmpty()) {
                Text("Historial de parámetros", style = MaterialTheme.typography.titleSmall)
                models.sortedByDescending { it.revision }.forEach { m ->
                    Text("#${m.revision} · desde ${m.effectiveDay} · introducido por ti\n${rd(m.fuelPriceMinor)}/gal · ${decimal(m.kmPerGallonMilli, 3)} km/gal\nMantenimiento ${rd(m.maintenancePerKmMinor)}/km · depreciación ${rd(m.depreciationPerKmMinor)}/km\nFijos ${rd(m.fixedMinor)} ${m.fixedBasis.label.lowercase()} · gastos sueltos ${m.looseWorkSharePct} %", style = MaterialTheme.typography.bodySmall)
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        } },
        confirmButton = { TextButton(enabled = input.values() != null && !busy, onClick = { scope.launch {
            busy = true
            try { error = onSave(input); if (error == null) onDismiss() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "No se pudo guardar el modelo. Vuelve a intentarlo." }
            finally { busy = false }
        } }) { Text("Guardar nueva versión") } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancelar") } })
}
