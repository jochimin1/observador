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
import com.ubers.observador.domain.ProfileValues
import com.ubers.observador.domain.SettingsProfile
import com.ubers.observador.domain.SettingsProfiles
import java.util.UUID
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

/** Saved profiles and which one, if any, matches the values in use. */
data class ProfilesUiState(
    val profiles: List<SettingsProfile> = emptyList(),
    val activeId: String? = null,
    val current: ProfileValues? = null
) {
    /** The profile whose values are exactly the ones in use. */
    val inUse: SettingsProfile? get() = current?.let { SettingsProfiles.matching(profiles, it, activeId) }

    /** The last applied profile, if its values were edited since (shown as "modificado"). */
    val edited: SettingsProfile? get() = if (inUse != null) null else profiles.firstOrNull { it.id == activeId }
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

    // ---- Master switch ----

    val observerEnabled: StateFlow<Boolean> = appSettings.observerEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun setObserverEnabled(enabled: Boolean) = viewModelScope.launch { appSettings.updateObserverEnabled(enabled) }

    // ---- Expenses (Fase 8B) ----

    val workSharePct: StateFlow<Int> = appSettings.workSharePct
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 100)

    val maintenanceIntervals: StateFlow<Map<com.ubers.observador.domain.expenses.ExpenseType, Long>> = appSettings.maintenanceIntervals
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val maintenanceNotification: StateFlow<Boolean> = appSettings.maintenanceNotification
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun setWorkSharePct(v: Int) = viewModelScope.launch { appSettings.updateWorkSharePct(v) }

    fun setMaintenanceInterval(type: com.ubers.observador.domain.expenses.ExpenseType, km: Int) = viewModelScope.launch {
        appSettings.updateMaintenanceInterval(type, km.toLong())
        com.ubers.observador.service.MaintenanceNotifier.check(getApplication())
    }

    fun setMaintenanceNotification(on: Boolean) = viewModelScope.launch { appSettings.updateMaintenanceNotification(on) }

    // ---- Goals (Fase 8C) ----

    val goals: StateFlow<com.ubers.observador.domain.earnings.GoalSettings> = appSettings.goals
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), com.ubers.observador.domain.earnings.GoalSettings())

    fun setDailyGoalPesos(pesos: Int) = viewModelScope.launch { appSettings.updateDailyGoal(pesos * 100L) }

    fun setWeeklyGoalPesos(pesos: Int) = viewModelScope.launch { appSettings.updateWeeklyGoal(pesos * 100L) }

    fun setWeekStart(day: java.time.DayOfWeek) = viewModelScope.launch { appSettings.updateWeekStart(day) }

    fun setGoalNotification(on: Boolean) = viewModelScope.launch {
        appSettings.updateGoalNotification(on)
        if (on) com.ubers.observador.service.GoalNotifier.check(getApplication())
    }

    // ---- Kilometres with GPS (Fase 8E) ----

    val distanceEnabled: StateFlow<Boolean> = appSettings.distanceEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val distanceMode: StateFlow<com.ubers.observador.domain.distance.DistanceMode> = appSettings.distanceMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), com.ubers.observador.domain.distance.DistanceMode.AUTO_AND_MANUAL)

    fun setDistanceEnabled(enabled: Boolean) = viewModelScope.launch { appSettings.updateDistanceEnabled(enabled) }

    fun setDistanceMode(mode: com.ubers.observador.domain.distance.DistanceMode) =
        viewModelScope.launch { appSettings.updateDistanceMode(mode) }

    // ---- Profiles ----

    val profiles: StateFlow<ProfilesUiState> = combine(
        appSettings.profiles,
        appSettings.activeProfileId,
        appSettings.profileValues
    ) { list, activeId, current -> ProfilesUiState(list, activeId, current) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProfilesUiState())

    private val _profileMessage = MutableStateFlow<String?>(null)
    val profileMessage: StateFlow<String?> = _profileMessage.asStateFlow()

    fun clearProfileMessage() {
        _profileMessage.value = null
    }

    /** Saves the values in use now as a new profile, which becomes the active one. */
    fun saveNewProfile(name: String) = viewModelScope.launch {
        val list = appSettings.profiles.first()
        val values = appSettings.profileValues.first()
        _profileMessage.value = when (val r = SettingsProfiles.create(list, name, values, { UUID.randomUUID().toString() })) {
            is SettingsProfiles.Result.Ok -> {
                appSettings.saveProfiles(r.profiles, r.profile.id)
                "Perfil \"${r.profile.name}\" guardado."
            }
            is SettingsProfiles.Result.Error -> r.message
        }
    }

    /** Replaces a profile's values with the ones in use now (after the driver confirms). */
    fun overwriteProfile(id: String) = viewModelScope.launch {
        val list = appSettings.profiles.first()
        _profileMessage.value = when (val r = SettingsProfiles.overwrite(list, id, appSettings.profileValues.first())) {
            is SettingsProfiles.Result.Ok -> {
                appSettings.saveProfiles(r.profiles, r.profile.id)
                "Perfil \"${r.profile.name}\" actualizado con los valores actuales."
            }
            is SettingsProfiles.Result.Error -> r.message
        }
    }

    fun renameProfile(id: String, name: String) = viewModelScope.launch {
        val list = appSettings.profiles.first()
        _profileMessage.value = when (val r = SettingsProfiles.rename(list, id, name)) {
            is SettingsProfiles.Result.Ok -> {
                appSettings.saveProfiles(r.profiles, appSettings.activeProfileId.first())
                "Ahora se llama \"${r.profile.name}\"."
            }
            is SettingsProfiles.Result.Error -> r.message
        }
    }

    fun deleteProfile(id: String) = viewModelScope.launch {
        val list = appSettings.profiles.first()
        val name = list.firstOrNull { it.id == id }?.name ?: return@launch
        val active = appSettings.activeProfileId.first()?.takeIf { it != id }
        appSettings.saveProfiles(SettingsProfiles.delete(list, id), active)
        _profileMessage.value = "Perfil \"$name\" borrado. Tus ajustes actuales no cambian."
    }

    /** Loads every value of the profile at once. A profile with a value out of range is refused. */
    fun applyProfile(id: String) = viewModelScope.launch {
        val profile = appSettings.profiles.first().firstOrNull { it.id == id } ?: return@launch
        _profileMessage.value = if (!profile.values.isValid) {
            "Este perfil tiene un valor fuera de rango y no se aplicó."
        } else {
            appSettings.applyProfile(profile)
            "Perfil \"${profile.name}\" aplicado."
        }
    }

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
