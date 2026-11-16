package com.ubers.observador.ui.settings

import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ubers.observador.R
import kotlinx.coroutines.launch
import com.ubers.observador.domain.sectors.CareSector
import com.ubers.observador.domain.sectors.CareSectorCodec
import com.ubers.observador.domain.sectors.OfferPlaceKind
import com.ubers.observador.domain.sectors.SectorLevel
import com.ubers.observador.domain.sectors.SectorMatcher
import com.ubers.observador.domain.sectors.SectorSchedule
import com.ubers.observador.domain.sectors.SectorWhere
import com.ubers.observador.ui.components.AppFilterChip
import com.ubers.observador.ui.components.IconSectionHeader
import com.ubers.observador.ui.components.SectionCard
import com.ubers.observador.ui.theme.appFieldColors
import com.ubers.observador.ui.theme.appFieldShape

private val WARN_COLOR = Color(0xFFE65100)
private val AVOID_COLOR = Color(0xFFC62828)

/**
 * 1.35.0: Ajustes › Sectores de cuidado. The driver lists the sectors to watch; each offer's pickup
 * and drop-off are compared with them and the floating card shows an orange («Aviso») or red
 * («Evitar») band. «Cerrar» (1.37.0) closes the card only in the «Automático» mode.
 * 1.47.0: other names, exceptions, schedule, modality, on/off and «Probar reglas».
 */
@Composable
fun SectorsSection(viewModel: SectorsViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    var dialogFor by rememberSaveable(stateSaver = androidx.compose.runtime.saveable.Saver<CareSector?, String>(
        save = { it?.let { sector -> com.ubers.observador.domain.sectors.CareSectorCodec.encode(listOf(sector)) } ?: "" },
        restore = { com.ubers.observador.domain.sectors.CareSectorCodec.decode(it)?.firstOrNull() }
    )) { mutableStateOf<CareSector?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var testing by rememberSaveable { mutableStateOf(false) }

    IconSectionHeader("Sectores de cuidado", R.drawable.ic_warning)
    SectionCard(
        title = "Avisar por sectores",
        icon = R.drawable.ic_warning,
        subtitle = if (state.sectors.isEmpty()) "Sin sectores" else
            "${state.sectors.size} " + if (state.sectors.size == 1) "sector" else "sectores",
        action = { Switch(checked = state.enabled, onCheckedChange = { viewModel.setEnabled(it) }) }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Cuando la recogida o el destino de una oferta está en uno de estos sectores, la tarjeta " +
                    "flotante lo dice arriba: naranja para «Aviso», roja y con vibración para «Evitar». " +
                    "Escribe el sector como lo pone Uber, por ejemplo «Los Alcarrizos» o «Santo Domingo Este». " +
                    "Cada sector puede tener otros nombres, excepciones, un horario y una modalidad.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (state.sectors.isEmpty()) {
                Text("Todavía no tienes sectores.", style = MaterialTheme.typography.bodyMedium)
            }
            state.sectors.forEachIndexed { i, s ->
                if (i > 0) HorizontalDivider()
                SectorRow(s, onEdit = { dialogFor = s }, onDelete = { viewModel.remove(s) })
            }
            // 1.37.0: what «Automático» closed (or would close) today
            if (state.closeLog.isNotEmpty()) {
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Cierres de hoy", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = { viewModel.refreshCloseLog() }) { Text("Actualizar") }
                }
                state.closeLog.forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // 1.56.0: evidence to turn the «Automático» close from simulation into a real touch
            if (state.sectors.isNotEmpty()) {
                HorizontalDivider()
                CloseEvidenceBlock()
            }
            if (state.sectors.isNotEmpty()) {
                OutlinedButton(onClick = { testing = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(painterResource(R.drawable.ic_science), contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("  Probar reglas con una dirección")
                }
            }
            OutlinedButton(
                onClick = { adding = true },
                enabled = state.sectors.size < CareSector.MAX_SECTORS,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(painterResource(R.drawable.ic_add), contentDescription = null, modifier = Modifier.size(18.dp))
                Text("  Añadir sector")
            }
        }
    }

    if (testing) {
        SectorTester(state, viewModel, onDismiss = { testing = false })
    }

    if (adding || dialogFor != null) {
        SectorDialog(
            initial = dialogFor,
            suggestions = state.suggestions,
            products = state.products,
            onSave = { sector ->
                viewModel.save(sector, replacing = dialogFor)
                adding = false
                dialogFor = null
            },
            onDismiss = {
                adding = false
                dialogFor = null
            }
        )
    }
}

@Composable
private fun SectorRow(sector: CareSector, onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onEdit).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                sector.name + if (sector.enabled) "" else " (desactivado)",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (sector.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "${sector.level.label} · ${sector.where.label}",
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    !sector.enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                    sector.level == SectorLevel.WARN -> WARN_COLOR
                    else -> AVOID_COLOR
                }
            )
            ruleLines(sector).forEach {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        IconButton(onClick = onDelete) {
            Icon(painterResource(R.drawable.ic_delete), contentDescription = "Quitar ${sector.name}")
        }
    }
}

