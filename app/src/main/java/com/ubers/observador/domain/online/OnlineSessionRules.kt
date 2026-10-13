package com.ubers.observador.domain.online

import java.util.UUID

/** What a step did, for the debug history: codes only. */
enum class OnlineTransition {
    STARTED_EXACT,
    STARTED_RECOVERED,
    /** After a restart, Uber was seen online again within the tolerance: the same session goes on. */
    CONTINUED_AFTER_RESTART,
    GAP_RECORDED,
    CLOSED_OFFLINE,
    CLOSED_PAUSED,
    CLOSED_LOST,
    CLOSED_SUPERSEDED
}

/** Counters for the debug panel. Memory only. */
data class OnlineDiagnostics(
    val onlineReads: Long = 0,
    val offlineReads: Long = 0,
    val mixedReadsIgnored: Long = 0,
    val confirmations: Long = 0,
    val sessionsStarted: Long = 0,
    val sessionsClosed: Long = 0,
    val gapsRecorded: Long = 0
)

/**
 * What one step asks for. [saves] are whole rows to insert or replace, in order (a session is
 * one row). [recheckAfterMs] asks the service to read the screen again to confirm a change.
 */
data class OnlineUpdate(
    val saves: List<OnlineSession> = emptyList(),
    val recheckAfterMs: Long? = null,
    val transitions: List<OnlineTransition> = emptyList()
) {
    companion object {
        val NOTHING = OnlineUpdate()
    }
}

/**
 * The rules of the online sessions (Fase 8A), one read at a time. Time comes only from the
 * arguments, so every rule is testable with made-up clocks.
 *
 * - A change of state is confirmed by the same signal in two reads [OnlinePolicy.confirmMs]
 *   apart; a ride screen with high confidence confirms "online" at once. Mixed reads don't count.
 * - A session starts at the first sighting of the confirmed signal. It is EXACT when "offline"
 *   was seen within the tolerance before; otherwise RECOVERED (the real start was earlier).
 * - Each sighting moves "last seen online". A gap longer than the tolerance is recorded and not
 *   counted as confirmed. The row is written at most once per [OnlinePolicy.heartbeatPersistMs],
 *   except when something else changes.
 * - It ends on a confirmed offline signal: at that sighting if it came within the tolerance,
 *   else confirmed at the last sighting and possibly at the signal.
 * - Pausing Observador closes it (PAUSED). After a restart the open row is kept: Uber seen
 *   online within the tolerance continues it, later closes it at its last sighting
 *   (OBSERVATION_LOST) and starts a recovered one.
 * - One open session: on restore, any older open one is closed with an anomaly. Over 24 hours is
 *   flagged, never cut. Nothing is deleted.
 *
 * Not thread-safe by design: the service calls it from the main thread.
 */
