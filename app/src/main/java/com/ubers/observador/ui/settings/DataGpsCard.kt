package com.ubers.observador.ui.settings

import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ubers.observador.R
import com.ubers.observador.data.db.AppDatabase
import com.ubers.observador.data.db.ObservationLog
import com.ubers.observador.data.backup.FullDataExport
import com.ubers.observador.domain.backup.BackupFormat
import com.ubers.observador.domain.backup.MaintenanceGate
import com.ubers.observador.service.DistanceMonitor
import com.ubers.observador.ui.components.SectionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
fun DataGpsCard(enabled: Boolean, permitted: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val live by DistanceMonitor.state.collectAsState()
    val dao = remember { AppDatabase.getInstance(context).observationLogDao() }
    val latest by remember { dao.latest("BATTERY_CHECK") }.collectAsState(initial = null)
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var sizes by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            sizes = withContext(Dispatchers.IO) {
                val db = context.getDatabasePath("observador.db")
                val bytes = db.length() + java.io.File(db.path + "-wal").length()
                val tiles = java.io.File(context.cacheDir, "tiles").walkTopDown().filter { it.isFile }.sumOf { it.length() }
                "Base de datos: ${BackupFormat.sizeText(bytes)} · mapas descargados: ${BackupFormat.sizeText(tiles)}"
            }
            delay(5_000)
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                try {
                    val output = context.contentResolver.openOutputStream(uri, "w") ?: error("No se pudo abrir el destino")
                    FullDataExport.write(context, output)
                    message = "Exportación completa guardada: todas las tablas y ajustes, sin anonimizar ni filtrar fechas."
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) }
                    message = "No se completó la exportación. Los datos originales se conservan."
                } finally { busy = false }
            }
        }
    }
    fun open(action: String, packageUri: Boolean = false) {
        runCatching { context.startActivity(Intent(action).apply { if (packageUri) data = Uri.parse("package:${context.packageName}") }) }
            .onFailure { message = "No se pudo abrir ese ajuste del teléfono." }
    }
    SectionCard(title = "GPS, batería y datos", icon = R.drawable.ic_route) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(when {
                !permitted -> "Falta ubicación precisa. Abre los permisos y elige ubicación precisa."
                !enabled -> "GPS desactivado en Observador."
                live.requestError != null -> live.requestError!!
                live.backgroundLimited -> "Android limitó el GPS en segundo plano. Abre Inicio y reanuda Observador."
                live.measuring == null -> "GPS preparado; no hay medición activa."
                live.lastFixAt == null -> "Esperando la primera posición."
                now - live.lastFixAt!! > 60_000 -> "Sin señal reciente; hay un hueco en la medición."
                else -> "Recibiendo posiciones."
            })
            live.lastFixAt?.let { Text("Última posición recibida hace ${((now - it).coerceAtLeast(0) / 1000)} s") }
            val d = live.diagnostics
            Text("Esta sesión: ${d.fixesReceived} posiciones recibidas · ${d.discardedAccuracy} imprecisas · ${d.discardedJump} saltos · ${d.gapsEstimated + d.gapsTooLong} huecos. Recibida no significa aceptada.", style = MaterialTheme.typography.bodySmall)
            Row {
                TextButton(onClick = { open(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true) }) { Text("Permisos") }
                TextButton(onClick = { open(Settings.ACTION_LOCATION_SOURCE_SETTINGS) }) { Text("Ubicación") }
                TextButton(onClick = { open(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) }) { Text("Batería") }
            }
            val record = latest?.let { runCatching { JSONObject(it.payload) }.getOrNull() }
            val started = record?.optString("phase") == "START"
            Text("Prueba de batería: inicia antes de un recorrido con Uber y Observador y termina al parar. Mide el teléfono completo; no separa el consumo de cada app.", style = MaterialTheme.typography.bodySmall)
            record?.optString("summary")?.takeIf { it.isNotEmpty() }?.let { Text(it) }
            OutlinedButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    try {
                        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                        val percent = if (level >= 0 && scale > 0) level * 100.0 / scale else -1.0
                        val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
                        val at = System.currentTimeMillis()
                        val p = JSONObject().put("phase", if (started) "END" else "START")
                            .put("percent", percent).put("plugged", plugged)
                        if (started) {
                            val hours = (at - (latest?.atMs ?: at)) / 3_600_000.0
                            val drop = record.optDouble("percent", -1.0) - percent
                            val valid = hours > 0 && percent >= 0 && record.optDouble("percent", -1.0) >= 0 && drop >= 0 && !plugged && !record.optBoolean("plugged")
                            p.put("startId", latest?.id).put("hours", hours).put("drop", drop)
                            p.put("summary", if (valid) "Descarga total: %.1f puntos en %.2f h (%.1f puntos/h). Una recarga intermedia invalida la comparación.".format(java.util.Locale.US, drop, hours, drop / hours) else "Muestra no comparable: carga conectada, nivel desconocido o duración inválida.")
                        } else p.put("summary", "Prueba iniciada: $percent % de batería.")
                        MaintenanceGate.shared.access { dao.insert(ObservationLog(kind = "BATTERY_CHECK", atMs = at, payload = p.toString())) }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        message = "No se pudo guardar la prueba."
                    } finally { busy = false }
                }
            }) { Text(if (started) "Terminar prueba de batería" else "Iniciar prueba de batería") }
            HorizontalDivider()
            Text("Conservación completa e indefinida", style = MaterialTheme.typography.titleSmall)
            Text("Se guardan nombres, direcciones, rutas y consultas de lugares disponibles. No se borra el historial automáticamente. Los permisos de Android y los controles de GPS siguen siendo necesarios.", style = MaterialTheme.typography.bodySmall)
            Text(sizes, style = MaterialTheme.typography.bodySmall)
            Text("La caché de mapas es temporal y puede renovarse. No contiene tu historial de recorridos.", style = MaterialTheme.typography.bodySmall)
            Button(enabled = !busy, onClick = { export.launch("observador-completo-${System.currentTimeMillis()}.json") }, modifier = Modifier.fillMaxWidth()) {
                Text(if (busy) "Procesando…" else "Exportar todos los datos (JSON)")
            }
            Text("Incluye identidad, coordenadas, consultas, evidencias y ajustes. Para restaurar la app, usa Crear respaldo.", style = MaterialTheme.typography.bodySmall)
            message?.let { Text(it) }
        }
    }
}
