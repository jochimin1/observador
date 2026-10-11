package com.ubers.observador

import com.ubers.observador.LifecycleFixtures.OFFER_HASH
import com.ubers.observador.LifecycleFixtures.OTHER_OFFER_HASH
import com.ubers.observador.domain.lifecycle.DetectorHealth
import com.ubers.observador.domain.lifecycle.LifecycleConfig
import com.ubers.observador.domain.lifecycle.LifecycleDiagnostics
import com.ubers.observador.domain.lifecycle.LifecycleReplay
import com.ubers.observador.domain.lifecycle.ReplayStep
import com.ubers.observador.domain.lifecycle.RideLifecycleEvent
import com.ubers.observador.domain.lifecycle.RideLifecycleEventType
import com.ubers.observador.domain.lifecycle.RideLifecyclePipeline
import com.ubers.observador.domain.lifecycle.RideLifecycleState
import com.ubers.observador.domain.lifecycle.RideLifecycleState.ARRIVED_OR_WAITING
import com.ubers.observador.domain.lifecycle.RideLifecycleState.COMPLETED
import com.ubers.observador.domain.lifecycle.RideLifecycleState.IDLE
import com.ubers.observador.domain.lifecycle.RideLifecycleState.IN_TRIP
import com.ubers.observador.domain.lifecycle.RideLifecycleState.OFFER_VISIBLE
import com.ubers.observador.domain.lifecycle.RideLifecycleState.TO_PICKUP
import com.ubers.observador.domain.lifecycle.RideLifecycleState.UNKNOWN
import com.ubers.observador.domain.lifecycle.ScreenSnapshot
import com.ubers.observador.domain.lifecycle.SkipReason
import com.ubers.observador.domain.lifecycle.UberScreenType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The whole pipeline with a fake clock: what the service does, without Android. */
class RideLifecyclePipelineTest {

    private var now = 1_000_000L
    private var ids = 0
    private val config = LifecycleConfig()
    private val pipeline = RideLifecyclePipeline(
        classifier = LifecycleFixtures.classifier,
        clock = { now },
        config = config,
        newSessionId = { "s${++ids}" },
        newEventId = { "e${++ids}" }
    )
    private val events = ArrayList<RideLifecycleEvent>()

    /** Feeds a screen and, like the service, reads it again when asked to confirm. */
    private fun feed(snapshot: ScreenSnapshot, hash: String? = null, afterMs: Long = 100) {
        now += afterMs
        var result = pipeline.onSnapshot(snapshot, hash)
        events += result.events
        while (true) {
            val wait = result.recheckAfterMs ?: break
            now += wait
            result = pipeline.onSnapshot(snapshot, hash)
            events += result.events
        }
    }

    private fun feedOnce(snapshot: ScreenSnapshot, hash: String? = null, afterMs: Long = 100) {
        now += afterMs
        events += pipeline.onSnapshot(snapshot, hash).events
    }

    private val state get() = pipeline.context.state
    private fun count(type: RideLifecycleEventType) = events.count { it.type == type }

    private fun rideToPickup() {
        feed(LifecycleFixtures.offer(), OFFER_HASH)
        feed(LifecycleFixtures.pickup())
    }

    @Test
    fun `normal ride through the pipeline`() {
        LifecycleFixtures.normalRide().forEach { feed(it.snapshot, it.offerHash, afterMs = 10_000) }
        assertEquals(COMPLETED, state)
        assertEquals(
            listOf(
                RideLifecycleEventType.OFFER_DETECTED, RideLifecycleEventType.ACCEPTANCE_CONFIRMED,
                RideLifecycleEventType.ARRIVAL_CONFIRMED, RideLifecycleEventType.TRIP_STARTED,
                RideLifecycleEventType.TRIP_COMPLETED
            ),
            events.map { it.type }
        )
        assertTrue(events.all { it.relatedOfferHash == OFFER_HASH && it.sessionId == events.first().sessionId })
    }

