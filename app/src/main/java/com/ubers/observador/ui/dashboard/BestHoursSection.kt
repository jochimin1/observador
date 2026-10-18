package com.ubers.observador.ui.dashboard

import com.ubers.observador.R
import com.ubers.observador.ui.components.SectionCard

import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ubers.observador.domain.BestHours
import com.ubers.observador.domain.SampleConfidence
import com.ubers.observador.domain.StatsPeriod
import com.ubers.observador.domain.TimeSlotStats
import kotlin.math.roundToInt

/**
 * "Mejores horas para salir": the best slot first (the one-glance answer), then the 12-slot
 * chart, then the Top 3. Everything shown was computed in the ViewModel; this only lays it out.
 * Tapping the best card, a bar or a Top 3 row opens [SlotDetailSheet] through [onSelectSlot].
 */
@Composable
fun BestHoursSection(
    state: BestHoursUiState,
    selectedPeriod: StatsPeriod,
    selectedStartHour: Int?,
    onSelectPeriod: (StatsPeriod) -> Unit,
    onSelectSlot: (TimeSlotStats) -> Unit,
    modifier: Modifier = Modifier
) {
    val use24Hour = DateFormat.is24HourFormat(LocalContext.current)
    val stats = state.stats
    SectionCard(
        title = "Mejores horas para salir",
        icon = R.drawable.ic_trending_up,
        subtitle = "Cuándo pagaron mejor los viajes que hiciste",
        badgeContainer = MaterialTheme.colorScheme.tertiaryContainer,
        badgeContent = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = modifier
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BestHours.PERIODS.forEach { period ->
                    FilterChip(
                        selected = period == selectedPeriod,
                        onClick = { onSelectPeriod(period) },
                        label = { Text(period.label) }
                    )
                }
            }
            when {
                state.loading -> Unit
                stats.totalOffers == 0 -> Note("Necesitamos más viajes realizados para identificar tus mejores horas.")
                else -> {
                    val best = stats.bestSlot
                    if (best == null) {
                        Note("Pocos datos todavía.\nSigue usando Observador y aquí aparecerán tus mejores franjas.")
                    } else {
                        BestSlotCard(best, use24Hour, onClick = { onSelectSlot(best) })
                    }
                    Text(
                        text = "Pago mediano por hora en cada franja",
                        style = MaterialTheme.typography.labelLarge
                    )
                    if (com.ubers.observador.ui.theme.LocalPastelBackground.current) {
                        // 1.28.0, Violeta: Vico columns; the slots are opened from the list below
                        com.ubers.observador.ui.earnings.VioletColumns(
                            values = stats.slots.map { it.medianRdpPerHour },
                            labels = stats.slots.mapIndexed { i, s -> if (i % 2 == 0) BestHours.axisLabel(s.startHour, use24Hour) else "" },
                            description = stats.slots.joinToString(". ") { BestHours.describe(it, isBest = it.startHour == best?.startHour) }
                        )
                    } else {
                        BestHoursChart(
                            slots = stats.slots,
                            bestStartHour = best?.startHour,
                            selectedStartHour = selectedStartHour,
                            minRdpPerHour = state.minRdpPerHour,
                            use24Hour = use24Hour,
                            onSelect = onSelectSlot
                        )
                    }
                    // With a single valid slot the list would only repeat the card above
                    if (stats.rankedSlots.size > 1) {
                        TopSlots(stats.rankedSlots, use24Hour, onSelectSlot)
                    }
                    Text(
                        text = "Basado en tus viajes realizados de los últimos ${state.period.label}. " +
                            "Toca una franja para ver el detalle.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** The answer at a glance: big hour range, big RD$/h, then the reasons in small print. */
@Composable
private fun BestSlotCard(slot: TimeSlotStats, use24Hour: Boolean, onClick: () -> Unit) {
    val spoken = remember(slot) {
        "Mejor franja observada. ${BestHours.describe(slot)} " +
            "Índice de oportunidad ${slot.opportunityIndex ?: 0} de 100."
    }
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .clickable(onClickLabel = "Ver detalle", role = Role.Button, onClick = onClick)
                .clearAndSetSemantics { contentDescription = spoken }
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text("🏆 Mejor franja observada", style = MaterialTheme.typography.labelLarge)
            Text(
                text = BestHours.fullSlotLabel(slot.startHour, slot.endHour, use24Hour),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "${hourly(slot.medianRdpPerHour)}/h mediano",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "${perKm(slot.medianRdpPerKm)}/km · ${percent(slot.goodOrBetterRate)} buenas o excelentes",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "${offers(slot.offerCount)} · ${slot.confidence.label}",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "Índice de oportunidad: ${slot.opportunityIndex ?: 0}/100",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/** Position, hours, RD$/h and index + offers: one row each, quick to scan. */
@Composable
private fun TopSlots(ranked: List<TimeSlotStats>, use24Hour: Boolean, onSelect: (TimeSlotStats) -> Unit) {
    Column {
        Text(
            text = "Top ${ranked.size} franjas",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.semantics { heading() }
        )
        ranked.forEachIndexed { i, slot ->
            val position = i + 1
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = MIN_TOUCH)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClickLabel = "Ver detalle", role = Role.Button) { onSelect(slot) }
                    .clearAndSetSemantics { contentDescription = BestHours.describeRank(position, slot) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "$position",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.widthIn(min = 20.dp)
                )
                Text(
                    text = BestHours.slotLabel(slot.startHour, slot.endHour, use24Hour),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "${hourly(slot.medianRdpPerHour)}/h",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "índice ${slot.opportunityIndex ?: 0} · ${offers(slot.offerCount)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** Everything known about one slot. Closes by swiping down or with Back. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SlotDetailSheet(slot: TimeSlotStats, isBest: Boolean, onDismiss: () -> Unit) {
    val use24Hour = DateFormat.is24HourFormat(LocalContext.current)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(modifier = Modifier.semantics(mergeDescendants = true) {}) {
                Text(
                    text = BestHours.fullSlotLabel(slot.startHour, slot.endHour, use24Hour),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                if (isBest) {
                    Text(
                        text = "🏆 Mejor franja observada",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    text = "${offers(slot.offerCount)} · ${slot.confidence.label}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (slot.offerCount == 0) {
                Note("No hiciste viajes en esta franja durante el periodo.")
            } else {
                if (!slot.isValid) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "Pocos datos: con menos de ${SampleConfidence.LOW_FROM} viajes estos números " +
                                "pueden cambiar mucho, y la franja no compite en el ranking.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
                SlotDetailLines(slot)
            }
            Text(
                text = "Tus viajes realizados en esta franja, con lo que prometía su oferta y tus " +
                    "ajustes actuales. No es el dinero real cobrado.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SlotDetailLines(slot: TimeSlotStats) {
    DetailLine("RD$/hora mediano", hourly(slot.medianRdpPerHour), strong = true)
    DetailLine("Rango típico (P25–P75)", "${hourly(slot.p25RdpPerHour)}–${(slot.p75RdpPerHour ?: 0.0).roundToInt()}")
    DetailLine("RD$/km mediano", perKm(slot.medianRdpPerKm))
    DetailLine("Score mediano", "${(slot.medianScore ?: 0.0).roundToInt()}")
    HorizontalDivider()
    DetailLine("Buenas + excelentes", percent(slot.goodOrBetterRate))
    DetailLine("Excelentes", percent(slot.excellentRate))
    DetailLine("Recogida típica", "%.1f km · %d min".format(slot.medianPickupKm ?: 0.0, (slot.medianPickupMinutes ?: 0.0).roundToInt()))
    HorizontalDivider()
    DetailLine("Frecuencia", "%.1f viajes/h".format(slot.offersPerHour))
    slot.minutesPerOffer?.let { DetailLine("Ritmo", "≈ 1 cada ${minutes(it)} min") }
    DetailLine("Días con viajes", "${slot.activeDays}")
    DetailLine("Confianza", slot.confidence.level)
    HorizontalDivider()
    DetailLine(
        label = "Índice de oportunidad",
        value = slot.opportunityIndex?.let { "$it/100" } ?: "No compite",
        strong = true
    )
}

// ---- Wording only: every number was computed before reaching the screen ----

private fun hourly(value: Double?) = "RD$${(value ?: 0.0).roundToInt()}"
private fun perKm(value: Double?) = "RD$%.2f".format(value ?: 0.0)
private fun percent(rate: Double) = "${(rate * 100).roundToInt()}%"
private fun offers(count: Int) = if (count == 1) "1 viaje" else "$count viajes"
private fun minutes(value: Double) = if (value < 10) "%.1f".format(value) else "${value.roundToInt()}"

private val MIN_TOUCH = 48.dp
