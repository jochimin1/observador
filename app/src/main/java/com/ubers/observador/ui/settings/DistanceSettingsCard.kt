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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
            InfoLine(R.drawable.ic_info, "Los km se guardan como totales. El recorrido de los viajes tomados se guarda aparte: «Recorrido de los viajes», abajo.")
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

/**
 * Fase 8K in Ajustes: «Grabar el recorrido de los viajes» (on unless switched off) and «Borrar
 * todos los recorridos». It uses the positions of «Medir km con GPS»: without them a trip is kept
 * without its route.
 */
@Composable
fun RouteSettingsCard(
    enabled: Boolean,
    gpsReady: Boolean,
    routes: Int,
    points: Int,
    onEnabled: (Boolean) -> Unit,
    onDeleteAll: () -> Unit
) {
    var confirming by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    if (confirming) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("¿Borrar todos los recorridos?") },
            text = {
                Text(
                    "Se borran los puntos y el trazo de $routes viaje(s). Los viajes, sus horas, los nombres de los lugares, " +
                        "los km y el dinero se quedan. No se puede deshacer."
                )
            },
            confirmButton = {
                TextButton(onClick = { confirming = false; onDeleteAll() }) {
                    Text("Borrar", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancelar") } }
        )
    }
    SectionCard(
        title = "Recorrido de los viajes",
        icon = R.drawable.ic_route,
        subtitle = when {
            !enabled -> "Apagado"
            !gpsReady -> "Activado · falta el GPS"
            else -> "Activado"
        },
        badgeContainer = MaterialTheme.colorScheme.tertiaryContainer,
        badgeContent = MaterialTheme.colorScheme.onTertiaryContainer,
        action = { Switch(checked = enabled, onCheckedChange = onEnabled) }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Guarda por dónde pasó cada viaje tomado, desde «Llegar por …» hasta el final, con la hora y el lugar " +
                    "de inicio y de fin. Lo ves en el detalle del viaje, sobre un mapa, y lo exportas en GPX o GeoJSON.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            InfoLine(R.drawable.ic_info, "Usa las mismas posiciones de «Medir km con GPS»: no gasta más batería. Se guarda siempre, hasta que lo borres.")
            InfoLine(R.drawable.ic_info, "El mapa con calles y los nombres de los lugares usan internet (OpenStreetMap y el servicio de mapas del teléfono).")
            if (enabled && !gpsReady) {
                InfoLine(R.drawable.ic_warning, "Con «Medir km con GPS» apagado o sin permiso, los viajes se guardan sin recorrido.", tint = MaterialTheme.colorScheme.error)
            }
            Text(
                if (routes == 0) "Aún no hay recorridos guardados." else "$routes recorrido(s) · $points punto(s) guardados",
                style = MaterialTheme.typography.bodyMedium
            )
            if (routes > 0) {
                TextButton(onClick = { confirming = true }) {
                    Text("Borrar todos los recorridos", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
