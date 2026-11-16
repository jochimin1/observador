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
    var password by androidx.compose.runtime.remember { mutableStateOf("") }
    var encrypted by rememberSaveable { mutableStateOf(false) }
    var confirming by rememberSaveable { mutableStateOf(false) }
    var eraseStep by rememberSaveable { mutableStateOf(0) } // 0 none, 1 warning, 2 type BORRAR
    var typed by rememberSaveable { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        viewModel.create(uri, if (encrypted) password else "")
        password = ""
    }

    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        viewModel.choose(uri, if (encrypted) password else "")
        password = ""
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
            Text(
                "El historial se conserva en este teléfono. Para cambiar de equipo, crea y restaura tu archivo de respaldo.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Switch(checked = encrypted, enabled = !state.busy, onCheckedChange = { encrypted = it; password = "" })
                Text("Cifrar / abrir respaldo cifrado")
            }
            if (encrypted) {
                OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Contraseña (mínimo 8 caracteres)") },
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Necesitas esta contraseña para restaurar. No se guarda y no se puede recuperar. El archivo cifrado incluye los mismos datos completos.", style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = { confirming = true }, enabled = !state.busy && (!encrypted || password.length >= 8), modifier = Modifier.fillMaxWidth()) {
                if (state.busy) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("  ${state.busyLabel}")
                } else {
                    Text("Crear respaldo")
                }
            }
            OutlinedButton(
                onClick = { restorePicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
                enabled = !state.busy && (!encrypted || password.length >= 8),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Restaurar respaldo") }
            TextButton(
                onClick = { typed = ""; eraseStep = 1 },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Borrar todos los datos", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }

    // 1.56.0: weekly automatic backup and the list of backups in its folder
    AutoBackupCard(onRestore = { uri -> viewModel.choose(uri, if (encrypted) password else "") }, busy = state.busy)

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
                    picker.launch(BackupFormat.fileName(System.currentTimeMillis(), ZoneId.systemDefault()).let { if (encrypted) it.removeSuffix(".zip") + ".obsenc" else it })
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


/** 1.56.0: «Respaldo automático semanal» and every Observador backup found in its folder. */
@Composable
private fun AutoBackupCard(onRestore: (android.net.Uri) -> Unit, busy: Boolean) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var version by androidx.compose.runtime.remember { mutableStateOf(0) }
    var enabled by androidx.compose.runtime.remember { mutableStateOf(com.ubers.observador.service.AutoBackup.enabled(context)) }
    var list by androidx.compose.runtime.remember { mutableStateOf<List<com.ubers.observador.domain.backup.AutoBackupRules.Entry>?>(null) }
    var status by androidx.compose.runtime.remember { mutableStateOf(com.ubers.observador.service.AutoBackup.status(context)) }
    var restoring by androidx.compose.runtime.remember { mutableStateOf<com.ubers.observador.domain.backup.AutoBackupRules.Entry?>(null) }
    androidx.compose.runtime.LaunchedEffect(version) {
        list = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.ubers.observador.service.AutoBackup.list(context) }
        status = com.ubers.observador.service.AutoBackup.status(context)
        enabled = com.ubers.observador.service.AutoBackup.enabled(context)
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) { com.ubers.observador.service.AutoBackup.setFolder(context, uri); version++ }
    }
    SectionCard(title = "Respaldo automático", icon = R.drawable.ic_download, subtitle = "Una vez por semana, con batería suficiente") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Guarda cada semana un respaldo completo, con todos tus datos, en la carpeta que elijas: el teléfono, la tarjeta SD o una carpeta de Drive si tienes la app. " +
                "Cada respaldo se comprueba después de escribirlo. No lleva contraseña; si quieres uno cifrado, créalo a mano arriba.",
                style = MaterialTheme.typography.bodySmall)
            val folder = com.ubers.observador.service.AutoBackup.folder(context)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (folder == null) "Sin carpeta elegida" else "Carpeta: " + (folder.lastPathSegment?.substringAfterLast(':') ?: "elegida"),
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                androidx.compose.material3.Switch(checked = enabled, enabled = folder != null, onCheckedChange = {
                    com.ubers.observador.service.AutoBackup.setEnabled(context, it); version++
                })
            }
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { folderPicker.launch(null) }) { Text(if (folder == null) "Elegir carpeta" else "Cambiar carpeta") }
                if (folder != null) TextButton(enabled = !busy, onClick = {
                    com.ubers.observador.service.AutoBackup.runNow(context)
                    scope.launch { kotlinx.coroutines.delay(15_000); version++ }
                }) { Text("Hacer uno ahora") }
            }
            if (folder != null) {
                androidx.compose.material3.HorizontalDivider()
                val l = list
                when {
                    l == null -> Text("Buscando respaldos…", style = MaterialTheme.typography.bodySmall)
                    l.isEmpty() -> Text("No hay respaldos de Observador en esa carpeta todavía.", style = MaterialTheme.typography.bodySmall)
                    else -> {
                        Text("${l.size} respaldos · ${BackupFormat.sizeText(com.ubers.observador.domain.backup.AutoBackupRules.totalBytes(l))} en total. " +
                            "Ninguno se borra solo; si necesitas espacio, bórralos desde la app de archivos.", style = MaterialTheme.typography.bodySmall)
                        l.forEach { e ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(LAST_BACKUP_FORMAT.format(Instant.ofEpochMilli(e.modifiedAt).atZone(ZoneId.systemDefault())) + " · " +
                                    BackupFormat.sizeText(e.bytes) + " · " + (if (e.automatic) "automático" else "manual") + if (e.encrypted) " · cifrado" else "",
                                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                TextButton(enabled = !busy, onClick = { restoring = e }) { Text("Restaurar") }
                            }
                        }
                    }
                }
                TextButton(onClick = { version++ }) { Text("Actualizar lista") }
            }
        }
    }
    restoring?.let { e ->
        AlertDialog(onDismissRequest = { restoring = null }, title = { Text("Restaurar este respaldo") },
            text = { Text("Se revisará el archivo antes de reemplazar nada y antes se guarda una copia de seguridad de lo actual." +
                if (e.encrypted) " Es un respaldo cifrado: activa «Cifrar / abrir respaldo cifrado» arriba y escribe su contraseña antes." else "") },
            confirmButton = { TextButton(onClick = {
                restoring = null
                scope.launch {
                    val uri = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.ubers.observador.service.AutoBackup.documentUri(context, e.name) }
                    if (uri != null) onRestore(uri)
                }
            }) { Text("Revisar y restaurar") } },
            dismissButton = { TextButton(onClick = { restoring = null }) { Text("Cancelar") } })
    }
}
