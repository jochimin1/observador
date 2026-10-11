package com.ubers.observador.domain.lifecycle

/** Where the ride seems to be. Kept apart from the screen: IN_TRIP can hold while the screen is UNKNOWN. */
enum class RideLifecycleState {
    IDLE,
    OFFER_VISIBLE,
    TO_PICKUP,
    ARRIVED_OR_WAITING,
    IN_TRIP,
    /** Terminal: stays until Uber shows it is searching again or a new offer arrives. */
    COMPLETED,
    /** Terminal, cancelled by someone: who is not known, so it is never guessed. */
    CANCELLED,
    /** Observation lost (service reconnected, or a trip went quiet): a valid state, not an error. */
    UNKNOWN;

    val isActiveTrip: Boolean get() = this == TO_PICKUP || this == ARRIVED_OR_WAITING || this == IN_TRIP
    val isTerminal: Boolean get() = this == COMPLETED || this == CANCELLED
}

/**
 * Logical events. There is deliberately no REJECTED: a card that disappears proves nothing,
 * so it is OFFER_DISAPPEARED. LIFECYCLE_LOST is observation lost, never a cancellation.
 */
enum class RideLifecycleEventType {
    OFFER_DETECTED,
    OFFER_DISAPPEARED,
    ACCEPTANCE_CONFIRMED,
    PICKUP_NAVIGATION_CONFIRMED,
    ARRIVAL_CONFIRMED,
    TRIP_STARTED,
    TRIP_COMPLETED,
    TRIP_CANCELLED,
    SEARCHING_CONFIRMED,
    LIFECYCLE_LOST
}

/** One input to the state machine: what was observed, when, and how sure we are. */
data class LifecycleInput(
    val type: RideLifecycleEventType,
    val atMs: Long,
    val offerHash: String? = null,
    val screen: UberScreenType = UberScreenType.UNKNOWN,
    val confidence: Double = 0.0,
    val evidence: List<EvidenceCode> = emptyList()
)

/**
 * A logical event that changed the lifecycle. One per real-world change: fifty identical
 * accessibility events make one of these, with one [eventId]. Carries codes, never screen text,
 * and is ready for Fase 7B to store without changes.
 */
data class RideLifecycleEvent(
    val eventId: String,
    val type: RideLifecycleEventType,
    val timestampMs: Long,
    val sessionId: String?,
    val relatedOfferHash: String?,
    val screenType: UberScreenType,
    val confidence: Double,
    val recovered: Boolean,
    val from: RideLifecycleState,
    val to: RideLifecycleState,
    val evidence: List<EvidenceCode>
)

/**
 * The lifecycle in memory (Fase 7A persists nothing). [milestones] only holds moments actually
 * seen: a lifecycle recovered mid-trip has no acceptance or start time, and none is made up.
 */
data class RideLifecycleContext(
    val state: RideLifecycleState = RideLifecycleState.IDLE,
    /** Temporary id from the first event of this lifecycle until it ends or is reset. */
    val sessionId: String? = null,
    val relatedOfferHash: String? = null,
    val startedFromKnownOffer: Boolean = false,
    val recovered: Boolean = false,
    val lastTransitionAt: Long? = null,
    val lastTransitionFrom: RideLifecycleState? = null,
    val currentScreen: UberScreenType = UberScreenType.UNKNOWN,
    val confidence: Double = 0.0,
    val milestones: Map<RideLifecycleEventType, Long> = emptyMap(),
    /** When Observador joined a lifecycle already under way; null if it saw it from the start. */
    val recoveredAt: Long? = null,
    /** Active state before observation was lost, so the same ride can be picked up again. */
    val stateBeforeLost: RideLifecycleState? = null
)

enum class IgnoreReason {
    /** The lifecycle is already there: a repeat of the same observation. */
    DUPLICATE,
    /** Uber can show the next offer during a trip; it does not replace the trip. */
    OFFER_DURING_ACTIVE_TRIP,
    /** Searching shown during a trip: not evidence that it ended or was cancelled. */
    SEARCHING_DURING_ACTIVE_TRIP,
    /** Nothing to do in this state, such as an offer disappearing when none was visible. */
    NOT_APPLICABLE
}

/** Explicit outcome of every input. Unexpected inputs are results, never exceptions: Uber is external. */
sealed interface TransitionResult {
    data class Applied(
        val previous: RideLifecycleState,
        val current: RideLifecycleState,
        val event: RideLifecycleEventType
    ) : TransitionResult

    /** Joined a lifecycle already under way, without inventing what came before. */
    data class Recovered(
        val previous: RideLifecycleState,
        val current: RideLifecycleState,
        val event: RideLifecycleEventType
    ) : TransitionResult

    data class Ignored(val state: RideLifecycleState, val event: RideLifecycleEventType, val reason: IgnoreReason) :
        TransitionResult

