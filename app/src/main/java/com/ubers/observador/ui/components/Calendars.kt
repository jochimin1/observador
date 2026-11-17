package com.ubers.observador.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kizitonwose.calendar.compose.HorizontalCalendar
import com.kizitonwose.calendar.compose.rememberCalendarState
import com.kizitonwose.calendar.core.CalendarDay
import com.kizitonwose.calendar.core.DayPosition
import com.kizitonwose.calendar.core.daysOfWeek
import com.ubers.observador.R
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * 1.62.0: the app's calendars, on kizitonwose/Calendar (MIT). One month at a time, swiped or with
 * the arrows, weeks starting on the driver's first day of the week. Used to pick a date, a range
 * of dates, and to show money per day in Ganancias. Dates are plain local dates (no time zone).
 */
private val ES = Locale.forLanguageTag("es-DO")
private val MONTH_TITLE = DateTimeFormatter.ofPattern("MMMM yyyy", ES)
private val DAY_LONG = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM yyyy", ES)
val CALENDAR_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy", ES)

/** What a day cell shows under its number (money of the day, for example), and how strong its colour is (0..1). */
data class DayMark(val text: String? = null, val strength: Float = 0f)

/**
 * One month calendar. [isSelected] / [inRange] paint the selection; [enabled] greys out days that
 * cannot be chosen; [marks] adds a line under the number.
 */
@Composable
fun MonthCalendar(
    initialMonth: YearMonth,
    onDay: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY,
    minMonth: YearMonth = initialMonth.minusYears(10),
    maxMonth: YearMonth = YearMonth.now().plusYears(1),
    isSelected: (LocalDate) -> Boolean = { false },
    inRange: (LocalDate) -> Boolean = { false },
    enabled: (LocalDate) -> Boolean = { true },
    marks: Map<LocalDate, DayMark> = emptyMap(),
    onMonthShown: (YearMonth) -> Unit = {}
) {
    val first = initialMonth.coerceIn(minMonth, maxMonth)
    val state = rememberCalendarState(startMonth = minMonth, endMonth = maxMonth, firstVisibleMonth = first, firstDayOfWeek = firstDayOfWeek)
    val scope = rememberCoroutineScope()
    val shown = state.firstVisibleMonth.yearMonth
    androidx.compose.runtime.LaunchedEffect(shown) { onMonthShown(shown) }
    val today = LocalDate.now()
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(enabled = shown > minMonth, onClick = { scope.launch { state.animateScrollToMonth(shown.minusMonths(1)) } }) {
                Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = "Mes anterior")
            }
            Text(MONTH_TITLE.format(shown).replaceFirstChar { it.titlecase(ES) }, style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            IconButton(enabled = shown < maxMonth, onClick = { scope.launch { state.animateScrollToMonth(shown.plusMonths(1)) } }) {
                Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = "Mes siguiente")
            }
        }
        HorizontalCalendar(
            state = state,
            monthHeader = {
                Row(Modifier.fillMaxWidth()) {
                    daysOfWeek(firstDayOfWeek).forEach { d ->
                        Text(d.getDisplayName(TextStyle.SHORT, ES).take(2).replaceFirstChar { it.titlecase(ES) },
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                    }
                }
            },
            dayContent = { day -> DayCell(day, today, isSelected(day.date), inRange(day.date), enabled(day.date), marks[day.date], onDay) }
        )
    }
}

