package com.ubers.observador

import com.ubers.observador.LifecycleFixtures.button
import com.ubers.observador.LifecycleFixtures.screen
import com.ubers.observador.LifecycleFixtures.text
import com.ubers.observador.data.db.toDomain
import com.ubers.observador.data.db.toEntity
import com.ubers.observador.data.debug.OnlineTestSeeder
import com.ubers.observador.data.debug.TripTestSeeder
import com.ubers.observador.domain.WorkdayClock
import com.ubers.observador.domain.finance.EarningsPeriodSummary
import com.ubers.observador.domain.finance.FinancialCompleteness
import com.ubers.observador.domain.finance.PlatformPeriodType
import com.ubers.observador.domain.finance.Reconciler
import com.ubers.observador.domain.finance.ReconciliationStatus
import com.ubers.observador.domain.finance.TripFinancialSummary
import com.ubers.observador.domain.finance.UberTodayPillDetector
import com.ubers.observador.domain.lifecycle.ScreenSnapshot
import com.ubers.observador.domain.lifecycle.UberScreenType
import com.ubers.observador.domain.money.MoneyAmount
import com.ubers.observador.domain.online.OnlineAnomaly
import com.ubers.observador.domain.online.OnlineEarnings
import com.ubers.observador.domain.online.OnlineEndReason
import com.ubers.observador.domain.online.OnlineFormat
import com.ubers.observador.domain.online.OnlineStatusKind
import com.ubers.observador.domain.online.OnlineGapCodec
import com.ubers.observador.domain.online.OnlineMetrics
import com.ubers.observador.domain.online.OnlinePolicy
import com.ubers.observador.domain.online.OnlineReading
import com.ubers.observador.domain.online.OnlineSession
import com.ubers.observador.domain.online.OnlineSessionRules
import com.ubers.observador.domain.online.OnlineSignal
import com.ubers.observador.domain.online.OnlineStartKind
import com.ubers.observador.domain.online.OnlineStateDetector
import com.ubers.observador.domain.online.OnlineTransition
import com.ubers.observador.domain.online.OnlineUpdate
import com.ubers.observador.domain.online.TimeSpan
import com.ubers.observador.domain.trips.Trip
import com.ubers.observador.domain.trips.TripStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Fase 8A: online sessions. The screens are synthetic, built from the labels of the driver's
 * image captures (12/10); they prove the rules, not that the accessibility tree says the same.
 */
class OnlineTest {

    private val min = 60_000L
    private val hour = 60 * min
    private val detector = OnlineStateDetector(LifecycleFixtures.UBER)

    private fun rules(): OnlineSessionRules {
        var n = 0
        return OnlineSessionRules(OnlinePolicy(), newId = { "s${++n}" })
    }

    private fun mapOnline() = screen(text("Estás conectado"), text("DOP 2,069.95"), text("HOY"))
    private fun raisedBar() = screen(text("Estás conectado"), button("DESCONECTARSE"))
    private fun mapOffline() = screen(text("Estás desconectado"))
    private fun home() = screen(text("Buenas tardes"), button("Conectarse"))

    private fun read(s: ScreenSnapshot, type: UberScreenType = UberScreenType.UNKNOWN, confidence: Double = 0.0) =
        detector.read(s, type, confidence)

    private val online get() = read(mapOnline())
    private val offline get() = read(mapOffline())

    /** Two reads 500 ms apart: what a confirmed change takes. */
    private fun OnlineSessionRules.confirm(reading: OnlineReading, atMs: Long): OnlineUpdate {
        onReading(reading, atMs)
        return onReading(reading, atMs + 500)
    }

    // --- signals ---

    @Test fun theFourLabelsAreReadWhole() {
        assertEquals(OnlineSignal.ONLINE, read(mapOnline()).signal)
        assertEquals(OnlineSignal.ONLINE, read(raisedBar()).signal)
        assertEquals(OnlineSignal.OFFLINE, read(mapOffline()).signal)
        assertEquals(OnlineSignal.OFFLINE, read(home()).signal)
        assertEquals(OnlineSignal.ONLINE, read(screen(text("ESTÁS CONECTADO."))).signal)
    }