    /** Impossible order (summary with no trip, going backwards): logged, state kept. */
    data class Invalid(val from: RideLifecycleState, val event: RideLifecycleEventType) : TransitionResult
}

/**
 * The ride lifecycle as an explicit state machine. Pure Kotlin: no Android, no clock of its own
 * (time comes with each input), no randomness unless [newSessionId] uses it. Not thread-safe:
 * one caller, one order (the pipeline runs on the service's main thread).
 */
class RideLifecycleStateMachine(private val newSessionId: () -> String) {

    var context = RideLifecycleContext()
        private set

    /** Records which screen is on view now, without changing the ride state. */
    fun observeScreen(screen: UberScreenType, confidence: Double) {
        context = context.copy(currentScreen = screen, confidence = confidence)
    }

    fun reset() {
        context = RideLifecycleContext()
    }

    fun process(input: LifecycleInput): TransitionResult {
        val state = context.state
        return when (input.type) {
            RideLifecycleEventType.OFFER_DETECTED -> when {
                state.isActiveTrip -> ignore(input, IgnoreReason.OFFER_DURING_ACTIVE_TRIP)
                state == RideLifecycleState.OFFER_VISIBLE && input.offerHash == context.relatedOfferHash ->
                    ignore(input, IgnoreReason.DUPLICATE)
                // Any other state, including UNKNOWN and the terminal ones: a new lifecycle, old offer forgotten
                else -> startNew(input)
            }

            RideLifecycleEventType.OFFER_DISAPPEARED ->
                if (state == RideLifecycleState.OFFER_VISIBLE) end(input, RideLifecycleState.IDLE)
                else ignore(input, IgnoreReason.NOT_APPLICABLE)

            RideLifecycleEventType.ACCEPTANCE_CONFIRMED,
            RideLifecycleEventType.PICKUP_NAVIGATION_CONFIRMED -> forward(input, RideLifecycleState.TO_PICKUP)

            RideLifecycleEventType.ARRIVAL_CONFIRMED -> forward(input, RideLifecycleState.ARRIVED_OR_WAITING)

            RideLifecycleEventType.TRIP_STARTED -> forward(input, RideLifecycleState.IN_TRIP)

            RideLifecycleEventType.TRIP_COMPLETED -> when {
                state == RideLifecycleState.IN_TRIP -> move(input, RideLifecycleState.COMPLETED)
                state == RideLifecycleState.COMPLETED -> ignore(input, IgnoreReason.DUPLICATE)
                state == RideLifecycleState.UNKNOWN && context.stateBeforeLost == RideLifecycleState.IN_TRIP ->
                    rejoin(input, RideLifecycleState.COMPLETED)
                // A summary with no trip seen in progress: nothing to complete
                else -> invalid(input)
            }

            RideLifecycleEventType.TRIP_CANCELLED -> when {
                state.isActiveTrip || state == RideLifecycleState.OFFER_VISIBLE -> move(input, RideLifecycleState.CANCELLED)
                state == RideLifecycleState.CANCELLED -> ignore(input, IgnoreReason.DUPLICATE)
                state == RideLifecycleState.UNKNOWN && context.stateBeforeLost?.isActiveTrip == true ->
                    rejoin(input, RideLifecycleState.CANCELLED)
                else -> invalid(input)
            }

            RideLifecycleEventType.SEARCHING_CONFIRMED -> when {
                state.isActiveTrip -> ignore(input, IgnoreReason.SEARCHING_DURING_ACTIVE_TRIP)
                state == RideLifecycleState.IDLE -> ignore(input, IgnoreReason.DUPLICATE)
                // Terminal, a visible offer that went away, or UNKNOWN: back to idle, lifecycle closed
                else -> end(input, RideLifecycleState.IDLE)
            }

            RideLifecycleEventType.LIFECYCLE_LOST ->
                if (state == RideLifecycleState.IDLE || state == RideLifecycleState.UNKNOWN) {
                    ignore(input, IgnoreReason.NOT_APPLICABLE)
                } else {
                    val result = move(input, RideLifecycleState.UNKNOWN)
                    context = context.copy(stateBeforeLost = state)
                    result
                }
        }
    }

    /**
     * Moves forward to [target] (TO_PICKUP, ARRIVED_OR_WAITING or IN_TRIP). From a visible offer
     * that is the positive evidence of acceptance; from IDLE, UNKNOWN or a finished ride it is a
     * recovery; going backwards is invalid.
     */
    private fun forward(input: LifecycleInput, target: RideLifecycleState): TransitionResult {
        val state = context.state
        return when {
            state == target -> ignore(input, IgnoreReason.DUPLICATE)
            state == RideLifecycleState.OFFER_VISIBLE -> move(input, target)
            state.isActiveTrip -> if (target.ordinal > state.ordinal) move(input, target) else invalid(input)
            state == RideLifecycleState.UNKNOWN && canRejoin(target) -> rejoin(input, target)
            else -> recover(input, target)
        }
    }

