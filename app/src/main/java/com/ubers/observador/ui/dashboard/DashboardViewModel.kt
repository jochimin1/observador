package com.ubers.observador.ui.dashboard

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ubers.observador.data.AppSettings
import com.ubers.observador.data.OfferRepository
import com.ubers.observador.data.db.AppDatabase
import com.ubers.observador.data.db.OfferEntity
import com.ubers.observador.data.export.ExportFormat
import com.ubers.observador.data.export.ExportRange
import com.ubers.observador.data.export.OfferExporter
import com.ubers.observador.domain.DashboardStats
import com.ubers.observador.domain.DayGroup
import com.ubers.observador.domain.EngineSettings
import com.ubers.observador.domain.OfferQuality
import com.ubers.observador.domain.QualityThresholds
import com.ubers.observador.domain.models.OfferAnalysis
import com.ubers.observador.domain.models.UberOffer
import com.ubers.observador.service.UberAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

data class DashboardUiState(
    val totalOffers: Int = 0,
    val offersAboveThreshold: Int = 0,
    val avgRdpPerKm: Double = 0.0,
    val avgRdpPerHour: Double = 0.0,
    val avgScore: Double = 0.0,
    val recentOffers: List<OfferEntity> = emptyList(),
    val days: List<DayGroup> = emptyList(),
    val thresholds: QualityThresholds = QualityThresholds()
)

sealed interface ExportEvent {
    data class ReadyToSave(val fileName: String, val format: ExportFormat) : ExportEvent
    data class ReadyToShare(val uri: Uri, val mimeType: String) : ExportEvent
    data class Saved(val count: Int) : ExportEvent
    data object Empty : ExportEvent
    data object Failed : ExportEvent
}

class DashboardViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.getInstance(app)
    private val repository = OfferRepository(db)
    private val settings = AppSettings(app)

    fun showTestOverlay() = viewModelScope.launch {
        val duration = settings.overlayDurationSeconds.first()
        val quality = OfferQuality.entries[testOverlayCount++ % OfferQuality.entries.size]
        val score = when (quality) {
            OfferQuality.BAD -> 35
            OfferQuality.GOOD -> 65
            OfferQuality.EXCELLENT -> 88
        }
        val fakeOffer = UberOffer(price = 136.06, pickupKm = 2.3, pickupMin = 7, tripKm = 8.5, tripMin = 18)
        val fakeAnalysis = OfferAnalysis(
            offer = fakeOffer,
            totalKm = 10.8,
            totalMin = 25,
            rdpPerKm = 12.60,
            rdpPerHour = 326.5,
            fuelCost = 16.2,
            netProfit = 119.86,
            score = score
        )
        // TYPE_ACCESSIBILITY_OVERLAY needs the service's own context, so go through the service
        val shown = UberAccessibilityService.instance?.showTestOverlay(fakeAnalysis, quality, duration) ?: false
        if (!shown) _messages.emit("Activa el servicio de accesibilidad para probar el overlay.")
    }

    // Each tap on "Probar overlay" previews the next category: MALO, BUENO, EXCELENTE
    private var testOverlayCount = 0

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _exportEvents = MutableSharedFlow<ExportEvent>(extraBufferCapacity = 1)
    val exportEvents: SharedFlow<ExportEvent> = _exportEvents.asSharedFlow()

    // Content generated in requestSave, waiting for the user to pick a destination
    private var pendingSave: PendingExport? = null

    /** Builds the export and, if there is data, asks the UI to open the system file picker. */
    fun requestSave(format: ExportFormat, range: ExportRange) = viewModelScope.launch {
        val export = buildExport(format, range) ?: return@launch
        pendingSave = export
        _exportEvents.emit(ExportEvent.ReadyToSave(OfferExporter.fileName(format, range), format))
    }

    /** Writes the pending export to [uri]; a null uri means the user cancelled the picker. */
    fun savePendingTo(uri: Uri?) = viewModelScope.launch {
        val export = pendingSave
        pendingSave = null
        if (uri == null) return@launch
        if (export == null) {
            _exportEvents.emit(ExportEvent.Failed)
            return@launch
        }
        val event: ExportEvent = try {
            withContext(Dispatchers.IO) {
                val stream = getApplication<Application>().contentResolver.openOutputStream(uri)
                    ?: throw IOException("No output stream for $uri")
                stream.use { it.write(export.content.toByteArray(Charsets.UTF_8)) }
            }
            ExportEvent.Saved(export.count)
        } catch (e: Exception) {
            Log.e(TAG, "Export save failed", e)
            ExportEvent.Failed
        }
        _exportEvents.emit(event)
    }

    /** Builds the export into the cache and asks the UI to open the share sheet. */
    fun requestShare(format: ExportFormat, range: ExportRange) = viewModelScope.launch {
        val export = buildExport(format, range) ?: return@launch
        val app = getApplication<Application>()
        val event: ExportEvent = try {
            val uri = withContext(Dispatchers.IO) {
                val dir = File(app.cacheDir, "exports").apply { mkdirs() }
                val file = File(dir, OfferExporter.fileName(format, range))
                file.writeText(export.content, Charsets.UTF_8)
                FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
            }
            ExportEvent.ReadyToShare(uri, format.mimeType)
        } catch (e: Exception) {
            Log.e(TAG, "Export share failed", e)
            ExportEvent.Failed
        }
        _exportEvents.emit(event)
    }

    /** Returns null (after emitting the matching event) when there is nothing to export. */
    private suspend fun buildExport(format: ExportFormat, range: ExportRange): PendingExport? {
        return try {
            val offers = repository.getOffersSince(range.sinceMillis())
            if (offers.isEmpty()) {
                _exportEvents.emit(ExportEvent.Empty)
                null
            } else {
                val content = withContext(Dispatchers.Default) { OfferExporter.export(offers, format) }
                PendingExport(content, offers.size)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Export build failed", e)
            _exportEvents.emit(ExportEvent.Failed)
            null
        }
    }

    val uiState: StateFlow<DashboardUiState> = combine(
        repository.observeTotalCount(),
        repository.observeAvgRdpPerKm(),
        repository.observeAvgRdpPerHour(),
        repository.observeAvgScore(),
        repository.observeRecent(RECENT_LIMIT)
    ) { total, avgKm, avgHour, avgScore, recent ->
        DashboardUiState(
            totalOffers = total,
            avgRdpPerKm = avgKm ?: 0.0,
            avgRdpPerHour = avgHour ?: 0.0,
            avgScore = avgScore ?: 0.0,
            recentOffers = recent
        )
    }.combine(repository.observeDaySummaries()) { state, summaries ->
        state.copy(days = DashboardStats.groupByDay(state.recentOffers, summaries))
    }.combine(settings.qualityThresholds) { state, thresholds ->
        val aboveThreshold = state.recentOffers.count { it.score >= thresholds.goodFrom }
        state.copy(offersAboveThreshold = aboveThreshold, thresholds = thresholds)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        DashboardUiState()
    )

    private class PendingExport(val content: String, val count: Int)

    private companion object {
        const val TAG = "DashboardViewModel"
        const val RECENT_LIMIT = 200
    }
}