    @Test fun desconectarseIsNeverConectarse() {
        // "desconectarse" contains "conectarse": a substring match would read the red button as offline
        assertEquals(OnlineSignal.ONLINE, read(screen(button("DESCONECTARSE"))).signal)
        // "estas desconectado" contains "conectado"
        assertEquals(OnlineSignal.OFFLINE, read(screen(text("Estás desconectado"))).signal)
        assertNull(read(screen(text("Conectarse para ver viajes"))).signal)
        assertNull(read(LifecycleFixtures.searching()).signal) // "Desconectarte" is not one of the labels
        assertNull(read(LifecycleFixtures.otherApp()).signal)
    }

    @Test fun mixedReadIsIgnoredAndCancelsNothing() {
        val mixed = read(screen(text("Estás conectado"), text("Estás desconectado")))
        assertTrue(mixed.mixed)
        assertNull(mixed.signal)
        val r = rules()
        r.onReading(online, 0)
        assertEquals(OnlineUpdate.NOTHING, r.onReading(mixed, 200))
        assertEquals(1L, r.diagnostics.mixedReadsIgnored)
        // The pending change survives the mixed frame
        assertEquals(1, r.onReading(online, 500).saves.size)
    }

    @Test fun rideScreensOfMediumOrHighConfidenceMeanOnline() {
        assertEquals(OnlineSignal.ONLINE, read(screen(), UberScreenType.OFFER, 0.75).signal)
        assertFalse(read(screen(), UberScreenType.IN_TRIP, 0.75).immediate)
        assertTrue(read(screen(), UberScreenType.PICKUP_NAVIGATION, 0.90).immediate)
        assertNull(read(screen(), UberScreenType.IN_TRIP, 0.60).signal)
        assertNull(read(screen(), UberScreenType.TRIP_SUMMARY, 0.90).signal)
    }

    // --- confirmation ---

    @Test fun aChangeNeedsTheSameSignalTwice500msApart() {
        val r = rules()
        val first = r.onReading(online, 0)
        assertEquals(500L, first.recheckAfterMs)
        assertTrue(first.saves.isEmpty())
        assertEquals(200L, r.onReading(online, 300).recheckAfterMs)
        val confirmed = r.onReading(online, 500)
        assertEquals(1, confirmed.saves.size)
        assertEquals(0L, confirmed.saves.single().startedAt)
        assertEquals(OnlineSignal.ONLINE, r.confirmed)
    }

    @Test fun aHighRideScreenConfirmsAtOnce() {
        val r = rules()
        val u = r.onReading(read(screen(), UberScreenType.OFFER, 0.90), 1_000)
        assertEquals(1_000L, u.saves.single().startedAt)
    }

    @Test fun aLoneOldSightingIsForgotten() {
        val r = rules()
        r.onReading(online, 0)
        assertTrue(r.onReading(online, 20_000).saves.isEmpty())
        assertEquals(20_000L, r.onReading(online, 20_500).saves.single().startedAt)
    }

    // --- start ---

    @Test fun startIsExactOnlyWhenOfflineWasSeenWithinTolerance() {
        val r = rules()
        r.confirm(offline, 0)
        val s = r.confirm(online, 10 * min).saves.single()
        assertEquals(OnlineStartKind.EXACT, s.startKind)
        assertEquals(10 * min, s.startedAt)

        val late = rules()
        late.confirm(offline, 0)
        assertEquals(OnlineStartKind.RECOVERED, late.confirm(online, 31 * min).saves.single().startKind)
        assertEquals(OnlineStartKind.RECOVERED, rules().confirm(online, 0).saves.single().startKind)
    }

    // --- gaps ---

    @Test fun aGapOf29MinutesCountsAndOneOf31DoesNot() {
        val r = rules()
        r.confirm(online, 0)
        r.onReading(online, 29 * min)
        assertEquals(0L, r.current!!.unobservedMs)
        val u = r.onReading(online, 60 * min)
        assertEquals(listOf(OnlineTransition.GAP_RECORDED), u.transitions)
        val s = r.current!!
        assertEquals(31 * min, s.unobservedMs)
        assertEquals(listOf(TimeSpan(29 * min, 60 * min)), s.gaps)
        assertEquals(29 * min, s.confirmedMs(60 * min))
        assertEquals(60 * min, s.possibleMs(60 * min))
    }

