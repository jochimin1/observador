package com.ubers.observador.data

import androidx.room.withTransaction
import com.ubers.observador.data.db.AppDatabase
import com.ubers.observador.data.db.toDomain
import com.ubers.observador.data.db.toEntity
import com.ubers.observador.data.db.toTripOfferSnapshot
import com.ubers.observador.domain.trips.Trip
import com.ubers.observador.domain.trips.TripAggregator
import com.ubers.observador.domain.trips.TripDecisionSnapshot
import com.ubers.observador.domain.trips.TripEvent
import com.ubers.observador.domain.trips.TripEventInput
import com.ubers.observador.domain.trips.TripRestorePolicy
import com.ubers.observador.domain.trips.TripSnapshot
import com.ubers.observador.domain.trips.TripStatus
import com.ubers.observador.domain.trips.TripWrite
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.util.UUID

/** A trip with its timeline, for the debug detail. */
data class TripWithEvents(val trip: Trip, val events: List<TripEvent>)

/**
 * The only way trips are written. Each call reads what it needs, lets TripAggregator decide, and
 * writes the trip and its event(s) in one Room transaction: an event never exists without its
 * trip update, and a trip never changes without its event. Callers must be a single writer
 * (TripPersistenceCoordinator) so decisions never interleave.
 */
class TripRepository(private val db: AppDatabase) {

    private val trips = db.tripDao()
    private val offers = db.offerDao()

    suspend fun persist(input: TripEventInput, decision: TripDecisionSnapshot?): TripWrite = db.withTransaction {
        val forSession = trips.getByLifecycleSessionId(input.lifecycleSessionId)?.toDomain()
        val others = trips.getByStatuses(OPEN_STATUSES).map { it.toDomain() }
            .filter { it.lifecycleSessionId != input.lifecycleSessionId }
        val offer = if (forSession == null) {
            input.relatedOfferHash?.let { offers.getLatestByHash(it)?.toTripOfferSnapshot() }
        } else {
            null
        }
        val write = TripAggregator.decide(
            input = input,
            snapshot = TripSnapshot(
                tripForSession = forSession,
                otherOpenTrips = others,
                eventAlreadyStored = trips.eventExists(input.eventId),
                nextSequence = (listOfNotNull(forSession) + others).associate { it.id to trips.nextSequence(it.id) },
                offer = offer,
                decision = decision
            ),
            newTripId = ::newId,
            newEventId = ::newId
        )
        apply(write)
        write
    }

    /** Start-up: keeps at most one open trip (see TripRestorePolicy) and returns it. */
    suspend fun restoreOpenTrip(atMs: Long): TripRestorePolicy.Plan = db.withTransaction {
        val open = trips.getByStatuses(OPEN_STATUSES).map { it.toDomain() }
        val plan = TripRestorePolicy.plan(open, open.associate { it.id to trips.nextSequence(it.id) }, atMs, ::newId)
        apply(plan.write)
        plan
    }

    /** Debug only: swaps the TEST trips for [testTrips] and their events, in one transaction. */
    suspend fun replaceTestTrips(prefix: String, testTrips: List<Trip>, events: List<TripEvent>) = db.withTransaction {
        require(testTrips.all { it.id.startsWith(prefix) }) { "test trips must start with $prefix" }
        trips.deleteTripsByIdPrefix(prefix)
        testTrips.forEach { trips.insertTrip(it.toEntity()) }
        events.forEach { trips.insertEvent(it.toEntity()) }
    }

    /** Debug only: deletes the TEST trips and, through the foreign key, their events. */
    suspend fun deleteTestTrips(prefix: String): Int = trips.deleteTripsByIdPrefix(prefix)

    fun observeActiveTrip(): Flow<Trip?> = trips.observeActiveTrip(OPEN_STATUSES).map { it?.toDomain() }

    /** Recent trips with their event counts; events themselves are not loaded. */
    fun observeRecentTrips(limit: Int): Flow<List<Pair<Trip, Int>>> =
        combine(trips.observeRecentTrips(limit), trips.observeEventCounts()) { list, counts ->
            val byTrip = counts.associate { it.tripId to it.count }
            list.map { it.toDomain() to (byTrip[it.id] ?: 0) }
        }

    fun observeCompletedSince(from: Long): Flow<List<Trip>> =
        trips.observeCompletedSince(from).map { list -> list.map { it.toDomain() } }

    fun observeTripsBetween(from: Long, to: Long): Flow<List<Trip>> =
        trips.observeTripsBetween(from, to).map { list -> list.map { it.toDomain() } }

    fun observeTripWithEvents(id: String): Flow<TripWithEvents?> =
        combine(trips.observeTrip(id), trips.observeEventsForTrip(id)) { trip, events ->
            trip?.let { TripWithEvents(it.toDomain(), events.mapNotNull { e -> e.toDomain() }) }
        }

    fun observeCounts(): Flow<Pair<Int, Int>> =
        combine(trips.observeTripCount(), trips.observeEventCount()) { t, e -> t to e }

    /** Fase 8F: stores the rider's name on the trip of [sessionId]; false when there is none or it was already stored. */
    suspend fun setRiderName(sessionId: String, name: String): Boolean = trips.setRiderName(sessionId, name) > 0

    suspend fun getTripByLifecycleSessionId(sessionId: String): Trip? =
        trips.getByLifecycleSessionId(sessionId)?.toDomain()

    /** Everything, for the debug integrity check. */
    suspend fun loadAll(): Pair<List<Trip>, List<TripEvent>> = db.withTransaction {
        trips.getAllTrips().map { it.toDomain() } to trips.getAllEvents().mapNotNull { it.toDomain() }
    }

    private suspend fun apply(write: TripWrite) {
        write.newTrip?.let { trips.insertTrip(it.toEntity()) }
        write.updatedTrips.forEach { trips.updateTrip(it.toEntity()) }
        write.events.forEach { trips.insertEvent(it.toEntity()) }
    }

    private fun newId(): String = UUID.randomUUID().toString()

    companion object {
        val OPEN_STATUSES: List<String> = TripStatus.entries.filter { it.isOpen }.map { it.storageKey }
    }
}
