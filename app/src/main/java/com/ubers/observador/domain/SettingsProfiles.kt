package com.ubers.observador.domain

import java.util.Base64

/**
 * The values a profile saves and restores: costs, minimums, score weights and cut-offs, and how
 * offers are shown. The working-day start hour is NOT part of a profile: it decides which day
 * each offer belongs to, and changing it with a profile would silently move the dashboard's days.
 */
data class ProfileValues(
    val fuelPricePerGallon: Double,
    val kmPerGallon: Double,
    /** The cost per km typed by hand (used when fuel price or consumption is 0). */
    val manualCostPerKm: Double,
    val minRdpPerKm: Double,
    val minRdpPerHour: Double,
    val maxPickupKm: Double,
    val maxPickupMin: Int,
    val goodFrom: Int,
    val excellentFrom: Int,
    val weights: ScoreWeights,
    val showOverlay: Boolean,
    val overlaySeconds: Int,
    val graceSeconds: Int,
    val mode: String
) {
    /** Same limits as the Ajustes fields: a profile can never bring in a value the screen would refuse. */
    val isValid: Boolean
        get() = fuelPricePerGallon in 0.0..SettingsLimits.FUEL_PRICE_MAX &&
            kmPerGallon in 0.0..SettingsLimits.KM_PER_GALLON_MAX &&
            manualCostPerKm in SettingsLimits.COST_PER_KM_MIN..SettingsLimits.COST_PER_KM_MAX &&
            minRdpPerKm in SettingsLimits.MIN_RDP_PER_KM_MIN..SettingsLimits.MIN_RDP_PER_KM_MAX &&
            minRdpPerHour in SettingsLimits.MIN_RDP_PER_HOUR_MIN..SettingsLimits.MIN_RDP_PER_HOUR_MAX &&
            maxPickupKm in SettingsLimits.PICKUP_KM_MIN..SettingsLimits.PICKUP_KM_MAX &&
            maxPickupMin in SettingsLimits.PICKUP_MIN_MIN..SettingsLimits.PICKUP_MIN_MAX &&
            goodFrom in SettingsLimits.GOOD_FROM_MIN..SettingsLimits.GOOD_FROM_MAX &&
            excellentFrom in SettingsLimits.EXCELLENT_FROM_MIN..SettingsLimits.EXCELLENT_FROM_MAX &&
            weights.isValid &&
            overlaySeconds in SettingsLimits.OVERLAY_SECONDS_MIN..SettingsLimits.OVERLAY_SECONDS_MAX &&
            graceSeconds in SettingsLimits.GRACE_SECONDS_MIN..SettingsLimits.GRACE_SECONDS_MAX &&
            mode in SettingsProfiles.MODES

    companion object {
        /** The app's own defaults, used to fill a key missing from an older saved profile. */
        val DEFAULTS = ProfileValues(
            fuelPricePerGallon = 0.0, kmPerGallon = 0.0, manualCostPerKm = 1.50,
            minRdpPerKm = 6.0, minRdpPerHour = 300.0, maxPickupKm = 6.0, maxPickupMin = 12,
            goodFrom = 60, excellentFrom = 80, weights = ScoreWeights.DEFAULT,
            showOverlay = true, overlaySeconds = 15, graceSeconds = 60, mode = "OBSERVER"
        )
    }
}

/** A named set of [values]. [id] never changes, so renaming keeps it the same profile. */
data class SettingsProfile(val id: String, val name: String, val values: ProfileValues)

/**
 * Text format for profiles, stored in DataStore. One line per profile:
 * `P1|<id>|<name in Base64>|key=value,key=value...`. Unknown keys are ignored and missing ones
 * take the defaults, so an older or newer app version can still read it; a broken line is skipped,
 * never fatal. The name is Base64 so any character (|, comma, accents, emoji) is safe.
 */
object SettingsProfileCodec {

    private const val VERSION = "P1"

    fun encode(profiles: List<SettingsProfile>): String = profiles.joinToString("\n") { p ->
        val v = p.values
        val fields = listOf(
            "fuel" to v.fuelPricePerGallon, "kmgal" to v.kmPerGallon, "cost" to v.manualCostPerKm,
            "minkm" to v.minRdpPerKm, "minh" to v.minRdpPerHour, "pkm" to v.maxPickupKm, "pmin" to v.maxPickupMin,
            "good" to v.goodFrom, "exc" to v.excellentFrom,
            "wkm" to v.weights.kmPct, "wh" to v.weights.hourPct, "wpkm" to v.weights.pickupKmPct, "wpmin" to v.weights.pickupMinPct,
            "ov" to v.showOverlay, "ovs" to v.overlaySeconds, "grace" to v.graceSeconds, "mode" to v.mode
        ).joinToString(",") { (k, value) -> "$k=$value" }
        val name = Base64.getUrlEncoder().withoutPadding().encodeToString(p.name.toByteArray(Charsets.UTF_8))
        "$VERSION|${p.id}|$name|$fields"
    }

