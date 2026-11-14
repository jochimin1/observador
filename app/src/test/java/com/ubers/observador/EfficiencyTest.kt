package com.ubers.observador

import com.ubers.observador.domain.distance.DistanceSegment
import com.ubers.observador.domain.distance.SegmentOrigin
import com.ubers.observador.domain.efficiency.EffOffer
import com.ubers.observador.domain.efficiency.EffTrip
import com.ubers.observador.domain.efficiency.Efficiency
import com.ubers.observador.domain.efficiency.Shift
import com.ubers.observador.domain.efficiency.TimeUse
import com.ubers.observador.domain.online.OnlineEndReason
import com.ubers.observador.domain.online.OnlineSession
import com.ubers.observador.domain.online.OnlineStartKind
import com.ubers.observador.domain.online.TimeSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** 1.48.0: states, uses, denominators, minimum sample and suggestions of «Horarios y eficiencia». */
class EfficiencyTest {

    private val zone = ZoneId.of("America/Santo_Domingo")
    private val MIN = 60_000L
    private val H = 60 * MIN

    /** 2026-11-10 (a Tuesday) at [h]:[m]. */
    private fun at(day: Int, h: Int, m: Int = 0): Long = LocalDateTime.of(2026, 11, day, h, m).atZone(zone).toInstant().toEpochMilli()

    private fun session(id: String, start: Long, end: Long, gaps: List<TimeSpan> = emptyList(), possibleEnd: Long? = null) = OnlineSession(
        id = id, startedAt = start, startKind = OnlineStartKind.EXACT, lastOnlineSeenAt = end, endedAt = end,
        possibleEndAt = possibleEnd, endReason = OnlineEndReason.WENT_OFFLINE, unobservedMs = gaps.sumOf { it.durationMs },
        gaps = gaps, createdAt = start, updatedAt = end
    )

    private fun trip(
        id: String, accepted: Long?, arrived: Long?, started: Long?, end: Long?, counted: Boolean = true,
        real: Long? = 20_000, seen: Long? = accepted, rdph: Double? = 400.0, product: String? = "Moto"
    ) = EffTrip(id, accepted, arrived, started, end, counted, seen, rdph, product, real)

    private fun offer(t: Long, rdph: Double = 300.0, product: String? = "Moto") = EffOffer(t, rdph, 30.0, 1.2, product)

    private val window = TimeSpan(at(10, 0), at(17, 0))
    private val now = at(17, 0)

    private fun report(
        sessions: List<OnlineSession>, offers: List<EffOffer> = emptyList(), trips: List<EffTrip> = emptyList(),
        segments: List<DistanceSegment> = emptyList(), w: TimeSpan = window
    ) = Efficiency.report(w, zone, now, sessions, offers, trips, segments)

    @Test fun statesAndUsesAddUpExactly() {
        // Tuesday 13:00–17:00 connected, with a 30-minute gap; a trip crossing the 14:00 and 16:00 boundaries
        val s = session("s", at(10, 13), at(10, 17), gaps = listOf(TimeSpan(at(10, 14, 30), at(10, 15, 0))))
        val t = trip("t", accepted = at(10, 13, 50), arrived = at(10, 13, 58), started = at(10, 14, 5), end = at(10, 16, 10))
        val r = report(listOf(s), trips = listOf(t))
        val total = r.total
        assertEquals(window.durationMs, total.readingMs + total.unreadMs + total.offlineMs)
        assertEquals(3 * H + 30 * MIN, total.readingMs)
        assertEquals(30 * MIN, total.unreadMs)
        assertEquals(total.readingMs, TimeUse.entries.sumOf { total.use(it) })
        assertEquals(8 * MIN, total.use(TimeUse.PICKUP))
        assertEquals(7 * MIN, total.use(TimeUse.WAITING))
        // 14:05–16:10 with the passenger, minus the 30 minutes not read
        assertEquals(125 * MIN - 30 * MIN, total.use(TimeUse.PASSENGER))
        // Each slot adds up too
        r.slots.forEach { slot -> assertEquals(slot.readingMs, TimeUse.entries.sumOf { slot.use(it) }) }
        assertEquals(r.slots.sumOf { it.readingMs }, total.readingMs)
        // 12–14 holds 50 min searching + 8 pickup + 2 waiting
        assertEquals(60 * MIN, r.slots[6].readingMs)
        assertEquals(5 * MIN, r.slots[7].use(TimeUse.WAITING))
    }