    /** After a loss, the same ride continues if Uber shows the stage it was in or a later one. */
    private fun canRejoin(target: RideLifecycleState): Boolean {
        val before = context.stateBeforeLost ?: return false
        val wasRide = before.isActiveTrip || before == RideLifecycleState.OFFER_VISIBLE
        return wasRide && target.ordinal >= before.ordinal
    }

    private fun startNew(input: LifecycleInput): TransitionResult {
        val previous = context.state
        context = RideLifecycleContext(
            state = RideLifecycleState.OFFER_VISIBLE,
            sessionId = newSessionId(),
            relatedOfferHash = input.offerHash,
            startedFromKnownOffer = input.offerHash != null,
            lastTransitionAt = input.atMs,
            lastTransitionFrom = previous,
            currentScreen = input.screen,
            confidence = input.confidence,
            milestones = mapOf(input.type to input.atMs)
        )
        return TransitionResult.Applied(previous, RideLifecycleState.OFFER_VISIBLE, input.type)
    }

    /** First sight of a ride already under way: new session, no earlier milestones, no offer link. */
    private fun recover(input: LifecycleInput, target: RideLifecycleState): TransitionResult {
        val previous = context.state
        context = RideLifecycleContext(
            state = target,
            sessionId = newSessionId(),
            recovered = true,
            recoveredAt = input.atMs,
            lastTransitionAt = input.atMs,
            lastTransitionFrom = previous,
            currentScreen = input.screen,
            confidence = input.confidence
        )
        return TransitionResult.Recovered(previous, target, input.type)
    }

    /** Back to the same ride after observation was lost: session and offer link are kept. */
    private fun rejoin(input: LifecycleInput, target: RideLifecycleState): TransitionResult {
        val previous = context.state
        context = context.copy(
            state = target,
            recovered = true,
            recoveredAt = context.recoveredAt ?: input.atMs,
            stateBeforeLost = null,
            lastTransitionAt = input.atMs,
            lastTransitionFrom = previous,
            currentScreen = input.screen,
            confidence = input.confidence
        )
        return TransitionResult.Recovered(previous, target, input.type)
    }

    private fun move(input: LifecycleInput, target: RideLifecycleState): TransitionResult {
        val previous = context.state
        context = context.copy(
            state = target,
            lastTransitionAt = input.atMs,
            lastTransitionFrom = previous,
            currentScreen = input.screen,
            confidence = input.confidence,
            milestones = context.milestones + (input.type to input.atMs)
        )
        return TransitionResult.Applied(previous, target, input.type)
    }

    /** Closes the lifecycle: session and offer link are cleared so the next one starts clean. */
    private fun end(input: LifecycleInput, target: RideLifecycleState): TransitionResult {
        val previous = context.state
        context = RideLifecycleContext(
            state = target,
            lastTransitionAt = input.atMs,
            lastTransitionFrom = previous,
            currentScreen = input.screen,
            confidence = input.confidence
        )
        return TransitionResult.Applied(previous, target, input.type)
    }

    private fun ignore(input: LifecycleInput, reason: IgnoreReason) =
        TransitionResult.Ignored(context.state, input.type, reason)

    private fun invalid(input: LifecycleInput) = TransitionResult.Invalid(context.state, input.type)
}

/** Turns a confirmed screen into the event it means for the current state, or null for none. */
object LifecycleEventDetector {

    fun eventFor(screen: UberScreenType, state: RideLifecycleState): RideLifecycleEventType? = when (screen) {
        UberScreenType.OFFER -> RideLifecycleEventType.OFFER_DETECTED
        UberScreenType.SEARCHING -> RideLifecycleEventType.SEARCHING_CONFIRMED
        // Only right after an offer is the pickup screen proof of acceptance; otherwise it is joined later
        UberScreenType.PICKUP_NAVIGATION ->
            if (state == RideLifecycleState.OFFER_VISIBLE) RideLifecycleEventType.ACCEPTANCE_CONFIRMED
            else RideLifecycleEventType.PICKUP_NAVIGATION_CONFIRMED
        UberScreenType.ARRIVED_OR_WAITING -> RideLifecycleEventType.ARRIVAL_CONFIRMED
        UberScreenType.IN_TRIP -> RideLifecycleEventType.TRIP_STARTED
        UberScreenType.TRIP_SUMMARY -> RideLifecycleEventType.TRIP_COMPLETED
        UberScreenType.CANCELLED -> RideLifecycleEventType.TRIP_CANCELLED
        UberScreenType.AMBIGUOUS, UberScreenType.UNKNOWN -> null
    }
}
