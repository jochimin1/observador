package com.ubers.observador.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ubers.observador.R
import com.ubers.observador.domain.DayStrip
import com.ubers.observador.domain.OfferQuality
import com.ubers.observador.domain.QualityGroups
import com.ubers.observador.domain.Rings
import com.ubers.observador.domain.StripDay
import com.ubers.observador.domain.distance.DistanceFormat
import com.ubers.observador.domain.earnings.EarningsFormat
import com.ubers.observador.domain.money.Currencies
import com.ubers.observador.domain.online.OnlineFormat
import com.ubers.observador.ui.earnings.GoalStripViewModel
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/*
 * Inicio in the Violeta theme (1.27.0): the purple goal card with its ring, the carousel of the
 * working day, the category groups and the strip of days. Same figures as the other themes.
 */

private val ES = Locale.forLanguageTag("es-DO")

/** A ring with its percentage in the middle; the track is a pale version of the color. */
@Composable
fun ProgressRing(percent: Int, color: Color, track: Color, size: Dp, stroke: Dp, textColor: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = stroke.toPx()
            val inset = w / 2
            val arcSize = androidx.compose.ui.geometry.Size(this.size.width - w, this.size.height - w)
            val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
            drawArc(track, 0f, 360f, false, topLeft, arcSize, style = Stroke(w))
            drawArc(color, -90f, 360f * percent.coerceIn(0, 100) / 100f, false, topLeft, arcSize, style = Stroke(w, cap = StrokeCap.Round))
        }
        Text("$percent%", color = textColor, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

/** The purple card of today's goal. Nothing without a goal; tapping opens Ganancias. */
@Composable
fun GoalHeroCard(onOpen: (() -> Unit)?, modifier: Modifier = Modifier, viewModel: GoalStripViewModel = viewModel()) {
    val strip by viewModel.state.collectAsState()
    val s = strip ?: return
    val p = s.progress
    val money = { minor: Long -> EarningsFormat.money(minor, Currencies.DOP) }
    val pct = Rings.ofPermille(p.permille)
    val line = if (p.reached) "¡Meta alcanzada!" else "Te faltan ${money(p.remainingMinor)}"
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.primary,
        shadowElevation = 6.dp,
        modifier = modifier.fillMaxWidth().clearAndSetSemantics {
            contentDescription = "${s.title}: ${money(p.earnedMinor)} de ${money(p.goalMinor)}, $pct por ciento. $line"
            if (onOpen != null) role = Role.Button
        }.then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier)
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(s.title, color = onPrimary.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium)
                Text("${money(p.earnedMinor)} de ${money(p.goalMinor)}", color = onPrimary, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(line, color = onPrimary.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
                if (onOpen != null) {
                    Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFFEDE7FF), modifier = Modifier.padding(top = 6.dp)) {
                        Text(
                            "Ver ganancias",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }
            }
            ProgressRing(pct, onPrimary, onPrimary.copy(alpha = 0.25f), 88.dp, 9.dp, onPrimary)
        }
    }
}

private data class JourneyTile(val label: String, val value: String, val detail: String, val percent: Int?, val color: Color, val tint: Color)

/** «En curso»: the working day in three colored cards, scrolled sideways. */
@Composable
fun JourneyCarousel(state: DashboardUiState, online: OnlineUiState, distance: DistanceUiState, modifier: Modifier = Modifier) {
    val o = online.summary
    val day = distance.day
    val tiles = listOfNotNull(
        JourneyTile(
            "Ofertas y viajes", "${state.originCounts.all} ofertas",
            "${state.originCounts.taken} tomadas", Rings.percent(state.originCounts.taken.toLong(), state.originCounts.all.toLong()),
            Color(0xFFE7F1FF), Color(0xFF0087FF)
        ),
        JourneyTile(
            "Tiempo en línea", OnlineFormat.duration(o.confirmedMs),
            o.utilizationPercent?.let { "$it% con viaje" } ?: "sin viajes medidos", o.utilizationPercent,
            Color(0xFFFFE9E1), Color(0xFFFF7D53)
        ).takeIf { !online.loading },
        day?.let { d ->
            JourneyTile(
                "Kilómetros de hoy", "${DistanceFormat.km(d.measuredMm)} km",
                "${DistanceFormat.km(d.passengerMm)} km con pasajero", DistanceFormat.percent(d.passengerMm, d.measuredMm),
                Color(0xFFE6F7EE), Color(0xFF1E9E5A)
            )
        }
    )
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle("En curso", tiles.size)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(tiles, key = { it.label }) { t ->
                Surface(shape = RoundedCornerShape(20.dp), color = t.color, modifier = Modifier.width(200.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(t.label, style = MaterialTheme.typography.bodySmall, color = Color(0xFF4A4760))
                        Text(t.value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Color(0xFF1B1B2F))
                        Text(t.detail, style = MaterialTheme.typography.bodySmall, color = Color(0xFF4A4760))
                        val f = (t.percent ?: 0) / 100f
                        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color.White)) {
                            Box(Modifier.fillMaxWidth(f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(t.tint))
                        }
                    }
                }
            }
        }
    }
}

/** «Grupos»: Excelentes, Buenas y Malas of the period, each with its ring. */
@Composable
fun QualityGroupsCard(state: DashboardUiState, modifier: Modifier = Modifier) {
    val groups = QualityGroups.of(state.summary)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle("Grupos", groups.size)
        groups.forEach { g ->
            val color = Color(g.quality.backgroundArgb)
            val name = when (g.quality) {
                OfferQuality.EXCELLENT -> "Excelentes"
                OfferQuality.GOOD -> "Buenas"
                OfferQuality.BAD -> "Malas"
            }
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest, shadowElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(14.dp).semantics(mergeDescendants = true) {},
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                        Icon(painterResource(R.drawable.ic_category), contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text("${g.count} ofertas · ${g.quality.label}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    ProgressRing(g.percent, color, color.copy(alpha = 0.18f), 52.dp, 6.dp, MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

/** The last seven working days; the chosen one in purple. Tapping it shows only that day. */
@Composable
fun DayStripRow(days: List<StripDay>, selected: LocalDate?, onSelect: (LocalDate?) -> Unit, modifier: Modifier = Modifier) {
    LazyRow(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(days, key = { it.date.toEpochDay() }) { d ->
            val chosen = d.date == selected
            val scheme = MaterialTheme.colorScheme
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (chosen) scheme.primary else scheme.surfaceContainerLowest,
                shadowElevation = if (chosen) 4.dp else 1.dp,
                modifier = Modifier.width(62.dp)
                    .semantics { this.selected = chosen }
                    .clickable(role = Role.Tab) { onSelect(if (chosen) null else d.date) }
            ) {
                val fg = if (chosen) scheme.onPrimary else scheme.onSurface
                Column(Modifier.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        d.date.month.getDisplayName(TextStyle.SHORT, ES).replace(".", "").replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.labelSmall, color = fg
                    )
                    Text("${d.date.dayOfMonth}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = fg)
                    Text(
                        if (d.isToday) "Hoy" else d.date.dayOfWeek.getDisplayName(TextStyle.SHORT, ES).replace(".", "").replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.labelSmall, color = fg
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        d.offers?.let { "$it" } ?: "·",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (chosen) fg else scheme.primary,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, count: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Box(Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 8.dp, vertical = 2.dp)) {
            Text("$count", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** The strip of the dashboard's period, for [DayStripRow]. */
fun stripDays(state: DashboardUiState): List<StripDay> {
    val today = state.currentWorkday ?: return emptyList()
    return DayStrip.of(today, state.days, state.period.workdays)
}