    @Test fun anOpenSessionCountsOnlyWhatWasSeen() {
        val r = rules()
        r.confirm(online, 0)
        r.onReading(online, 10 * min)
        val s = r.current!!
        assertEquals(25 * min, s.confirmedMs(25 * min)) // within tolerance: up to now
        assertEquals(10 * min, s.confirmedMs(50 * min)) // 40 min unseen: up to the last sighting
        assertTrue(s.isStale(50 * min))
    }

    @Test fun heartbeatIsWrittenAtMostOncePerMinute() {
        val r = rules()
        r.confirm(online, 0)
        assertTrue(r.onReading(online, 30_000).saves.isEmpty())
        assertEquals(1, r.onReading(online, 61_000).saves.size)
        assertEquals(61_000L, r.current!!.lastOnlineSeenAt)
    }

    // --- end ---

    @Test fun offlineWithinToleranceEndsAtTheSignal() {
        val r = rules()
        r.confirm(online, 0)
        r.onReading(online, 10 * min)
        val done = r.confirm(offline, 20 * min).saves.single()
        assertEquals(20 * min, done.endedAt)
        assertEquals(20 * min, done.possibleEndAt)
        assertEquals(OnlineEndReason.WENT_OFFLINE, done.endReason)
        assertNull(r.current)
    }

    @Test fun offlineAfterALongGapEndsAtTheLastSighting() {
        val r = rules()
        r.confirm(online, 0)
        r.onReading(online, 10 * min)
        val done = r.confirm(offline, 50 * min).saves.single()
        assertEquals(10 * min, done.endedAt)
        assertEquals(50 * min, done.possibleEndAt)
        assertEquals(10 * min, done.confirmedMs(60 * min))
        assertEquals(50 * min, done.possibleMs(60 * min))
    }

    @Test fun pauseClosesTheSessionAndResumeStartsARecoveredOne() {
        val r = rules()
        r.confirm(online, 0)
        r.onReading(online, 5 * min)
        val done = r.onPaused(7 * min).saves.single()
        assertEquals(OnlineEndReason.PAUSED, done.endReason)
        assertEquals(7 * min, done.endedAt)
        assertNull(r.confirmed)
        // Back from the pause: a change again, confirmed by two reads, and recovered
        assertTrue(r.onReading(online, 20 * min).saves.isEmpty())
        val next = r.onReading(online, 20 * min + 500).saves.single()
        assertEquals("s2", next.id)
        assertEquals(OnlineStartKind.RECOVERED, next.startKind)
    }

    // --- restart ---

    private fun openSession(lastSeen: Long) =
        OnlineSession("old", 0, OnlineStartKind.EXACT, lastSeen, createdAt = 0, updatedAt = lastSeen)

    @Test fun restartWithUberBackWithinToleranceContinuesTheSession() {
        val r = rules()
        assertTrue(r.restore(listOf(openSession(10 * min)), 12 * min).saves.isEmpty())
        val u = r.onReading(online, 30 * min)
        assertEquals(listOf(OnlineTransition.CONTINUED_AFTER_RESTART), u.transitions)
        assertEquals("old", u.saves.single().id)
        assertEquals(30 * min, u.saves.single().lastOnlineSeenAt)
    }

    @Test fun restartWithUberBackLateClosesItAsLostAndStartsAgain() {
        val r = rules()
        r.restore(listOf(openSession(10 * min)), 12 * min)
        val u = r.onReading(online, 41 * min)
        val (lost, next) = u.saves
        assertEquals(OnlineEndReason.OBSERVATION_LOST, lost.endReason)
        assertEquals(10 * min, lost.endedAt)
        assertEquals(OnlineStartKind.RECOVERED, next.startKind)
        assertEquals(41 * min, next.startedAt)
        assertEquals(next, r.current)
    }

    @Test fun restartThenOfflineUsesTheSameEndRule() {
        val r = rules()
        r.restore(listOf(openSession(10 * min)), 12 * min)
        val done = r.confirm(offline, 15 * min).saves.single()
        assertEquals(15 * min, done.endedAt)
        assertEquals(OnlineEndReason.WENT_OFFLINE, done.endReason)
    }

