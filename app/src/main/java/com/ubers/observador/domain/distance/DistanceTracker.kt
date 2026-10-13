package com.ubers.observador.domain.distance

import com.ubers.observador.domain.WorkdayClock
import java.util.UUID

/** When kilometres are measured (decision 12). Fixed storage keys. */
enum class DistanceMode(val storageKey: String) {
    /** On its own while connected to Uber, and with the button. */
    AUTO_AND_MANUAL("AUTO_AND_MANUAL"),
    MANUAL_ONLY("MANUAL_ONLY"),
    AUTO_ONLY("AUTO_ONLY");

    val allowsAuto: Boolean get() = this != MANUAL_ONLY
    val allowsManual: Boolean get() = this != AUTO_ONLY

    companion object {
        fun fromStorage(key: String?): DistanceMode = entries.firstOrNull { it.storageKey == key } ?: AUTO_AND_MANUAL
    }
}

/** Everything that decides whether the GPS should be on. */
data class DistanceConditions(
    val enabled: Boolean,
    val permitted: Boolean,
    val observerOn: Boolean,
    val mode: DistanceMode,
    /** An online session is open and Uber was seen within the tolerance. */
    val online: Boolean,
    val manualRequested: Boolean
)

object DistanceGate {

    /** How measuring should be running now, or null for GPS off. The button wins over "connected". */
    fun desired(c: DistanceConditions): SegmentOrigin? = when {
        !c.enabled || !c.permitted || !c.observerOn -> null
        c.manualRequested && c.mode.allowsManual -> SegmentOrigin.MANUAL
        c.online && c.mode.allowsAuto -> SegmentOrigin.AUTO_ONLINE
        else -> null
    }

    /** Why a stretch of [origin] ends when [c] no longer asks for it. */
    fun endReason(origin: SegmentOrigin, c: DistanceConditions): SegmentEndReason = when {
        !c.observerOn -> SegmentEndReason.PAUSED
        !c.enabled -> SegmentEndReason.DISABLED
        !c.permitted -> SegmentEndReason.PERMISSION_LOST
        origin == SegmentOrigin.AUTO_ONLINE && !c.online -> SegmentEndReason.WENT_OFFLINE
        else -> SegmentEndReason.STOPPED
    }
}

/** What one step asks to write: whole rows, in order. */
data class DistanceUpdate(
    val segments: List<DistanceSegment> = emptyList(),
    val trips: List<TripDistance> = emptyList()
) {
    val isEmpty: Boolean get() = segments.isEmpty() && trips.isEmpty()

    operator fun plus(other: DistanceUpdate) = DistanceUpdate(segments + other.segments, trips + other.trips)

    companion object {
        val NOTHING = DistanceUpdate()
    }
}

/** Counters for the debug panel. Memory only. */
data class DistanceDiagnostics(
    val fixesReceived: Long = 0,
    val discardedAccuracy: Long = 0,
    val discardedJump: Long = 0,
    val jitterIgnored: Long = 0,
    val gapsEstimated: Long = 0,
    val gapsTooLong: Long = 0
)

/**
 * The rules of Fase 8E, one position at a time, pure and testable.
 *
 * - Distance is the Haversine step between consecutive valid positions, in whole millimetres.
 * - Discarded: accuracy worse than 25 m; a step faster than 120 km/h (a jump). A step shorter
 *   than the accuracy of either position is GPS jitter: it adds nothing and the reference point
 *   stays (so standing at a light adds nothing, and slow movement still adds up later).
 * - More than 60 s between valid positions: that straight line is kept apart as "aprox. sin
 *   señal"; more than 10 minutes, nothing.
 * - Each metre goes to passenger, pickup, empty (online, no trip) or offline, by the ride context.
 * - A stretch that crosses the start of the working day is cut there; the new day starts at 0.
 * - Per trip (by lifecycle session): pickup and trip metres; FULL coverage only when measured from
 *   the pickup stage to the end of the trip with no gap.
 * - Positions live only in this object's memory: the last one, to measure the next step.
 *
 * Not thread-safe by design: called from the main thread.
 */
