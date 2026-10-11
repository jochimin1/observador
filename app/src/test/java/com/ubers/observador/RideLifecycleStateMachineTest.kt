package com.ubers.observador

import com.ubers.observador.domain.lifecycle.IgnoreReason
import com.ubers.observador.domain.lifecycle.LifecycleEventDetector
import com.ubers.observador.domain.lifecycle.LifecycleInput
import com.ubers.observador.domain.lifecycle.RideLifecycleEventType
import com.ubers.observador.domain.lifecycle.RideLifecycleEventType.ACCEPTANCE_CONFIRMED
import com.ubers.observador.domain.lifecycle.RideLifecycleEventType.ARRIVAL_CONFIRMED
import com.ubers.observador.domain.lifecycle.RideLifecycleEventType.LIFECYCLE_LOST
import com.ubers.observador.domain.lifecycle.RideLifecycleEventType.OFFER_DETECTED
import com.ubers.observador.domain.lifecycle.RideLifecycleEventType.OFFER_DISAPPEARED
import com.ubers.observador.domain.lifecycle.RideLifecycleEventType.PICKUP_NAVIGATION_CONFIRMED
import com.ubers.observador.domain.lifecycle.RideLifecycleEventType.SEARCHING_CONFIRMED
import com.ubers.observador.domain.lifecycle.RideLifecycleEventType.TRIP_CANCELLED
import com.ubers.observador.domain.lifecycle.RideLifecycleEventType.TRIP_COMPLETED
import com.ubers.observador.domain.lifecycle.RideLifecycleEventType.TRIP_STARTED
import com.ubers.observador.domain.lifecycle.RideLifecycleState
import com.ubers.observador.domain.lifecycle.RideLifecycleState.ARRIVED_OR_WAITING
import com.ubers.observador.domain.lifecycle.RideLifecycleState.CANCELLED
import com.ubers.observador.domain.lifecycle.RideLifecycleState.COMPLETED
import com.ubers.observador.domain.lifecycle.RideLifecycleState.IDLE
import com.ubers.observador.domain.lifecycle.RideLifecycleState.IN_TRIP
import com.ubers.observador.domain.lifecycle.RideLifecycleState.OFFER_VISIBLE
import com.ubers.observador.domain.lifecycle.RideLifecycleState.TO_PICKUP
import com.ubers.observador.domain.lifecycle.RideLifecycleState.UNKNOWN
import com.ubers.observador.domain.lifecycle.RideLifecycleStateMachine
import com.ubers.observador.domain.lifecycle.TransitionResult
import com.ubers.observador.domain.lifecycle.UberScreenType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The state machine alone: pure Kotlin, explicit inputs, no Android. */
class RideLifecycleStateMachineTest {

    private var sessions = 0
    private val machine = RideLifecycleStateMachine { "s${++sessions}" }
    private var clock = 1_000L

    private fun send(type: RideLifecycleEventType, hash: String? = null): TransitionResult {
        clock += 1_000
        return machine.process(LifecycleInput(type, clock, offerHash = hash))
    }

    private val state get() = machine.context.state

    @Test
    fun `normal ride goes through every stage`() {
        val path = listOf(
            send(OFFER_DETECTED, "A"), send(ACCEPTANCE_CONFIRMED), send(ARRIVAL_CONFIRMED),
            send(TRIP_STARTED), send(TRIP_COMPLETED)
        )
        assertEquals(
            listOf(OFFER_VISIBLE, TO_PICKUP, ARRIVED_OR_WAITING, IN_TRIP, COMPLETED),
            path.map { (it as TransitionResult.Applied).current }
        )
        assertEquals("A", machine.context.relatedOfferHash)
        assertTrue(machine.context.startedFromKnownOffer)
        assertFalse(machine.context.recovered)
        assertEquals(5, machine.context.milestones.size)
    }

    @Test
    fun `a disappearing offer closes nothing as rejected`() {
        send(OFFER_DETECTED, "A")
        val result = send(OFFER_DISAPPEARED)
        assertEquals(TransitionResult.Applied(OFFER_VISIBLE, IDLE, OFFER_DISAPPEARED), result)
        assertNull(machine.context.relatedOfferHash)
        assertFalse(RideLifecycleEventType.entries.any { it.name.contains("REJECT") })
    }

    @Test
    fun `explicit cancellation on the way to pickup`() {
        send(OFFER_DETECTED, "A"); send(ACCEPTANCE_CONFIRMED)
        assertEquals(TransitionResult.Applied(TO_PICKUP, CANCELLED, TRIP_CANCELLED), send(TRIP_CANCELLED))
    }

