package com.ubers.observador.domain.gps

import com.ubers.observador.domain.distance.DistanceBucket
import com.ubers.observador.domain.distance.DistancePolicy
import com.ubers.observador.domain.distance.Geo
import com.ubers.observador.domain.distance.LocationFix

/**
 * 1.45.0: how often the GPS is asked for positions.
 *
 * - [FIXED]: one position every 5 s in every phase, as up to 1.42.0. The default, because it is the
 *   one the kilometres and routes were validated with.
 * - [ADAPTIVE]: the same 5 s while moving with a trip under way; longer intervals when stopped or
 *   without a trip. Precision stays high in both: positions worse than 25 m are discarded by the
 *   kilometres, so a lower-precision request would only lose them.
 *
 * Which one uses less battery on this phone, with Uber open, is what «Batería medida» shows; the
 * default changes only with that evidence.
 */
enum class GpsFrequency(val storageKey: String, val label: String) {
    FIXED("FIXED", "Fija (cada 5 s)"),
    ADAPTIVE("ADAPTIVE", "Adaptativa (según fase y movimiento)");

    companion object {
        val DEFAULT = FIXED
        fun fromStorage(key: String?): GpsFrequency = entries.firstOrNull { it.storageKey == key } ?: DEFAULT
    }
}

/** What is asked of the location provider. Always high accuracy; only the timing changes. */
data class GpsRequest(val intervalMs: Long, val minIntervalMs: Long) {
    init {
        require(intervalMs > 0 && minIntervalMs in 1..intervalMs)
    }
}

object GpsPlan {

    /** Intervals of [GpsFrequency.ADAPTIVE], by phase, moving / stopped. */
    const val TRIP_MOVING_MS = 5_000L
    const val TRIP_STOPPED_MS = 10_000L
    const val EMPTY_MOVING_MS = 8_000L
    const val EMPTY_STOPPED_MS = 20_000L
    const val OFFLINE_MOVING_MS = 5_000L
    const val OFFLINE_STOPPED_MS = 15_000L

    /**
     * Every interval stays under [DistancePolicy.signalGapMs] (60 s): a longer one would turn every
     * step into «aprox. sin señal» and the kilometres would stop being measured.
     */
    fun of(frequency: GpsFrequency, phase: DistanceBucket, moving: Boolean, policy: DistancePolicy = DistancePolicy()): GpsRequest {
        val interval = when (frequency) {
            GpsFrequency.FIXED -> policy.fixIntervalMs
            GpsFrequency.ADAPTIVE -> when (phase) {
                DistanceBucket.PASSENGER, DistanceBucket.PICKUP -> if (moving) TRIP_MOVING_MS else TRIP_STOPPED_MS
                DistanceBucket.EMPTY -> if (moving) EMPTY_MOVING_MS else EMPTY_STOPPED_MS
                DistanceBucket.OFFLINE -> if (moving) OFFLINE_MOVING_MS else OFFLINE_STOPPED_MS
            }
        }.coerceAtMost(policy.signalGapMs / 2)
        return GpsRequest(interval, (interval / 2).coerceAtLeast(1_000L))
    }
}

/**
 * Whether the driver is moving, from the positions alone (no motion sensors). Stopped means every
 * valid position of the last [windowMs] stays within [radiusM] of the newest one; with too few
 * positions it says moving, so the GPS never slows down on a guess.
 */
class MovementDetector(
    private val windowMs: Long = 90_000L,
    private val radiusM: Double = 30.0,
    private val minFixes: Int = 4,
    private val maxAccuracyM: Float = DistancePolicy().maxAccuracyM
) {
    private val recent = ArrayDeque<LocationFix>()

    var moving: Boolean = true
        private set

    /** Returns whether [moving] changed. */
    fun onFix(fix: LocationFix): Boolean {
        if (fix.accuracyM.isNaN() || fix.accuracyM > maxAccuracyM) return false
        recent.addLast(fix)
        while (recent.isNotEmpty() && fix.timeMs - recent.first().timeMs > windowMs) recent.removeFirst()
        val spansWindow = recent.size >= minFixes && fix.timeMs - recent.first().timeMs >= windowMs * 2 / 3
        val still = spansWindow && recent.all { Geo.distanceM(it.latitude, it.longitude, fix.latitude, fix.longitude) <= radiusM }
        val now = !still
        if (now == moving) return false
        moving = now
        return true
    }

    fun reset() {
        recent.clear()
        moving = true
    }
}
