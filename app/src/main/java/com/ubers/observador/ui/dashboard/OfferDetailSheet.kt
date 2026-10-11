package com.ubers.observador.ui.dashboard

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
fun OfferDetailSheet(detail: OfferDetail, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        OfferDetailContent(detail)
    }
}

@Composable
private fun OfferDetailContent(detail: OfferDetail) {
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
            Surface(color = Color(quality.backgroundArgb), shape = RoundedCornerShape(8.dp)) {
                Text(
                    text = "${o.score} · ${quality.label}",
                    color = Color(quality.foregroundArgb),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
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
            ScorePart("Pago por km", ScoreBreakdown.WEIGHT_KM_PCT, parts.km)
            ScorePart("Pago por hora", ScoreBreakdown.WEIGHT_HOUR_PCT, parts.hour)
            ScorePart("Distancia de recogida", ScoreBreakdown.WEIGHT_PICKUP_KM_PCT, parts.pickupKm)
            ScorePart("Tiempo de recogida", ScoreBreakdown.WEIGHT_PICKUP_MIN_PCT, parts.pickupMin)
            HorizontalDivider()
            DetailLine("Score final", "${o.score} · ${quality.label}", strong = true)
        }

        Text(
            text = "Calculado con tus ajustes actuales. La exportación guarda los valores del momento " +
                "en que se detectó la oferta.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DetailSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        content()
    }
}

@Composable
private fun DetailLine(label: String, value: String, strong: Boolean = false) {
    val weight = if (strong) FontWeight.Bold else FontWeight.Normal
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, fontWeight = weight)
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
        LinearProgressIndicator(
            progress = { (points / 100.0).toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth()
        )
    }
}
