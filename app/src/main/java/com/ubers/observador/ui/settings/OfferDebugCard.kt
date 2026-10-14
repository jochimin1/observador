package com.ubers.observador.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ubers.observador.service.OfferDebug
import com.ubers.observador.service.UberAccessibilityService
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss")

/**
 * Fase 8F: why an offer card was or was not shown, and the full reading of the last offer and
 * pickup screens, to send them for a check. The copies contain names and addresses.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OfferDebugCard() {
    val s by OfferDebug.state.collectAsState()
    val context = LocalContext.current
    var message by remember { mutableStateOf<String?>(null) }
    fun copy(label: String, text: String?) {
        message = if (text == null) {
            "Todavía no hay $label en esta sesión del servicio."
        } else {
            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Observador · $label", text))
            "Copiada la $label. Contiene nombres y direcciones: compártela solo conmigo."
        }
    }
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Ofertas (diagnóstico)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            Text(
                "Desde que se activó el servicio. Si ves lecturas con «Aceptar» pero 0 leídas, Observador no entiende la tarjeta de Uber: " +
                    "copia la lectura de la oferta y envíamela.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            fun line(label: String, value: String) = "$label: $value"
            val lines = listOf(
                line("Servicio", if (UberAccessibilityService.instance != null) "conectado" else "sin conexión"),
                line("Lecturas con «Aceptar»", s.offerScreens.toString()),
                line("Ofertas leídas", s.parsed.toString() + if (s.fromOtherWindow > 0) " (${s.fromOtherWindow} en otra ventana)" else ""),
                line("Tarjetas mostradas", s.overlaysShown.toString() + if (s.overlaysSkippedBySetting > 0) " · ${s.overlaysSkippedBySetting} no por el ajuste «Mostrar tarjeta»" else ""),
                line("Notificaciones", s.notifications.toString()),
                line("Último resultado", s.lastStep?.label?.let { it + (s.lastAt?.let { at -> " · " + Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(TIME) } ?: "") } ?: "—"),
                line("Recogida leída", s.lastPlaces?.pickup ?: "—"),
                line("Destino leído", s.lastPlaces?.dropoff ?: "—"),
                line("Pasajero", s.lastRider ?: "—")
            ) + s.failures.map { (step, n) -> line("Falló · ${step.label}", n.toString()) }
            lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { copy("lectura de la oferta", OfferDebug.fullOffer()) }) { Text("Copiar oferta") }
                OutlinedButton(onClick = { copy("lectura de la recogida", OfferDebug.fullRider()) }) { Text("Copiar recogida") }
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
