package com.ubers.observador.domain.lifecycle

import java.util.UUID

/** Every timing rule of the pipeline, in one place and adjustable in tests. */
data class LifecycleConfig(
    /** A critical screen below HIGH confidence must still be there this long after it first appeared. */
    val confirmMs: Long = 500L,
    /** A visible offer with no card seen for this long is closed as OFFER_DISAPPEARED: clean-up, not rejection. */
    val offerStaleMs: Long = 60_000L,
    /** A ride with no ride screen for this long becomes UNKNOWN (observation lost), never completed or cancelled. */
    val activeStaleMs: Long = 15 * 60_000L,
    /** Size of the in-memory history shown in the debug panel. */
    val historySize: Int = 50
)

enum class DetectorHealth { HEALTHY, DEGRADED, UNKNOWN }

/** Counters for the debug panel, so a change in Uber's interface shows up as numbers. Memory only. */
data class LifecycleDiagnostics(
    val snapshotsReceived: Long = 0,
    val otherPackageIgnored: Long = 0,
    val duplicateSnapshotsIgnored: Long = 0,
    val snapshotsProcessed: Long = 0,
    val screensRecognized: Long = 0,
    val unknownScreens: Long = 0,
    val ambiguousScreens: Long = 0,
    val eventsGenerated: Long = 0,
    val duplicateEventsIgnored: Long = 0,
    val invalidTransitions: Long = 0,
    val recoveries: Long = 0,
    val detectorFailures: Long = 0
) {
    /** UNKNOWN until there is a sample; DEGRADED when nearly every screen goes unrecognised. */
    val health: DetectorHealth
        get() = when {
            snapshotsProcessed < MIN_SAMPLE -> DetectorHealth.UNKNOWN
            (unknownScreens + ambiguousScreens).toDouble() / snapshotsProcessed >= DEGRADED_UNKNOWN_SHARE ->
                DetectorHealth.DEGRADED
            else -> DetectorHealth.HEALTHY
        }

    companion object {
        const val MIN_SAMPLE = 20L
        const val DEGRADED_UNKNOWN_SHARE = 0.95
    }
}

enum class LogKind { EVENT, RECOVERY, INVALID }

/** One line of the debug history: codes and states only, no screen text. */
data class LifecycleLogEntry(
    val atMs: Long,
    val kind: LogKind,
    val event: RideLifecycleEventType,
    val from: RideLifecycleState,
    val to: RideLifecycleState
)

enum class SkipReason { OTHER_PACKAGE, DUPLICATE_SNAPSHOT }

data class PipelineResult(
    /** Null when the snapshot came from another app. */
    val classification: ScreenClassification?,
    val events: List<RideLifecycleEvent> = emptyList(),
    val transitions: List<TransitionResult> = emptyList(),
    /** Ask for another snapshot after this many ms to confirm a screen that is not yet stable. */
    val recheckAfterMs: Long? = null,
    val skipped: SkipReason? = null
)

/**
 * Snapshot -> classification -> stable screen -> event -> state machine, one snapshot at a time.
 *
 * Duplicates: a repaint with the same [ScreenFingerprint] is not classified again, and a screen
 * already turned into an event (same type, and same offer for offers) produces nothing more
 * until a different screen is handled, so fifty identical reads make one event. UNKNOWN and LOW
 * screens never change the ride, so a flicker through an unrecognised frame changes nothing.
 * Stability: acceptance, trip start, completion and cancellation below HIGH confidence must be
 * seen again [LifecycleConfig.confirmMs] later; the caller re-reads when [PipelineResult.recheckAfterMs]
 * asks. Time comes only from [clock], so every rule is testable with a fake clock.
 *
 * Not thread-safe by design: the service calls it from the main thread, which gives one order.
 */
