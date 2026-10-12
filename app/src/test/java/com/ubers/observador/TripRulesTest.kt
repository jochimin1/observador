package com.ubers.observador

import com.ubers.observador.data.debug.TripTestSeeder
import com.ubers.observador.domain.lifecycle.EvidenceCode
import com.ubers.observador.domain.lifecycle.LifecycleReplay
import com.ubers.observador.domain.lifecycle.ReplayStep
import com.ubers.observador.domain.lifecycle.RideLifecycleEventType
import com.ubers.observador.domain.lifecycle.RideLifecycleEventType.ACCEPTANCE_CONFIRMED
import com.ubers.observador.domain.lifecycle.RideLifecycleEventType.ARRIVAL_CONFIRMED
import com.ubers.observador.domain.lifecycle.RideLifecycleEventType.LIFECYCLE_LOST
import com.ubers.observador.domain.lifecycle.RideLifecycleEventType.OFFER_DETECTED
import com.ubers.observador.domain.lifecycle.RideLifecycleEventType.SEARCHING_CONFIRMED
import com.ubers.observador.domain.lifecycle.RideLifecycleEventType.TRIP_CANCELLED
import com.ubers.observador.domain.lifecycle.RideLifecycleEventType.TRIP_COMPLETED
import com.ubers.observador.domain.lifecycle.RideLifecycleEventType.TRIP_STARTED
import com.ubers.observador.domain.lifecycle.RideLifecycleState
import com.ubers.observador.domain.lifecycle.RideLifecycleState.ARRIVED_OR_WAITING
import com.ubers.observador.domain.lifecycle.RideLifecycleState.COMPLETED
import com.ubers.observador.domain.lifecycle.RideLifecycleState.IDLE
import com.ubers.observador.domain.lifecycle.RideLifecycleState.IN_TRIP
import com.ubers.observador.domain.lifecycle.RideLifecycleState.OFFER_VISIBLE
import com.ubers.observador.domain.lifecycle.RideLifecycleState.TO_PICKUP
import com.ubers.observador.domain.lifecycle.RideLifecycleState.UNKNOWN
import com.ubers.observador.domain.trips.InMemoryTripLedger
import com.ubers.observador.domain.trips.IntegrityCode
import com.ubers.observador.domain.trips.TimelineVerdict
import com.ubers.observador.domain.trips.Trip
import com.ubers.observador.domain.trips.TripAnomalyCode
import com.ubers.observador.domain.trips.TripEventInput
import com.ubers.observador.domain.trips.TripEventType
import com.ubers.observador.domain.trips.TripIntegrityChecker
import com.ubers.observador.domain.trips.TripOfferSnapshot
import com.ubers.observador.domain.trips.TripPersistenceResult
import com.ubers.observador.domain.trips.TripRestorePolicy
import com.ubers.observador.domain.trips.TripStatus
import com.ubers.observador.domain.trips.TripTimeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fase 7B rules, on the in-memory ledger that applies exactly what the database applies. */
class TripRulesTest {

    private var tripIds = 0
    private var sideIds = 0
    private val ledger = InMemoryTripLedger({ "trip-${++tripIds}" }, { "side-${++sideIds}" })
    private var events = 0
    private val offer = TripOfferSnapshot(136.06, 4.1, 7, 7.2, 11, 76, 900L)

    private fun input(
        type: RideLifecycleEventType,
        from: RideLifecycleState,
        to: RideLifecycleState,
        at: Long,
        session: String = "s1",
        hash: String? = "H",
        recovery: Boolean = false,
        eventId: String = "e${++events}"
    ) = TripEventInput(eventId, session, hash, type, from, to, at, "IN_TRIP", 0.75, recovery, listOf("END_TRIP_ACTION"))

    private fun send(
        type: RideLifecycleEventType,
        from: RideLifecycleState,
        to: RideLifecycleState,
        at: Long,
        session: String = "s1",
        hash: String? = "H",
        recovery: Boolean = false
    ) = ledger.persist(input(type, from, to, at, session, hash, recovery), offer = offer)

    private fun normalRide(session: String = "s1", start: Long = 1_000L): Trip {
        send(ACCEPTANCE_CONFIRMED, OFFER_VISIBLE, TO_PICKUP, start, session)
        send(ARRIVAL_CONFIRMED, TO_PICKUP, ARRIVED_OR_WAITING, start + 100, session)
        send(TRIP_STARTED, ARRIVED_OR_WAITING, IN_TRIP, start + 200, session)
        send(TRIP_COMPLETED, IN_TRIP, COMPLETED, start + 300, session)
        return requireNotNull(ledger.tripForSession(session))
    }

