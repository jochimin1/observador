package com.ubers.observador.domain.trips

import com.ubers.observador.domain.lifecycle.RideLifecycleEventType
import com.ubers.observador.domain.lifecycle.RideLifecycleState

/** Explicit outcome of persisting one event. Duplicates are results, never exceptions. */
sealed interface TripPersistenceResult {
    data class Created(val tripId: String) : TripPersistenceResult
    data class Updated(val tripId: String) : TripPersistenceResult
    data class Recovered(val tripId: String) : TripPersistenceResult

    /** Same eventId already stored, or ([logical]) the same stage of the same trip seen again. */
    data class DuplicateIgnored(val eventId: String, val logical: Boolean) : TripPersistenceResult

    /** Not about a trip (no trip yet and the event does not start one, such as searching). */
    data class NotApplicable(val eventId: String) : TripPersistenceResult

    /** Stored as an anomaly ([tripId] null when there was no trip to attach it to: not stored). */
    data class Anomaly(val tripId: String?, val code: TripAnomalyCode) : TripPersistenceResult
}

/** What the decision reads, all inside the same write transaction. */
data class TripSnapshot(
    val tripForSession: Trip?,
    /** Open trips of other sessions (there should be none). */
    val otherOpenTrips: List<Trip> = emptyList(),
    val eventAlreadyStored: Boolean = false,
    /** Next sequence number per existing trip id. */
    val nextSequence: Map<String, Int> = emptyMap(),
    /** The related offer, looked up by hash, copied only when a trip is created. */
    val offer: TripOfferSnapshot? = null,
    val decision: TripDecisionSnapshot? = null
)

/** Everything one event changes, applied by the repository in a single transaction. */
data class TripWrite(
    val newTrip: Trip? = null,
    val updatedTrips: List<Trip> = emptyList(),
    val events: List<TripEvent> = emptyList(),
    val result: TripPersistenceResult,
    /** Anomalies raised on other trips (e.g. a second trip began while one was open). */
    val sideAnomalies: List<TripAnomalyCode> = emptyList()
) {
    val isEmpty: Boolean get() = newTrip == null && updatedTrips.isEmpty() && events.isEmpty()
}

/**
 * Turns a lifecycle event into database changes. Pure: no Room, no clock, no randomness except
 * the id factories passed in. Rules:
 * - an offer never creates a trip; acceptance, or joining a ride under way, does;
 * - one lifecycle session is one trip; a second open trip interrupts the first, with an anomaly;
 * - every milestone time is first-write-wins and never invented (recoveries leave gaps as null);
 * - COMPLETED and CANCELLED exclude each other: a late contradiction is stored as an anomaly;
 * - searching while a trip is open closes it as INTERRUPTED, never as completed or cancelled.
 */
object TripAggregator {

    fun decide(
        input: TripEventInput,
        snapshot: TripSnapshot,
        newTripId: () -> String,
        newEventId: () -> String
    ): TripWrite {
        if (snapshot.eventAlreadyStored) {
            return TripWrite(result = TripPersistenceResult.DuplicateIgnored(input.eventId, logical = false))
        }
        val type = eventTypeOf(input)
            ?: return TripWrite(result = TripPersistenceResult.NotApplicable(input.eventId))
        val trip = snapshot.tripForSession
        return if (trip == null) create(input, type, snapshot, newTripId, newEventId) else update(input, type, trip, snapshot)
    }

    /** Stored event type: a recovery transition is always LIFECYCLE_RECOVERED. */
    fun eventTypeOf(input: TripEventInput): TripEventType? {
        if (input.recoveryTransition) return TripEventType.LIFECYCLE_RECOVERED
        return when (input.lifecycleType) {
            RideLifecycleEventType.ACCEPTANCE_CONFIRMED -> TripEventType.ACCEPTED
            RideLifecycleEventType.PICKUP_NAVIGATION_CONFIRMED -> TripEventType.PICKUP_NAVIGATION
            RideLifecycleEventType.ARRIVAL_CONFIRMED -> TripEventType.ARRIVED
            RideLifecycleEventType.TRIP_STARTED -> TripEventType.TRIP_STARTED
            RideLifecycleEventType.TRIP_COMPLETED -> TripEventType.TRIP_COMPLETED
            RideLifecycleEventType.TRIP_CANCELLED -> TripEventType.TRIP_CANCELLED
            RideLifecycleEventType.LIFECYCLE_LOST -> TripEventType.LIFECYCLE_LOST
            // Back to searching, or an offer cleared: ends an open trip without saying how
            RideLifecycleEventType.SEARCHING_CONFIRMED,
            RideLifecycleEventType.OFFER_DISAPPEARED -> TripEventType.INTERRUPTED
            RideLifecycleEventType.OFFER_DETECTED -> null
        }
    }

