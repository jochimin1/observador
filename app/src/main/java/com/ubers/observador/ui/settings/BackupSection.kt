package com.ubers.observador.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ubers.observador.R
import com.ubers.observador.domain.backup.BackupFormat
import com.ubers.observador.ui.components.IconSectionHeader
import com.ubers.observador.ui.components.SectionCard
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val LAST_BACKUP_FORMAT = DateTimeFormatter.ofPattern("d/M/yyyy h:mm a", Locale.forLanguageTag("es-DO"))

/**
 * 1.31.0: «Respaldo y datos» at the end of Ajustes. Crear respaldo saves one .zip where the driver
 * chooses (Descargas, Drive…); the app never sends it anywhere by itself. Restaurar and Borrar
 * todo come in the next versions.
 */
@Composable
fun BackupSection(viewModel: BackupViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    var confirming by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        viewModel.create(uri)
    }

    IconSectionHeader("Respaldo y datos", R.drawable.ic_download)
    SectionCard(title = "Respaldo", icon = R.drawable.ic_download) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Guarda en un solo archivo todo lo de la app: ofertas, viajes, dinero, gastos, kilómetros, recorridos y tus ajustes.",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "Ahora hay ${BackupFormat.contentText(
                    mapOf("detected_offers" to state.offers, "trips" to state.trips, "expense_entries" to state.expenses)
                )}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                state.lastBackupAt?.let {
                    "Último respaldo: " + LAST_BACKUP_FORMAT.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))
                } ?: "Todavía no has hecho ningún respaldo.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            state.message?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { viewModel.clearMessage() }) { Text("OK") }
                }
            }
            Button(onClick = { confirming = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                if (state.busy) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("  Creando respaldo…")
                } else {
                    Text("Crear respaldo")
                }
            }
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Crear respaldo") },
            text = {
                Text(
                    "El archivo lleva todo lo de la app, incluidos nombres de pasajeros, direcciones y recorridos del GPS. " +
                        "Guárdalo en un lugar seguro y no lo compartas con quien no deba verlo. " +
                        "La app no lo envía a ningún sitio: se guarda donde tú elijas."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    picker.launch(BackupFormat.fileName(System.currentTimeMillis(), ZoneId.systemDefault()))
                }) { Text("Elegir dónde guardar") }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancelar") } }
        )
    }
}