    @Test fun twoOpenSessionsKeepTheNewest() {
        val r = rules()
        val older = openSession(10 * min)
        val newer = OnlineSession("new", 20 * min, OnlineStartKind.RECOVERED, 25 * min, createdAt = 20 * min, updatedAt = 25 * min)
        val closed = r.restore(listOf(older, newer), 26 * min).saves.single()
        assertEquals("old", closed.id)
        assertEquals(OnlineEndReason.SUPERSEDED, closed.endReason)
        assertTrue(OnlineAnomaly.MULTIPLE_OPEN_SESSIONS in closed.anomalies)
        assertEquals("new", r.current!!.id)
    }

    @Test fun aSessionOver24HoursIsFlaggedNeverCut() {
        val r = rules()
        r.confirm(online, 0)
        var t = 0L
        while (t < 25 * hour) {
            t += 20 * min
            r.onReading(online, t)
        }
        val s = r.current!!
        assertTrue(OnlineAnomaly.LONGER_THAN_24H in s.anomalies)
        assertTrue(s.isOpen)
        assertEquals(0L, s.startedAt)
    }

    @Test fun gapsAndAnomaliesSurviveStorage() {
        val gaps = listOf(TimeSpan(10, 20), TimeSpan(30, 45))
        assertEquals(gaps, OnlineGapCodec.decode(OnlineGapCodec.encode(gaps)))
        assertEquals(emptyList<TimeSpan>(), OnlineGapCodec.decode(""))
        assertEquals(listOf(TimeSpan(1, 2)), OnlineGapCodec.decode("1-2;x-3;9-4"))
        val anomalies = setOf(OnlineAnomaly.LONGER_THAN_24H, OnlineAnomaly.MULTIPLE_OPEN_SESSIONS)
        assertEquals(anomalies, OnlineGapCodec.decodeAnomalies(OnlineGapCodec.encodeAnomalies(anomalies)))
    }

    // --- metrics ---

    private fun closed(start: Long, end: Long, kind: OnlineStartKind = OnlineStartKind.EXACT, gaps: List<TimeSpan> = emptyList()) =
        OnlineSession(
            "x$start", start, kind, end, endedAt = end, possibleEndAt = end, endReason = OnlineEndReason.WENT_OFFLINE,
            unobservedMs = gaps.sumOf { it.durationMs }, gaps = gaps, createdAt = start, updatedAt = end
        )

    private fun trip(id: String, accepted: Long?, completed: Long, started: Long? = null) = Trip(
        id = id, lifecycleSessionId = "l-$id", relatedOfferHash = null, status = TripStatus.COMPLETED,
        recovered = accepted == null, startedFromKnownOffer = accepted != null, acceptedAt = accepted,
        tripStartedAt = started, completedAt = completed, createdAt = accepted ?: started ?: completed, updatedAt = completed
    )

    private fun money(id: String, minor: Long?, completeness: FinancialCompleteness) = TripFinancialSummary(
        tripId = id, currencyCode = "DOP", offeredAmount = null, latestAbsoluteTotal = minor?.let { MoneyAmount(it, "DOP") },
        effectiveEarnings = minor?.let { MoneyAmount(it, "DOP") }, completeness = completeness
    )

    private val day = TimeSpan(0, 24 * hour)

    @Test fun busyTimeIsTheUnionOfOverlappingTrips() {
        val s = closed(0, 60 * min)
        val trips = listOf(trip("a", 10 * min, 30 * min), trip("b", 20 * min, 40 * min))
        val m = OnlineMetrics.summarize(day, listOf(s), trips, emptyList(), emptyMap(), 2 * hour)
        assertEquals(60 * min, m.confirmedMs)
        assertEquals(30 * min, m.busyMs)
        assertEquals(30 * min, m.waitingMs)
        assertEquals(50, m.utilizationPercent)
        assertFalse(m.busyIsMinimum)
    }

    @Test fun aTripJoinedWithoutItsAcceptanceIsAMinimum() {
        val s = closed(0, 60 * min)
        val m = OnlineMetrics.summarize(day, listOf(s), listOf(trip("r", null, 20 * min, started = 10 * min)), emptyList(), emptyMap(), 2 * hour)
        assertEquals(10 * min, m.busyMs)
        assertTrue(m.busyIsMinimum)
    }

