package com.ubers.observador.ui.earnings

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ubers.observador.R
import com.ubers.observador.domain.earnings.AssignPreview
import com.ubers.observador.domain.earnings.DayBar
import com.ubers.observador.domain.earnings.EarningsFormat
import com.ubers.observador.domain.earnings.EarningsRange
import com.ubers.observador.domain.earnings.EarningsReport
import com.ubers.observador.domain.earnings.GoalProgress
import com.ubers.observador.domain.earnings.ManualAssignmentRow
import com.ubers.observador.domain.earnings.PendingAmount
import com.ubers.observador.domain.earnings.TripMoneyRow
import com.ubers.observador.domain.earnings.TripTimeline
import com.ubers.observador.domain.expenses.ExpenseSummary
import com.ubers.observador.domain.expenses.NetResult
import com.ubers.observador.domain.finance.FinancialCompleteness
import com.ubers.observador.domain.finance.FinancialObservation
import com.ubers.observador.domain.finance.FinancialObservationType
import com.ubers.observador.domain.finance.FinancialSource
import com.ubers.observador.domain.finance.Reconciliation
import com.ubers.observador.domain.finance.ReconciliationStatus
import com.ubers.observador.domain.money.MoneyAmount
import com.ubers.observador.domain.money.MoneyFormat
import com.ubers.observador.domain.online.OnlineFormat
import com.ubers.observador.domain.online.OnlineSummary
import com.ubers.observador.domain.trips.Trip
import com.ubers.observador.ui.components.InfoLine
import com.ubers.observador.ui.components.LegendDot
import com.ubers.observador.ui.components.SectionCard
import com.ubers.observador.ui.components.SplitBar
import com.ubers.observador.ui.components.StatRow
import com.ubers.observador.ui.components.StatTile
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val ES = Locale.forLanguageTag("es-DO")
private val TIME = DateTimeFormatter.ofPattern("h:mm a", ES)
private val DAY_TIME = DateTimeFormatter.ofPattern("EEE d MMM · h:mm a", ES)

