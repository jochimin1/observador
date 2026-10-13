package com.ubers.observador

import com.ubers.observador.domain.CompletedTrips
import com.ubers.observador.domain.online.OnlineSession
import com.ubers.observador.domain.online.OnlineStartKind
import com.ubers.observador.domain.online.TripWaits
import com.ubers.observador.domain.online.WaitBucket
import com.ubers.observador.domain.trips.Trip
import com.ubers.observador.domain.trips.TripOfferSnapshot
import com.ubers.observador.domain.trips.TripStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/** Home screen with trips done only, and waits between trips for Análisis. */
class TripsViewTest {

    private val min = 60_000L
    private val hour = 60 * min
    private val t0 = 1_800_000_000_000L - 1_800_000_000_000L % (24 * hour) + 10 * hour // a day at 10:00 UTC

    private fun trip(
        id: String, accepted: Long?, completed: Long?, status: TripStatus = TripStatus.COMPLETED,
        cancelled: Long? = null, offer: Boolean = true
    ) = Trip(
        id = id, lifecycleSessionId = "l-$id", relatedOfferHash = if (offer) "h-$id" else null, status = status,
        recovered = accepted == null, startedFromKnownOffer = offer, acceptedAt = accepted, completedAt = completed,
        cancelledAt = cancelled, createdAt = accepted ?: completed ?: 0, updatedAt = completed ?: cancelled ?: 0,
        offer = if (offer) TripOfferSnapshot(150.0, 1.5, 4, 6.0, 15, 70, (accepted ?: 0) - 20_000) else null
    )

    private fun session(start: Long, end: Long) = OnlineSession(
        "s$start", start, OnlineStartKind.EXACT, end, endedAt = end, possibleEndAt = end, createdAt = start, updatedAt = end
    )

    // --- home: trips done only ---

    @Test fun onlyCompletedTripsWithTheirOfferBecomeRows() {
        val trips = listOf(
            trip("a", t0, t0 + 20 * min),
            trip("b", t0 + hour, null, TripStatus.CANCELLED, cancelled = t0 + hour + 5 * min),
            trip("c", null, t0 + 2 * hour, offer = false),
            trip("d", t0 + 3 * hour, t0 + 3 * hour + 25 * min)
        )
        val rows = CompletedTrips.asOfferRows(trips)
        assertEquals(listOf(t0 + 3 * hour + 25 * min, t0 + 20 * min), rows.map { it.id })
        val a = rows.last()
        assertEquals(150.0, a.price, 0.0)
        assertEquals(t0 - 20_000, a.detectedAt)
        assertEquals(70, a.score)
        assertEquals("h-a", a.offerHash)
    }

    // --- waits ---

    @Test fun aWaitGoesFromTheEndOfATripToAcceptingTheNext() {
        val trips = listOf(trip("a", t0, t0 + 20 * min), trip("b", t0 + 26 * min, t0 + 40 * min), trip("c", t0 + 50 * min, t0 + 70 * min))
        val waits = TripWaits.of(trips, listOf(session(t0 - min, t0 + 2 * hour)), t0 + 3 * hour)
        assertEquals(listOf(6 * min, 10 * min), waits.map { it.waitMs })
        assertTrue(waits.all { it.inSession })
        assertEquals(t0 + 20 * min, waits.first().startedAt)
    }

    @Test fun cancelledTripsEndAWaitAndJoinedTripsAreSkipped() {
        val trips = listOf(
            trip("a", t0, null, TripStatus.CANCELLED, cancelled = t0 + 5 * min),
            trip("b", t0 + 8 * min, t0 + 30 * min),
            trip("c", null, t0 + 50 * min, offer = false)
        )
        val waits = TripWaits.of(trips, emptyList(), t0 + hour)
        assertEquals(listOf(3 * min), waits.map { it.waitMs })
        assertTrue(!waits.single().inSession)
    }

    @Test fun aLongGapWithNoSessionIsABreakNotAWait() {
        val trips = listOf(trip("a", t0, t0 + 20 * min), trip("b", t0 + 80 * min, t0 + 90 * min))
        assertTrue(TripWaits.of(trips, emptyList(), t0 + 2 * hour).isEmpty())
        // The same gap inside one online session is a real wait
        assertEquals(60 * min, TripWaits.of(trips, listOf(session(t0 - min, t0 + 2 * hour)), t0 + 2 * hour).single().waitMs)
    }

    @Test fun overlappingTripsGiveNoNegativeWait() {
        val trips = listOf(trip("a", t0, t0 + 30 * min), trip("b", t0 + 20 * min, t0 + 50 * min))
        assertTrue(TripWaits.of(trips, emptyList(), t0 + hour).isEmpty())
    }

    @Test fun summaryHasAverageMedianLongestBucketsAndSlots() {
        // Three waits at 10:xx (2, 4, 6 min) and three at 14:xx (20, 25, 40 min)
        val trips = listOf(
            trip("a", t0, t0 + 10 * min), trip("b", t0 + 12 * min, t0 + 30 * min),
            trip("c", t0 + 34 * min, t0 + 50 * min), trip("d", t0 + 56 * min, t0 + 70 * min),
            trip("e", t0 + 4 * hour, t0 + 4 * hour + 10 * min), trip("f", t0 + 4 * hour + 30 * min, t0 + 4 * hour + 40 * min),
            trip("g", t0 + 4 * hour + 65 * min, t0 + 4 * hour + 80 * min), trip("h", t0 + 4 * hour + 120 * min, t0 + 4 * hour + 130 * min)
        )
        val waits = TripWaits.of(trips, listOf(session(t0 - min, t0 + 8 * hour)), t0 + 9 * hour)
        val s = TripWaits.summarize(waits, ZoneOffset.UTC)
        // 2, 4, 6, 20, 25, 40 plus the 170 min between d and e
        assertEquals(7, s.count)
        assertEquals(20 * min, s.medianMs)
        assertEquals(170 * min, s.longestMs)
        assertEquals(2, s.buckets[WaitBucket.SHORT])
        assertEquals(1, s.buckets[WaitBucket.MEDIUM])
        assertEquals(2, s.buckets[WaitBucket.LONG])
        assertEquals(2, s.buckets[WaitBucket.VERY_LONG])
        assertEquals(10, s.fastestSlot!!.startHour)
        assertEquals(5 * min, s.fastestSlot!!.medianMs) // 2, 4, 6 and the 170 that began at 11:10
        assertEquals(14, s.slowestSlot!!.startHour)
    }

    @Test fun noWaitsIsAnEmptySummary() {
        val s = TripWaits.summarize(emptyList(), ZoneOffset.UTC)
        assertEquals(0, s.count)
        assertNull(s.medianMs)
        assertNull(s.fastestSlot)
    }
}
