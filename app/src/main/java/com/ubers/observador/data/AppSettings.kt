package com.ubers.observador.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ubers.observador.domain.EngineSettings
import com.ubers.observador.domain.QualityThresholds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class AppSettings(private val context: Context) {

    companion object {
        private val KEY_COST_PER_KM = doublePreferencesKey("cost_per_km")
        private val KEY_FUEL_PRICE_PER_GALLON = doublePreferencesKey("fuel_price_per_gallon")
        private val KEY_KM_PER_GALLON = doublePreferencesKey("km_per_gallon")
        private val KEY_MIN_RDP_PER_KM =doublePreferencesKey("min_rdp_per_km")
        private val KEY_MIN_RDP_PER_HOUR = doublePreferencesKey("min_rdp_per_hour")
        private val KEY_MAX_PICKUP_KM = doublePreferencesKey("max_pickup_km")
        private val KEY_MAX_PICKUP_MIN = intPreferencesKey("max_pickup_min")
        private val KEY_MIN_SCORE = intPreferencesKey("min_score_threshold")
        private val KEY_EXCELLENT_SCORE = intPreferencesKey("excellent_score_threshold")
        private val KEY_OVERLAY_DURATION = intPreferencesKey("overlay_duration_seconds")
        private val KEY_MODE = stringPreferencesKey("operating_mode")
        private val KEY_SHOW_OVERLAY = androidx.datastore.preferences.core.booleanPreferencesKey("show_overlay")
        private val KEY_GRACE_WINDOW = intPreferencesKey("grace_window_seconds")
        private val KEY_WORKDAY_START = intPreferencesKey("workday_start_hour")

        const val DEFAULT_WORKDAY_START_HOUR = 4

        const val MODE_OBSERVER = "OBSERVER"
        const val MODE_ASSISTED = "ASSISTED"
        const val MODE_AUTO = "AUTO"
    }

    val engineSettings: Flow<EngineSettings> = context.dataStore.data.map { prefs ->
        val fuelPrice = prefs[KEY_FUEL_PRICE_PER_GALLON] ?: 0.0
        val kmPerGallon = prefs[KEY_KM_PER_GALLON] ?: 0.0
        // Fuel price + consumption take precedence; otherwise fall back to the manual cost per km
        val costPerKm = if (fuelPrice > 0 && kmPerGallon > 0) {
            fuelPrice / kmPerGallon
        } else {
            prefs[KEY_COST_PER_KM] ?: 1.50
        }
        EngineSettings(
            costPerKm = costPerKm,
            fuelPricePerGallon = fuelPrice,
            kmPerGallon = kmPerGallon,
            minRdpPerKm = prefs[KEY_MIN_RDP_PER_KM] ?: 6.0,
            minRdpPerHour = prefs[KEY_MIN_RDP_PER_HOUR] ?: 300.0,
            maxPickupKm = prefs[KEY_MAX_PICKUP_KM] ?: 6.0,
            maxPickupMin = prefs[KEY_MAX_PICKUP_MIN] ?: 12
        )
    }

    val minScoreThreshold: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_MIN_SCORE] ?: 60
    }

    val excellentScoreThreshold: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_EXCELLENT_SCORE] ?: 80
    }

    /** BUENO starts at the minimum score; EXCELENTE at its own threshold, always above it. */
    val qualityThresholds: Flow<QualityThresholds> = context.dataStore.data.map { prefs ->
        QualityThresholds.of(prefs[KEY_MIN_SCORE] ?: 60, prefs[KEY_EXCELLENT_SCORE] ?: 80)
    }

    val overlayDurationSeconds: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_OVERLAY_DURATION] ?: 15
    }

    val operatingMode: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_MODE] ?: MODE_OBSERVER
    }

    val showOverlay: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_SHOW_OVERLAY] ?: true
    }

    val graceWindowSeconds: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_GRACE_WINDOW] ?: 60
    }

    /** Hour (0-23) when the driver's working day starts; offers before it count in the day before. */
    val workdayStartHour: Flow<Int> = context.dataStore.data.map { prefs ->
        (prefs[KEY_WORKDAY_START] ?: DEFAULT_WORKDAY_START_HOUR).coerceIn(0, 23)
    }

    suspend fun updateWorkdayStartHour(value: Int) =
        context.dataStore.edit { it[KEY_WORKDAY_START] = value }

    suspend fun updateCostPerKm(value: Double) =
        context.dataStore.edit { it[KEY_COST_PER_KM] = value }

    suspend fun updateFuelPricePerGallon(value: Double) =
        context.dataStore.edit { it[KEY_FUEL_PRICE_PER_GALLON] = value }

    suspend fun updateKmPerGallon(value: Double) =
        context.dataStore.edit { it[KEY_KM_PER_GALLON] = value }

    suspend fun updateMinRdpPerKm(value: Double) =
        context.dataStore.edit { it[KEY_MIN_RDP_PER_KM] = value }

    suspend fun updateMinRdpPerHour(value: Double) =
        context.dataStore.edit { it[KEY_MIN_RDP_PER_HOUR] = value }

    suspend fun updateMaxPickupKm(value: Double) =
        context.dataStore.edit { it[KEY_MAX_PICKUP_KM] = value }

    suspend fun updateMaxPickupMin(value: Int) =
        context.dataStore.edit { it[KEY_MAX_PICKUP_MIN] = value }

    suspend fun updateMinScore(value: Int) =
        context.dataStore.edit { it[KEY_MIN_SCORE] = value }

    suspend fun updateExcellentScore(value: Int) =
        context.dataStore.edit { it[KEY_EXCELLENT_SCORE] = value }

    suspend fun updateOverlayDuration(value: Int) =
        context.dataStore.edit { it[KEY_OVERLAY_DURATION] = value }

    suspend fun updateMode(value: String) =
        context.dataStore.edit { it[KEY_MODE] = value }

    suspend fun updateShowOverlay(value: Boolean) =
        context.dataStore.edit { it[KEY_SHOW_OVERLAY] = value }

    suspend fun updateGraceWindow(value: Int) =
        context.dataStore.edit { it[KEY_GRACE_WINDOW] = value }
}
