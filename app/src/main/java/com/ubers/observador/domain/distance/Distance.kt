package com.ubers.observador.domain.distance

import java.time.LocalDate
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/** Every rule of Fase 8E in one place, adjustable in tests. */
data class DistancePolicy(
    /** Positions less precise than this are discarded. */
    val maxAccuracyM: Float = 25f,
    /** A step faster than this (120 km/h) is a GPS jump, not a trip. */
    val maxSpeedMps: Double = 120.0 / 3.6,
    /** Two valid positions further apart in time than this leave a gap: its distance is only an estimate. */
    val signalGapMs: Long = 60_000L,
    /** Beyond this without signal nothing is estimated at all. */
    val maxGapMs: Long = 10 * 60_000L,
    /** While measuring, the open stretch is written at most this often (a lost phone loses at most this). */
    val checkpointMs: Long = 60_000L,
    /** How often the location provider is asked for a position. */
    val fixIntervalMs: Long = 5_000L
)

/**
 * One position, in memory only. It is never stored, logged or exported: only the metres between
 * positions are kept. [toString] hides the coordinates so a stray log line cannot leak them.
 */
class LocationFix(val timeMs: Long, val latitude: Double, val longitude: Double, val accuracyM: Float) {
    override fun toString(): String = "LocationFix(t=$timeMs, ±${accuracyM}m)"
}

/** Great-circle distance (Haversine) on the mean Earth radius. */
object Geo {
    const val EARTH_RADIUS_M = 6_371_008.8

