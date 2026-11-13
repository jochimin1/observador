package com.ubers.observador.ui.health

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ubers.observador.BuildConfig
import com.ubers.observador.R
import com.ubers.observador.domain.health.HealthArea
import com.ubers.observador.domain.health.HealthCheck
import com.ubers.observador.domain.health.HealthExport
import com.ubers.observador.domain.health.HealthLevel
import com.ubers.observador.domain.health.HealthReport
import com.ubers.observador.domain.lifecycle.ReadingVersions
import com.ubers.observador.service.HealthMonitor
import com.ubers.observador.ui.components.IconBadge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 1.39.0: whether Observador is observing and saving, with what to do when it is not. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val report by remember(context) { HealthMonitor.reports(context) }.collectAsState(initial = null)
    val incidents by HealthMonitor.incidents.collectAsState()
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val current = report
        if (uri != null && current != null) scope.launch {
            message = try {
                val text = HealthExport.text(current, incidents, versions(context), ZoneId.systemDefault())
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) } ?: error("No destination")
                }
                "Diagnóstico exportado."
            } catch (_: Exception) { "No se pudo exportar el diagnóstico." }
        }
    }

    Scaffold(
        containerColor = com.ubers.observador.ui.theme.screenBackground(),
        topBar = {
            TopAppBar(
                colors = com.ubers.observador.ui.theme.topBarColors(),
                title = { Text("Salud de Observador") },
                navigationIcon = { TextButton(onClick = onBack) { Text("← Volver") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Spacer(Modifier.height(4.dp))
            val r = report
            if (r == null) Text("Comprobando…")
            else {
                Summary(r)
                r.checks.forEach { CheckCard(it) }
            }

            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Incidentes recientes", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                    if (incidents.isEmpty()) Text("Sin incidentes guardados.", style = MaterialTheme.typography.bodySmall)
                    val format = DateTimeFormatter.ofPattern("d MMM · HH:mm")
                    incidents.take(20).forEach { i ->
                        val time = Instant.ofEpochMilli(i.atMs).atZone(ZoneId.systemDefault()).format(format)
                        Text(
                            "$time · ${i.area.label} · ${i.title}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (i.resolved) MaterialTheme.colorScheme.onSurfaceVariant else levelColor(i.level)
                        )
                    }
                    Text(
                        "Se guardan los últimos 50 en el teléfono, fuera de los respaldos. Un problema que se mantiene se anota una sola vez.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (incidents.isNotEmpty()) TextButton(onClick = { HealthMonitor.clearIncidents() }) { Text("Borrar historial") }
                }
            }

            OutlinedButton(
                onClick = { launcher.launch("observador-diagnostico.txt") },
                enabled = report != null,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Exportar diagnóstico") }
            Text(
                "El diagnóstico lleva versiones, estados e incidentes. No lleva textos de Uber, nombres, direcciones, importes ni coordenadas.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun versions(context: android.content.Context): List<Pair<String, String>> {
    @Suppress("DEPRECATION")
    val uber = runCatching {
        context.packageManager.getPackageInfo("com.ubercab.driver", 0).let { "${it.versionName} (${it.longVersionCode})" }
    }.getOrDefault("no disponible")
    return listOf(
        "Observador" to "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        "Uber Driver" to uber,
        "Lector" to ReadingVersions.PARSER,
        "Clasificador" to ReadingVersions.CLASSIFIER,
        "Android" to "API ${android.os.Build.VERSION.SDK_INT}"
    )
}

@Composable
fun levelColor(level: HealthLevel): Color = when (level) {
    HealthLevel.FAIL -> MaterialTheme.colorScheme.error
    HealthLevel.WARN -> MaterialTheme.colorScheme.tertiary
    HealthLevel.OK -> MaterialTheme.colorScheme.primary
    HealthLevel.INFO, HealthLevel.OFF -> MaterialTheme.colorScheme.onSurfaceVariant
}

@DrawableRes
private fun levelIcon(level: HealthLevel): Int = when (level) {
    HealthLevel.FAIL -> R.drawable.ic_block
    HealthLevel.WARN -> R.drawable.ic_warning
    HealthLevel.OK -> R.drawable.ic_check_circle
    HealthLevel.INFO -> R.drawable.ic_info
    HealthLevel.OFF -> R.drawable.ic_pause_circle
}

private fun levelLabel(level: HealthLevel) = when (level) {
    HealthLevel.FAIL -> "Falla"
    HealthLevel.WARN -> "Revisar"
    HealthLevel.OK -> "Bien"
    HealthLevel.INFO -> "Sin datos aún"
    HealthLevel.OFF -> "Apagado"
}

@Composable
private fun Summary(report: HealthReport) {
    val worst = report.worst
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(levelIcon(report.overall), container = levelColor(report.overall), content = MaterialTheme.colorScheme.surface)
            Column(Modifier.padding(start = 12.dp)) {
                Text(
                    when {
                        worst?.level == HealthLevel.FAIL -> "Hay algo que no funciona"
                        worst != null -> "Hay algo que revisar"
                        else -> "Sin problemas detectados"
                    },
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold
                )
                Text(
                    worst?.title ?: "Cada estado se basa en lo que Observador ha visto, no solo en los permisos.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun CheckCard(check: HealthCheck) {
    val context = LocalContext.current
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(levelIcon(check.level), container = levelColor(check.level), content = MaterialTheme.colorScheme.surface, size = 28.dp)
                Column(Modifier.padding(start = 10.dp).weight(1f)) {
                    Text("${check.area.label} · ${levelLabel(check.level)}", style = MaterialTheme.typography.labelMedium, color = levelColor(check.level))
                    Text(check.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                }
            }
            Text(check.detail, style = MaterialTheme.typography.bodySmall)
            check.action?.let { Text("Qué hacer: $it", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium) }
            if (check.area == HealthArea.ACCESSIBILITY && check.level == HealthLevel.FAIL) {
                TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) {
                    Text("Abrir Accesibilidad")
                }
            }
            if (check.area == HealthArea.GPS && check.level.isProblem) {
                TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) {
                    Text("Abrir ajustes de ubicación")
                }
            }
        }
    }
}