    @Test
    fun `acceptance creates the trip`() {
        val result = send(ACCEPTANCE_CONFIRMED, OFFER_VISIBLE, TO_PICKUP, 1_000)
        assertEquals(TripPersistenceResult.Created("trip-1"), result)
        val trip = ledger.trips.single()
        assertEquals(TripStatus.TO_PICKUP, trip.status)
        assertEquals(1_000L, trip.acceptedAt ?: -1L)
        assertTrue(trip.startedFromKnownOffer)
        assertFalse(trip.recovered)
    }

    @Test
    fun `an offer alone never creates a trip`() {
        assertNull(TripEventInput.from(lifecycleEvent(OFFER_DETECTED, IDLE, OFFER_VISIBLE)))
        assertTrue(ledger.trips.isEmpty())
        val disappeared = send(RideLifecycleEventType.OFFER_DISAPPEARED, OFFER_VISIBLE, IDLE, 1_000)
        assertTrue(disappeared is TripPersistenceResult.NotApplicable)
        assertTrue(ledger.trips.isEmpty())
    }

    @Test
    fun `normal ride ends COMPLETED with every time and four events`() {
        val trip = normalRide()
        assertEquals(1, ledger.trips.size)
        assertEquals(TripStatus.COMPLETED, trip.status)
        assertEquals(listOf(1_000L, 1_100L, 1_200L, 1_300L), listOf(trip.acceptedAt, trip.arrivedAt, trip.tripStartedAt, trip.completedAt))
        assertNull(trip.cancelledAt)
        val timeline = ledger.eventsOf(trip.id)
        assertEquals(
            listOf(TripEventType.ACCEPTED, TripEventType.ARRIVED, TripEventType.TRIP_STARTED, TripEventType.TRIP_COMPLETED),
            timeline.map { it.type }
        )
        assertEquals(listOf(1, 2, 3, 4), timeline.map { it.sequence })
        assertEquals(TimelineVerdict.VALID, TripTimeline.validate(trip))
    }

    @Test
    fun `cancellation closes the trip as CANCELLED, never completed`() {
        send(ACCEPTANCE_CONFIRMED, OFFER_VISIBLE, TO_PICKUP, 1_000)
        send(TRIP_CANCELLED, TO_PICKUP, RideLifecycleState.CANCELLED, 1_500)
        val trip = ledger.trips.single()
        assertEquals(TripStatus.CANCELLED, trip.status)
        assertNull(trip.completedAt)
        assertEquals(1_500L, trip.cancelledAt ?: -1L)
    }

    @Test
    fun `searching with no ending seen interrupts the trip`() {
        send(ACCEPTANCE_CONFIRMED, OFFER_VISIBLE, TO_PICKUP, 1_000)
        send(TRIP_STARTED, TO_PICKUP, IN_TRIP, 1_200)
        send(LIFECYCLE_LOST, IN_TRIP, UNKNOWN, 1_300)
        send(SEARCHING_CONFIRMED, UNKNOWN, IDLE, 1_400)
        val trip = ledger.trips.single()
        assertEquals(TripStatus.INTERRUPTED, trip.status)
        assertNull(trip.completedAt)
        assertNull(trip.cancelledAt)
        assertEquals(1_400L, trip.interruptedAt ?: -1L)
    }

    @Test
    fun `recovered mid-trip invents no earlier time`() {
        val result = send(TRIP_STARTED, IDLE, IN_TRIP, 5_000, hash = null, recovery = true)
        assertEquals(TripPersistenceResult.Recovered("trip-1"), result)
        val trip = ledger.trips.single()
        assertTrue(trip.recovered)
        assertEquals(TripStatus.IN_TRIP, trip.status)
        assertNull(trip.acceptedAt)
        assertNull(trip.arrivedAt)
        assertNull(trip.tripStartedAt)
        assertNull(trip.relatedOfferHash)
        assertEquals(TripEventType.LIFECYCLE_RECOVERED, ledger.events.single().type)
        assertEquals(TimelineVerdict.RECOVERED_WITH_GAPS, TripTimeline.validate(trip))
    }

