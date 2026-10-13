package com.ubers.observador.ui.intelligence

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ubers.observador.R
import com.ubers.observador.domain.online.OnlineFormat
import com.ubers.observador.domain.online.WaitBucket
import com.ubers.observador.domain.online.WaitSummary
import com.ubers.observador.domain.online.TripWaits
import com.ubers.observador.ui.components.InfoLine
import com.ubers.observador.ui.components.LegendDot
import com.ubers.observador.ui.components.SectionCard
import com.ubers.observador.ui.components.StackedBar
import com.ubers.observador.ui.components.StatTile

private fun slot(h: Int) = "%d:00–%d:00".format(h, (h + 2) % 24)

/**
 * "Espera entre viajes": from the end of a trip to accepting the next. Average, median and the
 * longest; how waits split into short, medium and long; and the two-hour slots where you wait
 * least and most (only slots with 3 waits or more).
 */
@Composable
fun WaitsCard(s: WaitSummary) {
    SectionCard(
        title = "Espera entre viajes",
        icon = R.drawable.ic_hourglass_top,
        subtitle = "De terminar un viaje a aceptar el siguiente",
        badgeContainer = MaterialTheme.colorScheme.secondaryContainer,
        badgeContent = MaterialTheme.colorScheme.onSecondaryContainer
    ) {
        if (s.count == 0) {
            Text(
                "Aún no hay esperas en este periodo. Se cuentan cuando terminas un viaje y aceptas otro en la misma conexión.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else WaitsContent(s)
    }
}

@Composable
private fun WaitsContent(s: WaitSummary) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(R.drawable.ic_timer, "Mediana", s.medianMs?.let(OnlineFormat::duration) ?: "—", Modifier.weight(1f),
                    detail = "la mitad espera menos")
                StatTile(R.drawable.ic_hourglass_top, "Promedio", s.averageMs?.let(OnlineFormat::duration) ?: "—", Modifier.weight(1f),
                    detail = "${s.count} esperas")
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(R.drawable.ic_warning, "La más larga", s.longestMs?.let(OnlineFormat::duration) ?: "—", Modifier.weight(1f),
                    accent = MaterialTheme.colorScheme.error)
                val fast = s.fastestSlot
                StatTile(
                    R.drawable.ic_bolt, "Esperas menos", fast?.let { slot(it.startHour) } ?: "—", Modifier.weight(1f),
                    detail = fast?.let { "mediana ${OnlineFormat.duration(it.medianMs)} · ${it.count} esperas" }
                        ?: "Faltan ${TripWaits.MIN_SLOT_SAMPLES} esperas por franja",
                    accent = MaterialTheme.colorScheme.tertiary
                )
            }
            val scheme = MaterialTheme.colorScheme
            val colors = mapOf(
                WaitBucket.SHORT to scheme.primary,
                WaitBucket.MEDIUM to scheme.tertiary,
                WaitBucket.LONG to scheme.secondary,
                WaitBucket.VERY_LONG to scheme.error
            )
            val parts = WaitBucket.entries.map { (s.buckets[it] ?: 0).toLong() to colors.getValue(it) }
            StackedBar(parts, WaitBucket.entries.joinToString(", ") { "${it.label}: ${s.buckets[it] ?: 0}" })
            WaitBucket.entries.filter { (s.buckets[it] ?: 0) > 0 }.forEach { b ->
                LegendDot(colors.getValue(b), "${b.label}: ${s.buckets[b]}")
            }
            s.slowestSlot?.let {
                InfoLine(R.drawable.ic_trending_down, "Donde más esperas: ${slot(it.startHour)}, mediana ${OnlineFormat.duration(it.medianMs)}.")
            }
            if (s.outsideSession > 0) {
                InfoLine(
                    R.drawable.ic_info,
                    "${s.outsideSession} de ${s.count} sin sesión en línea alrededor: solo cuentan si la pausa fue de 30 min o menos."
                )
            }
        }
}