    @Test fun busyTimeLeavesOutUnseenGaps() {
        val s = closed(0, 90 * min, gaps = listOf(TimeSpan(30 * min, 70 * min)))
        val m = OnlineMetrics.summarize(day, listOf(s), listOf(trip("a", 20 * min, 80 * min)), emptyList(), emptyMap(), 2 * hour)
        assertEquals(50 * min, m.confirmedMs)
        assertEquals(90 * min, m.possibleMs)
        assertTrue(m.hasRange)
        assertEquals(20 * min, m.busyMs) // 20-30 and 70-80
    }

    @Test fun offersPerOnlineHourCountOnlyOffersWhileOnline() {
        val s = closed(0, 2 * hour)
        val offers = listOf(1, 10, 30, 70, 100).map { it * min } + (3 * hour)
        val m = OnlineMetrics.summarize(day, listOf(s), emptyList(), offers, emptyMap(), 4 * hour)
        assertEquals(5, m.offersSeen)
        assertEquals(25L, m.offersPerHourTenths)
    }

    @Test fun ratesNeedAtLeast15ConfirmedMinutes() {
        val m = OnlineMetrics.summarize(day, listOf(closed(0, 10 * min)), emptyList(), listOf(min), emptyMap(), hour)
        assertEquals(1, m.offersSeen)
        assertNull(m.offersPerHourTenths)
        assertNull(m.utilizationPercent)
    }

    @Test fun firstOfferIsMeasuredOnlyFromExactStarts() {
        val exact = closed(0, hour)
        val recovered = closed(2 * hour, 3 * hour, OnlineStartKind.RECOVERED)
        val offers = listOf(4 * min, 2 * hour + min)
        val m = OnlineMetrics.summarize(day, listOf(exact, recovered), emptyList(), offers, emptyMap(), 4 * hour)
        assertEquals(4 * min, m.firstOfferAvgMs)
        assertEquals(1, m.firstOfferSamples)
    }

    @Test fun waitBetweenTripsGoesFromAnEndToTheNextAcceptance() {
        val s = closed(0, 2 * hour)
        val trips = listOf(trip("a", 5 * min, 20 * min), trip("b", 26 * min, 40 * min), trip("c", 50 * min, 60 * min))
        val m = OnlineMetrics.summarize(day, listOf(s), trips, emptyList(), emptyMap(), 3 * hour)
        assertEquals(2, m.waitBetweenTripsSamples)
        assertEquals(8 * min, m.waitBetweenTripsAvgMs) // 6 and 10
    }

    @Test fun earningsPerHourUseConfirmedMoneyAndSayWhenPartial() {
        val s = closed(0, 2 * hour)
        val trips = listOf(trip("a", 5 * min, 20 * min), trip("b", 30 * min, 50 * min))
        val moneyByTrip = mapOf("a" to money("a", 30_000, FinancialCompleteness.CONFIRMED), "b" to money("b", null, FinancialCompleteness.UNKNOWN))
        val e = OnlineMetrics.summarize(day, listOf(s), trips, emptyList(), moneyByTrip, 3 * hour).earnings!!
        assertEquals(MoneyAmount(30_000, "DOP"), e.total)
        assertEquals(MoneyAmount(15_000, "DOP"), e.perHour)
        assertEquals(2, e.completedTrips)
        assertEquals(1, e.confirmedTrips)
        assertTrue(e.partial)
    }

    @Test fun noConfirmedMoneyMeansNoRate() {
        val s = closed(0, 2 * hour)
        val e = OnlineMetrics.summarize(day, listOf(s), listOf(trip("a", 5 * min, 20 * min)), emptyList(), emptyMap(), 3 * hour).earnings!!
        assertNull(e.perHour)
        assertEquals(0L, e.total.minorUnits)
    }

