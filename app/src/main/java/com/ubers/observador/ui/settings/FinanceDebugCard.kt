package com.ubers.observador.ui.settings

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ubers.observador.domain.finance.AmountSemantics
import com.ubers.observador.domain.finance.FinancialObservation
import com.ubers.observador.domain.finance.ReconciliationStatus
import com.ubers.observador.domain.money.MoneyAmount
import com.ubers.observador.domain.money.MoneyFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val MONEY_TIME = DateTimeFormatter.ofPattern("HH:mm")

private fun moneyClock(ms: Long?): String =
    ms?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(MONEY_TIME) } ?: "—"

private fun rd(minor: Long, currency: String) = MoneyFormat.format(MoneyAmount(minor, currency))

/**
 * Debug builds only (Fase 7C): "Dinero detectado". Real money read from Uber screens, the
 * working day's total with its coverage, and Uber's own figure when one was seen. Offer prices
 * never appear here as income. The money labels are candidates until real captures confirm them.
 */
@Composable
fun FinanceDebugCard(viewModel: FinanceDebugViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Dinero detectado", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            Text(
                text = "Solo dinero que Uber mostró después del viaje. El precio de una oferta nunca cuenta " +
                    "como ingreso. Las etiquetas de Uber aún son provisionales.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val m = state.metrics
            Text(
                text = "Esta sesión: pantallas ${m.financialScreensProcessed} · desconocidas ${m.financialScreensUnknown} · " +
                    "cantidades ${m.observationsCreated} · repetidas ${m.duplicatesIgnored} · sin vincular " +
                    "${m.unassignedObservations} · resúmenes ${m.summariesUpdated} · Uber ${m.snapshotsCreated} · " +
                    "sin etiqueta ${m.unlabeledAmounts} · ambiguas ${m.ambiguousAmounts} · ilegibles " +
                    "${m.moneyParseFailures} · errores ${m.persistenceErrors}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            state.workday?.let { day ->
                HorizontalDivider()
                val s = day.summary
                Text("Jornada actual", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text("Ingreso bruto real: ${rd(s.totalObservedEarnings, s.currencyCode)}", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "Viajes ${rd(s.tripEarnings, s.currencyCode)} · otros ingresos ${rd(s.unassignedEarnings, s.currencyCode)}\n" +
                        "Propinas ${rd(s.tips, s.currencyCode)} · promociones ${rd(s.promotions, s.currencyCode)} · " +
                        "ajustes ${rd(s.adjustments, s.currencyCode)}\n" +
                        "Completados ${s.completedTripCount} · con dinero confirmado ${s.confirmedTripCount} · " +
                        "parciales ${s.partialTripCount} · sin dato ${s.unknownTripCount}" +
                        (s.coverage?.let { " · cobertura $it%" } ?: ""),
                    style = MaterialTheme.typography.bodySmall
                )
                if (s.pendingReviewCount > 0) {
                    Text(
                        "${s.pendingReviewCount} cantidades pendientes de revisar (no sumadas)" +
                            if (s.unlinkedPendingCount > 0) ", ${s.unlinkedPendingCount} pendientes de vincular" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                val r = day.reconciliation
                Text(
                    text = "Uber (hoy): " + (day.latestUberDay?.let { "${MoneyFormat.format(it.amount)} a las ${moneyClock(it.capturedAtMs)}" } ?: "no visto") +
                        "\nConciliación: " + reconciliationLabel(r.status) +
                        (r.difference?.let { " · diferencia ${MoneyFormat.format(it, signed = true)}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { viewModel.loadTestMoney() }) { Text("Cargar prueba") }
                OutlinedButton(onClick = { viewModel.clearTestMoney() }) { Text("Borrar prueba") }
            }
            TextButton(onClick = { viewModel.rebuildSummaries() }) { Text("Reconstruir resúmenes") }
            state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

            Text(
                "Pendientes de vincular: ${state.unassigned.size}",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium
            )
            if (state.recent.isNotEmpty()) HorizontalDivider()
            state.recent.forEach { ObservationRow(it) }
        }
    }
}

@Composable
private fun ObservationRow(o: FinancialObservation) {
    val sign = o.semantics == AmountSemantics.DELTA
    Text(
        text = "${moneyClock(o.observedAtMs)} ${o.type.name} ${MoneyFormat.format(o.amount, signed = sign)} " +
            "${o.semantics.name.lowercase()} · " + (o.tripId?.let { "viaje ${it.take(8)}…" } ?: "sin vincular") +
            " · ${(o.confidence * 100).toInt()}%",
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace
    )
}

private fun reconciliationLabel(status: ReconciliationStatus): String = when (status) {
    ReconciliationStatus.MATCHED -> "coincide"
    ReconciliationStatus.DIFFERENCE -> "diferencia sin explicar"
    ReconciliationStatus.INCOMPLETE -> "pendiente (faltan datos)"
    ReconciliationStatus.NO_REFERENCE -> "sin dato de Uber"
    ReconciliationStatus.CURRENCY_MISMATCH -> "moneda distinta"
    ReconciliationStatus.PERIOD_MISMATCH -> "periodo de Uber distinto o desconocido"
}
