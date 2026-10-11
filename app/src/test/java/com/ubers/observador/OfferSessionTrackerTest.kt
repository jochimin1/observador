package com.ubers.observador

import com.ubers.observador.service.OfferSessionTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfferSessionTrackerTest {

    private val confirm = OfferSessionTracker.ABSENT_CONFIRM_MS

    @Test
    fun `same offer seen ten times starts a single session`() {
        val tracker = OfferSessionTracker()
        var starts = 0
        repeat(10) { i -> if (tracker.onOfferSeen("A", 1_000L + i * 300L)) starts++ }
        assertEquals(1, starts)
    }

    @Test
    fun `flicker shorter than the confirm time keeps the same session`() {
        val tracker = OfferSessionTracker()
        assertTrue(tracker.onOfferSeen("A", 0))
        assertFalse("too soon to call it gone", tracker.onOfferAbsent(500))
        assertFalse("card came back, same session", tracker.onOfferSeen("A", 800))
        assertFalse(tracker.onOfferAbsent(800 + confirm - 1))
        assertEquals("A", tracker.activeHash)
    }

    @Test
    fun `card gone for the confirm time ends the session exactly once`() {
        val tracker = OfferSessionTracker()
        tracker.onOfferSeen("A", 0)
        assertTrue(tracker.onOfferAbsent(confirm))
        assertNull(tracker.activeHash)
        assertFalse("already ended", tracker.onOfferAbsent(confirm * 2))
    }

    @Test
    fun `identical offer after the session ended starts a new session`() {
        val tracker = OfferSessionTracker()
        tracker.onOfferSeen("A", 0)
        tracker.onOfferAbsent(confirm)
        assertTrue(tracker.onOfferSeen("A", 10_000))
    }

    @Test
    fun `different offer replaces the active one`() {
        val tracker = OfferSessionTracker()
        assertTrue(tracker.onOfferSeen("A", 0))
        assertTrue(tracker.onOfferSeen("B", 300))
        assertEquals("B", tracker.activeHash)
        assertFalse(tracker.onOfferSeen("B", 600))
    }

    @Test
    fun `absence with no active session does nothing`() {
        val tracker = OfferSessionTracker()
        assertFalse(tracker.onOfferAbsent(10_000))
        assertFalse(tracker.hasActiveSession)
    }

    @Test
    fun `reset clears the active session`() {
        val tracker = OfferSessionTracker()
        tracker.onOfferSeen("A", 0)
        tracker.reset()
        assertNull(tracker.activeHash)
        assertTrue(tracker.onOfferSeen("A", 100))
    }
}