class RideLifecyclePipeline(
    private val classifier: UberScreenClassifier,
    private val clock: () -> Long,
    private val config: LifecycleConfig = LifecycleConfig(),
    newSessionId: () -> String = { UUID.randomUUID().toString() },
    private val newEventId: () -> String = { UUID.randomUUID().toString() }
) {

    private val machine = RideLifecycleStateMachine(newSessionId)
    private val history = ArrayDeque<LifecycleLogEntry>()

    val context: RideLifecycleContext get() = machine.context

    var diagnostics = LifecycleDiagnostics()
        private set

    /** Last classification computed, for the debug panel. */
    var lastClassification: ScreenClassification? = null
        private set

    val recentHistory: List<LifecycleLogEntry> get() = history.toList()

    private data class ScreenKey(val type: UberScreenType, val offerHash: String?)
    private data class Pending(val key: ScreenKey, val sinceMs: Long)

    private class Output {
        val events = ArrayList<RideLifecycleEvent>()
        val transitions = ArrayList<TransitionResult>()
        var recheckAfterMs: Long? = null
    }

    private var lastFingerprint: String? = null
    private var lastHandledKey: ScreenKey? = null
    private var pending: Pending? = null
    private var lastOfferSeenAt: Long? = null
    private var lastRideScreenAt: Long? = null

    fun onSnapshot(snapshot: ScreenSnapshot, offerHash: String?): PipelineResult {
        val now = clock()
        diagnostics = diagnostics.copy(snapshotsReceived = diagnostics.snapshotsReceived + 1)
        if (snapshot.packageName != classifier.targetPackage) {
            diagnostics = diagnostics.copy(otherPackageIgnored = diagnostics.otherPackageIgnored + 1)
            return PipelineResult(classification = null, skipped = SkipReason.OTHER_PACKAGE)
        }

        val fingerprint = ScreenFingerprint.of(snapshot, offerHash)
        val previous = lastClassification
        val repaint = fingerprint == lastFingerprint && previous != null
        val classification = if (repaint && previous != null) {
            diagnostics = diagnostics.copy(duplicateSnapshotsIgnored = diagnostics.duplicateSnapshotsIgnored + 1)
            previous
        } else {
            classifyAndCount(snapshot).also {
                lastFingerprint = fingerprint
                lastClassification = it
            }
        }
        machine.observeScreen(classification.type, classification.confidence)
        if (classification.type == UberScreenType.OFFER || offerHash != null) lastOfferSeenAt = now
        if (classification.type in RIDE_SCREENS) lastRideScreenAt = now

        val out = Output()
        handleScreen(classification, offerHash, now, out)
        checkTimeouts(now, out)
        return PipelineResult(
            classification = classification,
            events = out.events,
            transitions = out.transitions,
            recheckAfterMs = out.recheckAfterMs,
            skipped = if (repaint) SkipReason.DUPLICATE_SNAPSHOT else null
        )
    }

    /**
     * The service lost its connection (interrupted or reconnected): events may be missing, so the
     * ride becomes UNKNOWN. Its session and offer are kept, and the next ride screen rejoins it.
     */
    fun onConnectionLost(): PipelineResult {
        forgetScreens()
        val out = Output()
        apply(LifecycleInput(RideLifecycleEventType.LIFECYCLE_LOST, clock()), out)
        return PipelineResult(classification = null, events = out.events, transitions = out.transitions)
    }

    /** Starts over: ride, offer link and screen memory cleared. Counters and history stay. */
    fun reset() {
        machine.reset()
        forgetScreens()
        lastOfferSeenAt = null
        lastRideScreenAt = null
    }

    private fun forgetScreens() {
        lastFingerprint = null
        lastClassification = null
        lastHandledKey = null
        pending = null
    }

    private fun classifyAndCount(snapshot: ScreenSnapshot): ScreenClassification {
        val c = classifier.classify(snapshot)
        diagnostics = diagnostics.copy(
            snapshotsProcessed = diagnostics.snapshotsProcessed + 1,
            screensRecognized = diagnostics.screensRecognized + if (c.isRecognized) 1 else 0,
            unknownScreens = diagnostics.unknownScreens + if (c.type == UberScreenType.UNKNOWN) 1 else 0,
            ambiguousScreens = diagnostics.ambiguousScreens + if (c.type == UberScreenType.AMBIGUOUS) 1 else 0,
            detectorFailures = diagnostics.detectorFailures + c.failedDetectors.size
        )
        return c
    }

    private fun handleScreen(c: ScreenClassification, offerHash: String?, now: Long, out: Output) {
        // Unrecognised, ambiguous or weak: the ride stays as it is (state is not the screen)
        if (!c.isRecognized || c.level == ConfidenceLevel.LOW) {
            pending = null
            return
        }
        val key = ScreenKey(c.type, if (c.type == UberScreenType.OFFER) offerHash else null)
        if (key == lastHandledKey) {
            pending = null
            diagnostics = diagnostics.copy(duplicateEventsIgnored = diagnostics.duplicateEventsIgnored + 1)
            return
        }
        if (c.type in CRITICAL_SCREENS && c.level != ConfidenceLevel.HIGH) {
            val waiting = pending
            if (waiting == null || waiting.key != key) {
                pending = Pending(key, now)
                out.recheckAfterMs = config.confirmMs
                return
            }
            val waited = now - waiting.sinceMs
            if (waited < config.confirmMs) {
                out.recheckAfterMs = config.confirmMs - waited
                return
            }
        }
        pending = null
        lastHandledKey = key
        val type = LifecycleEventDetector.eventFor(c.type, machine.context.state) ?: return
        apply(
            LifecycleInput(
                type = type,
                atMs = now,
                offerHash = key.offerHash,
                screen = c.type,
                confidence = c.confidence,
                evidence = c.evidence.map { it.code }
            ),
            out
        )
    }

    /** Clean-up only: a timeout never means rejected, completed or cancelled. */
    private fun checkTimeouts(now: Long, out: Output) {
        val ctx = machine.context
        if (ctx.state == RideLifecycleState.OFFER_VISIBLE) {
            val seen = lastOfferSeenAt ?: ctx.lastTransitionAt ?: now
            if (now - seen >= config.offerStaleMs) {
                lastHandledKey = null
                apply(LifecycleInput(RideLifecycleEventType.OFFER_DISAPPEARED, now), out)
            }
        } else if (ctx.state.isActiveTrip) {
            val seen = lastRideScreenAt ?: ctx.lastTransitionAt ?: now
            if (now - seen >= config.activeStaleMs) {
                lastHandledKey = null
                apply(LifecycleInput(RideLifecycleEventType.LIFECYCLE_LOST, now), out)
            }
        }
    }

    private fun apply(input: LifecycleInput, out: Output) {
        val before = machine.context
        val result = machine.process(input)
        out.transitions += result
        when (result) {
            is TransitionResult.Applied -> record(input, before, result.previous, result.current, LogKind.EVENT, out)
            is TransitionResult.Recovered -> {
                diagnostics = diagnostics.copy(recoveries = diagnostics.recoveries + 1)
                record(input, before, result.previous, result.current, LogKind.RECOVERY, out)
            }
            is TransitionResult.Ignored -> if (result.reason == IgnoreReason.DUPLICATE) {
                diagnostics = diagnostics.copy(duplicateEventsIgnored = diagnostics.duplicateEventsIgnored + 1)
            }
            is TransitionResult.Invalid -> {
                diagnostics = diagnostics.copy(invalidTransitions = diagnostics.invalidTransitions + 1)
                remember(LifecycleLogEntry(input.atMs, LogKind.INVALID, input.type, result.from, result.from))
            }
        }
    }

    private fun record(
        input: LifecycleInput,
        before: RideLifecycleContext,
        from: RideLifecycleState,
        to: RideLifecycleState,
        kind: LogKind,
        out: Output
    ) {
        val after = machine.context
        // An event that closes a lifecycle still names the session and offer it closed
        out.events += RideLifecycleEvent(
            eventId = newEventId(),
            type = input.type,
            timestampMs = input.atMs,
            sessionId = after.sessionId ?: before.sessionId,
            relatedOfferHash = after.relatedOfferHash ?: before.relatedOfferHash,
            screenType = input.screen,
            confidence = input.confidence,
            recovered = after.recovered,
            from = from,
            to = to,
            evidence = input.evidence
        )
        diagnostics = diagnostics.copy(eventsGenerated = diagnostics.eventsGenerated + 1)
        remember(LifecycleLogEntry(input.atMs, kind, input.type, from, to))
    }

    private fun remember(entry: LifecycleLogEntry) {
        history.addLast(entry)
        while (history.size > config.historySize) history.removeFirst()
    }

    companion object {
        /** Screens whose event is a critical transition: acceptance, start, completion, cancellation. */
        val CRITICAL_SCREENS = setOf(
            UberScreenType.PICKUP_NAVIGATION,
            UberScreenType.IN_TRIP,
            UberScreenType.TRIP_SUMMARY,
            UberScreenType.CANCELLED
        )

        /** Screens that prove a ride is still under way. */
        val RIDE_SCREENS = setOf(
            UberScreenType.PICKUP_NAVIGATION,
            UberScreenType.ARRIVED_OR_WAITING,
            UberScreenType.IN_TRIP
        )
    }
}