    fun distanceM(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val la1 = Math.toRadians(aLat)
        val la2 = Math.toRadians(bLat)
        val dLat = la2 - la1
        val dLon = Math.toRadians(bLon - aLon)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(la1) * cos(la2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** In whole millimetres: each step is rounded once, so thousands of steps add up exactly. */
    fun distanceMm(a: LocationFix, b: LocationFix): Long =
        (distanceM(a.latitude, a.longitude, b.latitude, b.longitude) * 1000.0).roundToLong()
}

/** How a stretch started. Fixed storage keys. */
enum class SegmentOrigin(val storageKey: String) {
    /** On its own, while connected to Uber (8A sessions). */
    AUTO_ONLINE("AUTO_ONLINE"),
    /** With the "Iniciar recorrido" button. */
    MANUAL("MANUAL");

    companion object {
        fun fromStorage(key: String?): SegmentOrigin = entries.firstOrNull { it.storageKey == key } ?: MANUAL
    }
}

enum class SegmentEndReason(val storageKey: String) {
    WENT_OFFLINE("WENT_OFFLINE"),
    STOPPED("STOPPED"),
    PAUSED("PAUSED"),
    /** Cut at the start of the working day: the rest goes on in a new stretch. */
    WORKDAY_CHANGE("WORKDAY_CHANGE"),
    /** Still open after a restart: closed at its last checkpoint. */
    RESTARTED("RESTARTED"),
    DISABLED("DISABLED"),
    PERMISSION_LOST("PERMISSION_LOST");

    companion object {
        fun fromStorage(key: String?): SegmentEndReason? = entries.firstOrNull { it.storageKey == key }
    }
}

/** Where a metre goes. */
enum class DistanceBucket { PASSENGER, PICKUP, EMPTY, OFFLINE }

/** The stage of the trip under way, from the ride lifecycle (7A/7B). */
enum class TripStage { PICKUP, ON_TRIP }

/** What Observador knows when a position arrives: connected or not, and the trip under way. */
data class RideContext(
    val online: Boolean = false,
    /** The lifecycle session of the trip under way (7A), linked to its trip by the repository. */
    val tripSessionId: String? = null,
    val stage: TripStage? = null
) {
    val bucket: DistanceBucket
        get() = when {
            stage == TripStage.ON_TRIP -> DistanceBucket.PASSENGER
            stage == TripStage.PICKUP -> DistanceBucket.PICKUP
            online -> DistanceBucket.EMPTY
            else -> DistanceBucket.OFFLINE
        }
}

/**
 * One measured stretch. Metres are kept in millimetres so sums are exact; there are no
 * coordinates anywhere in it. [workday] is the working day it belongs to (a stretch that
 * crosses the start of the day is cut in two).
 */
data class DistanceSegment(
    val id: String,
    val workday: LocalDate,
    val startedAt: Long,
    val endedAt: Long? = null,
    val updatedAt: Long,
    val origin: SegmentOrigin,
    val endReason: SegmentEndReason? = null,
    /** Measured: steps between valid positions at most 60 s apart. */
    val measuredMm: Long = 0,
    /** "aprox. sin señal": straight lines over gaps of 1 to 10 minutes, never mixed with [measuredMm]. */
    val gapMm: Long = 0,
    val passengerMm: Long = 0,
    val pickupMm: Long = 0,
    val emptyMm: Long = 0,
    val offlineMm: Long = 0,
    val fixesUsed: Int = 0,
    val fixesDiscarded: Int = 0,
    val secondsWithoutSignal: Long = 0,
    val createdAt: Long
) {
    val isOpen: Boolean get() = endedAt == null

    fun add(bucket: DistanceBucket, mm: Long): DistanceSegment = when (bucket) {
        DistanceBucket.PASSENGER -> copy(measuredMm = measuredMm + mm, passengerMm = passengerMm + mm)
        DistanceBucket.PICKUP -> copy(measuredMm = measuredMm + mm, pickupMm = pickupMm + mm)
        DistanceBucket.EMPTY -> copy(measuredMm = measuredMm + mm, emptyMm = emptyMm + mm)
        DistanceBucket.OFFLINE -> copy(measuredMm = measuredMm + mm, offlineMm = offlineMm + mm)
    }
}

enum class TripDistanceCoverage(val storageKey: String) {
    /** Measured from acceptance to the end, with no gap. */
    FULL("FULL"),
    /** Some part was not measured (GPS off, a gap, measuring began mid-trip). */
    PARTIAL("PARTIAL");

    companion object {
        fun fromStorage(key: String?): TripDistanceCoverage = entries.firstOrNull { it.storageKey == key } ?: PARTIAL
    }
}

/** Real kilometres of one trip: pickup (acceptance to start) and trip (start to end). */
data class TripDistance(
    val tripSessionId: String,
    val pickupMm: Long = 0,
    val tripMm: Long = 0,
    val gapMm: Long = 0,
    val coverage: TripDistanceCoverage = TripDistanceCoverage.PARTIAL,
    val updatedAt: Long
)

/** The kilometres of one working day: the sum of its stretches. */
data class DayDistance(
    val workday: LocalDate,
    val segments: List<DistanceSegment> = emptyList(),
    val measuredMm: Long = 0,
    val gapMm: Long = 0,
    val passengerMm: Long = 0,
    val pickupMm: Long = 0,
    val emptyMm: Long = 0,
    val offlineMm: Long = 0
) {
    val measuring: Boolean get() = segments.any { it.isOpen }

    companion object {
        /** Sums the stretches of [workday]; others are ignored. A new day starts at 0. */
        fun of(workday: LocalDate, segments: List<DistanceSegment>): DayDistance {
            val day = segments.filter { it.workday == workday }.sortedBy { it.startedAt }
            return DayDistance(
                workday = workday,
                segments = day,
                measuredMm = day.sumOf { it.measuredMm },
                gapMm = day.sumOf { it.gapMm },
                passengerMm = day.sumOf { it.passengerMm },
                pickupMm = day.sumOf { it.pickupMm },
                emptyMm = day.sumOf { it.emptyMm },
                offlineMm = day.sumOf { it.offlineMm }
            )
        }
    }
}

/** Kilometre texts, one decimal, rounded half up from millimetres (never from a Double). */
object DistanceFormat {

    /** 84_349_000 mm -> "84.3". */
    fun km(mm: Long): String {
        val tenths = (mm.coerceAtLeast(0) + 50_000) / 100_000
        return "${tenths / 10}.${tenths % 10}"
    }

    /** "84.3 km medidos + 1.2 km aprox. sin señal", or just "84.3 km". */
    fun total(measuredMm: Long, gapMm: Long): String =
        if (gapMm >= 50_000) "${km(measuredMm)} km medidos + ${km(gapMm)} km aprox. sin señal" else "${km(measuredMm)} km"

    /** Share of [part] in [whole], 0-100, half up; null for an empty whole. */
    fun percent(part: Long, whole: Long): Int? = if (whole <= 0) null else ((part * 200 + whole) / (whole * 2)).toInt()
}