    @Test
    fun `fifty identical trip screens make one trip start`() {
        rideToPickup()
        feed(LifecycleFixtures.arrived())
        repeat(50) { feed(LifecycleFixtures.inTrip()) }
        assertEquals(IN_TRIP, state)
        assertEquals(1, count(RideLifecycleEventType.TRIP_STARTED))
        assertTrue(pipeline.diagnostics.duplicateSnapshotsIgnored >= 49L)
    }

    @Test
    fun `fifty equivalent accessibility events with a moving countdown make one event`() {
        rideToPickup()
        repeat(50) { i -> feed(LifecycleFixtures.pickup(eta = "${50 - i} min")) }
        assertEquals(TO_PICKUP, state)
        assertEquals(1, count(RideLifecycleEventType.ACCEPTANCE_CONFIRMED))
    }

    @Test
    fun `the same offer rendered many times is one detection`() {
        repeat(10) { feed(LifecycleFixtures.offer(), OFFER_HASH) }
        assertEquals(1, count(RideLifecycleEventType.OFFER_DETECTED))
        assertEquals(OFFER_VISIBLE, state)
    }

    @Test
    fun `an offer that goes away to searching is not rejected`() {
        feed(LifecycleFixtures.offer(), OFFER_HASH)
        feed(LifecycleFixtures.searching())
        assertEquals(IDLE, state)
        assertTrue(events.none { it.type.name.contains("REJECT") })
        assertNull(pipeline.context.relatedOfferHash)
    }

    @Test
    fun `an offer that goes away to an unknown screen stays visible until the clean-up`() {
        feed(LifecycleFixtures.offer(), OFFER_HASH)
        feed(LifecycleFixtures.unknown(), afterMs = 30_000)
        assertEquals(OFFER_VISIBLE, state)
        feed(LifecycleFixtures.unknown(), afterMs = config.offerStaleMs)
        assertEquals(IDLE, state)
        assertEquals(RideLifecycleEventType.OFFER_DISAPPEARED, events.last().type)
    }

    @Test
    fun `explicit cancellation screen cancels`() {
        rideToPickup()
        feed(LifecycleFixtures.cancelled())
        assertEquals(RideLifecycleState.CANCELLED, state)
        assertEquals(OFFER_HASH, events.last().relatedOfferHash)
    }

    @Test
    fun `flicker through unknown frames creates no transition`() {
        rideToPickup()
        val before = events.size
        repeat(3) {
            feedOnce(LifecycleFixtures.unknown(), afterMs = 30)
            feedOnce(LifecycleFixtures.pickup(), afterMs = 30)
        }
        assertEquals(TO_PICKUP, state)
        assertEquals(before, events.size)
        assertEquals(UberScreenType.PICKUP_NAVIGATION, pipeline.context.currentScreen)
    }

    @Test
    fun `the ride state survives a temporary unknown screen`() {
        rideToPickup()
        feed(LifecycleFixtures.inTrip())
        feed(LifecycleFixtures.unknown())
        assertEquals(IN_TRIP, state)
        assertEquals(UberScreenType.UNKNOWN, pipeline.context.currentScreen)
        feed(LifecycleFixtures.inTrip())
        assertEquals(IN_TRIP, state)
        assertEquals(1, count(RideLifecycleEventType.TRIP_STARTED))
    }

    @Test
    fun `a critical screen below HIGH waits for confirmation`() {
        feed(LifecycleFixtures.offer(), OFFER_HASH)
        now += 100
        val first = pipeline.onSnapshot(LifecycleFixtures.pickup(), null)
        assertEquals(OFFER_VISIBLE, state)
        assertEquals(config.confirmMs, first.recheckAfterMs ?: -1L)
        now += config.confirmMs
        pipeline.onSnapshot(LifecycleFixtures.pickup(), null)
        assertEquals(TO_PICKUP, state)
    }

    @Test
    fun `a partial render seen once does not move the ride`() {
        rideToPickup()
        feed(LifecycleFixtures.arrived())
        // One frame looks like a trip, then the arrival screen is back before confirmation
        feedOnce(LifecycleFixtures.inTrip(), afterMs = 80)
        feedOnce(LifecycleFixtures.arrived(), afterMs = 80)
        assertEquals(ARRIVED_OR_WAITING, state)
        assertEquals(0, count(RideLifecycleEventType.TRIP_STARTED))
    }