    @Test fun aSessionCrossingTheStartOfTheWorkdayIsSplit() {
        val clock = WorkdayClock(4, ZoneOffset.UTC)
        val d1 = LocalDate.of(2026, 10, 12)
        val start = clock.startOf(d1.plusDays(1)) - hour // 3:00
        val s = closed(start, start + 2 * hour)          // 3:00 to 5:00
        fun dayOf(d: LocalDate) = TimeSpan(clock.startOf(d), clock.startOf(d.plusDays(1)))
        val now = start + 10 * hour
        assertEquals(hour, OnlineMetrics.summarize(dayOf(d1), listOf(s), emptyList(), emptyList(), emptyMap(), now).confirmedMs)
        assertEquals(hour, OnlineMetrics.summarize(dayOf(d1.plusDays(1)), listOf(s), emptyList(), emptyList(), emptyMap(), now).confirmedMs)
    }

    @Test fun nothingOnlineIsNoData() {
        val m = OnlineMetrics.summarize(day, emptyList(), listOf(trip("a", 5 * min, 20 * min)), listOf(min), emptyMap(), hour)
        assertFalse(m.hasData)
        assertEquals(0L, m.confirmedMs)
    }

    // --- Uber's "HOY" pill ---

    private val pill = UberTodayPillDetector()
    private fun pillOf(s: ScreenSnapshot, type: UberScreenType = UberScreenType.UNKNOWN) = pill.detect(s, type, 1_000, ZoneOffset.UTC)

    @Test fun todayPillIsReadWithTheExactLabelBesideTheAmount() {
        val next = pillOf(mapOnline())!!
        assertEquals(MoneyAmount(206_995, "DOP"), next.amount)
        assertEquals(PlatformPeriodType.DAY, next.periodType)
        assertNull(next.periodStartMs)
        assertEquals(UberTodayPillDetector.SCREEN_TYPE, next.screenType)
        assertEquals(MoneyAmount(206_995, "DOP"), pillOf(screen(text("DOP 2,069.95 · HOY")))!!.amount)
        assertEquals(MoneyAmount(206_995, "DOP"), pillOf(screen(text("HOY ▾"), text("DOP 2,069.95")))!!.amount)
        val split = pillOf(screen(text("DOP"), text("2,069.95"), text("HOY")))!!
        assertEquals(MoneyAmount(206_995, "DOP"), split.amount)
        assertTrue(UberTodayPillDetector.CURRENCY_IN_OWN_NODE in split.evidence)
    }

    @Test fun todayPillIsIgnoredWithoutItsLabel() {
        assertNull(pillOf(screen(text("Estás conectado"), text("DOP 2,069.95"))))
        assertNull(pillOf(screen(text("DOP 2,069.95"), text("Hoy ganaste mucho"))))
        assertNull(pillOf(screen(text("DOP 2,069.95"), text("SEMANA"))))
        assertNull(pillOf(screen(text("DOP 136.06"), text("HOY")), UberScreenType.OFFER))
        // Two different figures labelled "hoy": neither is the day
        assertNull(pillOf(screen(text("DOP 10.00"), text("HOY"), text("DOP 20.00"), text("HOY"))))
    }

