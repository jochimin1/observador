package com.ubers.observador.domain.route

import com.ubers.observador.domain.distance.DistanceFormat
import com.ubers.observador.domain.distance.TripDistance
import com.ubers.observador.domain.trips.Trip
import com.ubers.observador.domain.trips.TripOutcome
import com.ubers.observador.domain.trips.TripStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Names for a GPS position, from the parts the map service gives (Fase 8K, decision 10). */
object PlaceNames {

    /** "Av. Winston Churchill 95, Piantini"; the street, then the sector (or the city). Null with nothing usable. */
    fun of(thoroughfare: String?, subThoroughfare: String?, subLocality: String?, locality: String?, featureName: String?): String? {
        val street = thoroughfare?.trim()?.takeIf { it.isNotEmpty() }?.let { s ->
            subThoroughfare?.trim()?.takeIf { it.isNotEmpty() && it != s }?.let { "$s $it" } ?: s
        } ?: featureName?.trim()?.takeIf { it.isNotEmpty() && it.any(Char::isLetter) }
        val area = (subLocality ?: locality)?.trim()?.takeIf { it.isNotEmpty() }
        return listOfNotNull(street, area?.takeIf { it != street }).joinToString(", ").ifEmpty { null }
    }

    /** The last part, usually the sector: "Calle 5, Los Alcarrizos" -> "Los Alcarrizos". */
    fun short(place: String?): String? {
        val p = place?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return p.substringAfterLast(", ").trim().ifEmpty { p }
    }
}

/** What Uber offered for one part of the trip, and what was really done. */
data class OfferedVsReal(
    val label: String,
    val offeredKm: Double?,
    val offeredMin: Int?,
    val realMm: Long?,
    val realMs: Long?
) {
    /** "Uber dijo 3.9 km y 10 min; hiciste 4.6 km y 17 min". */
    val text: String
        get() {
            val said = listOfNotNull(offeredKm?.let { "%.1f km".format(java.util.Locale.US, it) }, offeredMin?.let { "$it min" }).joinToString(" y ")
            val did = listOfNotNull(realMm?.let { "${DistanceFormat.km(it)} km" }, realMs?.let { "${TripSheets.minutes(it)} min" }).joinToString(" y ")
            return when {
                said.isNotEmpty() && did.isNotEmpty() -> "Uber dijo $said; hiciste $did"
                said.isNotEmpty() -> "Uber dijo $said; real sin medir"
                did.isNotEmpty() -> "Hiciste $did"
                else -> "Sin datos"
            }
        }
}

/**
 * The trip's card: who, when and where it began and ended (Fase 8K). Places are the names of the
 * GPS points when there are any, and Uber's addresses otherwise; both are kept apart so the
 * screen can say which is which.
 */
data class TripSheet(
    val riderName: String?,
    val status: TripStatus,
    val acceptedAt: Long?,
    val arrivedAt: Long?,
    val startAt: Long?,
    /** False when «Iniciar viaje» was not seen: [startAt] is the acceptance. */
    val startDistinguished: Boolean,
    val endAt: Long?,
    val startPlace: String?,
    val endPlace: String?,
    val uberPickup: String?,
    val uberDropoff: String?,
    val comparisons: List<OfferedVsReal>,
    /** From acceptance to the end. */
    val totalMs: Long?
) {
    val startShown: String? get() = startPlace ?: uberPickup
    val endShown: String? get() = endPlace ?: uberDropoff
}

object TripSheets {

    private val HOUR = DateTimeFormatter.ofPattern("h:mm a")
    private val HOUR_SHORT = DateTimeFormatter.ofPattern("h:mm")

    fun of(trip: Trip, route: TripRouteSummary?, distance: TripDistance?): TripSheet {
        val accepted = trip.acceptedAt ?: trip.pickupNavigationAt ?: trip.createdAt
        val routeStart = route?.takeIf { it.startWithPassenger && it.status != RouteStatus.OPEN }?.startAt
        val start = trip.tripStartedAt ?: routeStart
        val distinguished = start != null
        val routeEnd = route?.takeIf { it.status != RouteStatus.OPEN }?.endAt
        val end = when (trip.status) {
            TripStatus.COMPLETED -> trip.completedAt ?: routeEnd
            TripStatus.CANCELLED -> trip.cancelledAt ?: routeEnd
            // Closed by «Buscando»: the last point (the 45 s of searching already dropped) is nearer the truth
            TripStatus.INTERRUPTED -> routeEnd ?: trip.interruptedAt
            else -> null
        }
        val offer = trip.offer
        val comparisons = ArrayList<OfferedVsReal>()
        if (distinguished) {
            comparisons += OfferedVsReal("Recogida", offer?.pickupKm, offer?.pickupMinutes, distance?.pickupMm, start!! - accepted)
            if (end != null && end >= start) {
                comparisons += OfferedVsReal("Viaje", offer?.tripKm, offer?.tripMinutes, distance?.tripMm, end - start)
            }
        } else if (end != null && trip.status != TripStatus.CANCELLED) {
            // Without «Iniciar viaje» the parts are not told apart: compare the whole
            comparisons += OfferedVsReal(
                "Total (recogida + viaje)",
                offer?.let { it.pickupKm + it.tripKm },
                offer?.let { it.pickupMinutes + it.tripMinutes },
                distance?.let { it.pickupMm + it.tripMm },
                end - accepted
            )
        }
        return TripSheet(
            riderName = trip.riderName,
            status = trip.status,
            acceptedAt = accepted,
            arrivedAt = trip.arrivedAt,
            startAt = start ?: accepted,
            startDistinguished = distinguished,
            endAt = end,
            startPlace = route?.startPlace,
            endPlace = route?.endPlace,
            uberPickup = offer?.pickupAddress,
            uberDropoff = offer?.dropoffAddress,
            comparisons = comparisons,
            totalMs = end?.let { it - accepted }?.takeIf { it >= 0 }
        )
    }

    /** «Stefany · 6:42 → 7:01 · Los Ríos → Los Prados», leaving out what is not known. */
    fun oneLine(s: TripSheet, zone: ZoneId): String {
        val times = listOfNotNull(s.startAt?.let { hourShort(it, zone) }, s.endAt?.let { hourShort(it, zone) }).joinToString(" → ")
        val from = PlaceNames.short(s.startShown)
        val to = PlaceNames.short(s.endShown)
        val places = when {
            from != null && to != null -> "$from → $to"
            from != null -> "desde $from"
            to != null -> "hasta $to"
            else -> null
        }
        return listOfNotNull(s.riderName?.trim()?.takeIf { it.isNotEmpty() }, times.ifEmpty { null }, places).joinToString(" · ")
    }

    fun hour(ms: Long, zone: ZoneId): String = Instant.ofEpochMilli(ms).atZone(zone).format(HOUR)

    fun hourShort(ms: Long, zone: ZoneId): String = Instant.ofEpochMilli(ms).atZone(zone).format(HOUR_SHORT)

    /** Whole minutes, rounded half up: 89 s -> 1, 90 s -> 2. */
    fun minutes(ms: Long): Long = (ms.coerceAtLeast(0) + 30_000) / 60_000

    /** "17 min" or "1 h 05 min". */
    fun duration(ms: Long): String {
        val m = minutes(ms)
        return if (m < 60) "$m min" else "${m / 60} h ${(m % 60).toString().padStart(2, '0')} min"
    }
}
