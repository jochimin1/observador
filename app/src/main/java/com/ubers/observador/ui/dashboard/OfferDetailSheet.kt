package com.ubers.observador.ui.dashboard

import com.ubers.observador.data.db.action
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ubers.observador.domain.OfferDetail
import com.ubers.observador.domain.ScoreBreakdown
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val DETAIL_TIME_FORMAT = DateTimeFormatter.ofPattern("EEE d MMM · h:mm a")

/** Bottom panel with the why behind one offer. Closes by swiping down or with Back. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfferDetailSheet(
    detail: OfferDetail,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
    /** Fase 8K: the trip the offer became, if it was taken: its card and its route are shown. */
    trip: com.ubers.observador.domain.trips.Trip? = null
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(containerColor = com.ubers.observador.ui.theme.sheetContainerColor(), onDismissRequest = onDismiss, sheetState = sheetState) {
        OfferDetailContent(detail, onDelete, trip)
    }
}

@Composable
private fun OfferDetailContent(detail: OfferDetail, onDelete: (() -> Unit)?, trip: com.ubers.observador.domain.trips.Trip?) {
    val o = detail.offer
    val quality = detail.quality
    val parts = detail.breakdown
    val detectedAt = Instant.ofEpochMilli(o.detectedAt).atZone(ZoneId.systemDefault()).format(DETAIL_TIME_FORMAT)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "RD$%.2f".format(o.price),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = detectedAt,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            val pill = com.ubers.observador.ui.theme.LocalPastelBackground.current
            Surface(color = Color(quality.backgroundArgb), shape = RoundedCornerShape(if (pill) 50 else 8)) {
                Text(
                    text = "${o.score} · ${quality.label}",
                    color = Color(quality.foregroundArgb),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
        // Fase 8J: modality and "Exclusivo", then which button the card had
        listOfNotNull(o.product, "Exclusivo".takeIf { o.exclusive == true }).joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
            com.ubers.observador.ui.components.InfoLine(com.ubers.observador.R.drawable.ic_two_wheeler, "Modalidad: $it")
        }
        o.action?.let { a ->
            com.ubers.observador.ui.components.InfoLine(
                com.ubers.observador.R.drawable.ic_info,
                if (a == com.ubers.observador.domain.lifecycle.OfferCardAction.INTEREST) "Tarjeta del Radar: «Me interesa» (Uber decide si te la da)"
                else "Tarjeta con «Aceptar»"
            )
        }
        // Fase 8K: a taken offer shows its trip's card and route (with Uber's addresses in it)
        if (trip != null) {
            com.ubers.observador.ui.route.TripRouteSection(trip.id)
        }
        // Fase 8F: where from and where to, as Uber wrote them on the card
        if (trip == null && (o.pickupAddress != null || o.dropoffAddress != null)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                o.pickupAddress?.let { com.ubers.observador.ui.components.InfoLine(com.ubers.observador.R.drawable.ic_route, "Recogida: $it") }
                o.dropoffAddress?.let { com.ubers.observador.ui.components.InfoLine(com.ubers.observador.R.drawable.ic_flag, "Destino: $it") }
            }
        }

        DetailSection("Recorrido") {
            DetailLine("Recogida", "%.1f km · %d min".format(o.pickupKm, o.pickupMin))
            DetailLine("Viaje", "%.1f km · %d min".format(o.tripKm, o.tripMin))
            HorizontalDivider()
            DetailLine("Total", "%.1f km · %d min".format(o.totalKm, o.totalMin), strong = true)
        }

        DetailSection("Dinero") {
            DetailLine("Pago por km", "RD$%.2f".format(o.rdpPerKm))
            DetailLine("Pago por hora", "RD$%.0f".format(o.rdpPerHour))
            DetailLine("Combustible", "− RD$%.2f".format(o.fuelCost))
            HorizontalDivider()
            DetailLine("Ganancia neta", "RD$%.2f".format(o.netProfit), strong = true)
        }

        DetailSection("Cómo se armó el score") {
            ScorePart("Pago por km", parts.weights.kmPct, parts.km)
            ScorePart("Pago por hora", parts.weights.hourPct, parts.hour)
            ScorePart("Distancia de recogida", parts.weights.pickupKmPct, parts.pickupKm)
            ScorePart("Tiempo de recogida", parts.weights.pickupMinPct, parts.pickupMin)
            HorizontalDivider()
            DetailLine("Score final", "${o.score} · ${quality.label}", strong = true)
        }

        Text(
            text = "Lo que prometía la oferta de este viaje, calculado con tus ajustes actuales. " +
                "El dinero real llega con la pantalla Ganancias.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Also reachable without the swipe gesture, and from TalkBack
        onDelete?.let { delete ->
            TextButton(onClick = delete) {
                Text(text = "Borrar esta oferta", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun DetailSection(title: String, content: @Composable () -> Unit) {
    // 1.30.0: a white card of its own in Violeta
    com.ubers.observador.ui.components.SheetGroup {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        content()
    }
}

/** "Label ........ value" row, read by TalkBack as one item. Also used by the time slot detail. */
@Composable
internal fun DetailLine(label: String, value: String, strong: Boolean = false) {
    val weight = if (strong) FontWeight.Bold else FontWeight.Normal
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // The label wraps first, so a very large font never pushes the value off the screen
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = weight,
            modifier = Modifier.weight(1f)
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium, fontWeight = weight)
    }
}

@Composable
private fun ScorePart(label: String, weightPct: Int, points: Double) {
    Column(
        modifier = Modifier.semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = "$label ($weightPct%)", style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "${points.roundToInt()} pts",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
        }
        com.ubers.observador.ui.components.AppProgressBar((points / 100.0).toFloat())
    }
}