    @Test
    fun `opening mid-trip recovers IN_TRIP and invents nothing before it`() {
        feed(LifecycleFixtures.inTrip())
        assertEquals(IN_TRIP, state)
        assertTrue(pipeline.context.recovered)
        assertTrue(pipeline.context.milestones.isEmpty())
        assertEquals(listOf(RideLifecycleEventType.TRIP_STARTED), events.map { it.type })
        assertTrue(events.single().recovered)
        assertEquals(1L, pipeline.diagnostics.recoveries)
    }

    @Test
    fun `other apps are ignored and change nothing`() {
        rideToPickup()
        val result = pipeline.onSnapshot(LifecycleFixtures.otherApp(), null)
        assertEquals(SkipReason.OTHER_PACKAGE, result.skipped)
        assertNull(result.classification)
        assertEquals(TO_PICKUP, state)
        assertEquals(1L, pipeline.diagnostics.otherPackageIgnored)
    }

    @Test
    fun `minimizing Uber during a trip, even for long, is not a cancellation`() {
        rideToPickup()
        feed(LifecycleFixtures.inTrip())
        repeat(20) { feed(LifecycleFixtures.otherApp(), afterMs = 60_000) }
        feed(LifecycleFixtures.inTrip())
        assertEquals(IN_TRIP, state)
        assertEquals(0, count(RideLifecycleEventType.TRIP_CANCELLED))
    }

    @Test
    fun `a reconnection loses certainty but the same ride is picked up again`() {
        rideToPickup()
        feed(LifecycleFixtures.inTrip())
        val session = pipeline.context.sessionId
        pipeline.onConnectionLost()
        assertEquals(UNKNOWN, state)
        feed(LifecycleFixtures.inTrip())
        assertEquals(IN_TRIP, state)
        assertEquals(session, pipeline.context.sessionId)
        assertEquals(OFFER_HASH, pipeline.context.relatedOfferHash)
        assertTrue(pipeline.context.recovered)
    }

