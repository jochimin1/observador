package com.ubers.observador.data.debug

import com.ubers.observador.domain.lifecycle.RideLifecycleEventType
import com.ubers.observador.domain.lifecycle.RideLifecycleState
import com.ubers.observador.domain.trips.InMemoryTripLedger
import com.ubers.observador.domain.trips.Trip
import com.ubers.observador.domain.trips.TripDecisionSnapshot
import com.ubers.observador.domain.trips.TripEvent
import com.ubers.observador.domain.trips.TripEventInput
import com.ubers.observador.domain.trips.TripOfferSnapshot

/**
 * Debug-only TEST trips (Fase 7B), built by running fake lifecycle events through the same rules
 * real trips use, so they look exactly like stored data. Every trip id, session and event id
 * starts with [PREFIX]: "Borrar" removes only these (their events go with them through the
 * foreign key). Deterministic for a given [generate] time; loading twice replaces, never doubles.
 *
 * A. normal ride, B. recovered mid-trip, C. cancelled, D. interrupted, E. with an anomaly.
 */
object TripTestSeeder {

    const val PREFIX = "TEST-"
    private const val HOUR = 3_600_000L
    private const val MINUTE = 60_000L

    data class Seed(val trips: List<Trip>, val events: List<TripEvent>)

    fun generate(now: Long): Seed {
        var tripIds = 0
        var sideEvents = 0
        val ledger = InMemoryTripLedger(
            newTripId = { "${PREFIX}trip-${++tripIds}" },
            newEventId = { "${PREFIX}side-${++sideEvents}" }
        )
        val decision = TripDecisionSnapshot(6.0, 300.0, 6.0, 12, 60, 80)

        ride(ledger, "A", now - 5 * HOUR, decision, offer = true, steps = listOf(
            Step(ACCEPT, RideLifecycleState.OFFER_VISIBLE, RideLifecycleState.TO_PICKUP, 0),
            Step(ARRIVE, RideLifecycleState.TO_PICKUP, RideLifecycleState.ARRIVED_OR_WAITING, 6),
            Step(START, RideLifecycleState.ARRIVED_OR_WAITING, RideLifecycleState.IN_TRIP, 8),
            Step(COMPLETE, RideLifecycleState.IN_TRIP, RideLifecycleState.COMPLETED, 22)
        ))
        ride(ledger, "B", now - 4 * HOUR, decision, offer = false, steps = listOf(
            Step(START, RideLifecycleState.IDLE, RideLifecycleState.IN_TRIP, 0, recovery = true),
            Step(COMPLETE, RideLifecycleState.IN_TRIP, RideLifecycleState.COMPLETED, 11)
        ))
        ride(ledger, "C", now - 3 * HOUR, decision, offer = true, steps = listOf(
            Step(ACCEPT, RideLifecycleState.OFFER_VISIBLE, RideLifecycleState.TO_PICKUP, 0),
            Step(CANCEL, RideLifecycleState.TO_PICKUP, RideLifecycleState.CANCELLED, 4)
        ))
        ride(ledger, "D", now - 2 * HOUR, decision, offer = true, steps = listOf(
            Step(ACCEPT, RideLifecycleState.OFFER_VISIBLE, RideLifecycleState.TO_PICKUP, 0),
            Step(ARRIVE, RideLifecycleState.TO_PICKUP, RideLifecycleState.ARRIVED_OR_WAITING, 5),
            Step(START, RideLifecycleState.ARRIVED_OR_WAITING, RideLifecycleState.IN_TRIP, 7),
            // Back to searching with no ending seen
            Step(RideLifecycleEventType.SEARCHING_CONFIRMED, RideLifecycleState.UNKNOWN, RideLifecycleState.IDLE, 30)
        ))
        ride(ledger, "E", now - HOUR, decision, offer = true, steps = listOf(
            Step(ACCEPT, RideLifecycleState.OFFER_VISIBLE, RideLifecycleState.TO_PICKUP, 0),
            Step(START, RideLifecycleState.TO_PICKUP, RideLifecycleState.IN_TRIP, 6),
            Step(COMPLETE, RideLifecycleState.IN_TRIP, RideLifecycleState.COMPLETED, 18),
            // A contradiction after completion: kept as TERMINAL_STATE_CONFLICT, never overwritten
            Step(CANCEL, RideLifecycleState.IN_TRIP, RideLifecycleState.CANCELLED, 19)
        ))
        return Seed(ledger.trips, ledger.events)
    }

    private data class Step(
        val type: RideLifecycleEventType,
        val from: RideLifecycleState,
        val to: RideLifecycleState,
        val minute: Int,
        val recovery: Boolean = false
    )

    private fun ride(
        ledger: InMemoryTripLedger,
        name: String,
        start: Long,
        decision: TripDecisionSnapshot,
        offer: Boolean,
        steps: List<Step>
    ) {
        val hash = if (offer) "${PREFIX}136.06-4.1-7-7.2-11-$name" else null
        val snapshot = if (offer) TripOfferSnapshot(136.06, 4.1, 7, 7.2, 11, 76, start - MINUTE) else null
        steps.forEachIndexed { i, step ->
            ledger.persist(
                TripEventInput(
                    eventId = "${PREFIX}event-$name${i + 1}",
                    lifecycleSessionId = "${PREFIX}session-$name",
                    relatedOfferHash = hash,
                    lifecycleType = step.type,
                    from = step.from,
                    to = step.to,
                    timestampMs = start + step.minute * MINUTE,
                    screenType = null,
                    confidence = null,
                    recoveryTransition = step.recovery,
                    evidenceCodes = emptyList()
                ),
                offer = snapshot,
                decision = decision
            )
        }
    }

    private val ACCEPT = RideLifecycleEventType.ACCEPTANCE_CONFIRMED
    private val ARRIVE = RideLifecycleEventType.ARRIVAL_CONFIRMED
    private val START = RideLifecycleEventType.TRIP_STARTED
    private val COMPLETE = RideLifecycleEventType.TRIP_COMPLETED
    private val CANCEL = RideLifecycleEventType.TRIP_CANCELLED
}
