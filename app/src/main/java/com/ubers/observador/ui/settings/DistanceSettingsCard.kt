package com.ubers.observador.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.ubers.observador.R
import com.ubers.observador.domain.distance.DistanceMode
import com.ubers.observador.ui.components.InfoLine
import com.ubers.observador.ui.components.SectionCard
import com.ubers.observador.ui.components.rememberLocationPermissionRequest

/**
 * Fase 8E in Ajustes: "Medir km con GPS" (off until the driver turns it on and grants precise
 * location) and when it measures. Not part of profiles.
 */
@Composable
fun DistanceSettingsCard(
    enabled: Boolean,
    permitted: Boolean,
    mode: DistanceMode,
    onEnabled: (Boolean) -> Unit,
    onMode: (DistanceMode) -> Unit
) {
    val context = LocalContext.current
    val request = rememberLocationPermissionRequest { granted -> if (granted) onEnabled(true) }
    SectionCard(
        title = "Medir km con GPS",
        icon = R.drawable.ic_route,
        subtitle = if (enabled && permitted) "Activado" else "Apagado",
        badgeContainer = MaterialTheme.colorScheme.tertiaryContainer,
        badgeContent = MaterialTheme.colorScheme.onTertiaryContainer,
        action = {
            Switch(
                checked = enabled && permitted,
                onCheckedChange = { on -> if (!on) onEnabled(false) else if (permitted) onEnabled(true) else request() }
            )
        }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Cuenta los km reales del día con el GPS: con pasajero, a recoger, sin pasajero y fuera de línea. " +
                    "El GPS solo se enciende mientras mide.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column(Modifier.selectableGroup()) {
                ModeOption("Al conectarme a Uber y con el botón", DistanceMode.AUTO_AND_MANUAL, mode, onMode)
                ModeOption("Solo al conectarme a Uber", DistanceMode.AUTO_ONLY, mode, onMode)
                ModeOption("Solo con el botón «Iniciar recorrido»", DistanceMode.MANUAL_ONLY, mode, onMode)
            }
            InfoLine(R.drawable.ic_info, "Solo se guardan km y horas. Nunca coordenadas, rutas ni mapas, tampoco en las exportaciones.")
            if (enabled && !permitted) {
                InfoLine(R.drawable.ic_warning, "Falta el permiso de ubicación precisa.", tint = MaterialTheme.colorScheme.error)
                TextButton(onClick = {
                    // When Android stops asking, the permission can only be given in the app's settings
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                    )
                }) { Text("Abrir permisos de la app") }
            }
        }
    }
}

@Composable
private fun ModeOption(label: String, value: DistanceMode, current: DistanceMode, onMode: (DistanceMode) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = value == current, onClick = { onMode(value) }, role = Role.RadioButton)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = value == current, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
    }
}
