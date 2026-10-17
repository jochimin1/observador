package com.ubers.observador

import com.ubers.observador.LifecycleFixtures.text
import com.ubers.observador.LifecycleFixtures.screen
import com.ubers.observador.data.db.OfferEntity
import com.ubers.observador.domain.OfferLedger
import com.ubers.observador.domain.TakenFilter
import com.ubers.observador.domain.WorkdayClock
import com.ubers.observador.domain.earnings.EarningsRange
import com.ubers.observador.domain.earnings.EarningsReports
import com.ubers.observador.domain.earnings.EarningsWindows
import com.ubers.observador.domain.earnings.GoalSource
import com.ubers.observador.domain.earnings.Goals
import com.ubers.observador.domain.earnings.ManualAssignment
import com.ubers.observador.domain.finance.AmountSemantics
import com.ubers.observador.domain.finance.FinancialObservation
import com.ubers.observador.domain.finance.FinancialObservationType
import com.ubers.observador.domain.finance.FinancialScope
import com.ubers.observador.domain.finance.FinancialSource
import com.ubers.observador.domain.finance.LinkConfidence
import com.ubers.observador.domain.lifecycle.ConfidenceLevel
import com.ubers.observador.domain.lifecycle.EvidenceCode
import com.ubers.observador.domain.lifecycle.LifecycleConfig
import com.ubers.observador.domain.lifecycle.LifecycleReplay
import com.ubers.observador.domain.lifecycle.ReplayStep
import com.ubers.observador.domain.lifecycle.RideLifecycleEvent
import com.ubers.observador.domain.lifecycle.RideLifecycleEventType
import com.ubers.observador.domain.lifecycle.RideLifecyclePipeline
import com.ubers.observador.domain.lifecycle.RideLifecycleState
import com.ubers.observador.domain.lifecycle.UberScreenType
import com.ubers.observador.domain.money.Currencies
import com.ubers.observador.domain.money.MoneyAmount
import com.ubers.observador.domain.trips.InMemoryTripLedger
import com.ubers.observador.domain.trips.Trip
import com.ubers.observador.domain.trips.TripEventInput
import com.ubers.observador.domain.trips.TripOfferSnapshot
import com.ubers.observador.domain.trips.TripOutcome
import com.ubers.observador.domain.trips.TripStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * Fase 8H: a trip taken is registered even when the screens after "Llegar por …" are not
 * recognised, the home list tells offered from taken, and Meta de hoy can use Uber's own figure.
 */
class TripRegistrationTest {

    private val minute = 60_000L

    /** What Uber showed after accepting (screenshot of 14/10): the title "Llegar por <name>". */
    private fun llegarPor(name: String = "stefany") = screen(text("Llegar por $name", "title"), text("Mapa"))

    // --- the pickup screen ---

    @Test
    fun `Llegar por is the pickup screen with medium confidence and proven evidence`() {
        val c = LifecycleFixtures.classifier.classify(llegarPor())
        assertEquals(UberScreenType.PICKUP_NAVIGATION, c.type)
        assertEquals(ConfidenceLevel.MEDIUM, c.level)
        val header = c.evidence.single { it.code == EvidenceCode.PICKUP_HEADER }
        assertTrue(header.validated)
        assertFalse(c.provisional)
    }

    @Test
    fun `the guessed pickup phrases stay provisional and alone are still weak`() {
        val c = LifecycleFixtures.classifier.classify(screen(text("Recoger a Ana Ficticia")))
        assertEquals(UberScreenType.PICKUP_NAVIGATION, c.type)
        assertEquals(ConfidenceLevel.LOW, c.level)
        assertTrue(c.provisional)
    }

    @Test
    fun `accepting an offer reaches TO_PICKUP through Llegar por`() {
        val steps = listOf(
            ReplayStep(1_000_000L, LifecycleFixtures.searching()),
            ReplayStep(1_010_000L, LifecycleFixtures.offer(), LifecycleFixtures.OFFER_HASH),
            ReplayStep(1_020_000L, llegarPor())
        )
        val r = LifecycleReplay.run(steps, LifecycleFixtures.classifier)
        assertEquals(RideLifecycleState.TO_PICKUP, r.context.state)
        assertTrue(r.events.any { it.type == RideLifecycleEventType.ACCEPTANCE_CONFIRMED && it.relatedOfferHash == LifecycleFixtures.OFFER_HASH })
    }

