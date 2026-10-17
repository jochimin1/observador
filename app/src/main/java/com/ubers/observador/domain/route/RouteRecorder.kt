package com.ubers.observador.domain.route

import com.ubers.observador.domain.distance.Geo
import com.ubers.observador.domain.distance.LocationFix
import com.ubers.observador.domain.lifecycle.RideLifecycleState

/**
 * Fase 8K, pure and testable: turns the GPS positions that already arrive every 5 s (8E) into the
 * route of the taken trip under way.
 *
 * - Records only while a taken trip is open: from «Llegar por …» (TO_PICKUP) through waiting and
 *   the trip, until it closes (summary, cancellation, «Buscando» for 45 s, another trip). While the
 *   ride is lost from view (UNKNOWN) it pauses; nothing is recorded outside a trip.
 * - The same filters as the kilometres: accuracy worse than 25 m and steps faster than 120 km/h
 *   are dropped; a step shorter than the accuracy is GPS jitter and is not stored (standing at a
 *   light adds no points), unless the phase just changed.
 * - Points wait in memory and are handed out every 30 s, and at the end.
 * - 3 hours after it began, a trip's route stops (CAPPED).
 * - A trip closed by «Buscando» drops its last 45 s: Uber was already searching then.
 *
 * Not thread-safe by design: called from the main thread, like DistanceTracker.
 */
