package com.ubers.observador.ui.dashboard

import android.content.ClipData
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.Role
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
import com.ubers.observador.domain.DashboardStats
import com.ubers.observador.domain.DayGroup
import com.ubers.observador.domain.PeriodSummary
import com.ubers.observador.domain.QualityThresholds
import com.ubers.observador.domain.StatsPeriod
import com.ubers.observador.domain.TimeAgo
import com.ubers.observador.domain.formatNumber
import com.ubers.observador.ui.components.QualityBar
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onNavigateToSettings: () -> Unit,
    accessibilityEnabled: Boolean,
    viewModel: DashboardViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val selectedPeriod by viewModel.selectedPeriod.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var showExportDialog by rememberSaveable { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

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
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(painterResource(R.drawable.ic_settings), contentDescription = "Ajustes")
                    }
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
            ServiceStatusBar(
                enabled = accessibilityEnabled,
                lastOfferAt = state.lastRealOfferAt,
                nowMillis = state.nowMillis,
                loading = state.loading,
                onEnable = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            )
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                item {
                    SummaryCard(state = state, selected = selectedPeriod, onSelect = viewModel::selectPeriod)
                }
                item {
                    Text(
                        text = "Ofertas por jornada",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                if (!state.loading && state.days.isEmpty()) {
                    item {
                        Text(
                            text = if (state.period == StatsPeriod.ALL) {
                                "Aún no hay ofertas registradas.\nActiva el servicio de accesibilidad y abre Uber Driver."
                            } else {
                                "No hay ofertas en este periodo."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    state.days.forEach { day ->
                        item(key = "day-${day.date}") { DayHeader(day, state.currentWorkday) }
                        items(day.offers, key = { it.id }) { offer ->
                            OfferRow(offer = offer, thresholds = state.thresholds)
                        }
                    }
                }
            }
        }
    }
}

/** Green when Observador is reading Uber (with the time of the last real offer), red with a fix when not. */
@Composable
private fun ServiceStatusBar(
    enabled: Boolean,
    lastOfferAt: Long?,
    nowMillis: Long,
    loading: Boolean,
    onEnable: () -> Unit
) {
    val container = if (enabled) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer
    val content = if (enabled) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer
    val dot = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    Surface(color = container, contentColor = content, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(dot, CircleShape)
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .semantics(mergeDescendants = true) {}
            ) {
                Text(
                    text = if (enabled) "Leyendo ofertas" else "Inactivo",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                val detail = when {
                    !enabled -> "Observador no está leyendo Uber."
                    loading -> null
                    lastOfferAt == null -> "Aún no ha llegado ninguna oferta."
                    else -> "Última oferta ${TimeAgo.format(nowMillis, lastOfferAt)}"
                }
                if (detail != null) {
                    Text(text = detail, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (!enabled) {
                Button(onClick = onEnable) { Text("Activar") }
            }
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
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Resumen", style = MaterialTheme.typography.titleMedium)
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
                    text = "Sin ofertas en este periodo.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> {
                    SummaryTiles(state)
                    CategoryMix(summary)
                }
            }
        }
    }
}

/** Four numbers in a 2x2 grid; one column when the system font is very large. */
@Composable
private fun SummaryTiles(state: DashboardUiState) {
    val summary = state.summary
    val above = MaterialTheme.colorScheme.primary
    val below = MaterialTheme.colorScheme.error
    fun versusMin(value: Double, min: Double): Pair<String, Color> =
        if (value >= min) "▲ sobre tu mínimo de ${formatNumber(min)}" to above
        else "▼ bajo tu mínimo de ${formatNumber(min)}" to below

    val (kmNote, kmColor) = versusMin(summary.medianRdpPerKm, state.minRdpPerKm)
    val (hourNote, hourColor) = versusMin(summary.medianRdpPerHour, state.minRdpPerHour)
    val best = summary.best
    val tiles = listOf(
        TileData("Ofertas vistas", "${summary.count}", null, Color.Unspecified),
        TileData("RD$/km mediana", "%.2f".format(summary.medianRdpPerKm), kmNote, kmColor),
        TileData("RD$/h mediana", "%.0f".format(summary.medianRdpPerHour), hourNote, hourColor),
        TileData(
            label = "Mejor oferta",
            value = if (best != null) "RD$%.2f".format(best.price) else "-",
            note = best?.let { "score ${it.score} · ${state.thresholds.classify(it.score).label}" },
            noteColor = Color.Unspecified
        )
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (LocalDensity.current.fontScale > LARGE_FONT_SCALE) {
            tiles.forEach { t -> SummaryTile(t, Modifier.fillMaxWidth()) }
        } else {
            tiles.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEach { t -> SummaryTile(t, Modifier.weight(1f)) }
                }
            }
        }
    }
}

private data class TileData(val label: String, val value: String, val note: String?, val noteColor: Color)

@Composable
private fun SummaryTile(tile: TileData, modifier: Modifier) {
    val (label, value, note, noteColor) = tile
    // One stop for TalkBack: "RD$/km mediana, 14.25, sobre tu mínimo de 6"
    Column(modifier = modifier.semantics(mergeDescendants = true) {}) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(text = value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        if (note != null) {
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = if (noteColor == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else noteColor
            )
        }
    }
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

private const val LARGE_FONT_SCALE = 1.3f

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
private val OFFER_DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy · h:mm a")

@Composable
private fun DayHeader(day: DayGroup, currentWorkday: LocalDate?) {
    // Working days, not calendar days: with a 4 am start, 1:30 am still belongs to "Hoy"
    val label = when (day.date) {
        currentWorkday -> "Hoy"
        currentWorkday?.minusDays(1) -> "Ayer"
        else -> day.date.format(DAY_HEADER_FORMAT)
    }
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Text(text = label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(
            text = "%d ofertas · RD$%.2f/km mediana · score %.0f".format(
                day.count, day.medianRdpPerKm, day.avgScore
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun OfferRow(offer: OfferEntity, thresholds: QualityThresholds) {
    val detectedAt = remember(offer.detectedAt) {
        Instant.ofEpochMilli(offer.detectedAt)
            .atZone(ZoneId.systemDefault())
            .format(OFFER_DATE_TIME_FORMAT)
    }
    val quality = thresholds.classify(offer.score)
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                    text = "RD$%.2f".format(offer.price),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Text(
                    text = detectedAt,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "%.1f km (%.1f recogida + %.1f viaje) · %d min | RD$%.2f/km".format(
                        offer.totalKm, offer.pickupKm, offer.tripKm, offer.totalMin, offer.rdpPerKm
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // The label sits under the score so the verdict never depends on color alone
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(Color(quality.backgroundArgb), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${offer.score}",
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
    }
}
