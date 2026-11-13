package com.ubers.observador.data

import com.ubers.observador.domain.backup.MaintenanceGate
import androidx.room.withTransaction
import com.ubers.observador.data.db.AppDatabase
import com.ubers.observador.data.db.toDomain
import com.ubers.observador.data.db.toEntity
import com.ubers.observador.data.db.toTripOfferSnapshot
import com.ubers.observador.domain.trips.CorrectionField
import com.ubers.observador.domain.trips.CorrectionOrigin
import com.ubers.observador.domain.trips.Trip
import com.ubers.observador.domain.trips.TripCorrection
import com.ubers.observador.domain.trips.TripCorrections
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

    suspend fun persist(input: TripEventInput, decision: TripDecisionSnapshot?): TripWrite = MaintenanceGate.shared.access {
        db.withTransaction {
            val forSession = trips.getByLifecycleSessionId(input.lifecycleSessionId)?.toDomain()
            val others = trips.getByStatuses(OPEN_STATUSES).map { it.toDomain() }
                .filter { it.lifecycleSessionId != input.lifecycleSessionId }
            val offer = if (forSession == null) {
                input.relatedOfferHash?.let { offers.getLatestByHashAtOrBefore(it, input.timestampMs)?.toTripOfferSnapshot() }
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
    }

    /** Start-up: keeps at most one open trip (see TripRestorePolicy) and returns it. */
    suspend fun restoreOpenTrip(atMs: Long): TripRestorePolicy.Plan = MaintenanceGate.shared.access {
        db.withTransaction {
            val open = trips.getByStatuses(OPEN_STATUSES).map { it.toDomain() }
            val plan = TripRestorePolicy.plan(open, open.associate { it.id to trips.nextSequence(it.id) }, atMs, ::newId)
            apply(plan.write)
            plan
        }
    }

    /** Debug only: swaps the TEST trips for [testTrips] and their events, in one transaction. */
    suspend fun replaceTestTrips(prefix: String, testTrips: List<Trip>, events: List<TripEvent>) = MaintenanceGate.shared.access {
        db.withTransaction {
            require(testTrips.all { it.id.startsWith(prefix) }) { "test trips must start with $prefix" }
            trips.deleteTripsByIdPrefix(prefix)
            testTrips.forEach { trips.insertTrip(it.toEntity()) }
            events.forEach { trips.insertEvent(it.toEntity()) }
        }
    }

    /** Debug only: deletes the TEST trips and, through the foreign key, their events (and their corrections). */
    suspend fun deleteTestTrips(prefix: String): Int = MaintenanceGate.shared.access {
        db.withTransaction {
            trips.deleteCorrectionsByTripIdPrefix(prefix)
            trips.deleteTripsByIdPrefix(prefix)
        }
    }

    fun observeActiveTrip(): Flow<Trip?> = trips.observeActiveTrip(OPEN_STATUSES).map { it?.toDomain() }

    /** Recent trips with their event counts; events themselves are not loaded. */
    fun observeRecentTrips(limit: Int): Flow<List<Pair<Trip, Int>>> =
        combine(trips.observeRecentTrips(limit), trips.observeEventCounts()) { list, counts ->
            val byTrip = counts.associate { it.tripId to it.count }
            list.map { it.toDomain() to (byTrip[it.id] ?: 0) }
        }

    fun observeCompletedSince(from: Long): Flow<List<Trip>> =
        trips.observeCompletedSince(from).map { list -> list.map { it.toDomain() } }

    fun observeCreatedSince(from: Long): Flow<List<Trip>> =
        trips.observeCreatedSince(from).map { list -> list.map { it.toDomain() } }

    fun observeTripsBetween(from: Long, to: Long): Flow<List<Trip>> =
        trips.observeTripsBetween(from, to).map { list -> list.map { it.toDomain() } }

    fun observeTripWithEvents(id: String): Flow<TripWithEvents?> =
        combine(trips.observeTrip(id), trips.observeEventsForTrip(id)) { trip, events ->
            trip?.let { TripWithEvents(it.toDomain(), events.mapNotNull { e -> e.toDomain() }) }
        }

    fun observeCounts(): Flow<Pair<Int, Int>> =
        combine(trips.observeTripCount(), trips.observeEventCount()) { t, e -> t to e }

    /**
     * Fase 8F: stores the rider's name read on Uber's screen; false when there is no trip, it was
     * already stored or the driver corrected it (the driver's value wins). 1.40.0: every change is
     * recorded with its origin and the previous value.
     */
    suspend fun setRiderName(sessionId: String, name: String, nowMs: Long = System.currentTimeMillis()): Boolean = MaintenanceGate.shared.access {
        db.withTransaction {
            val trip = trips.getByLifecycleSessionId(sessionId) ?: return@withTransaction false
            if (trip.riderName == name) return@withTransaction false
            if (trips.correctedByDriver(trip.id, CorrectionField.RIDER_NAME.storageKey)) return@withTransaction false
            if (trips.setRiderName(sessionId, name) == 0) return@withTransaction false
            trips.insertCorrection(TripCorrection(newId(), trip.id, nowMs, CorrectionField.RIDER_NAME, trip.riderName, name, CorrectionOrigin.UBER_SCREEN).toEntity())
            true
        }
    }

    // ---- 1.40.0: corrections by the driver (Centro de Viajes) ----

    /** The driver's name for the rider (null or blank removes it). False when nothing changes. */
    suspend fun correctRiderName(tripId: String, name: String?, nowMs: Long): Boolean = MaintenanceGate.shared.access {
        db.withTransaction {
            val trip = trips.getById(tripId)?.toDomain() ?: return@withTransaction false
            val clean = TripCorrections.cleanName(name)
            if (clean == trip.riderName) return@withTransaction false
            trips.updateTrip(trip.copy(riderName = clean, updatedAt = nowMs).toEntity())
            trips.insertCorrection(TripCorrection(newId(), tripId, nowMs, CorrectionField.RIDER_NAME, trip.riderName, clean, CorrectionOrigin.DRIVER).toEntity())
            true
        }
    }

    /** Relabels a closed trip (see TripCorrections.correctStatus). False when not allowed. */
    suspend fun correctStatus(tripId: String, to: TripStatus, nowMs: Long, routeEndAt: Long? = null): Boolean = MaintenanceGate.shared.access {
        db.withTransaction {
            val trip = trips.getById(tripId)?.toDomain() ?: return@withTransaction false
            if (!TripCorrections.canCorrectStatus(trip, to)) return@withTransaction false
            val fixed = TripCorrections.correctStatus(trip, to, nowMs, routeEndAt)
            trips.updateTrip(fixed.toEntity())
            trips.insertCorrection(TripCorrection(newId(), tripId, nowMs, CorrectionField.STATUS,
                TripCorrections.encodeStatus(trip), TripCorrections.encodeStatus(fixed), CorrectionOrigin.DRIVER).toEntity())
            true
        }
    }

    /** Undoes the latest status correction, if it is the trip's current state. */
    suspend fun undoStatusCorrection(tripId: String, nowMs: Long): Boolean = MaintenanceGate.shared.access {
        db.withTransaction {
            val trip = trips.getById(tripId)?.toDomain() ?: return@withTransaction false
            val last = trips.latestCorrection(tripId, CorrectionField.STATUS.storageKey)?.toDomain() ?: return@withTransaction false
            if (last.origin != CorrectionOrigin.DRIVER || last.newValue != TripCorrections.encodeStatus(trip)) return@withTransaction false
            val old = TripCorrections.decodeStatus(last.oldValue) ?: return@withTransaction false
            val restored = TripCorrections.restoreStatus(trip, old, nowMs)
            trips.updateTrip(restored.toEntity())
            trips.insertCorrection(TripCorrection(newId(), tripId, nowMs, CorrectionField.STATUS,
                last.newValue, TripCorrections.encodeStatus(restored), CorrectionOrigin.DRIVER_UNDO).toEntity())
            true
        }
    }

    fun observeCorrections(tripId: String): Flow<List<TripCorrection>> =
        trips.observeCorrections(tripId).map { l -> l.mapNotNull { it.toDomain() } }

    /** Trips the driver changed by hand (Uber's own name readings do not count). */
    fun observeCorrectedTripIds(): Flow<Set<String>> = trips.observeCorrectedTripIds().map { it.toSet() }

    suspend fun getTripByLifecycleSessionId(sessionId: String): Trip? = MaintenanceGate.shared.access {
        trips.getByLifecycleSessionId(sessionId)?.toDomain()
    }

    /** Everything, for the debug integrity check. */
    suspend fun loadAll(): Pair<List<Trip>, List<TripEvent>> = MaintenanceGate.shared.access {
        db.withTransaction {
            trips.getAllTrips().map { it.toDomain() } to trips.getAllEvents().mapNotNull { it.toDomain() }
        }
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