    @Test fun todayPillIsAReferenceNeverIncome() {
        val reference = pillOf(mapOnline())!!
        val local = EarningsPeriodSummary("DOP", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        // Uber's day has unknown limits: never compared with a 4:00-4:00 working day
        assertEquals(ReconciliationStatus.PERIOD_MISMATCH, Reconciler.reconcile(local, 0, 24 * hour, reference).status)
    }

    // --- a whole working day ---

    @Test fun replayOfAWorkday() {
        val r = rules()
        val t0 = 8 * hour
        r.confirm(read(home()), t0)                                   // 8:00 home, "Conectarse"
        val start = r.confirm(online, t0 + min).saves.single()          // 8:01 "Estás conectado"
        assertEquals(OnlineStartKind.EXACT, start.startKind)
        r.onReading(read(screen(), UberScreenType.OFFER, 0.90), t0 + 10 * min)       // offer
        r.onReading(read(screen(), UberScreenType.IN_TRIP, 0.90), t0 + 20 * min)     // in trip
        r.onReading(online, t0 + 40 * min)                              // back on the map
        // Uber in the background for 40 minutes, then back
        assertEquals(listOf(OnlineTransition.GAP_RECORDED), r.onReading(online, t0 + 80 * min).transitions)
        r.onReading(read(raisedBar()), t0 + 85 * min)
        val done = r.confirm(offline, t0 + 90 * min).saves.single()
        assertEquals(t0 + 90 * min, done.endedAt)
        assertEquals(40 * min, done.unobservedMs)
        assertEquals(49 * min, done.confirmedMs(t0 + 2 * hour))        // 89 min minus the 40 unseen
        assertEquals(89 * min, done.possibleMs(t0 + 2 * hour))

        val tripA = trip("a", t0 + 10 * min, t0 + 30 * min)
        val m = OnlineMetrics.summarize(day, listOf(done), listOf(tripA), listOf(t0 + 10 * min), emptyMap(), t0 + 2 * hour)
        assertEquals(20 * min, m.busyMs)
        assertEquals(29 * min, m.waitingMs)
        assertEquals(1, m.offersSeen)
        assertEquals(9 * min, m.firstOfferAvgMs)
    }

    // --- texts ---

    @Test fun durationsAreCutToTheMinute() {
        assertEquals("menos de 1 min", OnlineFormat.duration(59_000))
        assertEquals("45 min", OnlineFormat.duration(45 * min + 59_000))
        assertEquals("1 h 25 min", OnlineFormat.duration(85 * min))
        assertEquals("2 h", OnlineFormat.duration(2 * hour))
        assertEquals("3.4", OnlineFormat.tenths(34))
    }

    @Test fun statusLineNeverConfusesUberWithThePause() {
        assertEquals(OnlineStatusKind.OFFLINE, OnlineFormat.status(null, 0).kind)
        val open = OnlineSession("s", 0, OnlineStartKind.EXACT, 80 * min, createdAt = 0, updatedAt = 80 * min)
        assertEquals("En línea en Uber · 1 h 25 min", OnlineFormat.status(open, 85 * min).text)
        assertEquals("En línea en Uber · al menos 1 h 25 min", OnlineFormat.status(open.copy(startKind = OnlineStartKind.RECOVERED), 85 * min).text)
        val stale = OnlineFormat.status(open, 125 * min)
        assertEquals(OnlineStatusKind.NOT_SEEN_LATELY, stale.kind)
        assertEquals("Uber sin ver desde hace 45 min", stale.text)
    }

    @Test fun earningsTextsAlwaysSayTheCoverage() {
        val partial = OnlineEarnings(MoneyAmount(30_000, "DOP"), MoneyAmount(15_000, "DOP"), 5, 3)
        assertEquals("RD$150.00/h", OnlineFormat.earningsPerHour(partial))
        assertEquals("Parcial: 3 de 5 viajes con dinero", OnlineFormat.coverage(partial))
        val none = OnlineEarnings(MoneyAmount(0, "DOP"), null, 2, 0)
        assertEquals("—", OnlineFormat.earningsPerHour(none))
        assertEquals("Sin dinero confirmado todavía", OnlineFormat.coverage(none))
    }

    // --- TEST data ---

    @Test fun testSessionsAreClosedPrefixedAndCoverTheTestTrips() {
        val now = 1_800_000_000_000L
        val sessions = OnlineTestSeeder.generate(now)
        assertEquals(sessions, OnlineTestSeeder.generate(now))
        assertTrue(sessions.all { it.id.startsWith(OnlineTestSeeder.PREFIX) && !it.isOpen })
        val trips = TripTestSeeder.generate(now).trips
        val m = OnlineMetrics.summarize(TimeSpan(now - 6 * hour, now), sessions, trips, emptyList(), emptyMap(), now)
        assertTrue(m.busyMs > 0)
        assertTrue(m.hasRange)
        assertEquals(3, m.sessionCount)
        assertEquals(1, m.recoveredSessionCount)
    }

    @Test fun aSessionSurvivesTheDatabaseRow() {
        OnlineTestSeeder.generate(1_800_000_000_000L).forEach { s -> assertEquals(s, s.toEntity().toDomain()) }
        val open = OnlineSession("o", 5, OnlineStartKind.RECOVERED, 9, createdAt = 5, updatedAt = 9)
        assertEquals(open, open.toEntity().toDomain())
        assertNull(open.toEntity().endedAt)
    }
}
