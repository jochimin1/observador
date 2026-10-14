package com.ubers.observador.ui.components

import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.ubers.observador.R
import com.ubers.observador.data.export.ReportFile
import com.ubers.observador.data.export.ReportOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** What the export asks the screen to do. */
sealed interface ReportExportEvent {
    data class ReadyToSave(val fileName: String, val mimeType: String) : ReportExportEvent
    data class ReadyToShare(val uri: Uri, val mimeType: String) : ReportExportEvent
    data class Saved(val fileName: String) : ReportExportEvent
    data object Empty : ReportExportEvent
    data object Failed : ReportExportEvent
}

/**
 * Saves a report with the system file picker or shares it (WhatsApp, Drive, correo), like the
 * offers export (Fase 8D). The file is built first; an empty table is not written.
 */
class ReportExporter(private val app: Application, private val scope: CoroutineScope) {

    private val _events = MutableSharedFlow<ReportExportEvent>(extraBufferCapacity = 2)
    val events: SharedFlow<ReportExportEvent> = _events.asSharedFlow()
    private var pending: ReportOutput? = null

    fun save(build: suspend () -> ReportOutput) = scope.launch {
        val out = built(build) ?: return@launch
        pending = out
        _events.emit(ReportExportEvent.ReadyToSave(out.fileName, out.mimeType))
    }

    fun savePendingTo(uri: Uri?) = scope.launch {
        val out = pending
        pending = null
        if (uri == null) return@launch
        if (out == null) {
            _events.emit(ReportExportEvent.Failed)
            return@launch
        }
        val event = try {
            withContext(Dispatchers.IO) {
                val stream = app.contentResolver.openOutputStream(uri) ?: throw IOException("No output stream")
                stream.use { it.write(out.bytes) }
            }
            ReportExportEvent.Saved(out.fileName)
        } catch (e: Exception) {
            Log.e(TAG, "Report save failed", e)
            ReportExportEvent.Failed
        }
        _events.emit(event)
    }

    fun share(build: suspend () -> ReportOutput) = scope.launch {
        val out = built(build) ?: return@launch
        val event = try {
            val uri = withContext(Dispatchers.IO) {
                val dir = File(app.cacheDir, "exports").apply { mkdirs() }
                val file = File(dir, out.fileName)
                file.writeBytes(out.bytes)
                FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
            }
            ReportExportEvent.ReadyToShare(uri, out.mimeType)
        } catch (e: Exception) {
            Log.e(TAG, "Report share failed", e)
            ReportExportEvent.Failed
        }
        _events.emit(event)
    }

    private suspend fun built(build: suspend () -> ReportOutput): ReportOutput? = try {
        val out = withContext(Dispatchers.Default) { build() }
        if (out.rows == 0) {
            _events.emit(ReportExportEvent.Empty)
            null
        } else {
            out
        }
    } catch (e: Exception) {
        Log.e(TAG, "Report build failed", e)
        _events.emit(ReportExportEvent.Failed)
        null
    }

    private companion object {
        const val TAG = "ReportExport"
    }
}

/** Wires the export events to the file picker, the share sheet and the snackbar. */
@Composable
fun ReportExportEffects(exporter: ReportExporter, snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val csv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { exporter.savePendingTo(it) }
    val json = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { exporter.savePendingTo(it) }
    val pdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { exporter.savePendingTo(it) }
    LaunchedEffect(exporter) {
        exporter.events.collect { event ->
            when (event) {
                is ReportExportEvent.ReadyToSave -> when (event.mimeType) {
                    "application/pdf" -> pdf.launch(event.fileName)
                    "application/json" -> json.launch(event.fileName)
                    else -> csv.launch(event.fileName)
                }
                is ReportExportEvent.ReadyToShare -> {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = event.mimeType
                        putExtra(Intent.EXTRA_STREAM, event.uri)
                        clipData = ClipData.newRawUri("", event.uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(send, "Compartir"))
                }
                is ReportExportEvent.Saved -> scope.launch { snackbar.showSnackbar("Guardado: ${event.fileName}") }
                ReportExportEvent.Empty -> scope.launch { snackbar.showSnackbar("No hay datos en este periodo para ese archivo") }
                ReportExportEvent.Failed -> scope.launch { snackbar.showSnackbar("No se pudo exportar") }
            }
        }
    }
}

/** Choose a file for [periodLabel], then save it or share it. */
@Composable
fun ReportExportDialog(
    periodLabel: String,
    files: List<ReportFile>,
    onSave: (ReportFile) -> Unit,
    onShare: (ReportFile) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(painterResource(R.drawable.ic_download), contentDescription = null) },
        title = { Text("Exportar") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "$periodLabel. Importes exactos con su moneda; nunca coordenadas. El archivo de viajes incluye el pasajero y las direcciones. El PDF lleva la nota de que no es un documento oficial de Uber.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                files.forEachIndexed { i, f ->
                    if (i > 0) HorizontalDivider()
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(f.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            FilledTonalButton(onClick = { onSave(f) }) {
                                Icon(painterResource(R.drawable.ic_download), contentDescription = null, modifier = Modifier.size(18.dp))
                                Text("Guardar", Modifier.padding(start = 6.dp))
                            }
                            OutlinedButton(onClick = { onShare(f) }) {
                                Icon(painterResource(R.drawable.ic_share), contentDescription = null, modifier = Modifier.size(18.dp))
                                Text("Compartir", Modifier.padding(start = 6.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } }
    )
}
