package com.ubers.observador.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ubers.observador.R
import com.ubers.observador.domain.SettingsProfile
import com.ubers.observador.ui.components.SectionCard
import com.ubers.observador.domain.SettingsProfiles
import com.ubers.observador.domain.formatNumber

/** The master switch: same state as the dashboard bar and the notification button. */
@Composable
fun ObserverSwitchCard(enabled: Boolean, onChange: (Boolean) -> Unit) {
    SectionCard(
        title = if (enabled) "Observador activo" else "Observador en pausa",
        icon = if (enabled) R.drawable.ic_sensors else R.drawable.ic_pause_circle,
        badgeContainer = if (enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.tertiaryContainer,
        badgeContent = if (enabled) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onTertiaryContainer,
        action = { Switch(checked = enabled, onCheckedChange = onChange) }
    ) {
        Column {
            Text(
                text = "En pausa no se leen ofertas ni viajes: sin tarjeta, sin avisos y sin registros. " +
                    "Si pausas durante un viaje, ese viaje queda como interrumpido. También puedes " +
                    "pausar desde la notificación. Para apagar también el GPS y salir, usa «Apagar Observador» (el ícono de apagar en Inicio).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private sealed interface ProfileDialog {
    data object New : ProfileDialog
    data class Rename(val profile: SettingsProfile) : ProfileDialog
    data class Apply(val profile: SettingsProfile) : ProfileDialog
    data class Overwrite(val profile: SettingsProfile) : ProfileDialog
    data class Delete(val profile: SettingsProfile) : ProfileDialog
    data class Context(val profile: SettingsProfile) : ProfileDialog
}

/**
 * Saved sets of settings ("Día", "Noche", "Lluvia"...), applied in one tap instead of editing every
 * field again. A profile saves costs, minimums, score weights and cut-offs, and how offers are
 * shown; not the working-day start (it decides which day each offer belongs to).
 */
@Composable
fun ProfilesCard(
    state: ProfilesUiState,
    message: String?,
    onClearMessage: () -> Unit,
    onSaveNew: (String) -> Unit,
    onApply: (String) -> Unit,
    onOverwrite: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onContext: (String, com.ubers.observador.domain.ProfileContext) -> Unit = { _, _ -> }
) {
    var dialog by remember { mutableStateOf<ProfileDialog?>(null) }
    val focus = LocalFocusManager.current

    when (val d = dialog) {
        null -> Unit
        ProfileDialog.New -> NameDialog(
            title = "Guardar ajustes actuales",
            initial = "",
            problem = { SettingsProfiles.nameProblem(it, state.profiles) },
            onConfirm = { onSaveNew(it); dialog = null },
            onDismiss = { dialog = null }
        )
        is ProfileDialog.Rename -> NameDialog(
            title = "Renombrar perfil",
            initial = d.profile.name,
            problem = { SettingsProfiles.nameProblem(it, state.profiles, exceptId = d.profile.id) },
            onConfirm = { onRename(d.profile.id, it); dialog = null },
            onDismiss = { dialog = null }
        )
        is ProfileDialog.Apply -> Confirm(
            title = "¿Aplicar \"${d.profile.name}\"?",
            text = "Tus valores actuales no están guardados en ningún perfil y se reemplazarán. " +
                "Si quieres conservarlos, guárdalos antes como perfil.",
            confirm = "Aplicar",
            onConfirm = { focus.clearFocus(); onApply(d.profile.id); dialog = null },
            onDismiss = { dialog = null }
        )
        is ProfileDialog.Overwrite -> Confirm(
            title = "¿Actualizar \"${d.profile.name}\"?",
            text = "Se guardarán en este perfil los valores que tienes ahora en Ajustes.",
            confirm = "Actualizar",
            onConfirm = { onOverwrite(d.profile.id); dialog = null },
            onDismiss = { dialog = null }
        )
        is ProfileDialog.Delete -> Confirm(
            title = "¿Borrar \"${d.profile.name}\"?",
            text = "Solo se borra el perfil guardado. Tus ajustes actuales no cambian.",
            confirm = "Borrar",
            onConfirm = { onDelete(d.profile.id); dialog = null },
            onDismiss = { dialog = null }
        )
        is ProfileDialog.Context -> ContextDialog(
            profile = d.profile,
            activeVehicle = state.activeVehicle,
            products = state.products,
            onConfirm = { onContext(d.profile.id, it); dialog = null },
            onDismiss = { dialog = null }
        )
    }

    SectionCard(
        title = "Perfiles",
        icon = R.drawable.ic_bookmarks,
        subtitle = "Tus ajustes guardados con nombre",
        badgeContainer = MaterialTheme.colorScheme.secondaryContainer,
        badgeContent = MaterialTheme.colorScheme.onSecondaryContainer
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val inUse = state.inUse
            val edited = state.edited
            Text(
                text = when {
                    inUse != null -> "En uso: ${inUse.name}"
                    edited != null -> "En uso: ${edited.name} (modificado)"
                    else -> "Los valores actuales no están guardados en ningún perfil."
                },
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "Guarda tus ajustes con un nombre y cámbialos de un toque. No incluye el inicio de la jornada.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (state.profiles.isNotEmpty()) HorizontalDivider()
            state.profiles.forEach { p ->
                ProfileRow(
                    profile = p,
                    inUse = p.id == inUse?.id,
                    contextLine = com.ubers.observador.domain.ContextProfiles.describe(p.context, p.context.vehicleId?.let { state.vehicleNames[it] }),
                    onContext = { dialog = ProfileDialog.Context(p) },
                    onApply = {
                        // Without a profile holding today's values, applying would lose them: ask first
                        if (inUse == null) {
                            dialog = ProfileDialog.Apply(p)
                        } else {
                            focus.clearFocus()
                            onApply(p.id)
                        }
                    },
                    onOverwrite = { dialog = ProfileDialog.Overwrite(p) },
                    onRename = { dialog = ProfileDialog.Rename(p) },
                    onDelete = { dialog = ProfileDialog.Delete(p) }
                )
            }
            val full = state.profiles.size >= SettingsProfiles.MAX_PROFILES
            OutlinedButton(onClick = { dialog = ProfileDialog.New }, enabled = !full) {
                Text(if (full) "Máximo ${SettingsProfiles.MAX_PROFILES} perfiles" else "Guardar ajustes actuales como perfil")
            }
            message?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClearMessage) { Text("OK") }
                }
            }
        }
    }
}

@Composable
private fun ProfileRow(
    profile: SettingsProfile,
    inUse: Boolean,
    contextLine: String?,
    onContext: () -> Unit,
    onApply: () -> Unit,
    onOverwrite: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    val v = profile.values
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(profile.name + if (inUse) " · en uso" else "", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                "Mín RD$${formatNumber(v.minRdpPerKm)}/km · RD$${formatNumber(v.minRdpPerHour)}/h · recogida ${formatNumber(v.maxPickupKm)} km",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            contextLine?.let {
                Text("Se sugiere: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        if (!inUse) TextButton(onClick = onApply) { Text("Aplicar") }
        Box {
            TextButton(onClick = { menu = true }) { Text("⋮") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Actualizar con valores actuales") }, onClick = { menu = false; onOverwrite() })
                DropdownMenuItem(text = { Text("Cuándo sugerirlo") }, onClick = { menu = false; onContext() })
                DropdownMenuItem(text = { Text("Renombrar") }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text("Borrar") }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    problem: (String) -> String?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initial) }
    var touched by remember { mutableStateOf(false) }
    val error = problem(name)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                colors = com.ubers.observador.ui.theme.appFieldColors(),
                shape = com.ubers.observador.ui.theme.appFieldShape(),
                value = name,
                onValueChange = { name = it; touched = true },
                label = { Text("Nombre (ej. Noche, Lluvia)") },
                isError = touched && error != null,
                supportingText = { if (touched && error != null) Text(error) },
                singleLine = true
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }, enabled = error == null) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable
private fun Confirm(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

/**
 * 1.44.0: when Inicio should suggest a profile. Only a suggestion: the driver still taps «Usar».
 * Modalities are the ones Uber has shown in real offers; nothing selected means "always".
 */
@Composable
private fun ContextDialog(
    profile: SettingsProfile,
    activeVehicle: com.ubers.observador.domain.expenses.VehicleProfile?,
    products: List<String>,
    onConfirm: (com.ubers.observador.domain.ProfileContext) -> Unit,
    onDismiss: () -> Unit
) {
    var shift by remember { mutableStateOf(profile.context.shift) }
    var product by remember { mutableStateOf(profile.context.product) }
    var forVehicle by remember { mutableStateOf(profile.context.vehicleId != null) }
    // A saved product no longer seen still shows, so it can be removed
    val options = (products + listOfNotNull(profile.context.product)).distinct()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cuándo sugerir «${profile.name}»") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("Inicio te lo propondrá cuando se cumpla todo lo marcado. Nunca se aplica solo.", style = MaterialTheme.typography.bodySmall)
                Text("Turno", style = MaterialTheme.typography.titleSmall)
                com.ubers.observador.domain.ShiftContext.entries.forEach { option ->
                    ChoiceRow(option.label, shift == option) { shift = option }
                }
                Text("Modalidad", style = MaterialTheme.typography.titleSmall)
                ChoiceRow("Cualquiera", product == null) { product = null }
                if (options.isEmpty()) {
                    Text("Aún no se ha leído la modalidad en ninguna oferta.", style = MaterialTheme.typography.bodySmall)
                }
                options.forEach { name -> ChoiceRow(name, product == name) { product = name } }
                if (activeVehicle != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(checked = forVehicle, onCheckedChange = { forVehicle = it })
                        Text("Solo con ${activeVehicle.name}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(
                    com.ubers.observador.domain.ProfileContext(
                        shift, product,
                        if (forVehicle) activeVehicle?.id ?: profile.context.vehicleId else null
                    )
                )
            }) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.compose.material3.RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
    }
}
