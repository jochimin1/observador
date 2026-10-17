package com.ubers.observador.domain.route

import com.ubers.observador.domain.distance.DistancePolicy
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Fase 8K: the route of each taken trip. Unlike 8E (totals only), here the positions are kept:
 * the driver chose it (Plan 8K, decision 1, 17/10). Only taken trips, only from «Llegar por …»
 * to the end of the trip, with the same positions and filters the kilometres already use.
 */

/** Which part of the trip a point belongs to. Fixed storage keys. */
enum class RoutePhase(val storageKey: String, val label: String) {
    /** Going to the rider, or waiting (from «Llegar por …»). */
    PICKUP("PICKUP", "Yendo a recoger"),
    /** With the rider («Iniciar viaje» seen). */
    PASSENGER("PASSENGER", "Con pasajero"),
    /** Not told apart: «Iniciar viaje» was never seen on this trip. */
    UNKNOWN("UNKNOWN", "Sin distinguir");

    companion object {
        fun fromStorage(key: String?): RoutePhase = entries.firstOrNull { it.storageKey == key } ?: UNKNOWN
    }
}

/** How much of the trip the route covers. */
enum class RouteCoverage(val storageKey: String, val label: String) {
    /** From «Llegar por …» to the end, with no gap of more than a minute. */
    FULL("FULL", "Completo"),
    /** Something is missing: the GPS came late, a gap, a restart or the 3-hour cap. */
    PARTIAL("PARTIAL", "Parcial"),
    /** Not one valid position: the GPS was off, without permission or without signal. */
    NO_GPS("NO_GPS", "Sin GPS");

    companion object {
        fun fromStorage(key: String?): RouteCoverage = entries.firstOrNull { it.storageKey == key } ?: PARTIAL
    }
}

enum class RouteStatus(val storageKey: String, val label: String) {
    OPEN("OPEN", "Grabando"),
    CLOSED("CLOSED", "Guardado"),
    /** Stopped at the 3-hour cap: what came after was not recorded. */
    CAPPED("CAPPED", "Recortado a 3 horas"),
    /** «Borrar recorrido»: the points are gone; times, places, km and money stay. */
    DELETED("DELETED", "Recorrido borrado");

    companion object {
        fun fromStorage(key: String?): RouteStatus = entries.firstOrNull { it.storageKey == key } ?: CLOSED
    }
}

/** Degrees as whole 1e-7 units (about 1 cm), like money in cents: exact to store and to compare. */
object E7 {
    const val SCALE = 10_000_000.0

    fun of(degrees: Double): Int = (degrees * SCALE).roundToLong().toInt()

    fun degrees(e7: Int): Double = e7 / SCALE

    /** 184_861_234 -> "18.4861234", -699_312_000 -> "-69.9312000": no floating point on the way out. */
    fun text(e7: Int): String {
        val v = e7.toLong()
        val a = abs(v)
        val sign = if (v < 0) "-" else ""
        return "$sign${a / 10_000_000}.${(a % 10_000_000).toString().padStart(7, '0')}"
    }
}

/**
 * One kept point. [seq] is its order inside the trip, given by the single writer when it is
 * stored (0 before). [accuracyDm] is the GPS accuracy in decimetres.
 */
data class RoutePoint(
    val seq: Int = 0,
    val atMs: Long,
    val latE7: Int,
    val lngE7: Int,
    val accuracyDm: Int,
    val phase: RoutePhase
) {
    val latitude: Double get() = E7.degrees(latE7)
    val longitude: Double get() = E7.degrees(lngE7)

    companion object {
        fun of(atMs: Long, latitude: Double, longitude: Double, accuracyM: Float, phase: RoutePhase) = RoutePoint(
            atMs = atMs,
            latE7 = E7.of(latitude),
            lngE7 = E7.of(longitude),
            accuracyDm = if (accuracyM.isNaN()) 0 else (accuracyM * 10f).toLong().coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
            phase = phase
        )
    }
}

/**
 * The summary of one trip's route (table trip_routes). Start is where the rider got in (the first
 * point with passenger); when «Iniciar viaje» was not seen, it is the first point and
 * [startWithPassenger] is false. End is the last point. Places are names from the GPS position
 * (with internet), filled in later.
 */
data class TripRouteSummary(
    val tripId: String,
    val status: RouteStatus = RouteStatus.OPEN,
    val coverage: RouteCoverage = RouteCoverage.FULL,
    val openedAt: Long,
    val closedAt: Long? = null,
    /** Positions that arrived during the trip, valid or not. */
    val pointsReceived: Int = 0,
    /** Points stored now (after simplifying, once closed). */
    val pointsKept: Int = 0,
    val firstAt: Long? = null,
    val lastAt: Long? = null,
    val secondsWithoutSignal: Long = 0,
    val startAt: Long? = null,
    val startLatE7: Int? = null,
    val startLngE7: Int? = null,
    val startWithPassenger: Boolean = false,
    val endAt: Long? = null,
    val endLatE7: Int? = null,
    val endLngE7: Int? = null,
    val startPlace: String? = null,
    val endPlace: String? = null,
    val updatedAt: Long
) {
    val isOpen: Boolean get() = status == RouteStatus.OPEN
    val hasPoints: Boolean get() = pointsKept > 0 && status != RouteStatus.DELETED

    /** Places still to be named from the GPS (needs internet). */
    val needsPlaces: Boolean
        get() = status != RouteStatus.OPEN && status != RouteStatus.DELETED &&
            ((startPlace == null && startLatE7 != null) || (endPlace == null && endLatE7 != null))
}

/** The rules of Fase 8K, adjustable in tests. The filters are 8E's own. */
data class RoutePolicy(
    val filters: DistancePolicy = DistancePolicy(),
    /** Points wait in memory at most this long: a phone switched off loses at most 30 s. */
    val flushMs: Long = 30_000L,
    /** Safety cap per trip. */
    val maxTripMs: Long = 3 * 3_600_000L,
    /** Simplifying removes points closer than this to the line. */
    val toleranceM: Double = 5.0,
    /** A trip closed by «Buscando» was over this long before (LifecycleConfig.searchEndsTripMs). */
    val searchEndsTripMs: Long = 45_000L
)

/** How a trip's route ends. */
data class RouteClose(
    val closedAt: Long,
    val coverage: RouteCoverage,
    /** Points after this are dropped (the 45 s Uber showed «Buscando» before the trip was closed). */
    val trimAfterMs: Long? = null,
    /** The trip was cancelled: its pickup points stay as pickup. */
    val cancelled: Boolean = false
)

/**
 * What one step asks to write for one trip (by lifecycle session; the writer finds the trip).
 * Counters are increments, so a route picked up again after a restart keeps adding up.
 */
data class RouteWrite(
    val session: String,
    /** The route starts now (a new row, or an earlier one reopened). */
    val openAt: Long? = null,
    val points: List<RoutePoint> = emptyList(),
    val receivedDelta: Int = 0,
    val gapSecondsDelta: Long = 0,
    /** The 3-hour cap was reached: nothing more is recorded for this trip. */
    val capped: Boolean = false,
    val close: RouteClose? = null
) {
    val isEmpty: Boolean
        get() = openAt == null && points.isEmpty() && receivedDelta == 0 && gapSecondsDelta == 0L && !capped && close == null
}

data class RouteUpdate(val writes: List<RouteWrite> = emptyList()) {
    val isEmpty: Boolean get() = writes.all { it.isEmpty }

    operator fun plus(other: RouteUpdate) = RouteUpdate(writes + other.writes)

    companion object {
        val NOTHING = RouteUpdate()
    }
}
