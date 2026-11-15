package com.ubers.observador.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ubers.observador.R
import com.ubers.observador.domain.OverlayField
import com.ubers.observador.domain.OverlayLayout
import com.ubers.observador.domain.OverlayPosition
import com.ubers.observador.domain.OverlayTextSize
import com.ubers.observador.domain.SettingsGroup
import com.ubers.observador.domain.SettingsIndex
import com.ubers.observador.ui.components.AppFilterChip
import com.ubers.observador.ui.components.SectionCard
import com.ubers.observador.ui.theme.appFieldColors
import com.ubers.observador.ui.theme.appFieldShape

private fun iconOf(g: SettingsGroup): Int = when (g) {
    SettingsGroup.READING -> R.drawable.ic_layers
    SettingsGroup.PROFIT -> R.drawable.ic_tune
    SettingsGroup.VEHICLE -> R.drawable.ic_two_wheeler
    SettingsGroup.WORKDAY -> R.drawable.ic_calendar_month
    SettingsGroup.PRIVACY -> R.drawable.ic_download
    SettingsGroup.APPEARANCE -> R.drawable.ic_contrast
}

/** 1.50.0: one group of Ajustes in the menu; opens its own screen. */
@Composable
internal fun GroupCard(group: SettingsGroup, onClick: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth().clickable(onClickLabel = "Abrir ${group.label}", role = Role.Button, onClick = onClick)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Decorative: the title says it
            Icon(painterResource(iconOf(group)), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(group.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                Text(group.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 1.50.0: finds a setting by name or by other words, and opens its group. */
@Composable
internal fun SettingsSearch(onOpen: (SettingsGroup) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    OutlinedTextField(
        value = query,
        onValueChange = { query = it.take(60) },
        label = { Text("Buscar un ajuste") },
        placeholder = { Text("Ej.: gasolina, meta, tarjeta, respaldo") },
        singleLine = true,
        trailingIcon = {
            if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                Icon(painterResource(R.drawable.ic_remove), contentDescription = "Borrar búsqueda")
            }
        },
        colors = appFieldColors(),
        shape = appFieldShape(),
        modifier = Modifier.fillMaxWidth()
    )
    if (query.isBlank()) return
    val results = SettingsIndex.search(query)
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 4.dp)) {
            if (results.isEmpty()) {
                Text("Ningún ajuste coincide con «$query».", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(14.dp))
            }
            results.take(8).forEachIndexed { i, e ->
                if (i > 0) HorizontalDivider()
                Column(
                    Modifier.fillMaxWidth().clickable(onClickLabel = "Abrir ${e.group.label}", role = Role.Button) { onOpen(e.group) }
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Text(e.title, style = MaterialTheme.typography.bodyLarge)
                    Text("En ${e.group.label}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** 1.50.0: which figures the card over Uber shows, in which order, where and how big. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OverlayLayoutCard(layout: OverlayLayout, onChange: (OverlayLayout) -> Unit) {
    SectionCard(title = "Cifras de la tarjeta", icon = R.drawable.ic_layers, subtitle = "${layout.fields.size} de ${OverlayLayout.MAX_FIELDS} cifras") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Elige de ${OverlayLayout.MIN_FIELDS} a ${OverlayLayout.MAX_FIELDS} cifras. Salen en este orden; usa las flechas para moverlas. " +
                    "La franja de sector y el color con su etiqueta siempre se muestran.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            layout.fields.forEachIndexed { i, f ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}. ${f.label}", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = { onChange(layout.move(f, -1)) }, enabled = i > 0, modifier = Modifier.semantics { contentDescription = "Subir ${f.label}" }) {
                        Text("↑", style = MaterialTheme.typography.titleMedium)
                    }
                    IconButton(onClick = { onChange(layout.move(f, 1)) }, enabled = i < layout.fields.lastIndex, modifier = Modifier.semantics { contentDescription = "Bajar ${f.label}" }) {
                        Text("↓", style = MaterialTheme.typography.titleMedium)
                    }
                    IconButton(onClick = { onChange(layout.toggle(f)) }, enabled = layout.fields.size > OverlayLayout.MIN_FIELDS, modifier = Modifier.semantics { contentDescription = "Quitar ${f.label}" }) {
                        Icon(painterResource(R.drawable.ic_remove), contentDescription = null)
                    }
                }
            }
            val missing = OverlayField.entries.filter { it !in layout.fields }
            if (missing.isNotEmpty()) {
                Text("Añadir", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    missing.forEach { f ->
                        AppFilterChip(
                            selected = false,
                            onClick = { if (layout.fields.size < OverlayLayout.MAX_FIELDS) onChange(layout.toggle(f)) },
                            label = { Text("+ ${f.label}") }
                        )
                    }
                }
                if (layout.fields.size >= OverlayLayout.MAX_FIELDS) {
                    Text("Quita una para añadir otra.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider()
            Text("Posición de la tarjeta", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OverlayPosition.entries.forEach { p ->
                    AppFilterChip(selected = layout.position == p, onClick = { onChange(layout.copy(position = p)) }, label = { Text(p.label) })
                }
            }
            if (layout.position != OverlayPosition.TOP) {
                Text(
                    "En el centro o abajo la tarjeta puede tapar botones de Uber, como «Aceptar». Tocarla la cierra, no acepta. Pruébala antes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Text("Tamaño de letra de la tarjeta", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OverlayTextSize.entries.forEach { t ->
                    AppFilterChip(selected = layout.textSize == t, onClick = { onChange(layout.copy(textSize = t)) }, label = { Text(t.label) })
                }
            }
            Text(
                "Usa «Probar tarjeta» abajo para verla con estos cambios antes de salir a trabajar.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