    @Test
    fun `a recovered trip completes as the same trip`() {
        send(TRIP_STARTED, IDLE, IN_TRIP, 5_000, hash = null, recovery = true)
        send(TRIP_COMPLETED, IN_TRIP, COMPLETED, 6_000, hash = null)
        assertEquals(1, ledger.trips.size)
        assertEquals("trip-1", ledger.trips.single().id)
        assertEquals(TripStatus.COMPLETED, ledger.trips.single().status)
    }

    @Test
    fun `fifty copies of one event make one row and keep the first time`() {
        send(ACCEPTANCE_CONFIRMED, OFFER_VISIBLE, TO_PICKUP, 1_000)
        val started = input(TRIP_STARTED, TO_PICKUP, IN_TRIP, 1_200, eventId = "same")
        val results = (0 until 50).map { i -> ledger.persist(started.copy(timestampMs = 1_200L + i)) }
        assertTrue(results.drop(1).all { it == TripPersistenceResult.DuplicateIgnored("same", logical = false) })
        assertEquals(1, ledger.events.count { it.eventId == "same" })
        assertEquals(1, ledger.trips.size)
        assertEquals(1_200L, ledger.trips.single().tripStartedAt ?: -1L)
    }

    @Test
    fun `the same stage with another event id is a logical duplicate and moves nothing`() {
        send(ACCEPTANCE_CONFIRMED, OFFER_VISIBLE, TO_PICKUP, 1_000)
        send(TRIP_STARTED, TO_PICKUP, IN_TRIP, 1_200)
        val again = send(TRIP_STARTED, TO_PICKUP, IN_TRIP, 1_250)
        assertTrue(again is TripPersistenceResult.DuplicateIgnored)
        assertEquals(1_200L, ledger.trips.single().tripStartedAt ?: -1L)
        assertEquals(2, ledger.events.size)
    }

    @Test
    fun `one session is one trip, two sessions are two trips with their own events`() {
        val a = normalRide("A", 1_000)
        val b = normalRide("B", 10_000)
        assertEquals(2, ledger.trips.size)
        assertFalse(a.id == b.id)
        assertTrue(ledger.eventsOf(a.id).all { it.lifecycleSessionId == "A" })
        assertTrue(ledger.eventsOf(b.id).all { it.lifecycleSessionId == "B" })
    }

    @Test
    fun `the same offer hash in two sessions is not taken for the same trip`() {
        normalRide("A", 1_000)
        normalRide("B", 10_000)
        assertEquals(2, ledger.trips.count { it.relatedOfferHash == "H" })
    }

    @Test
    fun `milestones are first-write-wins`() {
        send(ACCEPTANCE_CONFIRMED, OFFER_VISIBLE, TO_PICKUP, 1_000)
        val trip = ledger.trips.single()
        assertEquals(1_000L, TripAggregatorAccess.withAccepted(trip, 9_999).acceptedAt ?: -1L)
    }

    @Test
    fun `completed then cancelled is a terminal conflict, never an overwrite`() {
        normalRide()
        val result = send(TRIP_CANCELLED, IN_TRIP, RideLifecycleState.CANCELLED, 2_000)
        assertEquals(TripPersistenceResult.Anomaly("trip-1", TripAnomalyCode.TERMINAL_STATE_CONFLICT), result)
        val trip = ledger.trips.single()
        assertEquals(TripStatus.COMPLETED, trip.status)
        assertNull(trip.cancelledAt)
        assertTrue(trip.hasAnomalies)
        assertEquals(TripAnomalyCode.TERMINAL_STATE_CONFLICT, ledger.events.last().anomaly)
    }

    @Test
    fun `an event going backwards is kept as out of order`() {
        send(ACCEPTANCE_CONFIRMED, OFFER_VISIBLE, TO_PICKUP, 1_000)
        send(TRIP_STARTED, TO_PICKUP, IN_TRIP, 1_200)
        val result = send(ARRIVAL_CONFIRMED, TO_PICKUP, ARRIVED_OR_WAITING, 1_300)
        assertEquals(TripPersistenceResult.Anomaly("trip-1", TripAnomalyCode.EVENT_OUT_OF_ORDER), result)
        assertEquals(TripStatus.IN_TRIP, ledger.trips.single().status)
        assertNull(ledger.trips.single().arrivedAt)
    }