    private fun create(
        input: TripEventInput,
        type: TripEventType,
        snapshot: TripSnapshot,
        newTripId: () -> String,
        newEventId: () -> String
    ): TripWrite {
        val target = TripStatus.of(input.to)
        val startsTrip = target != null && (
            target.stage in TripStatus.TO_PICKUP.stage..TripStatus.IN_TRIP.stage ||
                // Uber only shows a cancellation for an accepted trip
                (target == TripStatus.CANCELLED && input.from == RideLifecycleState.OFFER_VISIBLE)
            )
        if (!startsTrip) {
            val harmless = type == TripEventType.INTERRUPTED || type == TripEventType.LIFECYCLE_LOST
            return TripWrite(
                result = if (harmless) {
                    TripPersistenceResult.NotApplicable(input.eventId)
                } else {
                    TripPersistenceResult.Anomaly(null, TripAnomalyCode.ORPHAN_EVENT)
                }
            )
        }
        val at = input.timestampMs
        val known = input.relatedOfferHash != null && !input.recoveryTransition
        val trip = withMilestone(
            Trip(
                id = newTripId(),
                lifecycleSessionId = input.lifecycleSessionId,
                relatedOfferHash = input.relatedOfferHash,
                // Kotlin 2 knows target is non-null here: startsTrip checked it
                status = target,
                recovered = input.recoveryTransition,
                startedFromKnownOffer = known,
                createdAt = at,
                updatedAt = at,
                lastEventId = input.eventId,
                offer = if (input.relatedOfferHash != null) snapshot.offer else null,
                decision = snapshot.decision
            ),
            type, at
        )
        val interrupted = snapshot.otherOpenTrips.map { open ->
            val eventId = newEventId()
            open.copy(
                status = TripStatus.INTERRUPTED,
                interruptedAt = open.interruptedAt ?: at,
                hasAnomalies = true,
                updatedAt = at,
                lastEventId = eventId
            ) to TripEvent(
                eventId = eventId,
                tripId = open.id,
                lifecycleSessionId = open.lifecycleSessionId,
                type = TripEventType.INTERRUPTED,
                timestampMs = at,
                sequence = snapshot.nextSequence[open.id] ?: 1,
                screenType = input.screenType,
                confidence = null,
                recovered = false,
                previousStatus = open.status,
                resultingStatus = TripStatus.INTERRUPTED,
                evidenceCodes = emptyList(),
                anomaly = TripAnomalyCode.MULTIPLE_ACTIVE_TRIPS
            )
        }
        return TripWrite(
            newTrip = trip,
            updatedTrips = interrupted.map { it.first },
            events = interrupted.map { it.second } + eventOf(input, type, trip, sequence = 1, previous = null, resulting = target),
            result = if (input.recoveryTransition) TripPersistenceResult.Recovered(trip.id) else TripPersistenceResult.Created(trip.id),
            sideAnomalies = interrupted.map { TripAnomalyCode.MULTIPLE_ACTIVE_TRIPS }
        )
    }