class OnlineSessionRules(
    private val policy: OnlinePolicy = OnlinePolicy(),
    private val newId: () -> String = { UUID.randomUUID().toString() }
) {

    private data class Pending(val signal: OnlineSignal, val sinceMs: Long)

    /** The open session, or null. */
    var current: OnlineSession? = null
        private set

    /** The last confirmed state; null when unknown (start, pause). */
    var confirmed: OnlineSignal? = null
        private set

    var lastOfflineSeenAt: Long? = null
        private set

    var diagnostics = OnlineDiagnostics()
        private set

    private var pending: Pending? = null
    private var lastSavedAt: Long? = null

    /** Set after a restart or reconnection: the open session is continued only if Uber comes back in time. */
    private var awaitingContinuation = false

    /**
     * Hands back the sessions still open in the database after a restart. The most recent stays
     * open; any other is closed at its last sighting with an anomaly (returned to be saved).
     */
    fun restore(open: List<OnlineSession>, nowMs: Long): OnlineUpdate {
        val sorted = open.sortedWith(compareByDescending<OnlineSession> { it.startedAt }.thenByDescending { it.lastOnlineSeenAt })
        val keep = sorted.firstOrNull()
        val extra = sorted.drop(1).map { s ->
            closed(s, s.lastOnlineSeenAt, s.lastOnlineSeenAt, OnlineEndReason.SUPERSEDED, nowMs)
                .let { it.copy(anomalies = it.anomalies + OnlineAnomaly.MULTIPLE_OPEN_SESSIONS) }
        }
        current = keep
        confirmed = if (keep != null) OnlineSignal.ONLINE else null
        pending = null
        lastSavedAt = keep?.updatedAt
        awaitingContinuation = keep != null
        diagnostics = diagnostics.copy(sessionsClosed = diagnostics.sessionsClosed + extra.size)
        return OnlineUpdate(saves = extra, transitions = extra.map { OnlineTransition.CLOSED_SUPERSEDED })
    }

    fun onReading(reading: OnlineReading, nowMs: Long): OnlineUpdate {
        if (reading.mixed) {
            diagnostics = diagnostics.copy(mixedReadsIgnored = diagnostics.mixedReadsIgnored + 1)
            return OnlineUpdate.NOTHING
        }
        // A read that says nothing (a menu, another screen of Uber) neither confirms nor cancels a change
        val signal = reading.signal ?: return OnlineUpdate.NOTHING
        diagnostics = when (signal) {
            OnlineSignal.ONLINE -> diagnostics.copy(onlineReads = diagnostics.onlineReads + 1)
            OnlineSignal.OFFLINE -> diagnostics.copy(offlineReads = diagnostics.offlineReads + 1)
        }
        if (signal == confirmed) {
            pending = null
            return sighting(signal, nowMs)
        }
        if (reading.immediate) {
            pending = null
            return confirm(signal, nowMs, nowMs)
        }
        val waiting = pending
        if (waiting == null || waiting.signal != signal || nowMs - waiting.sinceMs > policy.pendingMaxMs) {
            pending = Pending(signal, nowMs)
            return OnlineUpdate(recheckAfterMs = policy.confirmMs)
        }
        val waited = nowMs - waiting.sinceMs
        if (waited < policy.confirmMs) return OnlineUpdate(recheckAfterMs = policy.confirmMs - waited)
        pending = null
        return confirm(signal, waiting.sinceMs, nowMs)
    }

    /** Observador paused: what follows cannot be seen, so the open session closes here (decision 2). */
    fun onPaused(nowMs: Long): OnlineUpdate {
        pending = null
        confirmed = null
        awaitingContinuation = false
        val open = current ?: return OnlineUpdate.NOTHING
        current = null
        val done = closeAt(open, nowMs, OnlineEndReason.PAUSED, nowMs)
        return OnlineUpdate(saves = listOf(done), transitions = listOf(OnlineTransition.CLOSED_PAUSED))
    }

    /** The service lost its connection: events may have been missed, as after a restart. */
    fun onConnectionLost() {
        pending = null
        awaitingContinuation = current != null
    }

    private fun sighting(signal: OnlineSignal, nowMs: Long): OnlineUpdate {
        if (signal == OnlineSignal.OFFLINE) {
            lastOfflineSeenAt = nowMs
            return OnlineUpdate.NOTHING
        }
        val open = current ?: return confirm(OnlineSignal.ONLINE, nowMs, nowMs)
        val gap = nowMs - open.lastOnlineSeenAt
        if (awaitingContinuation) {
            awaitingContinuation = false
            if (gap > policy.toleranceMs) {
                // Too long since the last sighting before the restart: that session is closed where it was last seen
                val lost = closed(open, open.lastOnlineSeenAt, open.lastOnlineSeenAt, OnlineEndReason.OBSERVATION_LOST, nowMs)
                val next = start(nowMs, nowMs)
                diagnostics = diagnostics.copy(sessionsClosed = diagnostics.sessionsClosed + 1)
                return OnlineUpdate(
                    saves = listOf(lost, next),
                    transitions = listOf(OnlineTransition.CLOSED_LOST, OnlineTransition.STARTED_RECOVERED)
                )
            }
            val continued = flagLong(open.copy(lastOnlineSeenAt = maxOf(open.lastOnlineSeenAt, nowMs), updatedAt = nowMs))
            return save(continued, nowMs, listOf(OnlineTransition.CONTINUED_AFTER_RESTART))
        }
        if (gap > policy.toleranceMs) {
            val withGap = flagLong(
                open.copy(
                    lastOnlineSeenAt = nowMs,
                    unobservedMs = open.unobservedMs + gap,
                    gaps = open.gaps + TimeSpan(open.lastOnlineSeenAt, nowMs),
                    updatedAt = nowMs
                )
            )
            diagnostics = diagnostics.copy(gapsRecorded = diagnostics.gapsRecorded + 1)
            return save(withGap, nowMs, listOf(OnlineTransition.GAP_RECORDED))
        }
        if (nowMs <= open.lastOnlineSeenAt) return OnlineUpdate.NOTHING
        val seen = open.copy(lastOnlineSeenAt = nowMs, updatedAt = nowMs)
        val flagged = flagLong(seen)
        current = flagged
        val due = lastSavedAt?.let { nowMs - it >= policy.heartbeatPersistMs } ?: true
        return if (due || flagged.anomalies != open.anomalies) save(flagged, nowMs, emptyList()) else OnlineUpdate.NOTHING
    }

    private fun confirm(signal: OnlineSignal, firstSeenAt: Long, nowMs: Long): OnlineUpdate {
        confirmed = signal
        diagnostics = diagnostics.copy(confirmations = diagnostics.confirmations + 1)
        return when (signal) {
            OnlineSignal.ONLINE -> {
                val open = current
                if (open != null) return sighting(OnlineSignal.ONLINE, nowMs)
                val exact = lastOfflineSeenAt?.let { firstSeenAt - it in 0..policy.toleranceMs } == true
                val session = start(firstSeenAt, nowMs, exact)
                OnlineUpdate(
                    saves = listOf(session),
                    transitions = listOf(if (exact) OnlineTransition.STARTED_EXACT else OnlineTransition.STARTED_RECOVERED)
                )
            }
            OnlineSignal.OFFLINE -> {
                lastOfflineSeenAt = nowMs
                awaitingContinuation = false
                val open = current ?: return OnlineUpdate.NOTHING
                current = null
                val done = closeAt(open, firstSeenAt, OnlineEndReason.WENT_OFFLINE, nowMs)
                OnlineUpdate(saves = listOf(done), transitions = listOf(OnlineTransition.CLOSED_OFFLINE))
            }
        }
    }

    private fun start(startedAt: Long, nowMs: Long, exact: Boolean = false): OnlineSession {
        val session = OnlineSession(
            id = newId(),
            startedAt = startedAt,
            startKind = if (exact) OnlineStartKind.EXACT else OnlineStartKind.RECOVERED,
            lastOnlineSeenAt = maxOf(startedAt, nowMs),
            createdAt = nowMs,
            updatedAt = nowMs
        )
        current = session
        confirmed = OnlineSignal.ONLINE
        lastSavedAt = nowMs
        diagnostics = diagnostics.copy(sessionsStarted = diagnostics.sessionsStarted + 1)
        return session
    }

    /** Closes [open] by evidence seen at [atMs]: within the tolerance it ends there; later, it ends at the last sighting. */
    private fun closeAt(open: OnlineSession, atMs: Long, reason: OnlineEndReason, nowMs: Long): OnlineSession {
        val at = maxOf(atMs, open.startedAt)
        diagnostics = diagnostics.copy(sessionsClosed = diagnostics.sessionsClosed + 1)
        return if (at - open.lastOnlineSeenAt <= policy.toleranceMs) {
            val end = maxOf(at, open.lastOnlineSeenAt)
            closed(open, end, end, reason, nowMs)
        } else {
            closed(open, open.lastOnlineSeenAt, at, reason, nowMs)
        }
    }

    private fun closed(open: OnlineSession, endedAt: Long, possibleEndAt: Long, reason: OnlineEndReason, nowMs: Long): OnlineSession {
        val long = possibleEndAt - open.startedAt > policy.maxSessionMs
        return open.copy(
            endedAt = endedAt,
            possibleEndAt = possibleEndAt,
            endReason = reason,
            anomalies = if (long) open.anomalies + OnlineAnomaly.LONGER_THAN_24H else open.anomalies,
            updatedAt = nowMs
        )
    }

    private fun flagLong(session: OnlineSession): OnlineSession =
        if (session.lastOnlineSeenAt - session.startedAt > policy.maxSessionMs && OnlineAnomaly.LONGER_THAN_24H !in session.anomalies) {
            session.copy(anomalies = session.anomalies + OnlineAnomaly.LONGER_THAN_24H)
        } else {
            session
        }

    private fun save(session: OnlineSession, nowMs: Long, transitions: List<OnlineTransition>): OnlineUpdate {
        current = session
        lastSavedAt = nowMs
        return OnlineUpdate(saves = listOf(session), transitions = transitions)
    }
}
