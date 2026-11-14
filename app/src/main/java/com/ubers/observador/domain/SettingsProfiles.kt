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

/** 1.44.0: the part of the day a profile is meant for. */
enum class ShiftContext(val storageKey: String, val label: String) {
    ANY("any", "Cualquier hora"),
    DAY("day", "De día (6:00 a 18:59)"),
    NIGHT("night", "De noche (19:00 a 5:59)");

    fun matches(hour: Int): Boolean = when (this) {
        ANY -> true
        DAY -> hour in NIGHT_END until NIGHT_START
        NIGHT -> hour >= NIGHT_START || hour < NIGHT_END
    }

    companion object {
        const val NIGHT_START = 19
        const val NIGHT_END = 6
        fun fromStorage(key: String?) = entries.firstOrNull { it.storageKey == key } ?: ANY
    }
}

/**
 * 1.44.0: when a profile fits. Every condition set must hold; an empty context never suggests
 * anything. It only SUGGESTS: the driver confirms before anything changes.
 */
data class ProfileContext(
    val shift: ShiftContext = ShiftContext.ANY,
    /** Uber's modality as read on the card («Moto», «Moto Flash»); null = any. */
    val product: String? = null,
    /** The vehicle (1.43.0) the profile belongs to; null = any. */
    val vehicleId: String? = null
) {
    val isEmpty: Boolean get() = shift == ShiftContext.ANY && product == null && vehicleId == null
    val conditions: Int get() = listOf(shift != ShiftContext.ANY, product != null, vehicleId != null).count { it }
}

/** A named set of [values]. [id] never changes, so renaming keeps it the same profile. */
data class SettingsProfile(
    val id: String,
    val name: String,
    val values: ProfileValues,
    /** 1.44.0: when to suggest it; not part of [values], so matching "in use" ignores it. */
    val context: ProfileContext = ProfileContext()
)

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
        val name = b64(p.name)
        // 1.44.0: context keys; an older app ignores them (unknown keys are skipped)
        val c = p.context
        val context = listOfNotNull(
            "cshift=${c.shift.storageKey}".takeIf { c.shift != ShiftContext.ANY },
            c.product?.let { "cprod=${b64(it)}" },
            c.vehicleId?.let { "cveh=${b64(it)}" }
        ).joinToString("") { ",$it" }
        "$VERSION|${p.id}|$name|$fields$context"
    }

    private fun b64(text: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray(Charsets.UTF_8))
    private fun unb64(text: String) = String(Base64.getUrlDecoder().decode(text), Charsets.UTF_8)

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
        val context = ProfileContext(
            shift = ShiftContext.fromStorage(map["cshift"]),
            product = map["cprod"]?.let { runCatching { unb64(it) }.getOrNull() }?.takeIf { it.isNotBlank() },
            vehicleId = map["cveh"]?.let { runCatching { unb64(it) }.getOrNull() }?.takeIf { it.isNotBlank() }
        )
        return SettingsProfile(parts[1], name, values, context)
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

    /** 1.44.0: sets when a profile should be suggested; the values themselves do not change. */
    fun setContext(existing: List<SettingsProfile>, id: String, context: ProfileContext): Result {
        val old = existing.firstOrNull { it.id == id } ?: return Result.Error("Ese perfil ya no existe")
        val clean = context.copy(product = context.product?.trim()?.takeIf { it.isNotEmpty() }?.take(NAME_MAX))
        val updated = old.copy(context = clean)
        return Result.Ok(existing.map { if (it.id == id) updated else it }, updated)
    }

    /** The profile whose values are exactly the ones in use, if any (preferring [preferredId]). */
    fun matching(existing: List<SettingsProfile>, current: ProfileValues, preferredId: String?): SettingsProfile? =
        existing.firstOrNull { it.id == preferredId && it.values == current } ?: existing.firstOrNull { it.values == current }
}

/** 1.44.0: what the app knows about the moment, to suggest a profile. */
data class ContextNow(
    val hour: Int,
    /** Modality of the latest real offer, only if it is recent (see [ContextProfiles.PRODUCT_RECENT_MS]). */
    val product: String?,
    val vehicleId: String?
)

data class ProfileSuggestion(val profile: SettingsProfile, val reason: String)

/**
 * 1.44.0: suggests the profile made for this moment. Never applies anything: the driver confirms.
 * No suggestion when the profile in use already fits, or when nothing fits.
 */
object ContextProfiles {

    /** A modality only counts while offers of it keep arriving. */
    const val PRODUCT_RECENT_MS = 30 * 60_000L

    fun fits(context: ProfileContext, now: ContextNow): Boolean =
        !context.isEmpty && context.shift.matches(now.hour) &&
            (context.product == null || context.product.equals(now.product, ignoreCase = true)) &&
            (context.vehicleId == null || context.vehicleId == now.vehicleId)

    fun suggest(profiles: List<SettingsProfile>, inUseId: String?, now: ContextNow): ProfileSuggestion? {
        val inUse = profiles.firstOrNull { it.id == inUseId }
        if (inUse != null && fits(inUse.context, now)) return null
        // The most specific fitting profile; on a tie, the first one in the list
        val best = profiles.filter { fits(it.context, now) }.maxByOrNull { it.context.conditions } ?: return null
        if (best.id == inUseId) return null
        return ProfileSuggestion(best, reason(best.context))
    }

    fun reason(c: ProfileContext): String = listOfNotNull(
        when (c.shift) { ShiftContext.DAY -> "es de día"; ShiftContext.NIGHT -> "es de noche"; ShiftContext.ANY -> null },
        c.product?.let { "te están llegando ofertas $it" },
        c.vehicleId?.let { "es para la moto que usas" }
    ).joinToString(" y ").replaceFirstChar { it.uppercase() }

    fun describe(c: ProfileContext, vehicleName: String?): String? = if (c.isEmpty) null else listOfNotNull(
        c.shift.label.takeIf { c.shift != ShiftContext.ANY },
        c.product?.let { "Modalidad $it" },
        c.vehicleId?.let { "Moto: ${vehicleName ?: "otra moto"}" }
    ).joinToString(" · ")
}
