package com.ubers.observador.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ubers.observador.data.ReadingAuditRepository
import com.ubers.observador.data.db.AppDatabase
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun ReadingAuditSection() {
    val context = LocalContext.current
    val repo = remember(context) { ReadingAuditRepository(AppDatabase.getInstance(context)) }
    val latest by remember(repo) { repo.observeLatest() }.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            busy = true
            message = try {
                val output = context.contentResolver.openOutputStream(uri) ?: error("No destination")
                "${repo.export(output)} lecturas exportadas."
            } catch (_: Exception) { "No se pudo exportar el registro de lecturas." }
            busy = false
        }
    }
    OutlinedCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Registro de lecturas", style = MaterialTheme.typography.titleMedium)
            val reading = latest
            if (reading == null) Text("Todavía no hay lecturas de Uber registradas.")
            else {
                val time = Instant.ofEpochMilli(reading.observedAt).atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("d MMM · HH:mm:ss"))
                Text("Última lectura: $time")
                Text("${reading.acceptedCards} ofertas leídas · ${reading.rejectedCards} sin lectura completa")
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Ocultar detalles" else "Ver detalles de lectura") }
                if (expanded) {
                    Text("Uber observado: ${reading.uberVersion ?: "versión no disponible"}")
                    Text("Lector: ${reading.parserVersion}\nClasificador: ${reading.classifierVersion}", style = MaterialTheme.typography.bodySmall)
                    Text("Pantalla: ${reading.screenType}${if (reading.provisional) " · firma pendiente de validar" else ""}", style = MaterialTheme.typography.bodySmall)
                    if (reading.reasonCodes.isNotEmpty()) Text("Observaciones: ${reading.reasonCodes}", style = MaterialTheme.typography.bodySmall)
                }
            }
            Text("Conserva hasta 10 000 resultados con sus versiones y motivos. No guarda textos de pantalla, nombres, direcciones ni coordenadas en este registro.", style = MaterialTheme.typography.bodySmall)
            TextButton(enabled = !busy && latest != null, onClick = { launcher.launch("observador-lecturas.json") }) { Text("Exportar registro de lecturas") }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