    fun decode(text: String?): List<SettingsProfile> {
        if (text.isNullOrBlank()) return emptyList()
        return text.lines().mapNotNull { line -> runCatching { decodeLine(line) }.getOrNull() }
    }

    private fun decodeLine(line: String): SettingsProfile? {
        val parts = line.split('|')
        if (parts.size != 4 || parts[0] != VERSION || parts[1].isBlank()) return null
        val name = String(Base64.getUrlDecoder().decode(parts[2]), Charsets.UTF_8)
        val map = parts[3].split(',').mapNotNull { kv ->
            val i = kv.indexOf('=')
            if (i <= 0) null else kv.substring(0, i) to kv.substring(i + 1)
        }.toMap()
        val d = ProfileValues.DEFAULTS
        fun dbl(k: String, default: Double) = map[k]?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: default
        fun int(k: String, default: Int) = map[k]?.toIntOrNull() ?: default
        val values = ProfileValues(
            fuelPricePerGallon = dbl("fuel", d.fuelPricePerGallon),
            kmPerGallon = dbl("kmgal", d.kmPerGallon),
            manualCostPerKm = dbl("cost", d.manualCostPerKm),
            minRdpPerKm = dbl("minkm", d.minRdpPerKm),
            minRdpPerHour = dbl("minh", d.minRdpPerHour),
            maxPickupKm = dbl("pkm", d.maxPickupKm),
            maxPickupMin = int("pmin", d.maxPickupMin),
            goodFrom = int("good", d.goodFrom),
            excellentFrom = int("exc", d.excellentFrom),
            weights = ScoreWeights.of(int("wkm", 35), int("wh", 30), int("wpkm", 20), int("wpmin", 15)),
            showOverlay = map["ov"]?.toBooleanStrictOrNull() ?: d.showOverlay,
            overlaySeconds = int("ovs", d.overlaySeconds),
            graceSeconds = int("grace", d.graceSeconds),
            mode = map["mode"] ?: d.mode
        )
        return SettingsProfile(parts[1], name, values)
    }
}

/** Every rule for saving, renaming and deleting profiles. Pure: the screen only shows the result. */
object SettingsProfiles {

    const val MAX_PROFILES = 10
    const val NAME_MAX = 30
    val MODES = setOf("OBSERVER", "ASSISTED", "AUTO")

    sealed interface Result {
        data class Ok(val profiles: List<SettingsProfile>, val profile: SettingsProfile) : Result
        data class Error(val message: String) : Result
    }

    fun cleanName(raw: String): String = raw.trim().replace(Regex("\\s+"), " ")

    /** Null when the name is fine; otherwise what to tell the driver. */
    fun nameProblem(raw: String, existing: List<SettingsProfile>, exceptId: String? = null): String? {
        val name = cleanName(raw)
        return when {
            name.isEmpty() -> "Escribe un nombre"
            name.length > NAME_MAX -> "Máximo $NAME_MAX caracteres"
            existing.any { it.id != exceptId && it.name.equals(name, ignoreCase = true) } -> "Ya tienes un perfil con ese nombre"
            else -> null
        }
    }

    fun create(existing: List<SettingsProfile>, rawName: String, values: ProfileValues, newId: () -> String): Result {
        nameProblem(rawName, existing)?.let { return Result.Error(it) }
        if (existing.size >= MAX_PROFILES) return Result.Error("Máximo $MAX_PROFILES perfiles. Borra uno para guardar otro.")
        if (!values.isValid) return Result.Error("Hay un valor fuera de rango en Ajustes")
        val profile = SettingsProfile(newId(), cleanName(rawName), values)
        return Result.Ok(existing + profile, profile)
    }

    /** Saves the current values into an existing profile (same id, same name). */
    fun overwrite(existing: List<SettingsProfile>, id: String, values: ProfileValues): Result {
        val old = existing.firstOrNull { it.id == id } ?: return Result.Error("Ese perfil ya no existe")
        if (!values.isValid) return Result.Error("Hay un valor fuera de rango en Ajustes")
        val updated = old.copy(values = values)
        return Result.Ok(existing.map { if (it.id == id) updated else it }, updated)
    }

    fun rename(existing: List<SettingsProfile>, id: String, rawName: String): Result {
        val old = existing.firstOrNull { it.id == id } ?: return Result.Error("Ese perfil ya no existe")
        nameProblem(rawName, existing, exceptId = id)?.let { return Result.Error(it) }
        val updated = old.copy(name = cleanName(rawName))
        return Result.Ok(existing.map { if (it.id == id) updated else it }, updated)
    }

    fun delete(existing: List<SettingsProfile>, id: String): List<SettingsProfile> = existing.filterNot { it.id == id }

    /** The profile whose values are exactly the ones in use, if any (preferring [preferredId]). */
    fun matching(existing: List<SettingsProfile>, current: ProfileValues, preferredId: String?): SettingsProfile? =
        existing.firstOrNull { it.id == preferredId && it.values == current } ?: existing.firstOrNull { it.values == current }
}