    private fun update(input: TripEventInput, type: TripEventType, trip: Trip, snapshot: TripSnapshot): TripWrite {
        val current = trip.status
        val target = TripStatus.of(input.to)
        val sequence = snapshot.nextSequence[trip.id] ?: 1
        val terminalTarget = target?.takeIf { it == TripStatus.COMPLETED || it == TripStatus.CANCELLED }
        return when {
            current.isTerminal -> when {
                // Late but positive evidence of how an interrupted trip ended
                current == TripStatus.INTERRUPTED && terminalTarget != null -> move(input, type, trip, terminalTarget, sequence)
                terminalTarget == current -> logicalDuplicate(input)
                terminalTarget != null -> anomaly(input, type, trip, TripAnomalyCode.TERMINAL_STATE_CONFLICT, sequence)
                type == TripEventType.INTERRUPTED || type == TripEventType.LIFECYCLE_LOST ->
                    TripWrite(result = TripPersistenceResult.NotApplicable(input.eventId))
                else -> anomaly(input, type, trip, TripAnomalyCode.EVENT_OUT_OF_ORDER, sequence)
            }
            type == TripEventType.INTERRUPTED -> move(input, type, trip, TripStatus.INTERRUPTED, sequence)
            type == TripEventType.LIFECYCLE_LOST ->
                if (current == TripStatus.UNKNOWN) logicalDuplicate(input)
                else move(input, type, trip, TripStatus.UNKNOWN, sequence)
            target == null -> TripWrite(result = TripPersistenceResult.NotApplicable(input.eventId))
            current == TripStatus.UNKNOWN -> move(input, type, trip, target, sequence)
            target == current -> logicalDuplicate(input)
            target != TripStatus.CANCELLED && target.stage < current.stage ->
                anomaly(input, type, trip, TripAnomalyCode.EVENT_OUT_OF_ORDER, sequence)
            else -> move(input, type, trip, target, sequence)
        }
    }

    private fun move(input: TripEventInput, type: TripEventType, trip: Trip, target: TripStatus, sequence: Int): TripWrite {
        val at = input.timestampMs
        val updated = withMilestone(trip.copy(status = target, updatedAt = at, lastEventId = input.eventId), type, at)
        return TripWrite(
            updatedTrips = listOf(updated),
            events = listOf(eventOf(input, type, updated, sequence, previous = trip.status, resulting = target)),
            result = if (type == TripEventType.LIFECYCLE_RECOVERED) {
                TripPersistenceResult.Recovered(trip.id)
            } else {
                TripPersistenceResult.Updated(trip.id)
            }
        )
    }

    /** Stored for the audit trail; the trip keeps its status and no milestone is touched. */
    private fun anomaly(
        input: TripEventInput,
        type: TripEventType,
        trip: Trip,
        code: TripAnomalyCode,
        sequence: Int
    ): TripWrite {
        val updated = trip.copy(hasAnomalies = true, updatedAt = input.timestampMs, lastEventId = input.eventId)
        return TripWrite(
            updatedTrips = listOf(updated),
            events = listOf(eventOf(input, type, updated, sequence, trip.status, trip.status, code)),
            result = TripPersistenceResult.Anomaly(trip.id, code)
        )
    }

    private fun logicalDuplicate(input: TripEventInput) =
        TripWrite(result = TripPersistenceResult.DuplicateIgnored(input.eventId, logical = true))

    /** First observed time wins: a later or repeated event never moves a milestone. */
    fun withMilestone(trip: Trip, type: TripEventType, at: Long): Trip = when (type) {
        TripEventType.ACCEPTED -> trip.copy(acceptedAt = trip.acceptedAt ?: at)
        TripEventType.PICKUP_NAVIGATION -> trip.copy(pickupNavigationAt = trip.pickupNavigationAt ?: at)
        TripEventType.ARRIVED -> trip.copy(arrivedAt = trip.arrivedAt ?: at)
        TripEventType.TRIP_STARTED -> trip.copy(tripStartedAt = trip.tripStartedAt ?: at)
        TripEventType.TRIP_COMPLETED -> trip.copy(completedAt = trip.completedAt ?: at)
        TripEventType.TRIP_CANCELLED -> trip.copy(cancelledAt = trip.cancelledAt ?: at)
        TripEventType.INTERRUPTED -> trip.copy(interruptedAt = trip.interruptedAt ?: at)
        // Joining or losing a ride observes no stage moment
        TripEventType.LIFECYCLE_RECOVERED, TripEventType.LIFECYCLE_LOST -> trip
    }

