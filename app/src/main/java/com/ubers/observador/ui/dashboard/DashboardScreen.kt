package com.ubers.observador.ui.dashboard

import android.content.ClipData
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.Role
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ubers.observador.data.db.OfferEntity
import com.ubers.observador.domain.DayGroup
import com.ubers.observador.domain.QualityThresholds
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var showExportDialog by rememberSaveable { mutableStateOf(false) }

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

    LaunchedEffect(Unit) {
        viewModel.messages.collect { message ->
            scope.launch { snackbarHostState.showSnackbar(message) }
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
                    TextButton(onClick = { showExportDialog = true }) {
                        Text("Exportar")
                    }
                    TextButton(onClick = onNavigateToSettings) {
                        Text("Ajustes")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            item { StatsCard(state) }
            item {
                ServiceControlCard(
                    accessibilityEnabled = accessibilityEnabled,
                    onEnableAccessibility = {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    onTestOverlay = { viewModel.showTestOverlay() }
                )
            }
            item {
                Text(
                    text = "Ofertas por día",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            if (state.recentOffers.isEmpty()) {
                item {
                    Text(
                        text = "Aún no hay ofertas registradas.\nActiva el servicio de accesibilidad y abre Uber Driver.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                state.days.forEach { day ->
                    item(key = "day-${day.date}") { DayHeader(day) }
                    items(day.offers, key = { it.id }) { offer ->
                        OfferRow(offer = offer, thresholds = state.thresholds)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatsCard(state: DashboardUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Estadísticas", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem(label = "Total", value = "${state.totalOffers}")
                StatItem(label = "Score prom.", value = "%.0f".format(state.avgScore))
                StatItem(label = "RD$/km prom.", value = "%.2f".format(state.avgRdpPerKm))
                StatItem(label = "RD$/h prom.", value = "%.0f".format(state.avgRdpPerHour))
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(text = label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ServiceControlCard(
    accessibilityEnabled: Boolean,
    onEnableAccessibility: () -> Unit,
    onTestOverlay: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Servicio de Accesibilidad", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (accessibilityEnabled) {
                    "Activo. Observador está leyendo las ofertas de Uber Driver."
                } else {
                    "Para que Observador detecte las ofertas, debes activarlo en Accesibilidad del sistema."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!accessibilityEnabled) {
                    Button(onClick = onEnableAccessibility) {
                        Text("Activar accesibilidad")
                    }
                }
                OutlinedButton(onClick = onTestOverlay) {
                    Text("Probar overlay")
                }
            }
        }
    }
}

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
private fun DayHeader(day: DayGroup) {
    val today = LocalDate.now()
    val label = when (day.date) {
        today -> "Hoy"
        today.minusDays(1) -> "Ayer"
        else -> day.date.format(DAY_HEADER_FORMAT)
    }
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Text(text = label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(
            text = "%d ofertas · RD$%.2f/km prom. · score %.0f".format(
                day.count, day.avgRdpPerKm, day.avgScore
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