    @Test
    fun `a completion for an unknown session is an orphan and is not stored`() {
        val result = send(TRIP_COMPLETED, UNKNOWN, COMPLETED, 1_000, session = "ghost")
        assertEquals(TripPersistenceResult.Anomaly(null, TripAnomalyCode.ORPHAN_EVENT), result)
        assertTrue(ledger.events.isEmpty())
    }

    @Test
    fun `a second trip while one is open interrupts the first with an anomaly`() {
        send(ACCEPTANCE_CONFIRMED, OFFER_VISIBLE, TO_PICKUP, 1_000, session = "A")
        send(TRIP_STARTED, TO_PICKUP, IN_TRIP, 1_100, session = "A")
        send(ACCEPTANCE_CONFIRMED, OFFER_VISIBLE, TO_PICKUP, 2_000, session = "B")
        val a = requireNotNull(ledger.tripForSession("A"))
        val b = requireNotNull(ledger.tripForSession("B"))
        assertEquals(TripStatus.INTERRUPTED, a.status)
        assertTrue(a.hasAnomalies)
        assertEquals(TripAnomalyCode.MULTIPLE_ACTIVE_TRIPS, ledger.eventsOf(a.id).last().anomaly)
        assertEquals(TripStatus.TO_PICKUP, b.status)
        assertEquals(1, ledger.openTrips().size)
        assertEquals(1L, ledger.metrics.anomaliesDetected)
    }

    @Test
    fun `restart restores the open trip, marks it lost, and the same trip continues`() {
        send(ACCEPTANCE_CONFIRMED, OFFER_VISIBLE, TO_PICKUP, 1_000)
        send(TRIP_STARTED, TO_PICKUP, IN_TRIP, 1_200)
        val restored = ledger.restore(5_000)
        assertEquals("trip-1", restored?.id)
        assertEquals(TripStatus.UNKNOWN, restored?.status)
        assertEquals(RideLifecycleState.TO_PICKUP, TripRestorePolicy.lifecycleStageOf(requireNotNull(restored)))
        // Uber still in the trip: 7A rejoins the same session
        send(TRIP_STARTED, UNKNOWN, IN_TRIP, 5_100, recovery = true)
        send(TRIP_COMPLETED, IN_TRIP, COMPLETED, 6_000)
        assertEquals(1, ledger.trips.size)
        val trip = ledger.trips.single()
        assertEquals(TripStatus.COMPLETED, trip.status)
        assertEquals(1_200L, trip.tripStartedAt ?: -1L)
        assertEquals(
            listOf(TripEventType.ACCEPTED, TripEventType.TRIP_STARTED, TripEventType.LIFECYCLE_LOST,
                TripEventType.LIFECYCLE_RECOVERED, TripEventType.TRIP_COMPLETED),
            ledger.eventsOf(trip.id).map { it.type }
        )
    }

    @Test
    fun `restart while Uber is searching interrupts the open trip`() {
        send(ACCEPTANCE_CONFIRMED, OFFER_VISIBLE, TO_PICKUP, 1_000)
        send(TRIP_STARTED, TO_PICKUP, IN_TRIP, 1_200)
        ledger.restore(5_000)
        send(SEARCHING_CONFIRMED, UNKNOWN, IDLE, 5_100)
        assertEquals(TripStatus.INTERRUPTED, ledger.trips.single().status)
    }

    @Test
    fun `a summary after a restart completes the same trip`() {
        send(ACCEPTANCE_CONFIRMED, OFFER_VISIBLE, TO_PICKUP, 1_000)
        send(TRIP_STARTED, TO_PICKUP, IN_TRIP, 1_200)
        ledger.restore(5_000)
        send(TRIP_COMPLETED, UNKNOWN, COMPLETED, 5_100, recovery = true)
        assertEquals(TripStatus.COMPLETED, ledger.trips.single().status)
        assertEquals(1, ledger.trips.size)
    }

    @Test
    fun `restart with no open trip restores nothing, and finished trips are never active`() {
        normalRide()
        assertNull(ledger.restore(9_000))
        assertTrue(ledger.openTrips().isEmpty())
        assertEquals(TripStatus.COMPLETED, ledger.trips.single().status)
    }

