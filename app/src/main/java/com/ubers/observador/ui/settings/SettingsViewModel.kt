package com.ubers.observador.ui.settings

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ubers.observador.data.AppSettings
import com.ubers.observador.data.OfferRepository
import com.ubers.observador.data.db.AppDatabase
import com.ubers.observador.data.debug.TestOfferSeeder
import com.ubers.observador.domain.OfferQuality
import com.ubers.observador.domain.models.OfferAnalysis
import com.ubers.observador.domain.models.UberOffer
import com.ubers.observador.service.UberAccessibilityService
import com.ubers.observador.domain.EngineSettings
import com.ubers.observador.domain.QualityThresholds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZonedDateTime

data class SettingsUiState(
    val engineSettings: EngineSettings = EngineSettings(),
    val minScore: Int = 60,
    val overlayDuration: Int = 15,
    val showOverlay: Boolean = true,
    val mode: String = AppSettings.MODE_OBSERVER,
    val graceWindow: Int = 60,
    val excellentScore: Int = 80,
    val workdayStartHour: Int = AppSettings.DEFAULT_WORKDAY_START_HOUR
) {
    /** Effective cut-offs: EXCELENTE is always above BUENO even if the raw values cross. */
    val thresholds: QualityThresholds get() = QualityThresholds.of(minScore, excellentScore)
}

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val appSettings = AppSettings(app)

    val uiState: StateFlow<SettingsUiState> = combine(
        appSettings.engineSettings,
        appSettings.minScoreThreshold,
        appSettings.overlayDurationSeconds,
        appSettings.showOverlay,
        appSettings.operatingMode
    ) { engine, minScore, duration, showOverlay, mode ->
        SettingsUiState(
            engineSettings = engine,
            minScore = minScore,
            overlayDuration = duration,
            showOverlay = showOverlay,
            mode = mode
        )
    }.combine(appSettings.graceWindowSeconds) { state, graceWindow ->
        state.copy(graceWindow = graceWindow)
    }.combine(appSettings.excellentScoreThreshold) { state, excellent ->
        state.copy(excellentScore = excellent)
    }.combine(appSettings.workdayStartHour) { state, startHour ->
        state.copy(workdayStartHour = startHour)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun updateCostPerKm(v: Double) = viewModelScope.launch { appSettings.updateCostPerKm(v) }
    fun updateFuelPricePerGallon(v: Double) = viewModelScope.launch { appSettings.updateFuelPricePerGallon(v) }
    fun updateKmPerGallon(v: Double) = viewModelScope.launch { appSettings.updateKmPerGallon(v) }
    fun updateMinRdpPerKm(v: Double) = viewModelScope.launch { appSettings.updateMinRdpPerKm(v) }
    fun updateMinRdpPerHour(v: Double) = viewModelScope.launch { appSettings.updateMinRdpPerHour(v) }
    fun updateMaxPickupKm(v: Double) = viewModelScope.launch { appSettings.updateMaxPickupKm(v) }
    fun updateMaxPickupMin(v: Int) = viewModelScope.launch { appSettings.updateMaxPickupMin(v) }
    fun updateMinScore(v: Int) = viewModelScope.launch { appSettings.updateMinScore(v) }
    fun updateOverlayDuration(v: Int) = viewModelScope.launch { appSettings.updateOverlayDuration(v) }
    fun updateShowOverlay(v: Boolean) = viewModelScope.launch { appSettings.updateShowOverlay(v) }
    fun updateMode(v: String) = viewModelScope.launch { appSettings.updateMode(v) }
    fun updateGraceWindow(v: Int) = viewModelScope.launch { appSettings.updateGraceWindow(v) }
    fun updateExcellentScore(v: Int) = viewModelScope.launch { appSettings.updateExcellentScore(v) }
    fun updateWorkdayStartHour(v: Int) = viewModelScope.launch { appSettings.updateWorkdayStartHour(v) }

    // ---- Overlay preview ----

    // Each tap previews the next category: MALO, BUENO, EXCELENTE
    private var overlayPreviewCount = 0

    private val _overlayTestMessage = MutableStateFlow<String?>(null)
    val overlayTestMessage: StateFlow<String?> = _overlayTestMessage.asStateFlow()

    fun showTestOverlay() = viewModelScope.launch {
        val duration = appSettings.overlayDurationSeconds.first()
        val quality = OfferQuality.entries[overlayPreviewCount++ % OfferQuality.entries.size]
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
        _overlayTestMessage.value = if (shown) {
            "Mostrando la tarjeta ${quality.label} durante $duration s."
        } else {
            "Activa el servicio de accesibilidad para probar la tarjeta."
        }
    }

    // ---- Debug tools: known fake offers to check the dashboard without driving ----

    private val offers = OfferRepository(AppDatabase.getInstance(app))

    private val _testDataMessage = MutableStateFlow<String?>(null)
    val testDataMessage: StateFlow<String?> = _testDataMessage.asStateFlow()

    /** Replaces any earlier test offers with a fresh set, so loading twice never doubles them. */
    fun loadTestOffers() = viewModelScope.launch {
        _testDataMessage.value = try {
            val generated = TestOfferSeeder.generate(ZonedDateTime.now())
            offers.replaceByHashPrefix(TestOfferSeeder.HASH_PREFIX, generated)
            "${generated.size} ofertas de prueba cargadas"
        } catch (e: Exception) {
            Log.e(TAG, "Could not load test offers", e)
            "No se pudieron cargar las ofertas de prueba"
        }
    }

    /** Deletes only the test offers; real ones are never touched. */
    fun clearTestOffers() = viewModelScope.launch {
        _testDataMessage.value = try {
            val removed = offers.deleteByHashPrefix(TestOfferSeeder.HASH_PREFIX)
            if (removed == 0) "No había ofertas de prueba" else "$removed ofertas de prueba borradas"
        } catch (e: Exception) {
            Log.e(TAG, "Could not delete test offers", e)
            "No se pudieron borrar las ofertas de prueba"
        }
    }

    private companion object {
        const val TAG = "SettingsViewModel"
    }
}
