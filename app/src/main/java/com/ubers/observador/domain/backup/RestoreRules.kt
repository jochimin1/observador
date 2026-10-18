package com.ubers.observador.domain.backup

import com.ubers.observador.domain.SettingsProfileCodec

/** How the database inside a backup relates to the one this app version uses. */
enum class RestoreCompat {
    /** Same Room version: opens as is. */
    SAME,
    /** Older: Room migrates it when the app opens it (migrations never destroy data). */
    OLDER,
    /** Newer: made by a later version of the app; this one cannot read it. */
    NEWER
}

/** Settings read from a backup after checking each one against the same limits as Ajustes. */
data class ValidatedSettings(
    /** Ready to write, each with the type its DataStore key has. */
    val values: Map<String, Any>,
    /** Present in the file but invalid (or unreadable): they go back to their default and the driver is told. */
    val defaulted: List<String>,
    /** Not settings of this version, or ones that never travel: left out. */
    val ignored: List<String>
)

/** The order in which a restore runs. A failure at or after [REPLACE_DATABASE] rolls back. */
enum class RestoreStep(val label: String) {
    VERIFY_FILE("Revisando el archivo"),
    CHECK_COPY("Comprobando la base de datos"),
    SAFETY_BACKUP("Guardando copia de seguridad"),
    PAUSE_READING("Pausando la lectura"),
    DRAIN_WRITES("Guardando lo pendiente"),
    CLOSE_DATABASE("Cerrando la base de datos"),
    REPLACE_DATABASE("Reemplazando datos"),
    REPLACE_SETTINGS("Aplicando ajustes"),
    RESTART("Reiniciando la app");

    /** The steps the driver sees while restoring (the file was already checked when it was chosen). */
    companion object { val SHOWN: List<RestoreStep> = listOf(SAFETY_BACKUP, PAUSE_READING, REPLACE_DATABASE, REPLACE_SETTINGS, RESTART) }

    val needsRollback: Boolean get() = ordinal >= REPLACE_DATABASE.ordinal && this != RESTART
}

object RestoreRules {

    fun compat(backupDbVersion: Int, appDbVersion: Int): RestoreCompat = when {
        backupDbVersion == appDbVersion -> RestoreCompat.SAME
        backupDbVersion < appDbVersion -> RestoreCompat.OLDER
        else -> RestoreCompat.NEWER
    }

    /** Null when the restore can go on; otherwise what to tell the driver. */
    fun refusal(backupDbVersion: Int, appDbVersion: Int): String? = when {
        backupDbVersion < 1 -> "El respaldo está dañado (versión de base de datos inválida)."
        compat(backupDbVersion, appDbVersion) == RestoreCompat.NEWER -> "Actualiza Observador antes de restaurar este respaldo."
        else -> null
    }

    const val ROLLED_BACK = "No se restauró; tus datos están como antes."

    // --- settings ---

    private sealed interface Rule
    private object Bool : Rule
    private class IntR(val min: Int, val max: Int) : Rule
    private class LongR(val min: Long, val max: Long) : Rule
    private class DoubleR(val min: Double, val max: Double) : Rule
    private class Str(val allowed: Set<String>?, val maxLen: Int = 200) : Rule
    private object Profiles : Rule

    private val RULES: Map<String, Rule> = mapOf(
        "cost_per_km" to DoubleR(0.01, 100.0),
        "fuel_price_per_gallon" to DoubleR(0.0, 5_000.0),
        "km_per_gallon" to DoubleR(0.0, 500.0),
        "min_rdp_per_km" to DoubleR(0.5, 500.0),
        "min_rdp_per_hour" to DoubleR(10.0, 20_000.0),
        "max_pickup_km" to DoubleR(0.5, 50.0),
        "max_pickup_min" to IntR(1, 120),
        "min_score_threshold" to IntR(0, 99),
        "excellent_score_threshold" to IntR(1, 100),
        "overlay_duration_seconds" to IntR(5, 60),
        "grace_window_seconds" to IntR(10, 600),
        "workday_start_hour" to IntR(0, 23),
        "weight_km_pct" to IntR(0, 100),
        "weight_hour_pct" to IntR(0, 100),
        "weight_pickup_km_pct" to IntR(0, 100),
        "weight_pickup_min_pct" to IntR(0, 100),
        "work_share_pct" to IntR(0, 100),
        "night_from_hour" to IntR(0, 23),
        "night_to_hour" to IntR(0, 23),
        "operating_mode" to Str(setOf("OBSERVER", "ASSISTED", "AUTO")),
        "offer_alerts" to Str(setOf("ALL", "GOOD", "OFF")),
        "distance_mode" to Str(setOf("AUTO_AND_MANUAL", "MANUAL_ONLY", "AUTO_ONLY")),
        "week_start_day" to Str(setOf("MONDAY", "SUNDAY")),
        "app_theme" to Str(setOf("SYSTEM", "LIGHT", "DARK", "NIGHT", "SUN", "VIOLET")),
        "show_overlay" to Bool,
        "distance_enabled" to Bool,
        "route_enabled" to Bool,
        "goal_notification" to Bool,
        "weekly_report_notification" to Bool,
        "maintenance_notification" to Bool,
        "auto_night" to Bool,
        "daily_goal_minor" to LongR(0, 100_000_000L),
        "weekly_goal_minor" to LongR(0, 100_000_000L),
        "settings_profiles" to Profiles,
        "active_profile_id" to Str(null, 100),
        "shadow_model_version_id" to Str(null, 100)
    )