    @Test
    fun `two open trips at restart keep the newest and interrupt the other, deleting nothing`() {
        val older = trip("old", TripStatus.IN_TRIP, updatedAt = 1_000)
        val newer = trip("new", TripStatus.TO_PICKUP, updatedAt = 2_000)
        val plan = TripRestorePolicy.plan(listOf(older, newer), emptyMap(), 3_000) { "x${++sideIds}" }
        assertEquals("new", plan.active?.id)
        val updated = plan.write.updatedTrips.associateBy { it.id }
        assertEquals(TripStatus.INTERRUPTED, updated["old"]?.status)
        assertEquals(TripStatus.UNKNOWN, updated["new"]?.status)
        assertEquals(listOf(TripAnomalyCode.MULTIPLE_ACTIVE_TRIPS), plan.write.sideAnomalies)
        assertTrue(plan.write.newTrip == null)
    }

    @Test
    fun `timeline verdicts`() {
        val base = trip("t", TripStatus.COMPLETED, updatedAt = 0).copy(acceptedAt = 1, arrivedAt = 2, tripStartedAt = 3, completedAt = 4)
        assertEquals(TimelineVerdict.VALID, TripTimeline.validate(base))
        assertEquals(TimelineVerdict.INVALID_ORDER, TripTimeline.validate(base.copy(completedAt = 2)))
        assertEquals(TimelineVerdict.TERMINAL_CONFLICT, TripTimeline.validate(base.copy(cancelledAt = 5)))
        assertEquals(TimelineVerdict.MISSING_CONTEXT, TripTimeline.validate(base.copy(completedAt = null)))
        assertEquals(TimelineVerdict.RECOVERED_WITH_GAPS, TripTimeline.validate(base.copy(acceptedAt = null, arrivedAt = null)))
    }

    @Test
    fun `integrity checker finds orphans, two open trips, conflicts and bad order`() {
        normalRide()
        val clean = TripIntegrityChecker.check(ledger.trips, ledger.events)
        assertTrue(clean.isEmpty())
        val bad = listOf(
            trip("a", TripStatus.IN_TRIP, 1), trip("b", TripStatus.TO_PICKUP, 2),
            trip("c", TripStatus.COMPLETED, 3).copy(completedAt = 5, cancelledAt = 6),
            trip("d", TripStatus.COMPLETED, 4).copy(tripStartedAt = 9, completedAt = 5)
        )
        val codes = TripIntegrityChecker.check(bad, ledger.events).map { it.code }.toSet()
        assertEquals(
            setOf(IntegrityCode.ORPHAN_EVENT, IntegrityCode.MULTIPLE_OPEN_TRIPS, IntegrityCode.TERMINAL_CONFLICT, IntegrityCode.INVALID_ORDER),
            codes
        )
    }

    @Test
    fun `events are numbered in order inside each trip`() {
        val trip = normalRide()
        val seq = ledger.eventsOf(trip.id)
        assertEquals((1..seq.size).toList(), seq.map { it.sequence })
        assertTrue(seq.zipWithNext().all { (a, b) -> a.timestampMs <= b.timestampMs })
    }

    @Test
    fun `stored events hold codes and states, never screen text`() {
        normalRide()
        val dump = ledger.events.joinToString { it.toString() } + ledger.trips.joinToString { it.toString() }
        listOf("Ana", "Ficticia", "Calle", "Dejar a", "Recoger a").forEach { assertFalse(dump.contains(it)) }
        val codes = EvidenceCode.entries.map { it.name }.toSet()
        assertTrue(ledger.events.flatMap { it.evidenceCodes }.all { it in codes })
    }

    @Test
    fun `the original offer and score are kept on the trip`() {
        val trip = normalRide()
        assertEquals(offer, trip.offer)
        assertEquals(76, trip.offer?.scoreAtDetection)
    }

    @Test
    fun `storage keys are fixed and unknown keys never crash`() {
        assertEquals("IN_TRIP", TripStatus.IN_TRIP.storageKey)
        assertEquals(TripStatus.UNKNOWN, TripStatus.fromStorage("SOMETHING_NEW"))
        assertNull(TripEventType.fromStorage("SOMETHING_NEW"))
        assertTrue(TripStatus.entries.map { it.storageKey }.toSet().size == TripStatus.entries.size)
        // Evidence is stored by enum name: renaming one would orphan old rows
        assertEquals("END_TRIP_ACTION", EvidenceCode.END_TRIP_ACTION.name)
    }