/** «También: Alcarrizos», «Excepto: Herrera», «L M X · 20:00–05:00», «Solo Moto». */
private fun ruleLines(s: CareSector): List<String> = listOfNotNull(
    s.aliases.takeIf { it.isNotEmpty() }?.let { "También: " + it.joinToString(", ") },
    s.exceptions.takeIf { it.isNotEmpty() }?.let { "Excepto: " + it.joinToString(", ") },
    s.schedule?.label,
    s.product?.let { "Solo $it" }
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SectorDialog(
    initial: CareSector?,
    suggestions: List<String>,
    products: List<String>,
    onSave: (CareSector) -> Unit,
    onDismiss: () -> Unit
) {
    var name by rememberSaveable { mutableStateOf(initial?.name ?: "") }
    var where by rememberSaveable { mutableStateOf(initial?.where ?: SectorWhere.BOTH) }
    var level by rememberSaveable { mutableStateOf(initial?.level ?: SectorLevel.WARN) }
    var aliases by rememberSaveable { mutableStateOf(initial?.aliases?.joinToString("; ") ?: "") }
    var exceptions by rememberSaveable { mutableStateOf(initial?.exceptions?.joinToString("; ") ?: "") }
    var scheduled by rememberSaveable { mutableStateOf(initial?.schedule != null) }
    var days by rememberSaveable { mutableStateOf(initial?.schedule?.days ?: emptySet()) }
    var from by rememberSaveable { mutableStateOf(initial?.schedule?.fromMin?.let { SectorSchedule.hhmm(it) } ?: "20:00") }
    var to by rememberSaveable { mutableStateOf(initial?.schedule?.toMin?.let { SectorSchedule.hhmm(it) } ?: "05:00") }
    var product by rememberSaveable { mutableStateOf(initial?.product) }
    var enabled by rememberSaveable { mutableStateOf(initial?.enabled ?: true) }
    val fromMin = SectorSchedule.parseTime(from)
    val toMin = SectorSchedule.parseTime(to)
    val valid = name.isNotBlank() && (!scheduled || (fromMin != null && toMin != null))
    val productChoices = (listOfNotNull(product) + products).distinctBy { SectorMatcher.words(it) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Añadir sector" else "Editar sector") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(CareSector.MAX_NAME) },
                    label = { Text("Sector") },
                    placeholder = { Text("Ej.: Hato Nuevo") },
                    singleLine = true,
                    colors = appFieldColors(),
                    shape = appFieldShape(),
                    modifier = Modifier.fillMaxWidth()
                )
                if (initial == null && suggestions.isNotEmpty()) {
                    Text("Vistos en tus ofertas", style = MaterialTheme.typography.labelMedium)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        suggestions.forEach { s ->
                            AppFilterChip(selected = name == s, onClick = { name = s }, label = { Text(s) })
                        }
                    }
                }
                OutlinedTextField(
                    value = aliases,
                    onValueChange = { aliases = it.take(250) },
                    label = { Text("Otros nombres (opcional)") },
                    placeholder = { Text("Ej.: Alcarrizos; Los Alcarrizos") },
                    supportingText = { Text("Sepáralos con punto y coma. Cualquiera de ellos avisa.") },
                    colors = appFieldColors(),
                    shape = appFieldShape(),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = exceptions,
                    onValueChange = { exceptions = it.take(250) },
                    label = { Text("Excepto (opcional)") },
                    placeholder = { Text("Ej.: Herrera; Las Caobas") },
                    supportingText = { Text("Si la dirección también dice uno de estos, no avisa.") },
                    colors = appFieldColors(),
                    shape = appFieldShape(),
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Dónde mirar", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectorWhere.entries.forEach { w ->
                        AppFilterChip(selected = where == w, onClick = { where = w }, label = { Text(w.label) })
                    }
                }
                Text("Nivel", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectorLevel.entries.forEach { l ->
                        AppFilterChip(selected = level == l, onClick = { level = l }, label = { Text(l.label) })
                    }
                }
                Text(
                    when (level) {
                        SectorLevel.WARN -> "Franja naranja en la tarjeta y aviso en la notificación."
                        SectorLevel.AVOID -> "Franja roja, aviso en la notificación y 3 vibraciones cortas."
                        SectorLevel.CLOSE -> "En modo Automático, Observador cierra la tarjeta con la X a los 3 segundos " +
                            "(toca la tarjeta flotante para impedirlo). En Observador y Asistido es igual que «Evitar»."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Solo en un horario", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    Switch(checked = scheduled, onCheckedChange = { scheduled = it })
                }
                if (scheduled) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        SectorSchedule.DAY_LETTERS.forEachIndexed { i, letter ->
                            val d = i + 1
                            AppFilterChip(
                                selected = d in days,
                                onClick = { days = if (d in days) days - d else days + d },
                                label = { Text(letter) }
                            )
                        }
                    }
                    Text(
                        if (days.isEmpty()) "Sin días marcados: todos los días." else "Solo los días marcados.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = from, onValueChange = { from = it.take(5) }, label = { Text("Desde") },
                            isError = fromMin == null, singleLine = true, colors = appFieldColors(), shape = appFieldShape(),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = to, onValueChange = { to = it.take(5) }, label = { Text("Hasta") },
                            isError = toMin == null, singleLine = true, colors = appFieldColors(), shape = appFieldShape(),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Text(
                        "Formato 24 h. De 20:00 a 05:00 cruza la medianoche y cuenta como el día en que empieza. " +
                            "La misma hora en los dos es todo el día.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text("Modalidad", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AppFilterChip(selected = product == null, onClick = { product = null }, label = { Text("Todas") })
                    productChoices.forEach { p ->
                        AppFilterChip(selected = product == p, onClick = { product = p }, label = { Text(p) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Activo", style = MaterialTheme.typography.labelLarge)
                        Text(
                            "Apagado se conserva en la lista sin avisar.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                onSave(
                    CareSector(
                        name = name,
                        where = where,
                        level = level,
                        aliases = CareSectorCodec.splitNames(aliases, CareSector.MAX_ALIASES),
                        exceptions = CareSectorCodec.splitNames(exceptions, CareSector.MAX_EXCEPTIONS),
                        schedule = if (scheduled && fromMin != null && toMin != null) SectorSchedule(days, fromMin, toMin) else null,
                        product = product,
                        enabled = enabled
                    )
                )
            }) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

/**
 * 1.47.0: Probar reglas. The driver writes or picks a real address; each sector says whether it
 * would warn now and why, with the same checks as the floating card.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SectorTester(state: SectorsUiState, viewModel: SectorsViewModel, onDismiss: () -> Unit) {
    var address by rememberSaveable { mutableStateOf(state.recentAddresses.firstOrNull() ?: "") }
    var place by rememberSaveable { mutableStateOf(OfferPlaceKind.DROPOFF) }
    var product by rememberSaveable { mutableStateOf(state.products.firstOrNull()) }
    val parts = SectorMatcher.displayParts(address)
    val results = remember(address, place, product, state.sectors) { viewModel.explain(address, place, product) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Probar reglas") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it.take(200) },
                    label = { Text("Dirección como la escribe Uber") },
                    colors = appFieldColors(),
                    shape = appFieldShape(),
                    modifier = Modifier.fillMaxWidth()
                )
                if (state.recentAddresses.isNotEmpty()) {
                    Text("De tus ofertas recientes", style = MaterialTheme.typography.labelMedium)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        state.recentAddresses.forEach { a ->
                            AppFilterChip(selected = address == a, onClick = { address = a }, label = { Text(a.take(40)) })
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OfferPlaceKind.entries.forEach { k ->
                        AppFilterChip(selected = place == k, onClick = { place = k }, label = { Text("Como ${k.label.lowercase()}") })
                    }
                }
                if (state.products.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        state.products.forEach { p ->
                            AppFilterChip(selected = product == p, onClick = { product = p }, label = { Text(p) })
                        }
                    }
                }
                Text(
                    "Partes que se comparan: " + if (parts.isEmpty()) "ninguna" else parts.joinToString(" · ") { "«$it»" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text("Con la hora de ahora:", style = MaterialTheme.typography.labelMedium)
                HorizontalDivider()
                results.forEach { e ->
                    Column {
                        Text(
                            e.sector.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = when {
                                !e.warns -> MaterialTheme.colorScheme.onSurface
                                e.sector.level == SectorLevel.WARN -> WARN_COLOR
                                else -> AVOID_COLOR
                            }
                        )
                        Text(verdictText(e.verdict), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } }
    )
}

private fun verdictText(v: SectorMatcher.Verdict): String = when (v) {
    is SectorMatcher.Verdict.Matched -> "Avisa: «${v.name}» está en la parte «${v.part}»."
    is SectorMatcher.Verdict.Excepted -> "No avisa: «${v.name}» está, pero la excepción «${v.exception}» también."
    SectorMatcher.Verdict.Disabled -> "No avisa: está desactivado."
    SectorMatcher.Verdict.OtherPlace -> "No avisa: este sector se mira solo en la otra dirección."
    is SectorMatcher.Verdict.OutOfSchedule -> "No avisa ahora: su horario es ${v.schedule.label}."
    is SectorMatcher.Verdict.OtherProduct -> "No avisa: es solo para ${v.product}."
    SectorMatcher.Verdict.NotFound -> "No está en esta dirección."
}


/**
 * 1.56.0: every «Cerrar» countdown writes Uber's whole screen and what Observador decided. With a
 * few of them exported, the real X can be confirmed and the close stops being a simulation.
 */
@Composable
private fun CloseEvidenceBlock() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var count by remember { mutableStateOf<Int?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        count = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.ubers.observador.service.CloseLogStore.evidenceCount(context) }
    }
    val exporter = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            message = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri, "w")?.use { com.ubers.observador.service.CloseLogStore.exportEvidence(context, it) }; "Evidencia exportada." }
                    .getOrElse { "No se pudo exportar: ${it.message}" }
            }
        }
    }
    Text("Cierre con la X: ${if (com.ubers.observador.domain.sectors.CloseTarget.VALIDATED) "activo" else "en simulación"}", style = MaterialTheme.typography.labelLarge)
    Text(if (com.ubers.observador.domain.sectors.CloseTarget.VALIDATED) "Observador toca la X de Uber en las ofertas de sectores «Cerrar» en modo Automático."
        else "Para activarlo hace falta confirmar dónde está la X de Uber. Con el modo Automático y un sector en «Cerrar», cada oferta de ese sector guarda aquí la pantalla de Uber y lo que Observador habría hecho, sin tocar nada. " +
            "Cuando tengas 3 o más, exporta la evidencia y envíamela.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("${count ?: "…"} ofertas grabadas", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        TextButton(enabled = (count ?: 0) > 0, onClick = { exporter.launch("observador-evidencia-cierres-${java.time.LocalDate.now()}.zip") }) { Text("Exportar evidencia") }
    }
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}