    // --- the return to searching closes the trip ---

    @Test
    fun `searching that stays after a trip closes it without calling it completed`() {
        val steps = listOf(
            ReplayStep(1_000_000L, LifecycleFixtures.offer(), LifecycleFixtures.OFFER_HASH),
            ReplayStep(1_010_000L, llegarPor()),
            ReplayStep(1_020_000L, LifecycleFixtures.unknown()),
            ReplayStep(1_300_000L, LifecycleFixtures.searching())
        )
        val r = LifecycleReplay.run(steps, LifecycleFixtures.classifier)
        assertEquals(RideLifecycleState.IDLE, r.context.state)
        val close = r.events.last()
        assertEquals(RideLifecycleEventType.SEARCHING_CONFIRMED, close.type)
        assertEquals(RideLifecycleState.TO_PICKUP, close.from)
        assertNotNull(close.sessionId)
        assertTrue(r.events.none { it.type == RideLifecycleEventType.TRIP_COMPLETED })
    }

    @Test
    fun `one glimpse of searching during a trip changes nothing`() {
        var now = 1_000_000L
        val p = RideLifecyclePipeline(LifecycleFixtures.classifier, { now })
        val events = ArrayList<RideLifecycleEvent>()
        fun feed(s: com.ubers.observador.domain.lifecycle.ScreenSnapshot, hash: String? = null, after: Long = 1_000) {
            now += after
            var result = p.onSnapshot(s, hash)
            events += result.events
            while (true) {
                val wait = result.recheckAfterMs ?: break
                now += wait
                result = p.onSnapshot(s, hash)
                events += result.events
            }
        }
        feed(LifecycleFixtures.offer(), LifecycleFixtures.OFFER_HASH)
        feed(llegarPor())
        assertEquals(RideLifecycleState.TO_PICKUP, p.context.state)
        // The searching frame is seen once, then the ride screen is back 10 s later
        now += 1_000
        events += p.onSnapshot(LifecycleFixtures.searching(), null).events
        now += 10_000
        events += p.onSnapshot(llegarPor("stefany"), null).events
        now += 60_000
        events += p.onSnapshot(LifecycleFixtures.unknown(), null).events
        assertEquals(RideLifecycleState.TO_PICKUP, p.context.state)
        assertTrue(events.none { it.type == RideLifecycleEventType.SEARCHING_CONFIRMED })
    }

    @Test
    fun `an offer card in the middle of the wait restarts the count`() {
        var now = 1_000_000L
        val p = RideLifecyclePipeline(LifecycleFixtures.classifier, { now }, LifecycleConfig(searchEndsTripMs = 45_000L))
        now += 100
        p.onSnapshot(LifecycleFixtures.offer(), LifecycleFixtures.OFFER_HASH)
        now += 100
        var r = p.onSnapshot(llegarPor(), null)
        while (r.recheckAfterMs != null) { now += r.recheckAfterMs!!; r = p.onSnapshot(llegarPor(), null) }
        now += 1_000
        p.onSnapshot(LifecycleFixtures.searching(), null)
        now += 30_000
        p.onSnapshot(LifecycleFixtures.offer(price = "DOP90.00"), "OTHER")
        now += 30_000
        val after = p.onSnapshot(LifecycleFixtures.searching(), null)
        assertTrue(after.events.none { it.type == RideLifecycleEventType.SEARCHING_CONFIRMED })
        assertEquals(RideLifecycleState.TO_PICKUP, p.context.state)
    }

    // --- the stored trip and what counts ---

    private var tripIds = 0
    private val ledger = InMemoryTripLedger({ "trip-${++tripIds}" }, { "side-${++tripIds}" })
    private val offer = TripOfferSnapshot(136.06, 4.1, 7, 7.2, 11, 76, 900L)
    private var eventNo = 0