    @Test
    fun `metrics count what happened`() {
        normalRide("A", 1_000)
        send(ACCEPTANCE_CONFIRMED, OFFER_VISIBLE, TO_PICKUP, 5_000, session = "B")
        send(TRIP_CANCELLED, TO_PICKUP, RideLifecycleState.CANCELLED, 5_100, session = "B")
        send(TRIP_STARTED, IDLE, IN_TRIP, 6_000, session = "C", recovery = true)
        val m = ledger.metrics
        assertEquals(3L, m.tripsCreated)
        assertEquals(1L, m.recoveredTrips)
        assertEquals(1L, m.tripsCompleted)
        assertEquals(1L, m.tripsCancelled)
        assertEquals(7L, m.eventsPersisted)
    }

    @Test
    fun `test seeder builds the five scenarios, all marked TEST and repeatable`() {
        val seed = TripTestSeeder.generate(100_000_000L)
        assertEquals(5, seed.trips.size)
        assertTrue(seed.trips.all { it.id.startsWith(TripTestSeeder.PREFIX) && it.lifecycleSessionId.startsWith(TripTestSeeder.PREFIX) })
        assertTrue(seed.events.all { it.eventId.startsWith(TripTestSeeder.PREFIX) && it.tripId.startsWith(TripTestSeeder.PREFIX) })
        assertEquals(
            listOf(TripStatus.COMPLETED, TripStatus.COMPLETED, TripStatus.CANCELLED, TripStatus.INTERRUPTED, TripStatus.COMPLETED),
            seed.trips.map { it.status }
        )
        assertTrue(seed.trips[1].recovered)
        assertTrue(seed.trips[4].hasAnomalies)
        assertEquals(seed, TripTestSeeder.generate(100_000_000L))
        assertTrue(TripIntegrityChecker.check(seed.trips, seed.events).none { it.code == IntegrityCode.ORPHAN_EVENT })
    }

    @Test
    fun `end to end, a 7A replay of a normal ride becomes one completed trip`() {
        val replay = LifecycleReplay.run(LifecycleFixtures.normalRide(), LifecycleFixtures.classifier)
        replay.events.mapNotNull(TripEventInput::from).forEach { ledger.persist(it, offer = offer) }
        val trip = ledger.trips.single()
        assertEquals(TripStatus.COMPLETED, trip.status)
        assertEquals(LifecycleFixtures.OFFER_HASH, trip.relatedOfferHash)
        assertFalse(trip.hasAnomalies)
        val byType = replay.events.associateBy { it.type }
        assertEquals(byType[RideLifecycleEventType.ACCEPTANCE_CONFIRMED]?.timestampMs, trip.acceptedAt)
        assertEquals(byType[RideLifecycleEventType.TRIP_COMPLETED]?.timestampMs, trip.completedAt)
        assertEquals(4, ledger.eventsOf(trip.id).size)
        assertEquals(TimelineVerdict.VALID, TripTimeline.validate(trip))
    }

    @Test
    fun `end to end, a 7A replay that opens mid-trip makes one recovered trip that completes`() {
        val steps = listOf(
            ReplayStep(1_000_000L, LifecycleFixtures.inTrip()),
            ReplayStep(1_060_000L, LifecycleFixtures.summary())
        )
        val replay = LifecycleReplay.run(steps, LifecycleFixtures.classifier)
        replay.events.mapNotNull(TripEventInput::from).forEach { ledger.persist(it) }
        val trip = ledger.trips.single()
        assertTrue(trip.recovered)
        assertEquals(TripStatus.COMPLETED, trip.status)
        assertNull(trip.acceptedAt)
    }

    // ---- helpers ----

    private fun trip(id: String, status: TripStatus, updatedAt: Long) = Trip(
        id = id, lifecycleSessionId = "session-$id", relatedOfferHash = null, status = status,
        recovered = false, startedFromKnownOffer = false, createdAt = updatedAt, updatedAt = updatedAt
    )

    private fun lifecycleEvent(type: RideLifecycleEventType, from: RideLifecycleState, to: RideLifecycleState) =
        com.ubers.observador.domain.lifecycle.RideLifecycleEvent(
            eventId = "x", type = type, timestampMs = 1, sessionId = "s", relatedOfferHash = "H",
            screenType = com.ubers.observador.domain.lifecycle.UberScreenType.OFFER, confidence = 0.9,
            recovered = false, from = from, to = to, evidence = emptyList()
        )

    /** First-write-wins checked directly on the aggregator rule. */
    private object TripAggregatorAccess {
        fun withAccepted(trip: Trip, at: Long) =
            com.ubers.observador.domain.trips.TripAggregator.withMilestone(trip, TripEventType.ACCEPTED, at)
    }
}
