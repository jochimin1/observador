package com.ubers.observador.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ubers.observador.domain.lifecycle.ScreenClassification
import com.ubers.observador.service.LifecycleDebugState
import com.ubers.observador.service.RideLifecycleMonitor
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val HISTORY_TIME = DateTimeFormatter.ofPattern("HH:mm:ss")
private const val TICK_MS = 1_000L
private const val HASH_HEAD = 6
private const val HASH_TAIL = 3

/**
 * Debug builds only (Fase 7A): what the ride-lifecycle detector believes right now and why. It
 * only observes Uber. Shows evidence codes, not screen text; the copy button hides names and
 * addresses (see SnapshotSanitizer).
 */
@Composable
fun LifecycleDebugCard() {
    val state by RideLifecycleMonitor.state.collectAsState()
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var showHistory by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(TICK_MS)
            now = System.currentTimeMillis()
        }
    }

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Ciclo de viaje (diagnóstico)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            Text(
                text = "Solo observa: nunca toca nada en Uber. Las firmas de recogida, llegada, viaje, fin, " +
                    "cancelación y búsqueda son candidatas hasta validarlas con un viaje real.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            LifecycleSummary(state, now)
            EvidenceList(state.classification)
            HorizontalDivider()
            DiagnosticsLines(state)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    RideLifecycleMonitor.reset()
                    message = "Detector reiniciado."
                }) { Text("Reiniciar detector") }
                OutlinedButton(onClick = {
                    val dump = RideLifecycleMonitor.sanitizedLastSnapshot()
                    message = if (dump == null) {
                        "Aún no hay ninguna lectura de Uber."
                    } else {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(ClipData.newPlainText("Observador · lectura de pantalla", dump))
                        "Lectura copiada. Revísala antes de compartirla: nombres y direcciones van ocultos."
                    }
                }) { Text("Copiar lectura") }
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = { showHistory = !showHistory }) {
                Text(if (showHistory) "Ocultar eventos" else "Ver últimos eventos (${state.history.size})")
            }
            if (showHistory) HistoryList(state)
        }
    }
}

@Composable
private fun LifecycleSummary(state: LifecycleDebugState, now: Long) {
    val ctx = state.context
    val c = state.classification
    DebugLine("Servicio", if (state.serviceConnected) "conectado" else "sin conexión")
    DebugLine("Estado", ctx.state.name)
    DebugLine(
        "Pantalla",
        (c?.type?.name ?: "—") + if (c?.provisional == true) " (firma sin validar)" else ""
    )
    DebugLine("Confianza", c?.let { "${(it.confidence * 100).roundToInt()} % · ${it.level}" } ?: "—")
    DebugLine("Oferta relacionada", ctx.relatedOfferHash?.let(::shortHash) ?: "—")
    DebugLine("Sesión", ctx.sessionId?.take(HASH_HEAD + 2) ?: "—")
    DebugLine("Recuperado", if (ctx.recovered) "sí (sin datos previos)" else "no")
    DebugLine("Última transición", ctx.lastTransitionFrom?.let { "${it.name} → ${ctx.state.name}" } ?: "—")
    DebugLine("Hace", ctx.lastTransitionAt?.let { "${((now - it) / TICK_MS).coerceAtLeast(0)} s" } ?: "—")
}

@Composable
private fun EvidenceList(classification: ScreenClassification?) {
    val evidence = classification?.evidence.orEmpty()
    val candidates = classification?.candidates.orEmpty()
    if (evidence.isEmpty() && candidates.isEmpty()) return
    Text("Evidencia", style = MaterialTheme.typography.labelLarge)
    evidence.forEach { e ->
        val mark = if (e.validated) "✓" else "?"
        Text("$mark ${e.code.description}", style = MaterialTheme.typography.bodySmall)
    }
    if (candidates.isNotEmpty()) {
        Text(
            "Dudosa entre: " + candidates.joinToString { "${it.type.name} ${(it.confidence * 100).roundToInt()} %" },
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun DiagnosticsLines(state: LifecycleDebugState) {
    val d = state.diagnostics
    DebugLine("Salud del detector", d.health.name)
    Text(
        text = "Lecturas ${d.snapshotsReceived} · procesadas ${d.snapshotsProcessed} · repetidas " +
            "${d.duplicateSnapshotsIgnored} · otras apps ${d.otherPackageIgnored} · reconocidas " +
            "${d.screensRecognized} · desconocidas ${d.unknownScreens} · dudosas ${d.ambiguousScreens} · " +
            "eventos ${d.eventsGenerated} · repetidos ${d.duplicateEventsIgnored} · inválidas " +
            "${d.invalidTransitions} · recuperaciones ${d.recoveries} · fallos ${d.detectorFailures}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun HistoryList(state: LifecycleDebugState) {
    if (state.history.isEmpty()) {
        Text("Sin eventos todavía.", style = MaterialTheme.typography.bodySmall)
        return
    }
    state.history.asReversed().forEach { entry ->
        val time = Instant.ofEpochMilli(entry.atMs).atZone(ZoneId.systemDefault()).format(HISTORY_TIME)
        Text(
            text = "$time ${entry.kind.name} ${entry.event.name} · ${entry.from.name} → ${entry.to.name}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun DebugLine(label: String, value: String) {
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

private fun shortHash(hash: String): String =
    if (hash.length <= HASH_HEAD + HASH_TAIL) hash else hash.take(HASH_HEAD) + "…" + hash.takeLast(HASH_TAIL)