    private fun eventOf(
        input: TripEventInput,
        type: TripEventType,
        trip: Trip,
        sequence: Int,
        previous: TripStatus?,
        resulting: TripStatus?,
        anomaly: TripAnomalyCode? = null
    ) = TripEvent(
        eventId = input.eventId,
        tripId = trip.id,
        lifecycleSessionId = input.lifecycleSessionId,
        type = type,
        timestampMs = input.timestampMs,
        sequence = sequence,
        screenType = input.screenType,
        confidence = input.confidence,
        recovered = input.recoveryTransition,
        previousStatus = previous,
        resultingStatus = resulting,
        evidenceCodes = input.evidenceCodes,
        anomaly = anomaly
    )
}

/**
 * What to do with open trips found at start-up. Normally zero or one. If a bug left several,
 * the most recently updated stays open and the others become INTERRUPTED with an anomaly:
 * nothing is deleted. The one kept is first marked LIFECYCLE_LOST (UNKNOWN): nothing about it
 * has been seen in this process yet, and the next ride screen picks it up again.
 */
object TripRestorePolicy {

    data class Plan(val active: Trip?, val write: TripWrite)

    fun plan(openTrips: List<Trip>, nextSequence: Map<String, Int>, atMs: Long, newEventId: () -> String): Plan {
        val ordered = openTrips.sortedWith(compareByDescending<Trip> { it.updatedAt }.thenByDescending { it.createdAt })
        val keep = ordered.firstOrNull() ?: return Plan(null, TripWrite(result = TripPersistenceResult.NotApplicable("restore")))
        val updates = ArrayList<Trip>()
        val events = ArrayList<TripEvent>()
        val anomalies = ArrayList<TripAnomalyCode>()
        ordered.drop(1).forEach { extra ->
            val id = newEventId()
            updates += extra.copy(
                status = TripStatus.INTERRUPTED,
                interruptedAt = extra.interruptedAt ?: atMs,
                hasAnomalies = true,
                updatedAt = atMs,
                lastEventId = id
            )
            events += restoreEvent(id, extra, TripEventType.INTERRUPTED, TripStatus.INTERRUPTED, nextSequence, atMs, TripAnomalyCode.MULTIPLE_ACTIVE_TRIPS)
            anomalies += TripAnomalyCode.MULTIPLE_ACTIVE_TRIPS
        }
        if (keep.status != TripStatus.UNKNOWN) {
            val id = newEventId()
            updates += keep.copy(status = TripStatus.UNKNOWN, updatedAt = atMs, lastEventId = id)
            events += restoreEvent(id, keep, TripEventType.LIFECYCLE_LOST, TripStatus.UNKNOWN, nextSequence, atMs, null)
        }
        return Plan(
            active = keep,
            write = TripWrite(
                updatedTrips = updates,
                events = events,
                result = TripPersistenceResult.Recovered(keep.id),
                sideAnomalies = anomalies
            )
        )
    }

    /** The stage to hand back to Fase 7A. UNKNOWN (stage unseen) restarts from the earliest one. */
    fun lifecycleStageOf(trip: Trip): RideLifecycleState = when (trip.status) {
        TripStatus.ARRIVED_OR_WAITING -> RideLifecycleState.ARRIVED_OR_WAITING
        TripStatus.IN_TRIP -> RideLifecycleState.IN_TRIP
        else -> RideLifecycleState.TO_PICKUP
    }

    private fun restoreEvent(
        id: String,
        trip: Trip,
        type: TripEventType,
        resulting: TripStatus,
        nextSequence: Map<String, Int>,
        atMs: Long,
        anomaly: TripAnomalyCode?
    ) = TripEvent(
        eventId = id,
        tripId = trip.id,
        lifecycleSessionId = trip.lifecycleSessionId,
        type = type,
        timestampMs = atMs,
        sequence = nextSequence[trip.id] ?: 1,
        screenType = null,
        confidence = null,
        recovered = false,
        previousStatus = trip.status,
        resultingStatus = resulting,
        evidenceCodes = emptyList(),
        anomaly = anomaly
    )
}