    @Test
    fun `cancellation is accepted from every active stage`() {
        listOf(TO_PICKUP, ARRIVED_OR_WAITING, IN_TRIP).forEach { target ->
            machine.reset()
            send(OFFER_DETECTED, "A")
            if (target.ordinal >= TO_PICKUP.ordinal) send(ACCEPTANCE_CONFIRMED)
            if (target.ordinal >= ARRIVED_OR_WAITING.ordinal) send(ARRIVAL_CONFIRMED)
            if (target == IN_TRIP) send(TRIP_STARTED)
            assertEquals(target, state)
            send(TRIP_CANCELLED)
            assertEquals(CANCELLED, state)
        }
    }

    @Test
    fun `completion without a trip is an invalid transition, not a crash`() {
        val result = send(TRIP_COMPLETED)
        assertEquals(TransitionResult.Invalid(IDLE, TRIP_COMPLETED), result)
        assertEquals(IDLE, state)
        assertEquals(TransitionResult.Invalid(IDLE, TRIP_CANCELLED), send(TRIP_CANCELLED))
    }

    @Test
    fun `completion needs the trip to have started`() {
        send(OFFER_DETECTED, "A"); send(ACCEPTANCE_CONFIRMED)
        assertEquals(TransitionResult.Invalid(TO_PICKUP, TRIP_COMPLETED), send(TRIP_COMPLETED))
        assertEquals(TO_PICKUP, state)
    }

    @Test
    fun `going backwards is invalid and keeps the state`() {
        send(OFFER_DETECTED, "A"); send(ACCEPTANCE_CONFIRMED); send(TRIP_STARTED)
        assertEquals(TransitionResult.Invalid(IN_TRIP, ARRIVAL_CONFIRMED), send(ARRIVAL_CONFIRMED))
        assertEquals(IN_TRIP, state)
    }

    @Test
    fun `the same observation twice is ignored as a duplicate`() {
        send(OFFER_DETECTED, "A")
        assertEquals(TransitionResult.Ignored(OFFER_VISIBLE, OFFER_DETECTED, IgnoreReason.DUPLICATE), send(OFFER_DETECTED, "A"))
        send(ACCEPTANCE_CONFIRMED); send(TRIP_STARTED)
        assertEquals(TransitionResult.Ignored(IN_TRIP, TRIP_STARTED, IgnoreReason.DUPLICATE), send(TRIP_STARTED))
    }

    @Test
    fun `starting mid-trip recovers without inventing earlier moments`() {
        val result = send(TRIP_STARTED)
        assertEquals(TransitionResult.Recovered(IDLE, IN_TRIP, TRIP_STARTED), result)
        val ctx = machine.context
        assertTrue(ctx.recovered)
        assertEquals("s1", ctx.sessionId)
        assertTrue(ctx.milestones.isEmpty())
        assertNull(ctx.relatedOfferHash)
        assertFalse(ctx.startedFromKnownOffer)
        assertEquals(clock, ctx.recoveredAt ?: -1L)
    }

    @Test
    fun `a pickup screen with no offer seen is a recovery, not an acceptance`() {
        assertEquals(TransitionResult.Recovered(IDLE, TO_PICKUP, PICKUP_NAVIGATION_CONFIRMED), send(PICKUP_NAVIGATION_CONFIRMED))
        assertNull(machine.context.milestones[ACCEPTANCE_CONFIRMED])
    }

    @Test
    fun `a later screen right after the offer still proves acceptance and keeps the offer`() {
        send(OFFER_DETECTED, "A")
        assertEquals(TransitionResult.Applied(OFFER_VISIBLE, IN_TRIP, TRIP_STARTED), send(TRIP_STARTED))
        assertEquals("A", machine.context.relatedOfferHash)
    }

    @Test
    fun `terminal states wait for searching before going idle`() {
        send(TRIP_STARTED); send(TRIP_COMPLETED)
        assertEquals(COMPLETED, state)
        assertEquals(TransitionResult.Applied(COMPLETED, IDLE, SEARCHING_CONFIRMED), send(SEARCHING_CONFIRMED))
        assertNull(machine.context.sessionId)
    }

    @Test
    fun `a new offer after a completed ride starts a new lifecycle`() {
        send(OFFER_DETECTED, "A"); send(ACCEPTANCE_CONFIRMED); send(TRIP_STARTED); send(TRIP_COMPLETED)
        send(OFFER_DETECTED, "B")
        assertEquals(OFFER_VISIBLE, state)
        assertEquals("B", machine.context.relatedOfferHash)
        assertEquals("s2", machine.context.sessionId)
        assertEquals(1, machine.context.milestones.size)
    }

    @Test
    fun `a new offer after a cancelled ride starts a new lifecycle`() {
        send(OFFER_DETECTED, "A"); send(ACCEPTANCE_CONFIRMED); send(TRIP_CANCELLED)
        send(OFFER_DETECTED, "B")
        assertEquals("B", machine.context.relatedOfferHash)
        assertEquals("s2", machine.context.sessionId)
    }

    @Test
    fun `consecutive different offers each start their own lifecycle`() {
        send(OFFER_DETECTED, "A")
        val second = send(OFFER_DETECTED, "B")
        assertEquals(TransitionResult.Applied(OFFER_VISIBLE, OFFER_VISIBLE, OFFER_DETECTED), second)
        assertEquals("B", machine.context.relatedOfferHash)
        assertEquals("s2", machine.context.sessionId)
    }