@Composable
private fun DayCell(day: CalendarDay, today: LocalDate, selected: Boolean, inRange: Boolean, enabled: Boolean, mark: DayMark?, onDay: (LocalDate) -> Unit) {
    val inMonth = day.position == DayPosition.MonthDate
    val colors = MaterialTheme.colorScheme
    val background: Color = when {
        !inMonth -> Color.Transparent
        selected -> colors.primary
        inRange -> colors.primaryContainer
        mark != null && mark.strength > 0f -> colors.tertiary.copy(alpha = 0.12f + 0.5f * mark.strength.coerceIn(0f, 1f))
        else -> Color.Transparent
    }
    val content: Color = when {
        !inMonth -> Color.Transparent
        !enabled -> colors.onSurface.copy(alpha = 0.3f)
        selected -> colors.onPrimary
        inRange -> colors.onPrimaryContainer
        else -> colors.onSurface
    }
    val label = DAY_LONG.format(day.date) + (mark?.text?.let { ", $it" } ?: "") + if (selected) ", elegido" else ""
    Box(
        Modifier
            .aspectRatio(if (mark != null) 0.8f else 1f)
            .padding(2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .then(if (inMonth && day.date == today && !selected) Modifier.border(1.dp, colors.primary, RoundedCornerShape(10.dp)) else Modifier)
            .then(if (inMonth && enabled) Modifier.clickable { onDay(day.date) } else Modifier)
            .semantics { if (inMonth) contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        if (inMonth) Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(day.date.dayOfMonth.toString(), color = content, style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (day.date == today) FontWeight.Bold else FontWeight.Normal)
            mark?.text?.let { Text(it, color = content, style = MaterialTheme.typography.labelSmall, maxLines = 1) }
        }
    }
}

/** A dialog to pick one date; [max] / [min] limit it (null: no limit). */
@Composable
fun CalendarDateDialog(
    title: String,
    selected: LocalDate?,
    onDismiss: () -> Unit,
    onPick: (LocalDate) -> Unit,
    min: LocalDate? = null,
    max: LocalDate? = null,
    firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY
) {
    var chosen by remember { mutableStateOf(selected) }
    val start = selected ?: max?.takeIf { it.isBefore(LocalDate.now()) } ?: LocalDate.now()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MonthCalendar(
                    initialMonth = YearMonth.from(start),
                    onDay = { chosen = it },
                    firstDayOfWeek = firstDayOfWeek,
                    minMonth = min?.let(YearMonth::from) ?: YearMonth.from(start).minusYears(10),
                    maxMonth = max?.let(YearMonth::from) ?: YearMonth.now().plusYears(1),
                    isSelected = { it == chosen },
                    enabled = { d -> (min == null || !d.isBefore(min)) && (max == null || !d.isAfter(max)) }
                )
                Text(chosen?.let { DAY_LONG.format(it) } ?: "Toca un día", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(enabled = chosen != null, onClick = { chosen?.let(onPick) }) { Text("Listo") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

/** A dialog to pick a range: first tap is the start, second the end (an earlier day starts again). */
@Composable
fun CalendarRangeDialog(
    title: String,
    from: LocalDate?,
    to: LocalDate?,
    onDismiss: () -> Unit,
    onPick: (LocalDate, LocalDate) -> Unit,
    max: LocalDate? = null,
    firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY
) {
    var start by remember { mutableStateOf(from) }
    var end by remember { mutableStateOf(to) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MonthCalendar(
                    initialMonth = YearMonth.from(to ?: from ?: max ?: LocalDate.now()),
                    onDay = { d ->
                        val s = start
                        if (s == null || end != null || d.isBefore(s)) { start = d; end = null } else end = d
                    },
                    firstDayOfWeek = firstDayOfWeek,
                    maxMonth = max?.let(YearMonth::from) ?: YearMonth.now().plusYears(1),
                    isSelected = { it == start || it == end },
                    inRange = { d -> val s = start; val e = end; s != null && e != null && d.isAfter(s) && d.isBefore(e) },
                    enabled = { d -> max == null || !d.isAfter(max) }
                )
                Text(when {
                    start == null -> "Toca el primer día"
                    end == null -> "Desde ${CALENDAR_DATE.format(start)}: toca el último día (o «Listo» para un solo día)"
                    else -> "${CALENDAR_DATE.format(start)} → ${CALENDAR_DATE.format(end)}"
                }, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = start != null, onClick = { start?.let { s -> onPick(s, end ?: s) } }) { Text("Listo") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

/** A full-width button with the date that opens [CalendarDateDialog]. */
@Composable
fun CalendarDateButton(
    label: String,
    date: LocalDate?,
    onDate: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    min: LocalDate? = null,
    max: LocalDate? = null
) {
    var open by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = modifier.fillMaxWidth()) {
        Icon(painterResource(R.drawable.ic_calendar_month), contentDescription = null, modifier = Modifier.size(18.dp))
        Text("  $label: ${date?.let { CALENDAR_DATE.format(it) } ?: "elegir"}")
    }
    if (open) CalendarDateDialog(label, date, onDismiss = { open = false }, onPick = { onDate(it); open = false }, min = min, max = max)
}

/** A small dot legend, for calendars that colour days by intensity. */
@Composable
fun CalendarLegend(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.6f)))
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun YearMonth.coerceIn(min: YearMonth, max: YearMonth): YearMonth = if (this < min) min else if (this > max) max else this