    @Test fun aTripCrossingMidnightSplitsIntoBothDaysAndShifts() {
        val s = session("s", at(11, 23), at(12, 1))
        val t = trip("t", accepted = at(11, 23, 30), arrived = null, started = at(11, 23, 40), end = at(12, 0, 30))
        val r = report(listOf(s), trips = listOf(t))
        assertEquals(60 * MIN, r.weekdays.getValue(3).readingMs)
        assertEquals(60 * MIN, r.weekdays.getValue(4).readingMs)
        assertEquals(60 * MIN, r.shifts.getValue(Shift.NIGHT).readingMs)
        assertEquals(60 * MIN, r.shifts.getValue(Shift.DAWN).readingMs)
        // No arrival seen: pickup until the trip started, no waiting
        assertEquals(10 * MIN, r.total.use(TimeUse.PICKUP))
        assertEquals(0L, r.total.use(TimeUse.WAITING))
        assertEquals(50 * MIN, r.total.use(TimeUse.PASSENGER))
    }

    @Test fun aRecoveredTripWithoutAcceptanceHasNoPickupTime() {
        val s = session("s", at(10, 8), at(10, 10))
        val t = trip("t", accepted = null, arrived = null, started = at(10, 8, 30), end = at(10, 9), seen = null)
        val r = report(listOf(s), trips = listOf(t))
        assertEquals(0L, r.total.use(TimeUse.PICKUP))
        assertEquals(30 * MIN, r.total.use(TimeUse.PASSENGER))
        assertEquals(90 * MIN, r.total.use(TimeUse.SEARCHING))
        assertEquals(0, r.total.taken)
    }

    @Test fun overlappingTripsAreCountedOnceWithThePassengerFirst() {
        val s = session("s", at(10, 8), at(10, 10))
        val a = trip("a", at(10, 8), at(10, 8, 10), at(10, 8, 15), at(10, 9))
        val b = trip("b", at(10, 8, 50), null, null, null, counted = false, real = null)
        val r = report(listOf(s), trips = listOf(a, b))
        assertEquals(2 * H, r.total.readingMs)
        assertEquals(r.total.readingMs, TimeUse.entries.sumOf { r.total.use(it) })
        assertEquals(45 * MIN, r.total.use(TimeUse.PASSENGER))
    }

    @Test fun noConnectionIsNotObservedNotZeroOffers() {
        val s = session("s", at(10, 13), at(10, 17))
        val r = report(listOf(s), offers = (0 until 6).map { offer(at(10, 13, it * 5)) })
        val dawn = r.slots[2]
        assertEquals(0L, dawn.readingMs)
        assertNull(dawn.offersPerHour)
        assertTrue(dawn.offlineMs > 0)
        // 12–14 has only one hour read: under the minimum, no rate
        assertNull(r.slots[6].offersPerHour)
        // 14–16 read two hours without offers: a real zero
        assertEquals(0.0, r.slots[7].offersPerHour!!, 1e-9)
    }

    @Test fun denominators() {
        val s = session("s", at(10, 8), at(10, 10))
        val offers = (0 until 10).map { offer(at(10, 8) + it * 10 * MIN, rdph = 200.0 + it * 10) }
        val trips = listOf(
            trip("a", at(10, 8, 5), at(10, 8, 10), at(10, 8, 12), at(10, 8, 30), real = 25_000),
            trip("b", at(10, 9), at(10, 9, 5), at(10, 9, 8), at(10, 9, 30), real = null)
        )
        val r = report(listOf(s), offers, trips)
        val slot = r.slots[4]
        assertEquals(5.0, slot.offersPerHour!!, 1e-9)
        assertEquals(0.2, slot.takeRate!!, 1e-9)
        // Only the confirmed RD$250 counts, over 2 hours read; one trip is still pending
        assertEquals(125.0, slot.realPerHour!!, 1e-9)
        assertEquals(1, slot.pending)
        assertEquals(245.0, slot.medianOfferRdpH!!, 1e-9)
        // Fewer than 5 taken: no median of what they offered
        assertNull(slot.medianTakenRdpH)
    }