    private fun send(type: RideLifecycleEventType, from: RideLifecycleState, to: RideLifecycleState, at: Long, session: String = "s1") =
        ledger.persist(TripEventInput("e${++eventNo}", session, "H-$session", type, from, to, at, "PICKUP_NAVIGATION", 0.75, false, listOf("PICKUP_HEADER")), offer = offer)

    @Test
    fun `a trip closed by searching is stored INTERRUPTED without anomaly and counts as done but unconfirmed`() {
        send(RideLifecycleEventType.ACCEPTANCE_CONFIRMED, RideLifecycleState.OFFER_VISIBLE, RideLifecycleState.TO_PICKUP, 1_000)
        send(RideLifecycleEventType.SEARCHING_CONFIRMED, RideLifecycleState.TO_PICKUP, RideLifecycleState.IDLE, 90_000)
        val t = ledger.tripForSession("s1")!!
        assertEquals(TripStatus.INTERRUPTED, t.status)
        assertFalse(t.hasAnomalies)
        assertEquals(90_000L, TripOutcome.endedAt(t))
        assertFalse(TripOutcome.isConfirmed(t))
    }

    private fun base(id: String, status: TripStatus, accepted: Long? = 1_000L) = Trip(
        id = id, lifecycleSessionId = "l-$id", relatedOfferHash = "h-$id", status = status, recovered = false,
        startedFromKnownOffer = true, acceptedAt = accepted, createdAt = accepted ?: 0L, updatedAt = 100_000L, offer = offer
    )

    @Test
    fun `only the right trips count as done`() {
        assertEquals(50_000L, TripOutcome.endedAt(base("a", TripStatus.COMPLETED).copy(completedAt = 50_000L)))
        assertEquals(60_000L, TripOutcome.endedAt(base("b", TripStatus.INTERRUPTED).copy(interruptedAt = 60_000L)))
        // A second trip began: flagged, not trusted
        assertNull(TripOutcome.endedAt(base("c", TripStatus.INTERRUPTED).copy(interruptedAt = 60_000L, hasAnomalies = true)))
        // Never accepted nor heading to the rider
        assertNull(TripOutcome.endedAt(base("d", TripStatus.INTERRUPTED, accepted = null).copy(interruptedAt = 60_000L)))
        assertNull(TripOutcome.endedAt(base("e", TripStatus.CANCELLED).copy(cancelledAt = 60_000L)))
        assertNull(TripOutcome.endedAt(base("f", TripStatus.IN_TRIP)))
    }

    private val zone = ZoneId.of("America/Santo_Domingo")
    private val clock = WorkdayClock(4, zone)
    private fun at(h: Int, m: Int = 0) = java.time.LocalDateTime.of(2026, 10, 14, h, m).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `Ganancias lists the unconfirmed trip, counts it and adds no income`() {
        val done = base("a", TripStatus.COMPLETED).copy(completedAt = at(10), acceptedAt = at(9, 40))
        val interrupted = base("b", TripStatus.INTERRUPTED).copy(interruptedAt = at(11), acceptedAt = at(10, 40))
        val window = EarningsWindows.of(EarningsRange.DAY, 0, at(12), clock)
        val report = EarningsReports.build(window, clock, listOf(done, interrupted), emptyMap(), emptyList())
        assertEquals(2, report.trips.size)
        assertEquals(listOf(true, false).sorted(), report.trips.map { it.endConfirmed }.sorted())
        assertEquals(2, report.summary.completedTripCount)
        assertEquals(0, report.summary.confirmedTripCount)
        assertEquals(0L, report.summary.totalObservedEarnings)
    }