    @Test
    fun `a reconnection while idle invents no ride`() {
        feed(LifecycleFixtures.searching())
        pipeline.onConnectionLost()
        assertEquals(IDLE, state)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `a ride with no ride screen for a long time becomes UNKNOWN, never completed`() {
        rideToPickup()
        feed(LifecycleFixtures.unknown(), afterMs = config.activeStaleMs)
        assertEquals(UNKNOWN, state)
        assertEquals(0, count(RideLifecycleEventType.TRIP_COMPLETED))
        assertEquals(0, count(RideLifecycleEventType.TRIP_CANCELLED))
    }

    @Test
    fun `a completion screen with no trip is counted as invalid`() {
        feed(LifecycleFixtures.summary())
        assertEquals(IDLE, state)
        assertEquals(1L, pipeline.diagnostics.invalidTransitions)
        assertEquals(1, pipeline.recentHistory.size)
    }

    @Test
    fun `after a completed ride a new offer starts clean`() {
        LifecycleFixtures.normalRide().forEach { feed(it.snapshot, it.offerHash, afterMs = 10_000) }
        val first = pipeline.context.sessionId
        feed(LifecycleFixtures.searching())
        assertEquals(IDLE, state)
        feed(LifecycleFixtures.offer(price = "DOP98.50", pickup = "A 5 min (2.0 km)", trip = "9 min (3.4 km)"), OTHER_OFFER_HASH)
        assertEquals(OFFER_VISIBLE, state)
        assertEquals(OTHER_OFFER_HASH, pipeline.context.relatedOfferHash)
        assertFalse(first == pipeline.context.sessionId)
    }

    @Test
    fun `reset forgets the ride but keeps counters`() {
        rideToPickup()
        val processed = pipeline.diagnostics.snapshotsProcessed
        pipeline.reset()
        assertEquals(IDLE, state)
        assertNull(pipeline.context.relatedOfferHash)
        assertEquals(processed, pipeline.diagnostics.snapshotsProcessed)
        // The same pickup screen counts again after a reset: it is a recovery now
        feed(LifecycleFixtures.pickup())
        assertEquals(TO_PICKUP, state)
        assertTrue(pipeline.context.recovered)
    }

    @Test
    fun `each logical event has its own id and the fake clock time`() {
        LifecycleFixtures.normalRide().forEach { feed(it.snapshot, it.offerHash, afterMs = 10_000) }
        assertEquals(events.size, events.map { it.eventId }.toSet().size)
        assertTrue(events.zipWithNext().all { (a, b) -> a.timestampMs < b.timestampMs })
        assertTrue(events.all { it.timestampMs in 1_000_000L..now })
    }

    @Test
    fun `events and history carry codes, never the rider's name or address`() {
        LifecycleFixtures.normalRide().forEach { feed(it.snapshot, it.offerHash, afterMs = 10_000) }
        val dump = (events.map { it.toString() } + pipeline.recentHistory.map { it.toString() }).joinToString()
        assertFalse(dump.contains("Ficticia"))
        assertFalse(dump.contains("Imaginaria"))
        assertFalse(dump.contains("Calle"))
    }

    @Test
    fun `history keeps only the last entries`() {
        repeat(config.historySize + 20) { i ->
            feed(LifecycleFixtures.offer(price = "DOP${100 + i}.00"), "hash-$i")
        }
        assertEquals(config.historySize, pipeline.recentHistory.size)
    }

    @Test
    fun `detector health needs a sample and flags a detector that sees nothing`() {
        assertEquals(DetectorHealth.UNKNOWN, LifecycleDiagnostics(snapshotsProcessed = 5).health)
        assertEquals(DetectorHealth.DEGRADED, LifecycleDiagnostics(snapshotsProcessed = 100, unknownScreens = 96).health)
        assertEquals(DetectorHealth.HEALTHY, LifecycleDiagnostics(snapshotsProcessed = 100, unknownScreens = 40).health)
        // Told apart by letters: digits are masked, so "rara 1" and "rara 2" would be one repainted screen
        repeat(25) { i -> feed(LifecycleFixtures.screen(LifecycleFixtures.text("pantalla rara " + ('a' + i)))) }
        assertEquals(DetectorHealth.DEGRADED, pipeline.diagnostics.health)
    }

    // ---- Replay ----

    @Test
    fun `replay of a normal ride gives the expected states`() {
        val result = LifecycleReplay.run(LifecycleFixtures.normalRide(), LifecycleFixtures.classifier)
        assertEquals(listOf(IDLE, OFFER_VISIBLE, TO_PICKUP, ARRIVED_OR_WAITING, IN_TRIP, COMPLETED), result.states)
        assertEquals("s1", result.context.sessionId)
        assertEquals((1..5).map { "e$it" }, result.events.map { it.eventId })
    }

    @Test
    fun `replay is deterministic`() {
        val a = LifecycleReplay.run(LifecycleFixtures.normalRide(), LifecycleFixtures.classifier)
        val b = LifecycleReplay.run(LifecycleFixtures.normalRide(), LifecycleFixtures.classifier)
        assertEquals(a, b)
    }

    @Test
    fun `replay of two rides in a row keeps them apart`() {
        val first = LifecycleFixtures.normalRide(start = 1_000_000L)
        val second = listOf(
            ReplayStep(1_070_000L, LifecycleFixtures.searching()),
            ReplayStep(1_080_000L, LifecycleFixtures.offer(price = "DOP98.50", pickup = "A 5 min (2.0 km)", trip = "9 min (3.4 km)"), OTHER_OFFER_HASH),
            ReplayStep(1_090_000L, LifecycleFixtures.pickup()),
            ReplayStep(1_100_000L, LifecycleFixtures.cancelled())
        )
        val result = LifecycleReplay.run(first + second, LifecycleFixtures.classifier)
        assertEquals(RideLifecycleState.CANCELLED, result.states.last())
        val secondRide = result.events.dropWhile { it.type != RideLifecycleEventType.SEARCHING_CONFIRMED }.drop(1)
        assertTrue(secondRide.all { it.relatedOfferHash == OTHER_OFFER_HASH && it.sessionId == "s2" })
    }
}