/** One recorded read: when it happened, what was on screen and the offer parsed from it, if any. */
data class ReplayStep(val atMs: Long, val snapshot: ScreenSnapshot, val offerHash: String? = null)

data class ReplayResult(
    /** Ride state after each step. */
    val states: List<RideLifecycleState>,
    val events: List<RideLifecycleEvent>,
    val context: RideLifecycleContext,
    val diagnostics: LifecycleDiagnostics
)

/**
 * Plays a recorded sequence of screens through a fresh pipeline with a fake clock, re-reading a
 * screen when the pipeline asks to confirm it, as the service does. For regression tests, and
 * later for sequences captured on a real phone. Ids are numbered (s1, e1...) so results repeat.
 */
object LifecycleReplay {

    private const val MAX_RECHECKS = 5

    fun run(
        steps: List<ReplayStep>,
        classifier: UberScreenClassifier,
        config: LifecycleConfig = LifecycleConfig()
    ): ReplayResult {
        var now = steps.firstOrNull()?.atMs ?: 0L
        var sessions = 0
        var eventIds = 0
        val pipeline = RideLifecyclePipeline(
            classifier = classifier,
            clock = { now },
            config = config,
            newSessionId = { "s${++sessions}" },
            newEventId = { "e${++eventIds}" }
        )
        val states = ArrayList<RideLifecycleState>()
        val events = ArrayList<RideLifecycleEvent>()
        for (step in steps) {
            now = maxOf(now, step.atMs)
            var result = pipeline.onSnapshot(step.snapshot, step.offerHash)
            events += result.events
            var rechecks = 0
            while (rechecks < MAX_RECHECKS) {
                val wait = result.recheckAfterMs ?: break
                now += wait
                result = pipeline.onSnapshot(step.snapshot, step.offerHash)
                events += result.events
                rechecks++
            }
            states += pipeline.context.state
        }
        return ReplayResult(states, events, pipeline.context, pipeline.diagnostics)
    }
}
