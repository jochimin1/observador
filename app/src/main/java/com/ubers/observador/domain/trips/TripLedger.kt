package com.ubers.observador.domain.trips

/** Debug counters of the trip writer. Memory only. */
data class TripPersistenceMetrics(
    val tripsCreated: Long = 0,
    val recoveredTrips: Long = 0,
    val tripsCompleted: Long = 0,
    val tripsCancelled: Long = 0,
    val tripsInterrupted: Long = 0,
    val eventsPersisted: Long = 0,
    val duplicateEventsIgnored: Long = 0,
    val anomaliesDetected: Long = 0,
    val persistenceErrors: Long = 0
) {
    /** Counters after [write] was committed. */
    fun after(write: TripWrite): TripPersistenceMetrics {
        val stored = write.events.filter { it.anomaly == null }
        val created = write.newTrip
        return copy(
            tripsCreated = tripsCreated + if (created != null) 1 else 0,
            recoveredTrips = recoveredTrips + if (created?.recovered == true) 1 else 0,
            tripsCompleted = tripsCompleted + stored.count { it.type == TripEventType.TRIP_COMPLETED },
            tripsCancelled = tripsCancelled + stored.count { it.type == TripEventType.TRIP_CANCELLED },
            tripsInterrupted = tripsInterrupted + write.events.count { it.type == TripEventType.INTERRUPTED },
            eventsPersisted = eventsPersisted + write.events.size,
            duplicateEventsIgnored = duplicateEventsIgnored +
                if (write.result is TripPersistenceResult.DuplicateIgnored) 1 else 0,
            anomaliesDetected = anomaliesDetected + write.sideAnomalies.size +
                if (write.result is TripPersistenceResult.Anomaly) 1 else 0
        )
    }

    fun afterError(): TripPersistenceMetrics = copy(persistenceErrors = persistenceErrors + 1)
}

/**
 * Trips and events held in memory, written with exactly the rules the database uses
 * (TripAggregator). For JVM tests, and to build the debug TEST data so it is shaped like real
 * data. The database version is TripRepository: same decisions, inside a Room transaction.
 */
class InMemoryTripLedger(
    private val newTripId: () -> String,
    private val newEventId: () -> String
) {
    private val tripsById = LinkedHashMap<String, Trip>()
    private val storedEvents = ArrayList<TripEvent>()

    var metrics = TripPersistenceMetrics()
        private set

    val trips: List<Trip> get() = tripsById.values.toList()
    val events: List<TripEvent> get() = storedEvents.toList()

    fun eventsOf(tripId: String): List<TripEvent> = storedEvents.filter { it.tripId == tripId }.sortedBy { it.sequence }

    fun tripForSession(sessionId: String): Trip? = tripsById.values.firstOrNull { it.lifecycleSessionId == sessionId }

    fun openTrips(): List<Trip> = tripsById.values.filter { it.status.isOpen }

    fun persist(
        input: TripEventInput,
        offer: TripOfferSnapshot? = null,
        decision: TripDecisionSnapshot? = null
    ): TripPersistenceResult {
        val forSession = tripForSession(input.lifecycleSessionId)
        val others = openTrips().filter { it.lifecycleSessionId != input.lifecycleSessionId }
        val write = TripAggregator.decide(
            input = input,
            snapshot = TripSnapshot(
                tripForSession = forSession,
                otherOpenTrips = others,
                eventAlreadyStored = storedEvents.any { it.eventId == input.eventId },
                nextSequence = (listOfNotNull(forSession) + others).associate { it.id to nextSequence(it.id) },
                offer = offer,
                decision = decision
            ),
            newTripId = newTripId,
            newEventId = newEventId
        )
        apply(write)
        return write.result
    }

    /** Start-up recovery, as TripRepository.restoreOpenTrip does it. */
    fun restore(atMs: Long): Trip? {
        val open = openTrips()
        val plan = TripRestorePolicy.plan(open, open.associate { it.id to nextSequence(it.id) }, atMs, newEventId)
        apply(plan.write)
        return plan.active?.let { tripsById[it.id] }
    }

    /** Applies [write] like a transaction: checks first, then changes everything or nothing. */
    fun apply(write: TripWrite) {
        val ids = storedEvents.map { it.eventId }.toMutableSet()
        write.events.forEach { require(ids.add(it.eventId)) { "duplicate event id ${it.eventId}" } }
        write.newTrip?.let { trip ->
            require(trip.id !in tripsById) { "duplicate trip id ${trip.id}" }
            require(tripForSession(trip.lifecycleSessionId) == null) { "duplicate session ${trip.lifecycleSessionId}" }
        }
        val known = tripsById.keys + listOfNotNull(write.newTrip?.id)
        write.events.forEach { require(it.tripId in known) { "event ${it.eventId} has no trip" } }
        write.newTrip?.let { tripsById[it.id] = it }
        write.updatedTrips.forEach { tripsById[it.id] = it }
        storedEvents += write.events
        metrics = metrics.after(write)
    }

    private fun nextSequence(tripId: String): Int = (storedEvents.filter { it.tripId == tripId }.maxOfOrNull { it.sequence } ?: 0) + 1
}