private fun rd(minor: Long) = MoneyFormat.format(MoneyAmount(minor, "DOP"))
private fun time(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(TIME)
private fun dayTime(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DAY_TIME).replace(".", "")

/**
 * Ganancias (Fase 8C): real money of the day, week or month, with its coverage; goals with an
 * honest pace; time, expenses and Uber's reference; the bars per working day; the trips, offered
 * next to real; and the amounts waiting for a trip, which can be assigned by hand (and undone).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EarningsScreen(
    onOpenReports: ((com.ubers.observador.domain.earnings.EarningsWindow?) -> Unit)? = null,
    viewModel: EarningsViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val assign by viewModel.assign.collectAsState()
    val confirm by viewModel.confirm.collectAsState()
    val detail by viewModel.detail.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var rejecting by remember { mutableStateOf<FinancialObservation?>(null) }
    var editingGoals by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }

    com.ubers.observador.ui.components.ReportExportEffects(viewModel.exporter, snackbar)
    // The weekly summary notification: open the week that ended
    val pendingOpen by com.ubers.observador.ui.AppNavigation.pending.collectAsState()
    LaunchedEffect(pendingOpen) {
        if (pendingOpen == com.ubers.observador.ui.AppNavigation.OPEN_LAST_WEEK) {
            viewModel.showLastWeek()
            com.ubers.observador.ui.AppNavigation.pending.value = null
        }
    }
    if (exporting) {
        val window = state.report?.window
        com.ubers.observador.ui.components.ReportExportDialog(
            periodLabel = window?.let { "${EarningsFormat.windowLabel(it)} (${EarningsFormat.windowDates(it)})" } ?: state.range.label,
            files = com.ubers.observador.data.export.ReportFile.entries,
            onSave = { viewModel.saveFile(it); exporting = false },
            onShare = { viewModel.shareFile(it); exporting = false },
            onDismiss = { exporting = false }
        )
    }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(it) }
    }

    assign?.let { sheet ->
        ModalBottomSheet(onDismissRequest = { viewModel.closeAssign() }) {
            AssignSheetContent(sheet, onChoose = { viewModel.choose(it) })
        }
    }
    confirm?.let { c ->
        AssignConfirmDialog(c.amount, c.trip, c.preview, onConfirm = { viewModel.confirmAssign() }, onDismiss = { viewModel.cancelConfirm() })
    }
    rejecting?.let { o ->
        AlertDialog(
            onDismissRequest = { rejecting = null },
            icon = { Icon(painterResource(R.drawable.ic_block), contentDescription = null) },
            title = { Text("¿No es tuyo?") },
            text = {
                Text(
                    "${EarningsFormat.typeLabel(o.type)} de ${MoneyFormat.format(o.amount)} se marca como «no es mío / mal leído» y " +
                        "deja de estar pendiente. No se borra: queda en el historial."
                )
            },
            confirmButton = { TextButton(onClick = { viewModel.reject(o); rejecting = null }) { Text("Marcar") } },
            dismissButton = { TextButton(onClick = { rejecting = null }) { Text("Cancelar") } }
        )
    }
    if (editingGoals) {
        GoalDialog(
            dailyMinor = state.goals.settings.dailyMinor,
            weeklyMinor = state.goals.settings.weeklyMinor,
            onDismiss = { editingGoals = false },
            onSave = { d, w -> viewModel.setDailyGoal(d); viewModel.setWeeklyGoal(w); editingGoals = false }
        )
    }
    detail?.let { d ->
        ModalBottomSheet(onDismissRequest = { viewModel.closeTrip() }) { TripDetailContent(d) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ganancias") },
                actions = {
                    IconButton(
                        onClick = { if (onOpenReports != null) onOpenReports(state.report?.window) else exporting = true },
                        enabled = state.report != null
                    ) {
                        Icon(painterResource(R.drawable.ic_download), contentDescription = "Exportar este periodo")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item { PeriodPicker(state, onRange = { viewModel.select(it) }, onPrevious = { viewModel.previous() }, onNext = { viewModel.next() }) }
            if (!MONEY_LABELS_VALIDATED) item { ValidationNotice() }
            val report = state.report
            if (report == null) {
                item { Text(if (state.loading) "Cargando…" else "No se pudo calcular este periodo.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                item { IncomeCard(report) }
                state.weekly?.let { w -> item { WeeklyCard(w) } }
                item { GoalsCard(state.goals, onEdit = { editingGoals = true }) }
                if (report.window.range != EarningsRange.DAY) item { BarsCard(report) }
                item { TimeCard(state.online, state.km, report.summary.totalObservedEarnings, report.summary.confirmedTripCount) }
                state.expenses?.let { e -> item { ExpensesCard(e, state.net, report.window.range) } }
                item { UberCard(state.reconciliation, state.uberCapturedAt) }
                if (report.pending.isNotEmpty()) item { PendingCard(report.pending, onAssign = { viewModel.openAssign(it) }, onReject = { rejecting = it }) }
                if (state.manual.isNotEmpty()) item { ManualCard(state.manual, onUndo = { viewModel.undo(it) }) }
                item {
                    Text(
                        "Viajes realizados (${report.trips.size})" + report.trips.count { !it.endConfirmed }.let { n ->
                            if (n > 0) " · $n sin cierre confirmado" else ""
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.semantics { heading() }
                    )
                }
                if (report.trips.isEmpty()) {
                    item { InfoLine(R.drawable.ic_info, "Sin viajes terminados en este periodo.") }
                } else {
                    items(report.trips, key = { it.tripId }) { row ->
                        TripRow(row, showDay = report.window.range != EarningsRange.DAY) { viewModel.openTrip(row) }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- header

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeriodPicker(state: EarningsUiState, onRange: (EarningsRange) -> Unit, onPrevious: () -> Unit, onNext: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            EarningsRange.entries.forEachIndexed { index, r ->
                SegmentedButton(
                    selected = state.range == r,
                    onClick = { onRange(r) },
                    shape = SegmentedButtonDefaults.itemShape(index, EarningsRange.entries.size)
                ) { Text(r.label) }
            }
        }
        val window = state.report?.window
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPrevious) { Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = "Periodo anterior") }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(window?.let { EarningsFormat.windowLabel(it) } ?: state.range.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                window?.let {
                    Text(EarningsFormat.windowDates(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = onNext, enabled = state.offset < 0) {
                Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = "Periodo siguiente")
            }
        }
    }
}

@Composable
private fun ValidationNotice() {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            Icon(painterResource(R.drawable.ic_science), contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(20.dp))
            Text(
                "Las etiquetas de dinero de Uber aún no se han confirmado con un viaje real. Compara estas cifras con la app de Uber " +
                    "hasta que lo estén.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
    }
}

// ---------------------------------------------------------------- income

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IncomeCard(report: EarningsReport) {
    val s = report.summary
    val minimum = EarningsFormat.isMinimum(s)
    SectionCard(
        title = "Ingreso real",
        icon = R.drawable.ic_payments,
        subtitle = "Bruto, solo dinero que Observador vio en Uber. Las ofertas no cuentan."
    ) {
        Column(
            Modifier.clearAndSetSemantics {
                contentDescription = (if (minimum) "Al menos " else "") + EarningsFormat.money(s.totalObservedEarnings, s.currencyCode) + ". " + EarningsFormat.coverage(s)
            },
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (minimum) Text("al menos", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                EarningsFormat.money(s.totalObservedEarnings, s.currencyCode),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(EarningsFormat.coverage(s), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (s.completedTripCount > 0) {
            SplitBar(
                first = s.confirmedTripCount.toFloat() / s.completedTripCount,
                description = EarningsFormat.coverage(s),
                firstColor = MaterialTheme.colorScheme.primary,
                secondColor = MaterialTheme.colorScheme.surfaceContainerHighest
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(R.drawable.ic_two_wheeler, "Viajes", rd(s.tripEarnings), detail = "${s.confirmedTripCount} con total confirmado")
            listOf(
                Triple("Propinas", s.tips, R.drawable.ic_emoji_events),
                Triple("Promociones", s.promotions, R.drawable.ic_bolt),
                Triple("Ajustes", s.adjustments, R.drawable.ic_tune),
                Triple("Otros ingresos", s.otherEarnings, R.drawable.ic_category)
            ).filter { it.second != 0L }.forEach { (label, minor, icon) ->
                StatTile(icon, label, rd(minor), detail = "dentro del total")
            }
        }
        if (s.unassignedEarnings != 0L) {
            InfoLine(R.drawable.ic_info, "${rd(s.unassignedEarnings)} de promociones u otros ingresos del periodo, sin viaje: ya están en el total.")
        }
        if (s.unlinkedPendingCount > 0 || s.pendingReviewCount > 0) {
            InfoLine(
                R.drawable.ic_pending,
                "${s.pendingReviewCount} cantidad(es) sin sumar: pendientes de vincular o por revisar. Están abajo, nunca dentro del total.",
                tint = MaterialTheme.colorScheme.tertiary
            )
        }
    }
}

// ---------------------------------------------------------------- weekly summary (8D)

@Composable
private fun WeeklyCard(w: com.ubers.observador.domain.reports.WeeklyReport) {
    SectionCard(
        title = "Resumen de la semana",
        icon = R.drawable.ic_summarize,
        subtitle = "Con las mismas cifras de esta pantalla",
        badgeContainer = MaterialTheme.colorScheme.secondaryContainer,
        badgeContent = MaterialTheme.colorScheme.onSecondaryContainer
    ) {
        val c = w.comparison
        if (c != null) {
            val up = c.differenceMinor >= 0
            StatTile(
                if (up) R.drawable.ic_trending_up else R.drawable.ic_trending_down,
                "Frente a la semana anterior",
                MoneyFormat.format(MoneyAmount(c.differenceMinor, w.income.currencyCode), signed = true) +
                    (c.percent?.let { " (${if (it.signum() > 0) "+" else ""}${it.toPlainString()} %)" } ?: ""),
                Modifier.fillMaxWidth(),
                detail = "La semana anterior: ${rd(c.previousIncomeMinor)}",
                accent = if (up) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
            )
        } else {
            InfoLine(R.drawable.ic_info, w.notComparable ?: com.ubers.observador.domain.reports.WeeklyReports.NOT_COMPARABLE_MONEY)
        }
        StatRow { m ->
            StatTile(R.drawable.ic_timer, "En línea", w.onlineMs?.let { OnlineFormat.duration(it) } ?: "—", m, detail = w.busyPercent?.let { "ocupado $it %" })
            StatTile(R.drawable.ic_trending_up, "Por hora en línea", w.perHourMinor?.let { rd(it) + "/h" } ?: "—", m)
        }
        StatRow { m ->
            StatTile(R.drawable.ic_receipt_long, "Gastos", w.expensesWorkMinor?.let { rd(it) } ?: "—", m)
            StatTile(
                R.drawable.ic_account_balance_wallet, "Neto", w.netMinor?.let { rd(it) } ?: "—", m,
                detail = if (EarningsFormat.isMinimum(w.income)) "parcial" else null
            )
        }
        when (w.goalReached) {
            true -> InfoLine(R.drawable.ic_check_circle, "Meta semanal cumplida (${rd(w.goalMinor ?: 0)}).", tint = MaterialTheme.colorScheme.tertiary)
            false -> InfoLine(R.drawable.ic_flag, "Meta semanal no cumplida: ${rd(w.income.totalObservedEarnings)} de ${rd(w.goalMinor ?: 0)}.")
            null -> Unit
        }
    }
}

// ---------------------------------------------------------------- goals

@Composable
private fun GoalsCard(goals: GoalsUi, onEdit: () -> Unit) {
    SectionCard(
        title = "Metas",
        icon = R.drawable.ic_flag,
        subtitle = "Sobre el ingreso bruto real confirmado",
        badgeContainer = MaterialTheme.colorScheme.tertiaryContainer,
        badgeContent = MaterialTheme.colorScheme.onTertiaryContainer,
        action = { TextButton(onClick = onEdit) { Text(if (goals.settings.hasAny) "Editar" else "Poner meta") } }
    ) {
        if (!goals.settings.hasAny) {
            InfoLine(R.drawable.ic_lightbulb, "Ponte una meta diaria o semanal en RD$ y verás cuánto te falta.")
        } else {
            goals.daily?.let { GoalBlock("Hoy", it) }
            goals.weekly?.let { GoalBlock("Esta semana", it) }
        }
    }
}

@Composable
private fun GoalBlock(title: String, p: GoalProgress) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("${rd(p.earnedMinor)} de ${rd(p.goalMinor)}", style = MaterialTheme.typography.bodyMedium)
        }
        LinearProgressIndicator(
            progress = { p.permille / 1000f },
            modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(50)),
            color = if (p.reached) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            drawStopIndicator = {}
        )
        if (p.reached) {
            InfoLine(R.drawable.ic_check_circle, "¡Meta alcanzada!", tint = MaterialTheme.colorScheme.tertiary)
        } else {
            Text("Te faltan ${rd(p.remainingMinor)}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            if (p.source == com.ubers.observador.domain.earnings.GoalSource.UBER) {
                InfoLine(R.drawable.ic_info, "Avance según la cifra HOY que Uber muestra sobre el mapa; Observador aún no tiene el dinero de cada viaje.")
            }
            p.pace?.let {
                InfoLine(R.drawable.ic_speed, "${EarningsFormat.pace(it)} (${rd(it.perHourMinor)}/h en línea). Es una referencia, no una promesa.")
            }
            if (p.perDayMinor != null && p.daysLeft != null) {
                InfoLine(
                    R.drawable.ic_calendar_month,
                    "Unos ${rd(p.perDayMinor)} por jornada en ${if (p.daysLeft == 1) "la jornada que queda" else "las ${p.daysLeft} jornadas que quedan"}."
                )
            }
            if (title == "Hoy" && p.pace == null) {
                InfoLine(R.drawable.ic_info, "El ritmo aparece con 30 min en línea y 2 viajes con dinero confirmado hoy.")
            }
        }
    }
}

@Composable
private fun GoalDialog(dailyMinor: Long, weeklyMinor: Long, onDismiss: () -> Unit, onSave: (Long, Long) -> Unit) {
    var daily by remember { mutableStateOf(if (dailyMinor > 0) (dailyMinor / 100).toString() else "") }
    var weekly by remember { mutableStateOf(if (weeklyMinor > 0) (weeklyMinor / 100).toString() else "") }
    fun parse(text: String): Long? = text.trim().replace(",", "").let { if (it.isEmpty()) 0L else it.toLongOrNull() }
        ?.takeIf { it in 0..(com.ubers.observador.domain.earnings.Goals.MAX_GOAL_MINOR / 100) }
    val d = parse(daily)
    val w = parse(weekly)
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(painterResource(R.drawable.ic_flag), contentDescription = null) },
        title = { Text("Metas") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("En pesos, sobre el ingreso bruto real. Deja vacío o 0 para no usar esa meta.", style = MaterialTheme.typography.bodySmall)
                androidx.compose.material3.OutlinedTextField(
                    value = daily, onValueChange = { daily = it.filter { c -> c.isDigit() }.take(7) },
                    label = { Text("Meta diaria (RD$)") }, singleLine = true, isError = d == null,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                )
                androidx.compose.material3.OutlinedTextField(
                    value = weekly, onValueChange = { weekly = it.filter { c -> c.isDigit() }.take(7) },
                    label = { Text("Meta semanal (RD$)") }, singleLine = true, isError = w == null,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                )
            }
        },
        confirmButton = { TextButton(enabled = d != null && w != null, onClick = { onSave((d ?: 0) * 100, (w ?: 0) * 100) }) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

// ---------------------------------------------------------------- bars

@Composable
private fun BarsCard(report: EarningsReport) {
    val bars = report.bars
    val max = bars.maxOfOrNull { it.incomeMinor }?.coerceAtLeast(1) ?: 1
    val best = report.bestDay
    SectionCard(
        title = "Por jornada",
        icon = R.drawable.ic_leaderboard,
        subtitle = best?.let { "Mejor día: ${EarningsFormat.dayShort(it.day)} ${it.day.dayOfMonth} · ${rd(it.incomeMinor)}" } ?: "Sin ingreso confirmado todavía"
    ) {
        val bestColor = MaterialTheme.colorScheme.tertiary
        val normal = MaterialTheme.colorScheme.primary
        val dense = bars.size > 10
        Row(
            Modifier.fillMaxWidth().height(140.dp),
            horizontalArrangement = Arrangement.spacedBy(if (dense) 2.dp else 6.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            bars.forEach { bar ->
                val isBest = best?.day == bar.day
                Column(
                    Modifier.weight(1f).fillMaxHeight().clearAndSetSemantics { contentDescription = barDescription(bar, isBest) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom
                ) {
                    if (bar.unconfirmedTrips > 0) {
                        Box(Modifier.size(6.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.6f)))
                        Box(Modifier.height(2.dp))
                    }
                    val fraction = bar.incomeMinor.toFloat() / max
                    Box(
                        Modifier.fillMaxWidth().fillMaxHeight(if (bar.incomeMinor > 0) fraction.coerceIn(0.03f, 0.88f) else 0.01f)
                            .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                            .background(if (isBest) bestColor else if (bar.incomeMinor > 0) normal else MaterialTheme.colorScheme.surfaceContainerHighest)
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (dense) 2.dp else 6.dp)) {
            bars.forEachIndexed { i, bar ->
                val show = !dense || i % 5 == 0 || i == bars.lastIndex
                Text(
                    if (!show) "" else if (dense) bar.day.dayOfMonth.toString() else EarningsFormat.dayShort(bar.day),
                    modifier = Modifier.weight(1f).clearAndSetSemantics { },
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        FlowRow2 {
            LegendDot(normal, "Ingreso real")
            LegendDot(bestColor, "Mejor día")
            if (bars.any { it.unconfirmedTrips > 0 }) LegendDot(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.6f), "Viajes sin dinero confirmado")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRow2(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
}

private fun barDescription(bar: DayBar, best: Boolean): String =
    "${EarningsFormat.dayShort(bar.day)} ${bar.day.dayOfMonth}: ${rd(bar.incomeMinor)}" +
        (if (bar.unconfirmedTrips > 0) ", ${bar.unconfirmedTrips} viaje(s) sin dinero confirmado" else "") +
        if (best) ", mejor día" else ""

// ---------------------------------------------------------------- time, expenses, Uber

@Composable
private fun TimeCard(online: OnlineSummary?, km: Long?, incomeMinor: Long, confirmedTrips: Int) {
    SectionCard(title = "Tiempo y distancia", icon = R.drawable.ic_schedule, subtitle = "Del medidor de tiempo en línea y de los km por GPS") {
        if (online == null && (km == null || km == 0L)) {
            InfoLine(R.drawable.ic_info, "Sin tiempo en línea ni km medidos en este periodo.")
        }
        if (online != null) {
            StatRow { m ->
                StatTile(R.drawable.ic_timer, "En línea", OnlineFormat.duration(online.confirmedMs), m, detail = if (online.hasRange) "hasta ${OnlineFormat.duration(online.possibleMs)}" else null)
                StatTile(R.drawable.ic_monitor_heart, "Ocupado", online.utilizationPercent?.let { "$it %" } ?: "—", m, detail = OnlineFormat.duration(online.busyMs))
            }
            StatTile(
                R.drawable.ic_trending_up, "Ganancia por hora en línea", OnlineFormat.earningsPerHour(online.earnings),
                Modifier.fillMaxWidth(), detail = OnlineFormat.coverage(online.earnings)
            )
        }
        if (km != null && km > 0) {
            val perKm = if (confirmedTrips > 0) incomeMinor / km else null
            StatRow { m ->
                StatTile(R.drawable.ic_route, "Km por GPS", "$km km", m, detail = "aprox.")
                StatTile(R.drawable.ic_speed, "Por km", perKm?.let { rd(it) } ?: "—", m, detail = "ingreso real / km")
            }
        }
    }
}

@Composable
private fun ExpensesCard(e: ExpenseSummary, net: NetResult?, range: EarningsRange) {
    SectionCard(title = "Gastos y neto", icon = R.drawable.ic_receipt_long, subtitle = "De lo que anotaste en Gastos") {
        StatRow { m ->
            StatTile(R.drawable.ic_receipt_long, "Gastos de trabajo", rd(e.workMinor), m, detail = if (e.workSharePct < 100) "${e.workSharePct} % de ${rd(e.totalMinor)}" else null)
            if (net != null) {
                StatTile(
                    R.drawable.ic_account_balance_wallet, "Neto", rd(net.netMinor), m,
                    detail = if (net.partial) "parcial: ${net.confirmedTrips} de ${net.completedTrips} viajes con dinero" else "ingreso − gastos",
                    accent = if (net.netMinor >= 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
                )
            } else {
                StatTile(R.drawable.ic_local_gas_station, "Combustible", rd(e.fuelMinor), m)
            }
        }
        if (range == EarningsRange.DAY) {
            InfoLine(R.drawable.ic_info, "El neto se ve por semana y por mes: un tanqueo haría ver mal un buen día.")
        }
    }
}

@Composable
private fun UberCard(r: Reconciliation?, capturedAt: Long?) {
    SectionCard(title = "Uber dice", icon = R.drawable.ic_compare_arrows, subtitle = "Referencia de Uber para comparar; nunca se suma ni se ajusta") {
        val platform = r?.platform
        if (r == null || platform == null) {
            InfoLine(R.drawable.ic_info, "Sin referencia de Uber en este periodo. Se guarda cuando abres el resumen de ganancias de Uber.")
        } else {
            StatRow { m ->
                StatTile(R.drawable.ic_bookmarks, "Uber", MoneyFormat.format(platform), m, detail = capturedAt?.let { "visto ${dayTime(it)}" })
                StatTile(R.drawable.ic_payments, "Observador", r.local?.let { MoneyFormat.format(it) } ?: "—", m)
            }
            val tint = when (r.status) {
                ReconciliationStatus.MATCHED -> MaterialTheme.colorScheme.tertiary
                ReconciliationStatus.DIFFERENCE -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            val icon = if (r.status == ReconciliationStatus.MATCHED) R.drawable.ic_check_circle else R.drawable.ic_compare_arrows
            InfoLine(icon, EarningsFormat.reconciliation(r.status) + (r.difference?.let { " (${MoneyFormat.format(it, signed = true)})" } ?: ""), tint = tint)
        }
    }
}

// ---------------------------------------------------------------- pending and manual

@Composable
private fun PendingCard(pending: List<PendingAmount>, onAssign: (FinancialObservation) -> Unit, onReject: (FinancialObservation) -> Unit) {
    SectionCard(
        title = "Pendientes de vincular",
        icon = R.drawable.ic_pending,
        subtitle = "Dinero visto sin viaje. No se suma hasta que lo asignes.",
        badgeContainer = MaterialTheme.colorScheme.secondaryContainer,
        badgeContent = MaterialTheme.colorScheme.onSecondaryContainer
    ) {
        pending.forEachIndexed { i, p ->
            if (i > 0) HorizontalDivider()
            val o = p.observation
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(EarningsFormat.typeLabel(o.type), style = MaterialTheme.typography.titleSmall)
                        Text(dayTime(o.observedAtMs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(MoneyFormat.format(o.amount), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                if (!p.assignable) InfoLine(R.drawable.ic_warning, "Por revisar: lectura dudosa o parte de un desglose; no se puede asignar.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (p.assignable) {
                        FilledTonalButton(onClick = { onAssign(o) }) {
                            Icon(painterResource(R.drawable.ic_link), contentDescription = null, modifier = Modifier.size(18.dp))
                            Text("Asignar a un viaje", Modifier.padding(start = 6.dp))
                        }
                    }
                    OutlinedButton(onClick = { onReject(o) }) {
                        Icon(painterResource(R.drawable.ic_block), contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("No es mío", Modifier.padding(start = 6.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ManualCard(rows: List<ManualAssignmentRow>, onUndo: (ManualAssignmentRow) -> Unit) {
    SectionCard(title = "Asignadas a mano", icon = R.drawable.ic_link, subtitle = "Puedes deshacerlas; todo queda en el historial") {
        rows.forEachIndexed { i, r ->
            if (i > 0) HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${EarningsFormat.typeLabel(r.copy.type)} · ${MoneyFormat.format(r.copy.amount)}", style = MaterialTheme.typography.titleSmall)
                    Text("Vista ${dayTime(r.copy.observedAtMs)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { onUndo(r) }) {
                    Icon(painterResource(R.drawable.ic_undo), contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Deshacer", Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun AssignSheetContent(sheet: AssignSheet, onChoose: (Trip) -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Asignar ${EarningsFormat.typeLabel(sheet.amount.type).lowercase()} de ${MoneyFormat.format(sheet.amount.amount)}", style = MaterialTheme.typography.titleLarge)
        Text(
            "Viajes terminados de esa jornada y la anterior, el más cercano a ${time(sheet.amount.observedAtMs)} primero.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        when {
            sheet.loading -> Text("Buscando viajes…")
            sheet.candidates.isEmpty() -> InfoLine(R.drawable.ic_info, "No hay viajes terminados cerca de esa hora.")
            else -> sheet.candidates.forEach { (trip, summary) ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth().clickable { onChoose(trip) }
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.ic_two_wheeler), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(
                                "Terminó ${dayTime(requireNotNull(com.ubers.observador.domain.trips.TripOutcome.endedAt(trip)))}" + (trip.riderName?.let { " · $it" } ?: ""),
                                style = MaterialTheme.typography.titleSmall
                            )
                            trip.offer?.dropoffAddress?.let {
                                Text("Destino: $it", style = MaterialTheme.typography.bodySmall, maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            }
                            Text(
                                "Ofertado ${trip.offer?.let { MoneyAmount.fromLegacyDouble(it.offeredAmount, "DOP")?.let { MoneyFormat.format(it) } } ?: "—"} · " +
                                    "Real ${summary?.effectiveEarnings?.let { MoneyFormat.format(it) } ?: "sin confirmar"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null)
                    }
                }
            }
        }
    }
}

@Composable
private fun AssignConfirmDialog(amount: FinancialObservation, trip: Trip, preview: AssignPreview, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(painterResource(R.drawable.ic_link), contentDescription = null) },
        title = { Text("¿Asignar a este viaje?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${EarningsFormat.typeLabel(amount.type)} de ${MoneyFormat.format(amount.amount)} al viaje que terminó ${dayTime(requireNotNull(com.ubers.observador.domain.trips.TripOutcome.endedAt(trip)))}.")
                Text(
                    "Real del viaje: ${preview.before.effectiveEarnings?.let { MoneyFormat.format(it) } ?: "sin confirmar"} → " +
                        (preview.after.effectiveEarnings?.let { MoneyFormat.format(it) } ?: "sin confirmar"),
                    fontWeight = FontWeight.SemiBold
                )
                preview.replacesTotal?.let {
                    InfoLine(
                        R.drawable.ic_warning,
                        "El viaje ya tiene otro total (${MoneyFormat.format(it)}). Si el nuevo es más reciente pasa a ser el total, y el viaje queda marcado con totales en conflicto.",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
                if (preview.newAnomalies.isNotEmpty() && preview.replacesTotal == null) {
                    InfoLine(R.drawable.ic_warning, "El viaje quedará marcado para revisar.", tint = MaterialTheme.colorScheme.error)
                }
                Text("No se edita nada: se guarda una anulación de la cantidad y una copia en el viaje. Puedes deshacerlo.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Asignar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

// ---------------------------------------------------------------- trips

@Composable
private fun TripRow(row: TripMoneyRow, showDay: Boolean, onClick: () -> Unit) {
    val (statusText, statusColor) = moneyStatus(row)
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(if (showDay) dayTime(row.completedAt) else time(row.completedAt), style = MaterialTheme.typography.titleSmall)
                Text(
                    "Ofertado ${row.offered?.let { MoneyFormat.format(it) } ?: "—"}" + (row.riderName?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (row.pickupAddress != null || row.dropoffAddress != null) {
                    Text(
                        "${row.pickupAddress ?: "?"} → ${row.dropoffAddress ?: "?"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(statusColor))
                    Text(statusText, style = MaterialTheme.typography.labelMedium, color = statusColor)
                }
                if (!row.endConfirmed) {
                    Text(
                        "Cierre sin confirmar: Uber volvió a buscando",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(row.real?.let { MoneyFormat.format(it) } ?: "—", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                row.difference?.let { d ->
                    Text(
                        MoneyFormat.format(d, signed = true) + (row.differencePercent?.let { " (${if (it.signum() > 0) "+" else ""}${it.toPlainString()} %)" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (d.minorUnits >= 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun moneyStatus(row: TripMoneyRow): Pair<String, Color> = when {
    row.hasAnomalies -> "Revisar" to MaterialTheme.colorScheme.error
    row.completeness == FinancialCompleteness.CONFIRMED -> (if (row.tipMinor > 0) "Confirmado · con propina" else "Confirmado") to MaterialTheme.colorScheme.tertiary
    row.completeness == FinancialCompleteness.PARTIAL -> "Parcial: sin total" to MaterialTheme.colorScheme.secondary
    else -> "Sin dinero visto" to MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun TripDetailContent(d: TripDetail) {
    Column(
        Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Viaje de las ${time(d.row.completedAt)}", style = MaterialTheme.typography.titleLarge)
        // Fase 8F: who and where, as Uber showed them (with the trip, its card below says it all)
        if (d.trip == null) {
            d.row.riderName?.let { InfoLine(R.drawable.ic_info, "Pasajero: $it") }
            d.row.pickupAddress?.let { InfoLine(R.drawable.ic_route, "Recogida: $it") }
            d.row.dropoffAddress?.let { InfoLine(R.drawable.ic_flag, "Destino: $it") }
        }
        StatRow { m ->
            StatTile(R.drawable.ic_bookmarks, "Ofertado", d.row.offered?.let { MoneyFormat.format(it) } ?: "—", m, detail = "referencia")
            StatTile(R.drawable.ic_payments, "Real", d.row.real?.let { MoneyFormat.format(it) } ?: "—", m, detail = moneyStatus(d.row).first)
        }
        val trip = d.trip
        // Fase 8K: who, when and where, offered against real, and the route on the map
        if (trip != null) com.ubers.observador.ui.route.TripRouteSection(trip.id)
        if (trip != null) {
            Text("Línea de tiempo", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            TripTimeline.of(trip).forEach { (label, at) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary))
                    Text(time(at), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (d.observations.isNotEmpty()) {
            Text("Dinero visto", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            val voided = d.observations.mapNotNull { it.voidsObservationId }.toSet()
            d.observations.filter { it.type != FinancialObservationType.VOID }.forEach { o ->
                val gone = o.observationId in voided
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(EarningsFormat.typeLabel(o.type) + if (o.source == FinancialSource.MANUAL) " · a mano" else "", style = MaterialTheme.typography.bodyMedium)
                        Text(time(o.observedAtMs) + if (gone) " · anulada" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        MoneyFormat.format(o.amount),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (gone) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        } else if (d.trip != null) {
            InfoLine(R.drawable.ic_info, "Observador no vio dinero de este viaje.")
        }
    }
}