    private const val INTERVAL_PREFIX = "maintenance_interval_km_"
    private val INTERVAL_RULE = LongR(0, 100_000L)

    private fun ruleFor(key: String): Rule? =
        RULES[key] ?: if (key.startsWith(INTERVAL_PREFIX) && key.length > INTERVAL_PREFIX.length) INTERVAL_RULE else null

    private fun accept(rule: Rule, v: Any): Any? = when (rule) {
        is Bool -> v as? Boolean
        is IntR -> (v as? Int ?: (v as? Long)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt())?.takeIf { it in rule.min..rule.max }
        is LongR -> (v as? Long ?: (v as? Int)?.toLong())?.takeIf { it in rule.min..rule.max }
        is DoubleR -> (v as? Number)?.toDouble()?.takeIf { it.isFinite() && it >= rule.min && it <= rule.max }
        is Str -> (v as? String)?.takeIf { it.length <= rule.maxLen && (rule.allowed == null || it in rule.allowed) }
        is Profiles -> (v as? String)?.takeIf { text ->
            text.length <= 200_000 && SettingsProfileCodec.decode(text).size == text.lines().count { it.isNotBlank() }
        }
    }

    /**
     * Each restored setting must pass what Ajustes would accept. An invalid one is not written, so it
     * goes back to its default, and it is listed so the driver is told.
     */
    fun validateSettings(decoded: DecodedSettings): ValidatedSettings {
        val ok = java.util.TreeMap<String, Any>()
        val defaulted = ArrayList<String>()
        val ignored = ArrayList<String>()
        for (key in decoded.rejected) if (ruleFor(key) != null && !BackupFormat.isVolatile(key)) defaulted += key else ignored += key
        for ((key, value) in decoded.values) {
            val rule = ruleFor(key)
            if (rule == null || BackupFormat.isVolatile(key)) { ignored += key; continue }
            val accepted = accept(rule, value)
            if (accepted == null) defaulted += key else ok[key] = accepted
        }
        return ValidatedSettings(ok, defaulted.sorted(), ignored.sorted())
    }

    private val LABELS = mapOf(
        "cost_per_km" to "costo por km", "fuel_price_per_gallon" to "precio del combustible", "km_per_gallon" to "km por galón",
        "min_rdp_per_km" to "mínimo por km", "min_rdp_per_hour" to "mínimo por hora", "max_pickup_km" to "recogida máxima (km)",
        "max_pickup_min" to "recogida máxima (min)", "min_score_threshold" to "puntaje bueno", "excellent_score_threshold" to "puntaje excelente",
        "overlay_duration_seconds" to "duración del aviso", "daily_goal_minor" to "meta diaria", "weekly_goal_minor" to "meta semanal",
        "app_theme" to "tema", "operating_mode" to "modo", "settings_profiles" to "perfiles", "work_share_pct" to "porcentaje de trabajo"
    )

    fun label(key: String): String = LABELS[key] ?: key.replace('_', ' ')

    /** The line shown after a restore when some settings had to go back to their default. */
    fun defaultedText(defaulted: List<String>): String? =
        if (defaulted.isEmpty()) null
        else "${defaulted.size} " + (if (defaulted.size == 1) "ajuste tenía un valor inválido y volvió" else "ajustes tenían valores inválidos y volvieron") +
            " a su valor original: " + defaulted.joinToString(", ") { label(it) } + "."

    // --- the texts ---

    private val NOW_KEYS = listOf("detected_offers", "trips", "expense_entries")

    /** «Respaldo del 12/10/2026 8:30 p. m.: …. Ahora tienes: …. Lo que tienes ahora se reemplaza.» */
    fun comparisonText(backupDate: String, backupRows: Map<String, Int>, nowRows: Map<String, Int>): String =
        "Respaldo del $backupDate: ${BackupFormat.contentText(backupRows)}.\n" +
            "Ahora tienes: ${BackupFormat.contentText(nowRows.filterKeys { it in NOW_KEYS || it == "fuel_entries" })}.\n" +
            "Lo que tienes ahora se reemplaza."

    fun restoredText(rows: Map<String, Int>, migrated: Boolean, defaulted: List<String>): String =
        "Respaldo restaurado: ${BackupFormat.contentText(rows)}." +
            (if (migrated) " Era de una versión anterior y se actualizó." else "") +
            (defaultedText(defaulted)?.let { " $it" } ?: "")
}
