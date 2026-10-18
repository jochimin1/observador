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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.ubers.observador.domain.backup.ResetRules
import com.ubers.observador.ui.components.IconSectionHeader
import com.ubers.observador.ui.components.SectionCard
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val LAST_BACKUP_FORMAT = DateTimeFormatter.ofPattern("d/M/yyyy h:mm a", Locale.forLanguageTag("es-DO"))

/**
 * 1.31.0: «Respaldo y datos» at the end of Ajustes. Crear respaldo saves one .zip where the driver
 * chooses (Descargas, Drive…); the app never sends it anywhere by itself. Restaurar respaldo (1.32.0) replaces everything
 * with the file's content after an automatic copy of what there is now; Borrar todo comes next.
 */
@Composable
fun BackupSection(viewModel: BackupViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    var confirming by rememberSaveable { mutableStateOf(false) }
    var eraseStep by rememberSaveable { mutableStateOf(0) } // 0 none, 1 warning, 2 type BORRAR
    var typed by rememberSaveable { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        viewModel.create(uri)
    }

    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        viewModel.choose(uri)
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
                    Text("  ${state.busyLabel}")
                } else {
                    Text("Crear respaldo")
                }
            }
            OutlinedButton(
                onClick = { restorePicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Restaurar respaldo") }
            TextButton(
                onClick = { typed = ""; eraseStep = 1 },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Borrar todos los datos", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }

    state.progress?.let { progress ->
        // Cannot be dismissed: the data is being replaced or erased
        AlertDialog(
            onDismissRequest = {},
            title = { Text(progress.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    LinearProgressIndicator(progress = { (progress.index + 1f) / progress.steps.size }, modifier = Modifier.fillMaxWidth())
                    progress.steps.forEachIndexed { i, label ->
                        Text(
                            (if (i < progress.index) "✓  " else if (i == progress.index) "›  " else "    ") + label + (if (i == progress.index) "…" else ""),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (i > progress.index) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Text("No cierres la app.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {}
        )
    }

    state.preview?.let { preview ->
        AlertDialog(
            onDismissRequest = { viewModel.cancelRestore() },
            title = { Text("Restaurar respaldo") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(preview.text)
                    preview.warning?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = { TextButton(onClick = { viewModel.confirmRestore() }) { Text("Restaurar") } },
            dismissButton = { TextButton(onClick = { viewModel.cancelRestore() }) { Text("Cancelar") } }
        )
    }

    if (eraseStep == 1) {
        AlertDialog(
            onDismissRequest = { eraseStep = 0 },
            title = { Text("Borrar todos los datos") },
            text = {
                Text(
                    ResetRules.warningText(
                        mapOf("detected_offers" to state.offers, "trips" to state.trips, "expense_entries" to state.expenses)
                    ) + "\n\n¿Quieres guardar un respaldo antes?"
                )
            },
            confirmButton = { TextButton(onClick = { eraseStep = 2 }) { Text("Continuar sin respaldo", color = MaterialTheme.colorScheme.error) } },
            dismissButton = {
                Row {
                    TextButton(onClick = { eraseStep = 0 }) { Text("Cancelar") }
                    TextButton(onClick = { eraseStep = 0; confirming = true }) { Text("Hacer respaldo") }
                }
            }
        )
    }

    if (eraseStep == 2) {
        AlertDialog(
            onDismissRequest = { eraseStep = 0 },
            title = { Text("Escribe ${ResetRules.CONFIRM_WORD} para confirmar") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Esto vacía la app como si acabaras de instalarla. No se puede deshacer.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = typed, onValueChange = { typed = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(
                    enabled = ResetRules.isConfirmed(typed),
                    onClick = { eraseStep = 0; typed = ""; viewModel.eraseAll() }
                ) { Text("Borrar todo", color = if (ResetRules.isConfirmed(typed)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
            },
            dismissButton = { TextButton(onClick = { eraseStep = 0; typed = "" }) { Text("Cancelar") } }
        )
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

/**
 * 1.32.2: the result of a restore. The app restarts after restoring and opens on Inicio, so the result
 * waits in the settings and is shown here, over whatever screen opens, until the driver closes it.
 */
@Composable
fun RestoreResultDialog() {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val settings = androidx.compose.runtime.remember { com.ubers.observador.data.AppSettings(context) }
    val notice by settings.pendingNotice.collectAsState(initial = null)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val text = notice ?: return
    AlertDialog(
        onDismissRequest = {},
        title = { Text(ResetRules.noticeTitle(text)) },
        text = { Text(ResetRules.noticeBody(text)) },
        confirmButton = { TextButton(onClick = { scope.launch { settings.setPendingNotice(null) } }) { Text("Listo") } }
    )
}
