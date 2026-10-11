package com.ubers.observador.service

/**
 * Tracks which offer card is currently on screen. A "session" starts when a card appears
 * and ends once it has been gone for [absentConfirmMs], so a UI flicker doesn't split one
 * offer into two sessions.
 *
 * Pure Kotlin with an explicit clock, so it can be unit tested without Android.
 * Call it from a single thread (the service uses the main thread).
 */
class OfferSessionTracker(private val absentConfirmMs: Long = ABSENT_CONFIRM_MS) {

    /** Hash of the offer on screen, or null when no card is being shown. */
    var activeHash: String? = null
        private set

    private var lastSeenAt = 0L

    val hasActiveSession: Boolean get() = activeHash != null

    /**
     * The card with [hash] was read at [now] (ms, monotonic clock).
     * Returns true when this starts a new session: nothing was active, or a different
     * offer replaced the previous one. Returns false for the same offer still on screen.
     */
    fun onOfferSeen(hash: String, now: Long): Boolean {
        lastSeenAt = now
        if (activeHash == hash) return false
        activeHash = hash
        return true
    }

    /**
     * No card was found. Returns true only when the active session just ended, meaning the
     * card has been missing for at least [absentConfirmMs] since it was last seen.
     */
    fun onOfferAbsent(now: Long): Boolean {
        if (activeHash == null) return false
        if (now - lastSeenAt < absentConfirmMs) return false
        activeHash = null
        return true
    }

    fun reset() {
        activeHash = null
    }

    companion object {
        const val ABSENT_CONFIRM_MS = 1_500L
    }
}