    @Test
    fun `an unconfirmed trip can receive a manual amount`() {
        val interrupted = base("b", TripStatus.INTERRUPTED).copy(interruptedAt = at(11), acceptedAt = at(10, 40))
        val amount = FinancialObservation(
            observationId = "o1", observedAtMs = at(11, 5), lifecycleSessionId = null, tripId = null,
            type = FinancialObservationType.TRIP_TOTAL, semantics = AmountSemantics.ABSOLUTE_TOTAL,
            amount = MoneyAmount(15_000, Currencies.DOP), scope = FinancialScope.UNKNOWN, confidence = 0.9,
            evidence = emptyList(), source = FinancialSource.UBER_SCREEN, screenType = "TRIP_EARNINGS",
            linkConfidence = LinkConfidence.LOW, breakdownGroupId = null
        )
        assertEquals(listOf("b"), ManualAssignment.candidates(amount, listOf(interrupted), clock).map { it.id })
    }

    // --- Meta de hoy with Uber's figure ---

    @Test
    fun `Uber's HOY figure raises the goal only when it is higher`() {
        val own = Goals.progress(1_000_000, 0)
        val withUber = Goals.withUberFigure(own, 693_000)!!
        assertEquals(693_000L, withUber.earnedMinor)
        assertEquals(307_000L, withUber.remainingMinor)
        assertEquals(GoalSource.UBER, withUber.source)
        assertNull(withUber.pace)
        val keeps = Goals.withUberFigure(Goals.progress(1_000_000, 800_000), 693_000)!!
        assertEquals(GoalSource.OWN, keeps.source)
        assertEquals(800_000L, keeps.earnedMinor)
        assertNull(Goals.withUberFigure(null, 1_000))
        assertEquals(GoalSource.OWN, Goals.withUberFigure(own, null)!!.source)
    }

    // --- Todo / Ofertados / Tomados ---

    private fun offerRow(id: Long, hash: String, at: Long) = OfferEntity(
        id = id, offerHash = hash, detectedAt = at, price = 100.0, pickupKm = 1.0, pickupMin = 3, tripKm = 5.0, tripMin = 12,
        totalKm = 6.0, totalMin = 15, rdpPerKm = 16.0, rdpPerHour = 400.0, fuelCost = 10.0, netProfit = 90.0, score = 70
    )

    @Test
    fun `the home list tells offered from taken`() {
        val offers = listOf(offerRow(1, "h-a", 3_000), offerRow(2, "h-x", 2_000), offerRow(3, "h-b", 1_000))
        val trips = listOf(
            base("a", TripStatus.COMPLETED).copy(completedAt = 5_000L),
            base("b", TripStatus.CANCELLED).copy(cancelledAt = 4_000L)
        )
        val ledger = OfferLedger.of(offers, trips)
        assertEquals(3, ledger.counts.all)
        assertEquals(2, ledger.counts.taken)
        assertEquals(1, ledger.counts.offered)
        assertEquals(listOf(1L, 3L), ledger.filtered(TakenFilter.TAKEN).map { it.id })
        assertEquals(listOf(2L), ledger.filtered(TakenFilter.OFFERED).map { it.id })
        assertEquals(TripStatus.CANCELLED, ledger.tripStatus[3L])
        assertEquals(3, ledger.filtered(TakenFilter.ALL).size)
    }

    @Test
    fun `a taken trip whose offer row is gone still shows in the list`() {
        val trips = listOf(base("a", TripStatus.INTERRUPTED).copy(interruptedAt = 7_000L))
        val ledger = OfferLedger.of(emptyList(), trips)
        assertEquals(1, ledger.counts.taken)
        assertEquals(0, ledger.counts.offered)
        assertEquals(136.06, ledger.rows.single().price, 0.0)
        assertEquals(TripStatus.INTERRUPTED, ledger.tripStatus[ledger.rows.single().id])
    }

    @Test
    fun `with two trips for one offer the newest decides`() {
        val offers = listOf(offerRow(1, "h-a", 3_000))
        val older = base("a", TripStatus.INTERRUPTED).copy(updatedAt = 10_000L, interruptedAt = 9_000L)
        val newer = base("a2", TripStatus.COMPLETED).copy(relatedOfferHash = "h-a", updatedAt = 20_000L, completedAt = 19_000L)
        assertEquals(TripStatus.COMPLETED, OfferLedger.of(offers, listOf(older, newer)).tripStatus[1L])
    }
}
