package com.ubers.observador.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
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
import com.ubers.observador.domain.sectors.CareSector
import com.ubers.observador.domain.sectors.SectorLevel
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
 * («Evitar») band. Nothing is closed or touched in Uber here.
 */
@Composable
fun SectorsSection(viewModel: SectorsViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    var dialogFor by remember { mutableStateOf<CareSector?>(null) }
    var adding by remember { mutableStateOf(false) }

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
                    "Escribe el sector como lo pone Uber, por ejemplo «Los Alcarrizos» o «Santo Domingo Este».",
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

    if (adding || dialogFor != null) {
        SectorDialog(
            initial = dialogFor,
            suggestions = state.suggestions,
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
            Text(sector.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                "${levelLabel(sector.level)} · ${sector.where.label}",
                style = MaterialTheme.typography.bodySmall,
                color = if (sector.level == SectorLevel.WARN) WARN_COLOR else AVOID_COLOR
            )
        }
        IconButton(onClick = onDelete) {
            Icon(painterResource(R.drawable.ic_delete), contentDescription = "Quitar ${sector.name}")
        }
    }
}

/** «Cerrar» only exists from v1.37.0 on; until then a stored «Cerrar» is shown and treated as «Evitar». */
private fun levelLabel(level: SectorLevel): String = if (level == SectorLevel.CLOSE) "Evitar" else level.label

@Composable
private fun SectorDialog(
    initial: CareSector?,
    suggestions: List<String>,
    onSave: (CareSector) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var where by remember { mutableStateOf(initial?.where ?: SectorWhere.BOTH) }
    var level by remember { mutableStateOf(if (initial?.level == SectorLevel.CLOSE) SectorLevel.AVOID else initial?.level ?: SectorLevel.WARN) }
    val valid = name.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Añadir sector" else "Editar sector") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                Text("Dónde mirar", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectorWhere.entries.forEach { w ->
                        AppFilterChip(selected = where == w, onClick = { where = w }, label = { Text(w.label) })
                    }
                }
                Text("Nivel", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(SectorLevel.WARN, SectorLevel.AVOID).forEach { l ->
                        AppFilterChip(selected = level == l, onClick = { level = l }, label = { Text(l.label) })
                    }
                }
                Text(
                    if (level == SectorLevel.WARN) "Franja naranja en la tarjeta y aviso en la notificación."
                    else "Franja roja, aviso en la notificación y 3 vibraciones cortas.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onSave(CareSector(name, where, level)) }) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}
