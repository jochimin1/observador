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
import com.ubers.observador.domain.ProfileValues
import com.ubers.observador.domain.ProfitSettingsSnapshot
import com.ubers.observador.domain.QualityThresholds
import com.ubers.observador.domain.ScoreWeights
import com.ubers.observador.domain.SettingsProfile
import com.ubers.observador.domain.SettingsProfileCodec
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
        private val KEY_WEIGHT_KM = intPreferencesKey("weight_km_pct")
        private val KEY_WEIGHT_HOUR = intPreferencesKey("weight_hour_pct")
        private val KEY_WEIGHT_PICKUP_KM = intPreferencesKey("weight_pickup_km_pct")
        private val KEY_WEIGHT_PICKUP_MIN = intPreferencesKey("weight_pickup_min_pct")
        private val KEY_SHADOW_MODEL = stringPreferencesKey("shadow_model_version_id")
        private val KEY_OBSERVER_ENABLED = androidx.datastore.preferences.core.booleanPreferencesKey("observer_enabled")
        private val KEY_PROFILES = stringPreferencesKey("settings_profiles")
        private val KEY_ACTIVE_PROFILE = stringPreferencesKey("active_profile_id")
        // Fase 8E: kilometres with GPS. Off until the driver turns it on and grants location
        private val KEY_DISTANCE_ENABLED = androidx.datastore.preferences.core.booleanPreferencesKey("distance_enabled")
        private val KEY_DISTANCE_MODE = stringPreferencesKey("distance_mode")
        private val KEY_DISTANCE_MANUAL = androidx.datastore.preferences.core.booleanPreferencesKey("distance_manual")
        // Fase 8B: expenses. Not part of profiles
        private val KEY_WORK_SHARE = intPreferencesKey("work_share_pct")
        private val KEY_MAINTENANCE_NOTIFICATION = androidx.datastore.preferences.core.booleanPreferencesKey("maintenance_notification")
        private fun intervalKey(type: com.ubers.observador.domain.expenses.ExpenseType) =
            androidx.datastore.preferences.core.longPreferencesKey("maintenance_interval_km_${type.storageKey.lowercase()}")
        private fun notifiedKey(type: com.ubers.observador.domain.expenses.ExpenseType) =
            stringPreferencesKey("maintenance_notified_${type.storageKey.lowercase()}")

        // Fase 8C: goals. Not part of profiles
        private val KEY_DAILY_GOAL = androidx.datastore.preferences.core.longPreferencesKey("daily_goal_minor")
        private val KEY_WEEKLY_GOAL = androidx.datastore.preferences.core.longPreferencesKey("weekly_goal_minor")
        private val KEY_GOAL_NOTIFICATION = androidx.datastore.preferences.core.booleanPreferencesKey("goal_notification")
        private val KEY_WEEK_START = stringPreferencesKey("week_start_day")
        private val KEY_GOAL_NOTIFIED_DAY = stringPreferencesKey("goal_notified_day")
        private val KEY_GOAL_NOTIFIED_WEEK = stringPreferencesKey("goal_notified_week")
        // Fase 8D: "Tu resumen de la semana está listo" (decision 10: off until turned on)
        private val KEY_WEEKLY_REPORT_NOTIFICATION = androidx.datastore.preferences.core.booleanPreferencesKey("weekly_report_notification")
        private val KEY_WEEKLY_REPORT_NOTIFIED = stringPreferencesKey("weekly_report_notified")

        // Fase 8G: looks. Not part of profiles
        private val KEY_THEME = stringPreferencesKey("app_theme")
        private val KEY_AUTO_NIGHT = androidx.datastore.preferences.core.booleanPreferencesKey("auto_night")
        private val KEY_NIGHT_FROM = intPreferencesKey("night_from_hour")
        private val KEY_NIGHT_TO = intPreferencesKey("night_to_hour")

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
            maxPickupMin = prefs[KEY_MAX_PICKUP_MIN] ?: 12,
            weights = ScoreWeights.of(
                prefs[KEY_WEIGHT_KM], prefs[KEY_WEIGHT_HOUR], prefs[KEY_WEIGHT_PICKUP_KM], prefs[KEY_WEIGHT_PICKUP_MIN]
            )
        )
    }

    /** Every setting that decides a score, read together (Fase 7D). */
    val profitSettings: Flow<ProfitSettingsSnapshot> = context.dataStore.data.map { prefs ->
        ProfitSettingsSnapshot(
            minRdpPerKm = prefs[KEY_MIN_RDP_PER_KM] ?: 6.0,
            minRdpPerHour = prefs[KEY_MIN_RDP_PER_HOUR] ?: 300.0,
            maxPickupKm = prefs[KEY_MAX_PICKUP_KM] ?: 6.0,
            maxPickupMinutes = prefs[KEY_MAX_PICKUP_MIN] ?: 12,
            weights = ScoreWeights.of(
                prefs[KEY_WEIGHT_KM], prefs[KEY_WEIGHT_HOUR], prefs[KEY_WEIGHT_PICKUP_KM], prefs[KEY_WEIGHT_PICKUP_MIN]
            ),
            goodFrom = QualityThresholds.of(prefs[KEY_MIN_SCORE] ?: 60, prefs[KEY_EXCELLENT_SCORE] ?: 80).goodFrom,
            excellentFrom = QualityThresholds.of(prefs[KEY_MIN_SCORE] ?: 60, prefs[KEY_EXCELLENT_SCORE] ?: 80).excellentFrom
        )
    }

    /**
     * Master switch. When false Observador reads nothing from Uber: no offers, no overlay, no
     * notifications, no trips, no money. Default true, so updating the app changes nothing.
     */
    val observerEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_OBSERVER_ENABLED] ?: true }

    suspend fun updateObserverEnabled(value: Boolean) = context.dataStore.edit {
        it[KEY_OBSERVER_ENABLED] = value
        // Pausing also ends a measurement started with the button (decision 12: pause stops the GPS)
        if (!value) it[KEY_DISTANCE_MANUAL] = false
    }

    /** Daily and weekly goals, the week start and the notification (Fase 8C). */
    val goals: Flow<com.ubers.observador.domain.earnings.GoalSettings> = context.dataStore.data.map { prefs ->
        com.ubers.observador.domain.earnings.GoalSettings(
            dailyMinor = (prefs[KEY_DAILY_GOAL] ?: 0L).coerceIn(0, com.ubers.observador.domain.earnings.Goals.MAX_GOAL_MINOR),
            weeklyMinor = (prefs[KEY_WEEKLY_GOAL] ?: 0L).coerceIn(0, com.ubers.observador.domain.earnings.Goals.MAX_GOAL_MINOR),
            notify = prefs[KEY_GOAL_NOTIFICATION] ?: false,
            weekStart = if (prefs[KEY_WEEK_START] == java.time.DayOfWeek.SUNDAY.name) java.time.DayOfWeek.SUNDAY else java.time.DayOfWeek.MONDAY
        )
    }

    suspend fun updateDailyGoal(minor: Long) = context.dataStore.edit { it[KEY_DAILY_GOAL] = minor.coerceIn(0, com.ubers.observador.domain.earnings.Goals.MAX_GOAL_MINOR) }

    suspend fun updateWeeklyGoal(minor: Long) = context.dataStore.edit { it[KEY_WEEKLY_GOAL] = minor.coerceIn(0, com.ubers.observador.domain.earnings.Goals.MAX_GOAL_MINOR) }

    suspend fun updateGoalNotification(value: Boolean) = context.dataStore.edit { it[KEY_GOAL_NOTIFICATION] = value }

    suspend fun updateWeekStart(day: java.time.DayOfWeek) = context.dataStore.edit {
        it[KEY_WEEK_START] = if (day == java.time.DayOfWeek.SUNDAY) day.name else java.time.DayOfWeek.MONDAY.name
    }

    /** The day and week ("2026-10-13") whose "meta alcanzada" was already notified, so it is sent once. */
    val goalNotified: Flow<Pair<String?, String?>> = context.dataStore.data.map { it[KEY_GOAL_NOTIFIED_DAY] to it[KEY_GOAL_NOTIFIED_WEEK] }

    suspend fun markGoalNotified(day: String?, week: String?) = context.dataStore.edit { prefs ->
        day?.let { prefs[KEY_GOAL_NOTIFIED_DAY] = it }
        week?.let { prefs[KEY_GOAL_NOTIFIED_WEEK] = it }
    }

    /** The Monday notification with the summary of the week that ended (Fase 8D). Off by default. */
    val weeklyReportNotification: Flow<Boolean> = context.dataStore.data.map { it[KEY_WEEKLY_REPORT_NOTIFICATION] ?: false }

    suspend fun updateWeeklyReportNotification(value: Boolean) = context.dataStore.edit { it[KEY_WEEKLY_REPORT_NOTIFICATION] = value }

    /** First day ("2026-10-05") of the last week whose summary was notified, so it is sent once. */
    val weeklyReportNotified: Flow<String?> = context.dataStore.data.map { it[KEY_WEEKLY_REPORT_NOTIFIED] }

    suspend fun markWeeklyReportNotified(weekKey: String) = context.dataStore.edit { it[KEY_WEEKLY_REPORT_NOTIFIED] = weekKey }

    /** Theme, and "Noche" by the hour (Fase 8G). */
    val themeSettings: Flow<com.ubers.observador.domain.ThemeSettings> = context.dataStore.data.map { prefs ->
        com.ubers.observador.domain.ThemeSettings(
            theme = com.ubers.observador.domain.AppTheme.fromStorage(prefs[KEY_THEME]),
            autoNight = prefs[KEY_AUTO_NIGHT] ?: false,
            nightFromHour = (prefs[KEY_NIGHT_FROM] ?: 19).coerceIn(0, 23),
            nightToHour = (prefs[KEY_NIGHT_TO] ?: 6).coerceIn(0, 23)
        )
    }

    suspend fun updateTheme(theme: com.ubers.observador.domain.AppTheme) = context.dataStore.edit { it[KEY_THEME] = theme.storageKey }

    suspend fun updateAutoNight(value: Boolean) = context.dataStore.edit { it[KEY_AUTO_NIGHT] = value }

    suspend fun updateNightHours(from: Int, to: Int) = context.dataStore.edit {
        it[KEY_NIGHT_FROM] = from.coerceIn(0, 23)
        it[KEY_NIGHT_TO] = to.coerceIn(0, 23)
    }

    /** Share of the motorbike used for work, 0-100 (decision 8: one % for everything, 100 by default). */
    val workSharePct: Flow<Int> = context.dataStore.data.map { it[KEY_WORK_SHARE] ?: 100 }

    suspend fun updateWorkSharePct(value: Int) = context.dataStore.edit {
        it[KEY_WORK_SHARE] = value.coerceIn(com.ubers.observador.domain.expenses.ExpenseLimits.WORK_SHARE_MIN, com.ubers.observador.domain.expenses.ExpenseLimits.WORK_SHARE_MAX)
    }

    /** Reminder interval per maintenance kind, in km; 0 = no reminder. None comes preset (it depends on the bike and the oil). */
    val maintenanceIntervals: Flow<Map<com.ubers.observador.domain.expenses.ExpenseType, Long>> = context.dataStore.data.map { prefs ->
        com.ubers.observador.domain.expenses.ExpenseType.REMINDABLE.associateWith { prefs[intervalKey(it)] ?: 0L }
    }

    suspend fun updateMaintenanceInterval(type: com.ubers.observador.domain.expenses.ExpenseType, km: Long) =
        context.dataStore.edit { it[intervalKey(type)] = km.coerceIn(0, com.ubers.observador.domain.expenses.ExpenseLimits.INTERVAL_MAX_KM) }

    /** Decision 10: the maintenance notification is on by default. */
    val maintenanceNotification: Flow<Boolean> = context.dataStore.data.map { it[KEY_MAINTENANCE_NOTIFICATION] ?: true }

    suspend fun updateMaintenanceNotification(value: Boolean) = context.dataStore.edit { it[KEY_MAINTENANCE_NOTIFICATION] = value }

    /** What was last notified per kind ("change id:level"), so the same warning is not repeated. */
    val maintenanceNotified: Flow<Map<com.ubers.observador.domain.expenses.ExpenseType, String>> = context.dataStore.data.map { prefs ->
        com.ubers.observador.domain.expenses.ExpenseType.REMINDABLE.mapNotNull { t -> prefs[notifiedKey(t)]?.let { t to it } }.toMap()
    }

    suspend fun markMaintenanceNotified(type: com.ubers.observador.domain.expenses.ExpenseType, key: String) =
        context.dataStore.edit { it[notifiedKey(type)] = key }

    /** "Medir km con GPS" (Fase 8E). Not part of profiles. */
    val distanceEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_DISTANCE_ENABLED] ?: false }

    suspend fun updateDistanceEnabled(value: Boolean) = context.dataStore.edit {
        it[KEY_DISTANCE_ENABLED] = value
        if (!value) it[KEY_DISTANCE_MANUAL] = false
    }

    val distanceMode: Flow<com.ubers.observador.domain.distance.DistanceMode> =
        context.dataStore.data.map { com.ubers.observador.domain.distance.DistanceMode.fromStorage(it[KEY_DISTANCE_MODE]) }

    suspend fun updateDistanceMode(value: com.ubers.observador.domain.distance.DistanceMode) =
        context.dataStore.edit { it[KEY_DISTANCE_MODE] = value.storageKey }

    /** The "Iniciar recorrido" button; kept across restarts so a measurement survives the app being closed. */
    val distanceManual: Flow<Boolean> = context.dataStore.data.map { it[KEY_DISTANCE_MANUAL] ?: false }

    suspend fun updateDistanceManual(value: Boolean) = context.dataStore.edit { it[KEY_DISTANCE_MANUAL] = value }

    /** Saved settings profiles (see SettingsProfileCodec). A broken entry is skipped, never fatal. */
    val profiles: Flow<List<SettingsProfile>> = context.dataStore.data.map { SettingsProfileCodec.decode(it[KEY_PROFILES]) }

    /** The profile last applied or saved, to show "Perfil: Noche" (and whether it was edited since). */
    val activeProfileId: Flow<String?> = context.dataStore.data.map { it[KEY_ACTIVE_PROFILE] }

    suspend fun saveProfiles(profiles: List<SettingsProfile>, activeId: String?) = context.dataStore.edit {
        it[KEY_PROFILES] = SettingsProfileCodec.encode(profiles)
        if (activeId == null) it.remove(KEY_ACTIVE_PROFILE) else it[KEY_ACTIVE_PROFILE] = activeId
    }

    /** The values a profile would save, exactly as stored now (manual cost per km, not the derived one). */
    val profileValues: Flow<ProfileValues> = context.dataStore.data.map { prefs ->
        val d = ProfileValues.DEFAULTS
        ProfileValues(
            fuelPricePerGallon = prefs[KEY_FUEL_PRICE_PER_GALLON] ?: d.fuelPricePerGallon,
            kmPerGallon = prefs[KEY_KM_PER_GALLON] ?: d.kmPerGallon,
            manualCostPerKm = prefs[KEY_COST_PER_KM] ?: d.manualCostPerKm,
            minRdpPerKm = prefs[KEY_MIN_RDP_PER_KM] ?: d.minRdpPerKm,
            minRdpPerHour = prefs[KEY_MIN_RDP_PER_HOUR] ?: d.minRdpPerHour,
            maxPickupKm = prefs[KEY_MAX_PICKUP_KM] ?: d.maxPickupKm,
            maxPickupMin = prefs[KEY_MAX_PICKUP_MIN] ?: d.maxPickupMin,
            goodFrom = prefs[KEY_MIN_SCORE] ?: d.goodFrom,
            excellentFrom = prefs[KEY_EXCELLENT_SCORE] ?: d.excellentFrom,
            weights = ScoreWeights.of(prefs[KEY_WEIGHT_KM], prefs[KEY_WEIGHT_HOUR], prefs[KEY_WEIGHT_PICKUP_KM], prefs[KEY_WEIGHT_PICKUP_MIN]),
            showOverlay = prefs[KEY_SHOW_OVERLAY] ?: d.showOverlay,
            overlaySeconds = prefs[KEY_OVERLAY_DURATION] ?: d.overlaySeconds,
            graceSeconds = prefs[KEY_GRACE_WINDOW] ?: d.graceSeconds,
            mode = prefs[KEY_MODE] ?: d.mode
        )
    }

    /** Applies a whole profile in one edit, so no offer is ever judged with half of two profiles. */
    suspend fun applyProfile(profile: SettingsProfile) = context.dataStore.edit {
        val v = profile.values
        it[KEY_FUEL_PRICE_PER_GALLON] = v.fuelPricePerGallon
        it[KEY_KM_PER_GALLON] = v.kmPerGallon
        it[KEY_COST_PER_KM] = v.manualCostPerKm
        it[KEY_MIN_RDP_PER_KM] = v.minRdpPerKm
        it[KEY_MIN_RDP_PER_HOUR] = v.minRdpPerHour
        it[KEY_MAX_PICKUP_KM] = v.maxPickupKm
        it[KEY_MAX_PICKUP_MIN] = v.maxPickupMin
        it[KEY_MIN_SCORE] = v.goodFrom
        it[KEY_EXCELLENT_SCORE] = v.excellentFrom
        it[KEY_WEIGHT_KM] = v.weights.kmPct
        it[KEY_WEIGHT_HOUR] = v.weights.hourPct
        it[KEY_WEIGHT_PICKUP_KM] = v.weights.pickupKmPct
        it[KEY_WEIGHT_PICKUP_MIN] = v.weights.pickupMinPct
        it[KEY_SHOW_OVERLAY] = v.showOverlay
        it[KEY_OVERLAY_DURATION] = v.overlaySeconds
        it[KEY_GRACE_WINDOW] = v.graceSeconds
        it[KEY_MODE] = v.mode
        it[KEY_ACTIVE_PROFILE] = profile.id
    }

    /** The model version scored silently next to the active one, or null when no shadow test runs. */
    val shadowModelVersionId: Flow<String?> = context.dataStore.data.map { it[KEY_SHADOW_MODEL] }

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

    /**
     * Writes a whole model in one edit (promotion or rollback, only after the user confirms), so
     * no offer is ever scored with half of one model and half of another.
     */
    suspend fun applyProfitSettings(model: ProfitSettingsSnapshot) = context.dataStore.edit {
        it[KEY_MIN_RDP_PER_KM] = model.minRdpPerKm
        it[KEY_MIN_RDP_PER_HOUR] = model.minRdpPerHour
        it[KEY_MAX_PICKUP_KM] = model.maxPickupKm
        it[KEY_MAX_PICKUP_MIN] = model.maxPickupMinutes
        it[KEY_WEIGHT_KM] = model.weights.kmPct
        it[KEY_WEIGHT_HOUR] = model.weights.hourPct
        it[KEY_WEIGHT_PICKUP_KM] = model.weights.pickupKmPct
        it[KEY_WEIGHT_PICKUP_MIN] = model.weights.pickupMinPct
        it[KEY_MIN_SCORE] = model.goodFrom
        it[KEY_EXCELLENT_SCORE] = model.excellentFrom
    }

    suspend fun updateShadowModel(versionId: String?) = context.dataStore.edit {
        if (versionId == null) it.remove(KEY_SHADOW_MODEL) else it[KEY_SHADOW_MODEL] = versionId
    }

    suspend fun updateGraceWindow(value: Int) =
        context.dataStore.edit { it[KEY_GRACE_WINDOW] = value }
}
