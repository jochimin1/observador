package com.ubers.observador.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ubers.observador.R
import com.ubers.observador.domain.online.OnlineFormat
import com.ubers.observador.domain.online.OnlineSession
import com.ubers.observador.service.RideLifecycleMonitor
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")
private val CLOCK_S = DateTimeFormatter.ofPattern("HH:mm:ss")
private const val TICK_MS = 1_000L

private fun at(ms: Long, f: DateTimeFormatter = CLOCK): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(f)

/**
 * Debug builds only (Fase 8A): what the online detector believes now, the stored sessions and
 * TEST sessions. Codes only; "Copiar lectura" hides names and addresses like the ride panel.
 */
@Composable
fun OnlineDebugCard(viewModel: OnlineDebugViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val live = state.live
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var showHistory by remember { mutableStateOf(false) }
    var copyMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(TICK_MS)
            now = System.currentTimeMillis()
        }
    }

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_schedule), contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Text(
                    "  Sesiones en línea (diagnóstico)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
            }
            Text(
                text = "Lee «Estás conectado», «DESCONECTARSE», «Estás desconectado» y «Conectarse» (texto " +
                    "completo) y las pantallas de viaje. Las firmas salen de capturas de imagen: falta confirmarlas " +
                    "con «Copiar lectura» en el teléfono.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Line("Restaurada", if (live.restored) "sí" else "esperando la base de datos")
            Line("Estado confirmado", live.confirmed?.name ?: "—")
            Line("Última señal", live.lastReadingAt?.let { "${at(it, CLOCK_S)} · " + live.lastEvidence.joinToString { e -> e.name } } ?: "—")
            val current = live.current
            if (current == null) {
                Line("Sesión abierta", "ninguna")
            } else {
                Line("Sesión abierta", "${current.id.take(8)} · desde ${at(current.startedAt)} · ${current.startKind.name}")
                Line("Visto en línea", "hace ${((now - current.lastOnlineSeenAt) / TICK_MS).coerceAtLeast(0)} s")
                Line("Confirmado", OnlineFormat.duration(current.confirmedMs(now)))
                Line("No observado", OnlineFormat.duration(current.unobservedMs) + " · ${current.gaps.size} huecos")
            }
            val d = live.diagnostics
            Text(
                text = "Lecturas en línea ${d.onlineReads} · fuera ${d.offlineReads} · mezcladas ${d.mixedReadsIgnored} · " +
                    "confirmaciones ${d.confirmations} · sesiones abiertas ${d.sessionsStarted} · cerradas " +
                    "${d.sessionsClosed} · huecos ${d.gapsRecorded} · guardadas ${state.stored}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            HorizontalDivider()
            Text("Últimas sesiones", style = MaterialTheme.typography.labelLarge)
            if (state.recent.isEmpty()) {
                Text("Ninguna todavía.", style = MaterialTheme.typography.bodySmall)
            } else {
                state.recent.forEach { SessionLine(it, now) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { viewModel.loadTestSessions() }) { Text("Cargar TEST") }
                OutlinedButton(onClick = { viewModel.clearTestSessions() }) { Text("Borrar TEST") }
            }
            OutlinedButton(onClick = {
                val dump = RideLifecycleMonitor.sanitizedLastSnapshot()
                copyMessage = if (dump == null) {
                    "Aún no hay ninguna lectura de Uber."
                } else {
                    context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText("Observador · lectura de pantalla", dump))
                    "Lectura copiada (nombres y direcciones ocultos). Pégala en el chat para confirmar las señales."
                }
            }) { Text("Copiar lectura") }
            (copyMessage ?: state.message)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = { showHistory = !showHistory }) {
                Text(if (showHistory) "Ocultar cambios" else "Ver últimos cambios (${live.history.size})")
            }
            if (showHistory) {
                if (live.history.isEmpty()) Text("Sin cambios todavía.", style = MaterialTheme.typography.bodySmall)
                live.history.asReversed().forEach { entry ->
                    Text("${at(entry.atMs, CLOCK_S)} ${entry.transition.name}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
private fun SessionLine(s: OnlineSession, now: Long) {
    val end = s.endedAt?.let { at(it) } ?: "abierta"
    val extra = buildList {
        add(s.startKind.name)
        s.endReason?.let { add(it.name) }
        if (s.unobservedMs > 0) add("sin ver ${OnlineFormat.duration(s.unobservedMs)}")
        if (s.anomalies.isNotEmpty()) add(s.anomalies.joinToString { it.name })
    }.joinToString(" · ")
    Text(
        text = "${at(s.startedAt)}–$end · ${OnlineFormat.duration(s.confirmedMs(now))} · $extra" +
            if (s.id.startsWith("TEST-")) " · TEST" else "",
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace
    )
}

@Composable
private fun Line(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}