    @Test
    fun `an offer during a trip does not replace the trip`() {
        send(OFFER_DETECTED, "A"); send(ACCEPTANCE_CONFIRMED); send(TRIP_STARTED)
        assertEquals(
            TransitionResult.Ignored(IN_TRIP, OFFER_DETECTED, IgnoreReason.OFFER_DURING_ACTIVE_TRIP),
            send(OFFER_DETECTED, "B")
        )
        assertEquals("A", machine.context.relatedOfferHash)
    }

    @Test
    fun `searching during a trip is neither completion nor cancellation`() {
        send(TRIP_STARTED)
        assertEquals(
            TransitionResult.Ignored(IN_TRIP, SEARCHING_CONFIRMED, IgnoreReason.SEARCHING_DURING_ACTIVE_TRIP),
            send(SEARCHING_CONFIRMED)
        )
        assertEquals(IN_TRIP, state)
    }

    @Test
    fun `lost observation keeps the ride and rejoins it`() {
        send(OFFER_DETECTED, "A"); send(ACCEPTANCE_CONFIRMED); send(TRIP_STARTED)
        send(LIFECYCLE_LOST)
        assertEquals(UNKNOWN, state)
        assertEquals(TransitionResult.Recovered(UNKNOWN, IN_TRIP, TRIP_STARTED), send(TRIP_STARTED))
        assertEquals("s1", machine.context.sessionId)
        assertEquals("A", machine.context.relatedOfferHash)
        assertTrue(machine.context.recovered)
        send(TRIP_COMPLETED)
        assertEquals(COMPLETED, state)
    }

    @Test
    fun `a summary after a lost trip completes that same trip`() {
        send(TRIP_STARTED); send(LIFECYCLE_LOST)
        assertEquals(TransitionResult.Recovered(UNKNOWN, COMPLETED, TRIP_COMPLETED), send(TRIP_COMPLETED))
        assertEquals("s1", machine.context.sessionId)
    }

    @Test
    fun `a clear new offer in UNKNOWN starts fresh and drops the old offer`() {
        send(OFFER_DETECTED, "A"); send(ACCEPTANCE_CONFIRMED); send(LIFECYCLE_LOST)
        send(OFFER_DETECTED, "B")
        assertEquals(OFFER_VISIBLE, state)
        assertEquals("B", machine.context.relatedOfferHash)
        assertEquals("s2", machine.context.sessionId)
        assertNull(machine.context.stateBeforeLost)
    }

    @Test
    fun `reset clears the ride, the offer link and every moment`() {
        send(OFFER_DETECTED, "A"); send(ACCEPTANCE_CONFIRMED)
        machine.reset()
        val ctx = machine.context
        assertEquals(IDLE, ctx.state)
        assertNull(ctx.sessionId)
        assertNull(ctx.relatedOfferHash)
        assertTrue(ctx.milestones.isEmpty())
    }

    @Test
    fun `every state answers every event without throwing`() {
        RideLifecycleState.entries.forEach { from ->
            RideLifecycleEventType.entries.forEach { event ->
                machine.reset()
                reach(from)
                send(event, "Z")
            }
        }
    }

    @Test
    fun `screens map to events, and the pickup screen means acceptance only after an offer`() {
        assertEquals(ACCEPTANCE_CONFIRMED, LifecycleEventDetector.eventFor(UberScreenType.PICKUP_NAVIGATION, OFFER_VISIBLE))
        assertEquals(PICKUP_NAVIGATION_CONFIRMED, LifecycleEventDetector.eventFor(UberScreenType.PICKUP_NAVIGATION, IDLE))
        assertEquals(TRIP_STARTED, LifecycleEventDetector.eventFor(UberScreenType.IN_TRIP, TO_PICKUP))
        assertNull(LifecycleEventDetector.eventFor(UberScreenType.UNKNOWN, IN_TRIP))
        assertNull(LifecycleEventDetector.eventFor(UberScreenType.AMBIGUOUS, IN_TRIP))
    }

    private fun reach(target: RideLifecycleState) {
        when (target) {
            IDLE -> Unit
            OFFER_VISIBLE -> send(OFFER_DETECTED, "A")
            TO_PICKUP -> send(PICKUP_NAVIGATION_CONFIRMED)
            ARRIVED_OR_WAITING -> send(ARRIVAL_CONFIRMED)
            IN_TRIP -> send(TRIP_STARTED)
            COMPLETED -> { send(TRIP_STARTED); send(TRIP_COMPLETED) }
            CANCELLED -> { send(TRIP_STARTED); send(TRIP_CANCELLED) }
            UNKNOWN -> { send(TRIP_STARTED); send(LIFECYCLE_LOST) }
        }
        assertEquals(target, state)
    }
}
