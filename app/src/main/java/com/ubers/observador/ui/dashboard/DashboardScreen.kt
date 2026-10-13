package com.ubers.observador.ui.dashboard

import android.content.ClipData
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import com.ubers.observador.R
import com.ubers.observador.data.export.ExportFormat
import com.ubers.observador.data.export.ExportRange
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ubers.observador.data.db.OfferEntity
import com.ubers.observador.domain.CategoryFilter
import com.ubers.observador.domain.DashboardStats
import com.ubers.observador.domain.DayGroup
import com.ubers.observador.domain.OfferDetails
import com.ubers.observador.domain.OfferListOptions
import com.ubers.observador.domain.OfferQuality
import com.ubers.observador.domain.OfferSort
import com.ubers.observador.domain.PeriodSummary
import com.ubers.observador.domain.QualityThresholds
import com.ubers.observador.domain.StatsPeriod
import com.ubers.observador.domain.TimeAgo
import com.ubers.observador.domain.formatNumber
import com.ubers.observador.domain.online.OnlineStatusKind
import com.ubers.observador.domain.online.OnlineStatusLine
import com.ubers.observador.ui.components.IconBadge
import com.ubers.observador.ui.components.IconSectionHeader
import com.ubers.observador.ui.components.QualityBar
import com.ubers.observador.ui.components.SectionCard
import com.ubers.observador.ui.components.StatTile
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    accessibilityEnabled: Boolean,
    viewModel: DashboardViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val selectedPeriod by viewModel.selectedPeriod.collectAsState()
    val bestHours by viewModel.bestHours.collectAsState()
    val bestHoursPeriod by viewModel.selectedBestHoursPeriod.collectAsState()
    val online by viewModel.online.collectAsState()
    val distance by viewModel.distance.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var showExportDialog by rememberSaveable { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var selectedOfferId by rememberSaveable { mutableStateOf<Long?>(null) }
    // Looked up in the current list: if the offer leaves the period, the panel closes by itself
    val selectedOffer = remember(selectedOfferId, state.days) {
        selectedOfferId?.let { id -> state.days.asSequence().flatMap { it.offers }.firstOrNull { it.id == id } }
    }

    val csvSaveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ExportFormat.CSV.mimeType)
    ) { uri -> viewModel.savePendingTo(uri) }
    val jsonSaveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ExportFormat.JSON.mimeType)
    ) { uri -> viewModel.savePendingTo(uri) }

    LaunchedEffect(Unit) {
        viewModel.exportEvents.collect { event ->
            when (event) {
                is ExportEvent.ReadyToSave -> when (event.format) {
                    ExportFormat.CSV -> csvSaveLauncher.launch(event.fileName)
                    ExportFormat.JSON -> jsonSaveLauncher.launch(event.fileName)
                }
                is ExportEvent.ReadyToShare -> {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = event.mimeType
                        putExtra(Intent.EXTRA_STREAM, event.uri)
                        // ClipData makes the read grant reach the chooser and the target app
                        clipData = ClipData.newRawUri("", event.uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(send, "Compartir exportación"))
                }
                // launch: showSnackbar suspends until dismissed and must not block the collector
                is ExportEvent.Saved -> scope.launch {
                    snackbarHostState.showSnackbar("${event.count} ofertas exportadas")
                }
                ExportEvent.Empty -> scope.launch {
                    snackbarHostState.showSnackbar("No hay ofertas en ese rango")
                }
                ExportEvent.Failed -> scope.launch {
                    snackbarHostState.showSnackbar("No se pudo exportar")
                }
            }
        }
    }


    // The tapped time slot stays highlighted after its panel closes; looked up by hour, so the
    // panel follows new offers and a change of period while it is open
    var selectedSlotStart by rememberSaveable { mutableStateOf<Int?>(null) }
    var showSlotDetail by rememberSaveable { mutableStateOf(false) }
    val selectedSlot = remember(selectedSlotStart, bestHours.stats) {
        selectedSlotStart?.let { hour -> bestHours.stats.slots.firstOrNull { it.startHour == hour } }
    }
    if (showSlotDetail && selectedSlot != null) {
        SlotDetailSheet(
            slot = selectedSlot,
            isBest = selectedSlot.startHour == bestHours.stats.bestSlot?.startHour,
            onDismiss = { showSlotDetail = false }
        )
    }

    LaunchedEffect(Unit) {
        viewModel.deletedEvents.collect { deleted ->
            scope.launch {
                // A new deletion replaces the previous notice instead of queueing behind it
                snackbarHostState.currentSnackbarData?.dismiss()
                val result = snackbarHostState.showSnackbar(
                    message = deleted.message,
                    actionLabel = "Deshacer",
                    duration = SnackbarDuration.Long
                )
                if (result == SnackbarResult.ActionPerformed) viewModel.undoDelete(deleted.offers)
            }
        }
    }

    if (selectedOffer != null) {
        OfferDetailSheet(
            detail = OfferDetails.of(selectedOffer, state.engineSettings, state.thresholds),
            onDismiss = { selectedOfferId = null }
        )
    }

    if (showExportDialog) {
        ExportDialog(
            onDismiss = { showExportDialog = false },
            onSave = { format, range ->
                showExportDialog = false
                viewModel.requestSave(format, range)
            },
            onShare = { format, range ->
                showExportDialog = false
                viewModel.requestShare(format, range)
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Observador") },
                actions = {
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(painterResource(R.drawable.ic_more_vert), contentDescription = "Más opciones")
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Exportar ofertas") },
                                onClick = {
                                    showMenu = false
                                    showExportDialog = true
                                }
                            )
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Fixed above the list: whether Observador is reading Uber is the first thing to know
            val observing by viewModel.observerEnabled.collectAsState()
            ServiceStatusBar(
                enabled = accessibilityEnabled,
                paused = !observing,
                lastOfferAt = state.lastRealOfferAt,
                nowMillis = state.nowMillis,
                loading = state.loading,
                online = online.status,
                onEnable = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                onToggle = { viewModel.setObserverEnabled(!observing) }
            )
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                item {
                    SummaryCard(state = state, selected = selectedPeriod, onSelect = viewModel::selectPeriod)
                }
                item {
                    OnlineCard(state = online, modifier = Modifier.padding(top = 16.dp))
                }
                item {
                    DistanceCard(
                        state = distance,
                        onEnable = { viewModel.setDistanceEnabled(true) },
                        onManual = viewModel::setManualDistance,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                }
                item {
                    BestHoursSection(
                        state = bestHours,
                        selectedPeriod = bestHoursPeriod,
                        selectedStartHour = selectedSlotStart,
                        onSelectPeriod = viewModel::selectBestHoursPeriod,
                        onSelectSlot = { slot ->
                            selectedSlotStart = slot.startHour
                            showSlotDetail = true
                        },
                        modifier = Modifier.padding(top = 16.dp)
                    )
                }
                item {
                    IconSectionHeader(
                        text = "Viajes por jornada",
                        icon = R.drawable.ic_calendar_month,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                if (state.summary.count > 0) {
                    item {
                        ListControls(
                            state = state,
                            onFilter = viewModel::selectFilter,
                            onSort = viewModel::selectSort
                        )
                    }
                }
                if (!state.loading && state.days.isEmpty()) {
                    item {
                        Text(
                            text = when {
                                state.summary.count > 0 && state.filter != CategoryFilter.ALL ->
                                    "No hay viajes ${state.filter.quality?.label} en este periodo."
                                state.period == StatsPeriod.ALL ->
                                    "Aún no hay viajes realizados.\nAparecen cuando Observador ve un viaje de principio a fin."
                                else -> "No hay viajes realizados en este periodo."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    state.days.forEach { day ->
                        item(key = "day-${day.date}") {
                            DayHeader(
                                day = day,
                                currentWorkday = state.currentWorkday,
                                filtered = state.filter != CategoryFilter.ALL
                            )
                        }
                        items(day.offers, key = { it.id }) { offer ->
                            OfferRow(
                                offer = offer,
                                thresholds = state.thresholds,
                                onClick = { selectedOfferId = offer.id }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Green when Observador is reading Uber (with the time of the last real offer), amber-ish when the
 * driver paused it (one tap to resume), red with a fix when the accessibility service is off.
 * A second line (Fase 8A) says whether Uber itself is online; it never stands in for the pause.
 */
@Composable
private fun ServiceStatusBar(
    enabled: Boolean,
    paused: Boolean,
    lastOfferAt: Long?,
    nowMillis: Long,
    loading: Boolean,
    online: OnlineStatusLine?,
    onEnable: () -> Unit,
    onToggle: () -> Unit
) {
    val reading = enabled && !paused
    val scheme = MaterialTheme.colorScheme
    val container = when {
        !enabled -> scheme.errorContainer
        paused -> scheme.tertiaryContainer
        else -> scheme.secondaryContainer
    }
    val content = when {
        !enabled -> scheme.onErrorContainer
        paused -> scheme.onTertiaryContainer
        else -> scheme.onSecondaryContainer
    }
    val (badge, badgeColor) = when {
        !enabled -> R.drawable.ic_sensors_off to scheme.error
        paused -> R.drawable.ic_pause_circle to scheme.tertiary
        else -> R.drawable.ic_sensors to scheme.primary
    }
    Surface(color = container, contentColor = content, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            IconBadge(badge, container = badgeColor, content = scheme.surface, size = 34.dp)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .semantics(mergeDescendants = true) {},
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = when {
                        !enabled -> "Inactivo"
                        paused -> "En pausa"
                        else -> "Leyendo ofertas"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                val detail = when {
                    !enabled -> "Observador no está leyendo Uber."
                    paused -> "No se leen ofertas ni viajes hasta que reanudes."
                    loading -> null
                    lastOfferAt == null -> "Aún no ha llegado ninguna oferta."
                    else -> "Última oferta ${TimeAgo.format(nowMillis, lastOfferAt)}"
                }
                if (detail != null) {
                    Text(text = detail, style = MaterialTheme.typography.bodySmall)
                }
                // Uber's own state only means something while Observador is reading it
                if (reading && online != null) OnlineStatusRow(online)
            }
            when {
                !enabled -> Button(onClick = onEnable) { Text("Activar") }
                paused -> Button(onClick = onToggle) {
                    Icon(painterResource(R.drawable.ic_play_circle), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Reanudar")
                }
                reading -> OutlinedButton(onClick = onToggle) {
                    Icon(painterResource(R.drawable.ic_pause_circle), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Pausar")
                }
            }
        }
    }
}

/** "En línea en Uber · 1 h 25 min" in a small pill, colored by state. */
@Composable
private fun OnlineStatusRow(line: OnlineStatusLine) {
    val scheme = MaterialTheme.colorScheme
    val (icon, tint) = when (line.kind) {
        OnlineStatusKind.ONLINE -> R.drawable.ic_two_wheeler to scheme.primary
        OnlineStatusKind.NOT_SEEN_LATELY -> R.drawable.ic_hourglass_top to scheme.tertiary
        OnlineStatusKind.OFFLINE -> R.drawable.ic_two_wheeler to scheme.outline
    }
    Surface(
        shape = RoundedCornerShape(50),
        color = scheme.surface.copy(alpha = 0.6f),
        modifier = Modifier.padding(top = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
            Text(line.text, style = MaterialTheme.typography.labelMedium, color = scheme.onSurface)
        }
    }
}

@Composable
private fun SummaryCard(
    state: DashboardUiState,
    selected: StatsPeriod,
    onSelect: (StatsPeriod) -> Unit
) {
    val summary = state.summary
    SectionCard(
        title = "Viajes realizados",
        icon = R.drawable.ic_leaderboard,
        subtitle = "Solo los viajes que hiciste, juzgados con tus ajustes de hoy"
    ) {
        // Scrolls sideways if a large system font makes the chips wider than the screen
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatsPeriod.entries.forEach { period ->
                FilterChip(
                    selected = period == selected,
                    onClick = { onSelect(period) },
                    label = { Text(period.label) }
                )
            }
        }
        when {
            state.loading -> Unit
            summary.count == 0 -> Text(
                text = "Sin viajes realizados en este periodo.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            else -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SummaryTiles(state)
                CategoryMix(summary)
            }
        }
    }
}

/** Four numbers in a 2x2 grid of icon tiles; one column when the system font is very large. */
@Composable
private fun SummaryTiles(state: DashboardUiState) {
    val summary = state.summary
    val above = MaterialTheme.colorScheme.primary
    val below = MaterialTheme.colorScheme.error
    fun versusMin(value: Double, min: Double): Pair<String, Color> =
        if (value >= min) "▲ sobre tu mínimo de ${formatNumber(min)}" to above
        else "▼ bajo tu mínimo de ${formatNumber(min)}" to below

    val (kmNote, kmColor) = versusMin(summary.medianRdpPerKm, state.engineSettings.minRdpPerKm)
    val (hourNote, hourColor) = versusMin(summary.medianRdpPerHour, state.engineSettings.minRdpPerHour)
    val best = summary.best
    val tiles = listOf(
        TileData(R.drawable.ic_two_wheeler, "Viajes", "${summary.count}", null, Color.Unspecified),
        TileData(R.drawable.ic_route, "RD$/km mediana", "%.2f".format(summary.medianRdpPerKm), kmNote, kmColor),
        TileData(R.drawable.ic_speed, "RD$/h mediana", "%.0f".format(summary.medianRdpPerHour), hourNote, hourColor),
        TileData(
            icon = R.drawable.ic_trending_up,
            label = "Mejor viaje",
            value = if (best != null) "RD$%.2f".format(best.price) else "-",
            note = best?.let { "score ${it.score} · ${state.thresholds.classify(it.score).label}" },
            noteColor = Color.Unspecified
        )
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (LocalDensity.current.fontScale > LARGE_FONT_SCALE) {
            tiles.forEach { t -> SummaryTile(t, Modifier.fillMaxWidth()) }
        } else {
            tiles.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    pair.forEach { t -> SummaryTile(t, Modifier.weight(1f)) }
                }
            }
        }
    }
}

private data class TileData(val icon: Int, val label: String, val value: String, val note: String?, val noteColor: Color)

@Composable
private fun SummaryTile(tile: TileData, modifier: Modifier) {
    // One stop for TalkBack: "RD$/km mediana: 14.25, sobre tu mínimo de 6"
    StatTile(
        icon = tile.icon,
        label = tile.label,
        value = tile.value,
        modifier = modifier,
        detail = tile.note,
        detailColor = tile.noteColor
    )
}

/** The MALO / BUENO / EXCELENTE bar with count and percentage under each part. */
@Composable
private fun CategoryMix(summary: PeriodSummary) {
    val counts = listOf(summary.bad, summary.good, summary.excellent)
    val (badPct, goodPct, excellentPct) = DashboardStats.percentages(counts)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        QualityBar(
            bad = summary.bad,
            good = summary.good,
            excellent = summary.excellent,
            description = "Reparto: MALO $badPct por ciento, BUENO $goodPct por ciento, " +
                "EXCELENTE $excellentPct por ciento"
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val style = MaterialTheme.typography.labelSmall
            Text("MALO ${summary.bad} · $badPct%", style = style)
            Text("BUENO ${summary.good} · $goodPct%", style = style)
            Text("EXCELENTE ${summary.excellent} · $excellentPct%", style = style)
        }
    }
}

/** Above this system font scale, layouts switch to one column or thin out labels. */
internal const val LARGE_FONT_SCALE = 1.3f

@Composable
private fun ExportDialog(
    onDismiss: () -> Unit,
    onSave: (ExportFormat, ExportRange) -> Unit,
    onShare: (ExportFormat, ExportRange) -> Unit
) {
    var range by rememberSaveable { mutableStateOf(ExportRange.ALL) }
    var format by rememberSaveable { mutableStateOf(ExportFormat.CSV) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Exportar ofertas") },
        text = {
            Column {
                Text("Rango", style = MaterialTheme.typography.labelLarge)
                ExportRange.entries.forEach { option ->
                    RadioOption(
                        label = option.label,
                        selected = range == option,
                        onSelect = { range = option }
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text("Formato", style = MaterialTheme.typography.labelLarge)
                ExportFormat.entries.forEach { option ->
                    RadioOption(
                        label = option.label,
                        selected = format == option,
                        onSelect = { format = option }
                    )
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { onShare(format, range) }) { Text("Compartir") }
                TextButton(onClick = { onSave(format, range) }) { Text("Guardar") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}

@Composable
private fun RadioOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}

private val DAY_HEADER_FORMAT = DateTimeFormatter.ofPattern("EEE dd MMM yyyy")
private val OFFER_TIME_FORMAT = DateTimeFormatter.ofPattern("h:mm a")

/** Working days, not calendar days: with a 4 am start, 1:30 am still belongs to "Hoy". */
private fun dayLabel(date: LocalDate, currentWorkday: LocalDate?): String = when (date) {
    currentWorkday -> "Hoy"
    currentWorkday?.minusDays(1) -> "Ayer"
    else -> date.format(DAY_HEADER_FORMAT)
}

@Composable
private fun DayHeader(
    day: DayGroup,
    currentWorkday: LocalDate?,
    filtered: Boolean
) {
    // With a filter on, say how many of the day's trips are shown: "3 de 12 viajes"
    val count = if (filtered) "${day.offers.size} de ${day.count} viajes" else "${day.count} ${if (day.count == 1) "viaje" else "viajes"}"
    Row(
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = dayLabel(day.date, currentWorkday),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "%s · RD$%.2f/km mediana · score %.0f".format(count, day.medianRdpPerKm, day.avgScore),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Category chips with their counts, and the order of the offers inside each working day. */
@Composable
private fun ListControls(
    state: DashboardUiState,
    onFilter: (CategoryFilter) -> Unit,
    onSort: (OfferSort) -> Unit
) {
    var sortMenu by remember { mutableStateOf(false) }
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CategoryFilter.entries.forEach { filter ->
                FilterChip(
                    selected = filter == state.filter,
                    onClick = { onFilter(filter) },
                    label = { Text("${filter.label} ${OfferListOptions.countFor(filter, state.summary)}") }
                )
            }
        }
        Box {
            TextButton(onClick = { sortMenu = true }) { Text("Orden: ${state.sort.label} ▾") }
            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                OfferSort.entries.forEach { sort ->
                    DropdownMenuItem(
                        text = { Text(if (sort == state.sort) "${sort.label}  ✓" else sort.label) },
                        onClick = {
                            sortMenu = false
                            onSort(sort)
                        }
                    )
                }
            }
        }
    }
}

/** Swipe a row to the left to delete it; the snackbar offers "Deshacer". */
@OptIn(ExperimentalMaterial3Api::class)

/**
 * One offer, readable at a glance: colour stripe, price and RD$/km in large type, the time only
 * (the day header already says which day). Tapping opens the detail panel.
 */
@Composable
private fun OfferRow(
    offer: OfferEntity,
    thresholds: QualityThresholds,
    onClick: () -> Unit
) {
    val time = remember(offer.detectedAt) {
        Instant.ofEpochMilli(offer.detectedAt).atZone(ZoneId.systemDefault()).format(OFFER_TIME_FORMAT)
    }
    val quality = thresholds.classify(offer.score)
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .background(MaterialTheme.colorScheme.background)
                .clickable(onClickLabel = "Ver detalle", onClick = onClick)
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .fillMaxHeight()
                    .background(Color(quality.backgroundArgb), RoundedCornerShape(3.dp))
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                    text = "RD$%.2f".format(offer.price),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "RD$%.2f/km · %d min".format(offer.rdpPerKm, offer.totalMin),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "%s · recogida %.1f km · viaje %.1f km".format(time, offer.pickupKm, offer.tripKm),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            ScoreBadge(score = offer.score, quality = quality)
        }
        HorizontalDivider()
    }
}

/** Score with its label underneath, so the verdict never depends on colour alone. */
@Composable
private fun ScoreBadge(score: Int, quality: OfferQuality) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(Color(quality.backgroundArgb), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "$score",
                color = Color(quality.foregroundArgb),
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        }
        Text(
            text = quality.label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}