    @Test fun offeredPriceNeverBecomesResult() {
        val s = session("s", at(10, 8), at(10, 10))
        val offers = (0 until 10).map { offer(at(10, 8) + it * 10 * MIN, rdph = 900.0) }
        val trips = listOf(trip("a", at(10, 8, 5), at(10, 8, 10), at(10, 8, 12), at(10, 8, 30), real = null, rdph = 900.0))
        val r = report(listOf(s), offers, trips)
        assertEquals(0.0, r.slots[4].realPerHour!!, 1e-9)
        assertEquals(0L, r.total.realMinor)
    }

    @Test fun suggestionsNeedSampleAndAreAtMostThree() {
        // Little time: nothing
        val short = report(listOf(session("s", at(10, 8), at(10, 10))))
        assertTrue(short.suggestions.isEmpty())

        // Long days: 06–10 busy every day, 14–18 mostly searching on three days
        val sessions = (10..16).map { d -> session("m$d", at(d, 6), at(d, 10)) } +
            (10..16).map { d -> session("a$d", at(d, 14), at(d, 18)) }
        val trips = (10..16).flatMap { d ->
            (0 until 4).map { i -> trip("m$d-$i", at(d, 6 + i), at(d, 6 + i, 5), at(d, 6 + i, 10), at(d, 6 + i, 55), real = 30_000) } +
                listOf(trip("a$d", at(d, 14), at(d, 14, 5), at(d, 14, 10), at(d, 14, 40), real = 15_000))
        }
        val r = report(sessions, trips = trips, w = TimeSpan(at(10, 0), at(17, 0)))
        assertTrue(r.suggestions.isNotEmpty())
        assertTrue(r.suggestions.size <= Efficiency.MAX_SUGGESTIONS)
        assertTrue(r.suggestions.any { it.text.contains("buscando") })
        assertTrue(r.suggestions.zipWithNext().all { (x, y) -> x.lostMinutes >= y.lostMinutes })
    }

    @Test fun kmAndShifts() {
        val seg = DistanceSegment(
            "d", LocalDate.of(2026, 11, 10), at(10, 8), at(10, 9), at(10, 9), SegmentOrigin.AUTO_ONLINE,
            measuredMm = 10_000_000, gapMm = 500_000, passengerMm = 6_000_000, pickupMm = 1_500_000, emptyMm = 2_500_000, createdAt = at(10, 8)
        )
        val r = report(listOf(session("s", at(10, 8), at(10, 9))), segments = listOf(seg))
        assertEquals(10_000_000L, r.km.measuredMm)
        assertEquals(500_000L, r.km.gapMm)
        assertEquals(Shift.MORNING, Shift.of(6))
        assertEquals(Shift.NIGHT, Shift.of(23))
        assertEquals(Shift.DAWN, Shift.of(0))
    }

    @Test fun productsKeepTheirOwnFigures() {
        val s = session("s", at(10, 8), at(10, 10))
        val offers = (0 until 6).map { offer(at(10, 8, it * 10), product = "Moto") } + (0 until 2).map { offer(at(10, 9, it * 10), product = "Moto Flash") }
        val r = report(listOf(s), offers, listOf(trip("a", at(10, 8, 5), at(10, 8, 10), at(10, 8, 12), at(10, 8, 30), product = "moto")))
        val moto = r.products.first { it.product == "Moto" }
        assertEquals(6, moto.offers)
        assertEquals(1, moto.taken)
        assertNull(r.products.first { it.product == "Moto Flash" }.takeRate)
    }
}