class DistanceTracker(
    private val policy: DistancePolicy = DistancePolicy(),
    private val newId: () -> String = { UUID.randomUUID().toString() }
) {

    private class TripAcc(
        val session: String,
        var pickupMm: Long = 0,
        var tripMm: Long = 0,
        var gapMm: Long = 0,
        /** Seen from the pickup stage while measuring. */
        val fromStart: Boolean,
        /** Measuring stopped, or a gap, while this trip went on. */
        var interrupted: Boolean = false
    )

    var open: DistanceSegment? = null
        private set

    var diagnostics = DistanceDiagnostics()
        private set

    private var clock: WorkdayClock? = null
    private var anchor: LocationFix? = null
    private var lastSavedAt: Long? = null
    private var context = RideContext()
    private var trip: TripAcc? = null

    /** Closes stretches left open by a previous process at their last checkpoint. */
    fun restore(stillOpen: List<DistanceSegment>): DistanceUpdate =
        DistanceUpdate(segments = stillOpen.map { it.copy(endedAt = it.updatedAt, endReason = SegmentEndReason.RESTARTED) })

    fun start(origin: SegmentOrigin, nowMs: Long, workdayClock: WorkdayClock): DistanceUpdate {
        clock = workdayClock
        val previous = open
        val closed = if (previous != null) close(previous, nowMs, SegmentEndReason.STOPPED) else DistanceUpdate.NOTHING
        val segment = newSegment(origin, nowMs, workdayClock)
        open = segment
        anchor = null
        lastSavedAt = nowMs
        return closed + DistanceUpdate(segments = listOf(segment))
    }

    fun stop(reason: SegmentEndReason, nowMs: Long): DistanceUpdate {
        val segment = open ?: return DistanceUpdate.NOTHING
        return close(segment, nowMs, reason)
    }

    /** The working-day hour changed in Ajustes: applies from the next position. */
    fun setClock(workdayClock: WorkdayClock) {
        clock = workdayClock
    }

    /** The ride changed (a trip began, moved on or ended), with or without a new position. */
    fun onContext(ctx: RideContext, nowMs: Long): DistanceUpdate {
        val previous = context
        context = ctx
        if (ctx.tripSessionId == previous.tripSessionId) return DistanceUpdate.NOTHING
        val out = ArrayList<TripDistance>()
        trip?.let { ended ->
            out += snapshot(ended, nowMs, finished = open != null)
            trip = null
        }
        val session = ctx.tripSessionId
        if (session != null) {
            trip = TripAcc(session, fromStart = open != null && ctx.stage == TripStage.PICKUP)
        }
        return DistanceUpdate(trips = out)
    }

    fun onFix(fix: LocationFix, ctx: RideContext): DistanceUpdate {
        var out = onContext(ctx, fix.timeMs)
        val current = open ?: return out
        val workdayClock = clock ?: return out
        diagnostics = diagnostics.copy(fixesReceived = diagnostics.fixesReceived + 1)
        if (fix.accuracyM > policy.maxAccuracyM || fix.accuracyM.isNaN()) {
            diagnostics = diagnostics.copy(discardedAccuracy = diagnostics.discardedAccuracy + 1)
            open = current.copy(fixesDiscarded = current.fixesDiscarded + 1, updatedAt = maxOf(current.updatedAt, fix.timeMs))
            return out + checkpoint(fix.timeMs)
        }
        // A new working day: the stretch is cut at its start and goes on in a new one
        var segment = current
        val day = workdayClock.workdayOf(fix.timeMs)
        if (day.isAfter(segment.workday)) {
            val boundary = workdayClock.startOf(day)
            out = out + close(segment, boundary, SegmentEndReason.WORKDAY_CHANGE, keepTrip = true)
            segment = newSegment(segment.origin, boundary, workdayClock)
            out = out + DistanceUpdate(segments = listOf(segment))
            lastSavedAt = fix.timeMs
        }
        segment = step(segment, fix)
        open = segment
        return out + checkpoint(fix.timeMs)
    }

    private fun step(segment: DistanceSegment, fix: LocationFix): DistanceSegment {
        val from = anchor
        if (from == null) {
            anchor = fix
            return segment.copy(fixesUsed = segment.fixesUsed + 1, updatedAt = fix.timeMs)
        }
        val dt = fix.timeMs - from.timeMs
        if (dt <= 0) {
            return segment.copy(fixesDiscarded = segment.fixesDiscarded + 1)
        }
        val mm = Geo.distanceMm(from, fix)
        if (dt > policy.signalGapMs) {
            anchor = fix
            val seconds = dt / 1000
            return if (dt <= policy.maxGapMs) {
                diagnostics = diagnostics.copy(gapsEstimated = diagnostics.gapsEstimated + 1)
                trip?.let { it.gapMm += mm; it.interrupted = true }
                segment.copy(gapMm = segment.gapMm + mm, secondsWithoutSignal = segment.secondsWithoutSignal + seconds,
                    fixesUsed = segment.fixesUsed + 1, updatedAt = fix.timeMs)
            } else {
                diagnostics = diagnostics.copy(gapsTooLong = diagnostics.gapsTooLong + 1)
                trip?.interrupted = true
                segment.copy(secondsWithoutSignal = segment.secondsWithoutSignal + seconds, fixesUsed = segment.fixesUsed + 1,
                    updatedAt = fix.timeMs)
            }
        }
        // mm / ms = m/s
        if (mm.toDouble() / dt > policy.maxSpeedMps) {
            diagnostics = diagnostics.copy(discardedJump = diagnostics.discardedJump + 1)
            return segment.copy(fixesDiscarded = segment.fixesDiscarded + 1, updatedAt = fix.timeMs)
        }
        val jitterMm = (maxOf(from.accuracyM, fix.accuracyM) * 1000f).toLong()
        if (mm < jitterMm) {
            // Standing still: nothing added, the reference point stays, its clock moves on
            diagnostics = diagnostics.copy(jitterIgnored = diagnostics.jitterIgnored + 1)
            anchor = LocationFix(fix.timeMs, from.latitude, from.longitude, minOf(from.accuracyM, fix.accuracyM))
            return segment.copy(fixesUsed = segment.fixesUsed + 1, updatedAt = fix.timeMs)
        }
        anchor = fix
        trip?.let { t ->
            when (context.stage) {
                TripStage.PICKUP -> t.pickupMm += mm
                TripStage.ON_TRIP -> t.tripMm += mm
                null -> Unit
            }
        }
        return segment.add(context.bucket, mm).copy(fixesUsed = segment.fixesUsed + 1, updatedAt = fix.timeMs)
    }

    private fun checkpoint(nowMs: Long): DistanceUpdate {
        val segment = open ?: return DistanceUpdate.NOTHING
        val due = lastSavedAt?.let { nowMs - it >= policy.checkpointMs } ?: true
        if (!due) return DistanceUpdate.NOTHING
        lastSavedAt = nowMs
        val trips = trip?.let { listOf(snapshot(it, nowMs, finished = false)) }.orEmpty()
        return DistanceUpdate(segments = listOf(segment), trips = trips)
    }

    private fun close(segment: DistanceSegment, atMs: Long, reason: SegmentEndReason, keepTrip: Boolean = false): DistanceUpdate {
        val end = maxOf(atMs, segment.startedAt)
        val done = segment.copy(endedAt = end, endReason = reason, updatedAt = maxOf(segment.updatedAt, end))
        open = null
        if (!keepTrip) anchor = null
        lastSavedAt = null
        val trips = if (!keepTrip) {
            trip?.let { t ->
                // Measuring stopped while the trip goes on: what follows is not measured
                t.interrupted = true
                listOf(snapshot(t, end, finished = false))
            }.orEmpty()
        } else {
            emptyList()
        }
        return DistanceUpdate(segments = listOf(done), trips = trips)
    }

    private fun snapshot(t: TripAcc, nowMs: Long, finished: Boolean) = TripDistance(
        tripSessionId = t.session,
        pickupMm = t.pickupMm,
        tripMm = t.tripMm,
        gapMm = t.gapMm,
        coverage = if (finished && t.fromStart && !t.interrupted && t.gapMm == 0L) TripDistanceCoverage.FULL else TripDistanceCoverage.PARTIAL,
        updatedAt = nowMs
    )

    private fun newSegment(origin: SegmentOrigin, startMs: Long, workdayClock: WorkdayClock) = DistanceSegment(
        id = newId(),
        workday = workdayClock.workdayOf(startMs),
        startedAt = startMs,
        updatedAt = startMs,
        origin = origin,
        createdAt = startMs
    )
}