enum class TimelineVerdict { VALID, RECOVERED_WITH_GAPS, INVALID_ORDER, TERMINAL_CONFLICT, MISSING_CONTEXT }

object TripTimeline {

    /**
     * Gaps are allowed (a recovered trip has no acceptance time) and are not corruption; an
     * impossible order (finished before it started) or both endings at once are.
     */
    fun validate(trip: Trip): TimelineVerdict {
        if (trip.completedAt != null && trip.cancelledAt != null) return TimelineVerdict.TERMINAL_CONFLICT
        val ordered = listOfNotNull(
            trip.acceptedAt, trip.pickupNavigationAt, trip.arrivedAt, trip.tripStartedAt, trip.completedAt
        )
        if (ordered.zipWithNext().any { (a, b) -> b < a }) return TimelineVerdict.INVALID_ORDER
        val start = listOfNotNull(trip.acceptedAt, trip.pickupNavigationAt, trip.arrivedAt, trip.tripStartedAt).minOrNull()
        val ending = trip.cancelledAt ?: trip.interruptedAt
        if (start != null && ending != null && ending < start) return TimelineVerdict.INVALID_ORDER
        val missingEnd = when (trip.status) {
            TripStatus.COMPLETED -> trip.completedAt == null
            TripStatus.CANCELLED -> trip.cancelledAt == null
            TripStatus.INTERRUPTED -> trip.interruptedAt == null
            else -> false
        }
        if (missingEnd) return TimelineVerdict.MISSING_CONTEXT
        val expectsStart = trip.status == TripStatus.IN_TRIP || trip.status == TripStatus.COMPLETED
        val expectsArrival = expectsStart || trip.status == TripStatus.ARRIVED_OR_WAITING
        val gaps = trip.recovered ||
            (trip.status.stage >= TripStatus.TO_PICKUP.stage && trip.status != TripStatus.CANCELLED &&
                trip.status != TripStatus.INTERRUPTED && trip.acceptedAt == null) ||
            (expectsArrival && trip.arrivedAt == null) ||
            (expectsStart && trip.tripStartedAt == null)
        return if (gaps) TimelineVerdict.RECOVERED_WITH_GAPS else TimelineVerdict.VALID
    }
}

enum class IntegrityCode { ORPHAN_EVENT, MULTIPLE_OPEN_TRIPS, TERMINAL_CONFLICT, INVALID_ORDER, DUPLICATE_SESSION, DUPLICATE_EVENT_ID }

data class IntegrityIssue(val code: IntegrityCode, val tripId: String? = null, val eventId: String? = null)

/** Debug and test check over the whole history; not run on every start-up. */
object TripIntegrityChecker {

    fun check(trips: List<Trip>, events: List<TripEvent>): List<IntegrityIssue> {
        val issues = ArrayList<IntegrityIssue>()
        val ids = trips.map { it.id }.toSet()
        events.filter { it.tripId !in ids }.forEach { issues += IntegrityIssue(IntegrityCode.ORPHAN_EVENT, it.tripId, it.eventId) }
        events.groupBy { it.eventId }.filterValues { it.size > 1 }.keys
            .forEach { issues += IntegrityIssue(IntegrityCode.DUPLICATE_EVENT_ID, eventId = it) }
        trips.groupBy { it.lifecycleSessionId }.filterValues { it.size > 1 }.values
            .forEach { dup -> dup.forEach { issues += IntegrityIssue(IntegrityCode.DUPLICATE_SESSION, it.id) } }
        val open = trips.filter { it.status.isOpen }
        if (open.size > 1) open.forEach { issues += IntegrityIssue(IntegrityCode.MULTIPLE_OPEN_TRIPS, it.id) }
        trips.forEach { trip ->
            when (TripTimeline.validate(trip)) {
                TimelineVerdict.TERMINAL_CONFLICT -> issues += IntegrityIssue(IntegrityCode.TERMINAL_CONFLICT, trip.id)
                TimelineVerdict.INVALID_ORDER -> issues += IntegrityIssue(IntegrityCode.INVALID_ORDER, trip.id)
                else -> Unit
            }
        }
        return issues
    }
}