class RouteRecorder(
    private val policy: RoutePolicy = RoutePolicy(),
    /** The app starts it off until it has read «Grabar recorrido» from Ajustes. */
    startEnabled: Boolean = true
) {

    private class Acc(
        val session: String,
        val openedAt: Long,
        /** Seen from «Llegar por …»: nothing before it was missed. */
        val fromStart: Boolean
    ) {
        var phase: RoutePhase? = null
        var paused = false
        var capped = false
        /** A gap of more than a minute, or the GPS came late. */
        var interrupted = false
        var last: RoutePoint? = null
        var lastValidAt: Long? = null
        var lastFlushAt: Long = openedAt
        val pending = ArrayList<RoutePoint>()
        var received = 0
        var gapSeconds = 0L
    }

    var enabled: Boolean = startEnabled
        private set

    private var acc: Acc? = null

    /** The session being recorded, or null. */
    val recordingSession: String? get() = acc?.takeIf { !it.capped }?.session

    /** «Grabar recorrido» in Ajustes. Switching it off closes the route under way with what it has. */
    fun setEnabled(on: Boolean, nowMs: Long): RouteUpdate {
        if (on == enabled) return RouteUpdate.NOTHING
        enabled = on
        if (on) return RouteUpdate.NOTHING
        val current = acc ?: return RouteUpdate.NOTHING
        acc = null
        current.interrupted = true
        return RouteUpdate(listOf(close(current, nowMs, trim = false, cancelled = false)))
    }

    /** The ride lifecycle changed: [sessionId] and [state] as 7A sees them now. */
    fun onRide(sessionId: String?, state: RideLifecycleState, nowMs: Long): RouteUpdate {
        val writes = ArrayList<RouteWrite>()
        val current = acc
        if (current != null) {
            val sameTrip = sessionId == current.session
            when {
                sameTrip && state.isActiveTrip -> {
                    current.paused = false
                    current.phase = phaseOf(state)
                }
                sameTrip && state == RideLifecycleState.UNKNOWN -> {
                    // Lost from view: nothing is recorded until the ride is seen again
                    if (!current.paused) current.interrupted = true
                    current.paused = true
                }
                else -> {
                    // Over: summary or cancellation of this trip, back to searching, or another trip
                    acc = null
                    val bySearching = state == RideLifecycleState.IDLE
                    val cancelled = sameTrip && state == RideLifecycleState.CANCELLED
                    writes += close(current, nowMs, trim = bySearching, cancelled = cancelled)
                }
            }
        }
        if (acc == null && enabled && sessionId != null && state.isActiveTrip) {
            val opened = Acc(sessionId, nowMs, fromStart = state == RideLifecycleState.TO_PICKUP)
            opened.phase = phaseOf(state)
            acc = opened
            writes += RouteWrite(session = sessionId, openAt = nowMs)
        }
        return RouteUpdate(writes)
    }

    fun onFix(fix: LocationFix): RouteUpdate {
        val a = acc ?: return RouteUpdate.NOTHING
        if (a.capped || a.paused) return RouteUpdate.NOTHING
        val phase = a.phase ?: return RouteUpdate.NOTHING
        if (fix.timeMs - a.openedAt > policy.maxTripMs) {
            a.capped = true
            a.interrupted = true
            return RouteUpdate(listOf(flush(a, fix.timeMs).copy(capped = true)))
        }
        a.received++
        val f = policy.filters
        if (fix.accuracyM.isNaN() || fix.accuracyM > f.maxAccuracyM) return due(a, fix.timeMs)
        val last = a.last
        var stepMm = Long.MAX_VALUE
        if (last != null) {
            val dt = fix.timeMs - last.atMs
            if (dt <= 0) return due(a, fix.timeMs)
            stepMm = Geo.distanceMm(LocationFix(last.atMs, last.latitude, last.longitude, last.accuracyDm / 10f), fix)
            // A jump only within a minute: after a longer gap the new position is taken as it is (8E)
            if (dt <= f.signalGapMs && stepMm.toDouble() / dt > f.maxSpeedMps) return due(a, fix.timeMs)
        }
        // A valid position. A minute or more without one (or before the first) is a gap
        val quiet = fix.timeMs - (a.lastValidAt ?: a.openedAt)
        if (quiet > f.signalGapMs) {
            a.interrupted = true
            a.gapSeconds += quiet / 1000
        }
        a.lastValidAt = fix.timeMs
        if (last != null) {
            val jitterMm = (maxOf(last.accuracyDm / 10f, fix.accuracyM) * 1000f).toLong()
            if (stepMm < jitterMm && phase == last.phase) return due(a, fix.timeMs)
        }
        val point = RoutePoint.of(fix.timeMs, fix.latitude, fix.longitude, fix.accuracyM, phase)
        a.pending += point
        a.last = point
        return due(a, fix.timeMs)
    }

    /** Hands out the points of the last 30 s. */
    private fun due(a: Acc, nowMs: Long): RouteUpdate {
        if (nowMs - a.lastFlushAt < policy.flushMs) return RouteUpdate.NOTHING
        val w = flush(a, nowMs)
        return if (w.isEmpty) RouteUpdate.NOTHING else RouteUpdate(listOf(w))
    }

    private fun flush(a: Acc, nowMs: Long): RouteWrite {
        val w = RouteWrite(
            session = a.session,
            points = a.pending.toList(),
            receivedDelta = a.received,
            gapSecondsDelta = a.gapSeconds
        )
        a.pending.clear()
        a.received = 0
        a.gapSeconds = 0
        a.lastFlushAt = nowMs
        return w
    }

    private fun close(a: Acc, nowMs: Long, trim: Boolean, cancelled: Boolean): RouteWrite {
        // Silence up to the end counts too (the GPS went off, or never came)
        if (!a.capped && !a.paused && nowMs - (a.lastValidAt ?: a.openedAt) > policy.filters.signalGapMs) {
            a.interrupted = true
        }
        val coverage = if (a.fromStart && !a.interrupted) RouteCoverage.FULL else RouteCoverage.PARTIAL
        return flush(a, nowMs).copy(
            close = RouteClose(
                closedAt = nowMs,
                coverage = coverage,
                trimAfterMs = if (trim) nowMs - policy.searchEndsTripMs else null,
                cancelled = cancelled
            )
        )
    }

    private fun phaseOf(state: RideLifecycleState): RoutePhase = when (state) {
        RideLifecycleState.IN_TRIP -> RoutePhase.PASSENGER
        else -> RoutePhase.PICKUP
    }
}
