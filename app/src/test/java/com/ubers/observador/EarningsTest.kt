package com.ubers.observador

import com.ubers.observador.domain.WorkdayClock
import com.ubers.observador.domain.earnings.AssignCheck
import com.ubers.observador.domain.earnings.AssignRejection
import com.ubers.observador.domain.earnings.EarningsFormat
import com.ubers.observador.domain.earnings.EarningsRange
import com.ubers.observador.domain.earnings.EarningsReports
import com.ubers.observador.domain.earnings.EarningsWindows
import com.ubers.observador.domain.earnings.Goals
import com.ubers.observador.domain.earnings.ManualAssignment
import com.ubers.observador.domain.finance.AmountSemantics
import com.ubers.observador.domain.finance.AmountSemantics.ABSOLUTE_TOTAL
import com.ubers.observador.domain.finance.AmountSemantics.DELTA
import com.ubers.observador.domain.finance.EarningsPeriods
import com.ubers.observador.domain.finance.FinancialAnomaly
import com.ubers.observador.domain.finance.FinancialCompleteness
import com.ubers.observador.domain.finance.FinancialObservation
import com.ubers.observador.domain.finance.FinancialObservationType
import com.ubers.observador.domain.finance.FinancialObservationType.PROMOTION
import com.ubers.observador.domain.finance.FinancialObservationType.TIP
import com.ubers.observador.domain.finance.FinancialObservationType.TRIP_TOTAL
import com.ubers.observador.domain.finance.FinancialScope
import com.ubers.observador.domain.finance.FinancialSource
import com.ubers.observador.domain.finance.InMemoryFinancialLedger
import com.ubers.observador.domain.finance.LinkConfidence
import com.ubers.observador.domain.finance.TripFinancialSummary
import com.ubers.observador.domain.money.Currencies
import com.ubers.observador.domain.money.MoneyAmount
import com.ubers.observador.domain.trips.Trip
import com.ubers.observador.domain.trips.TripOfferSnapshot
import com.ubers.observador.domain.trips.TripStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Fase 8C: Ganancias periods, goals with an honest pace, and manual assignment without double counting. */
class EarningsTest {

    private val zone = ZoneId.of("America/Santo_Domingo")
    private val clock = WorkdayClock(4, zone)
    private val minute = 60_000L
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) = LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()
    private fun dop(minor: Long) = MoneyAmount(minor, Currencies.DOP)

    private fun trip(id: String, completedAt: Long?, offered: Double? = 150.0, status: TripStatus = TripStatus.COMPLETED) = Trip(
        id = id, lifecycleSessionId = "s-$id", relatedOfferHash = null, status = status, recovered = false,
        startedFromKnownOffer = offered != null, acceptedAt = completedAt?.minus(20 * minute), completedAt = completedAt,
        createdAt = (completedAt ?: 0L) - 25 * minute, updatedAt = completedAt ?: 0L,
        offer = offered?.let { TripOfferSnapshot(it, 1.0, 3, 5.0, 15, 70, (completedAt ?: 0L) - 25 * minute) }
    )

    private fun obs(
        type: FinancialObservationType, semantics: AmountSemantics, minor: Long, atMs: Long,
        id: String = "TEST-o-$type-$minor-$atMs", trip: String? = null, confidence: Double = 0.95,
        scope: FinancialScope = if (trip != null) FinancialScope.TRIP else FinancialScope.UNKNOWN,
        currency: String = Currencies.DOP, voids: String? = null
    ) = FinancialObservation(
        observationId = id, observedAtMs = atMs, lifecycleSessionId = null, tripId = trip, type = type,
        semantics = semantics, amount = MoneyAmount(minor, currency), scope = scope, confidence = confidence,
        evidence = listOf("TEST"), source = FinancialSource.TEST, screenType = "TEST",
        linkConfidence = if (trip != null) LinkConfidence.HIGH else LinkConfidence.LOW, voidsObservationId = voids
    )

    private fun confirmed(id: String, minor: Long, tip: Long = 0) = TripFinancialSummary(
        tripId = id, currencyCode = Currencies.DOP, offeredAmount = dop(15_000), latestAbsoluteTotal = dop(minor),
        effectiveEarnings = dop(minor), tip = tip, completeness = FinancialCompleteness.CONFIRMED
    )

    // ---- Periods (decision 6: week from Monday at the workday start) ----

    @Test fun dayWindowFollowsTheWorkdayStart() {
        val now = at(2026, 10, 11, 2) // Sunday 2:00 belongs to Saturday's working day
        val w = EarningsWindows.of(EarningsRange.DAY, 0, now, clock)
        assertEquals(LocalDate.of(2026, 10, 10), w.firstDay)
        assertEquals(at(2026, 10, 10, 4), w.startMs)
        assertEquals(at(2026, 10, 11, 4), w.endMs)
        assertEquals("Hoy", EarningsFormat.windowLabel(w))
        assertEquals("Ayer", EarningsFormat.windowLabel(EarningsWindows.of(EarningsRange.DAY, -1, now, clock)))
    }

    @Test fun weekStartsMondayAtFourAndSundayWhenChosen() {
        val now = at(2026, 10, 11, 2) // working day Saturday 10
        val w = EarningsWindows.of(EarningsRange.WEEK, 0, now, clock)
        assertEquals(LocalDate.of(2026, 10, 5), w.firstDay)
        assertEquals(LocalDate.of(2026, 10, 11), w.lastDay)
        assertEquals(at(2026, 10, 5, 4), w.startMs)
        assertEquals(at(2026, 10, 12, 4), w.endMs)
        assertEquals(7, w.days.size)
        val previous = EarningsWindows.of(EarningsRange.WEEK, -1, now, clock)
        assertEquals(LocalDate.of(2026, 9, 28), previous.firstDay)
        assertEquals(w.startMs, previous.endMs)
        val sunday = EarningsWindows.of(EarningsRange.WEEK, 0, now, clock, DayOfWeek.SUNDAY)
        assertEquals(LocalDate.of(2026, 10, 4), sunday.firstDay)
        // Monday 3:00 is still Sunday's working day: last day of the Monday week
        val mondayEarly = EarningsWindows.of(EarningsRange.WEEK, 0, at(2026, 10, 12, 3), clock)
        assertEquals(LocalDate.of(2026, 10, 5), mondayEarly.firstDay)
    }

    @Test fun monthWindowsAndNoFuture() {
        val now = at(2026, 10, 13, 12)
        val m = EarningsWindows.of(EarningsRange.MONTH, -1, now, clock)
        assertEquals(LocalDate.of(2026, 9, 1), m.firstDay)
        assertEquals(LocalDate.of(2026, 9, 30), m.lastDay)
        assertEquals(30, m.days.size)
        assertEquals(EarningsWindows.of(EarningsRange.MONTH, 0, now, clock), EarningsWindows.of(EarningsRange.MONTH, 3, now, clock))
        val feb = EarningsWindows.of(EarningsRange.MONTH, -8, now, clock)
        assertEquals(28, feb.days.size)
    }

    @Test fun daysLeftCountsToday() {
        val now = at(2026, 10, 7, 12) // Wednesday
        val w = EarningsWindows.of(EarningsRange.WEEK, 0, now, clock)
        assertEquals(5, EarningsWindows.daysLeft(w, now, clock))
        assertEquals(0, EarningsWindows.daysLeft(EarningsWindows.of(EarningsRange.WEEK, -1, now, clock), now, clock))
    }

    // ---- Report ----

    @Test fun reportSumsRealMoneyOnlyAndBarsAddUp() {
        val now = at(2026, 10, 7, 20)
        val w = EarningsWindows.of(EarningsRange.WEEK, 0, now, clock)
        val trips = listOf(
            trip("t1", at(2026, 10, 5, 9)), trip("t2", at(2026, 10, 6, 10)), trip("t3", at(2026, 10, 6, 11)),
            trip("t4", at(2026, 10, 7, 1)), // Wednesday 1:00 = Tuesday's working day
            trip("old", at(2026, 10, 4, 10)), trip("open", null, status = TripStatus.IN_TRIP)
        )
        val summaries = mapOf("t1" to confirmed("t1", 20_000), "t2" to confirmed("t2", 15_000, tip = 2_000), "t4" to confirmed("t4", 30_000), "old" to confirmed("old", 99_999))
        val promo = obs(PROMOTION, DELTA, 5_000, at(2026, 10, 6, 12), scope = FinancialScope.PERIOD)
        val looseTip = obs(TIP, DELTA, 3_000, at(2026, 10, 6, 12, 5))
        val r = EarningsReports.build(w, clock, trips, summaries, listOf(promo, looseTip), Currencies.DOP)
        assertEquals(70_000, r.summary.totalObservedEarnings) // 200 + 150 + 300 + promo 50, never the loose tip
        assertEquals(4, r.summary.completedTripCount)
        assertEquals(3, r.summary.confirmedTripCount)
        assertTrue(EarningsFormat.isMinimum(r.summary))
        assertEquals("3 de 4 viajes con dinero confirmado", EarningsFormat.coverage(r.summary))
        assertEquals(r.summary.totalObservedEarnings, r.bars.sumOf { it.incomeMinor })
        val tuesday = r.bars.first { it.day == LocalDate.of(2026, 10, 6) }
        assertEquals(50_000, tuesday.incomeMinor) // 150 + 300 (1:00 Wednesday) + promo 50
        assertEquals(1, tuesday.unconfirmedTrips)
        assertEquals(LocalDate.of(2026, 10, 6), r.bestDay?.day)
        assertEquals(listOf("t4", "t3", "t2", "t1"), r.trips.map { it.tripId })
        val t3 = r.trips.first { it.tripId == "t3" }
        assertNull(t3.real)
        assertNotNull(t3.offered) // the offer stays a reference
        assertEquals(listOf(looseTip.observationId), r.pending.map { it.observation.observationId })
        assertTrue(r.pending.single().assignable)
    }

    @Test fun aVoidWrittenDaysLaterStillCancelsTheAmount() {
        val now = at(2026, 10, 9, 12)
        val promo = obs(PROMOTION, DELTA, 5_000, at(2026, 10, 6, 12), scope = FinancialScope.PERIOD)
        val void = ManualAssignment.reject(promo, now)
        val w = EarningsWindows.of(EarningsRange.DAY, -3, now, clock)
        val r = EarningsReports.build(w, clock, emptyList(), emptyMap(), listOf(promo, void))
        assertEquals(0, r.summary.totalObservedEarnings)
        assertTrue(r.pending.isEmpty())
    }

    @Test fun bestDayIsNullWithoutIncome() {
        val now = at(2026, 10, 7, 12)
        val r = EarningsReports.build(EarningsWindows.of(EarningsRange.WEEK, 0, now, clock), clock, emptyList(), emptyMap(), emptyList())
        assertNull(r.bestDay)
        assertEquals(7, r.bars.size)
    }

    @Test fun lowConfidenceAmountsArePendingButNotAssignable() {
        val low = obs(TIP, DELTA, 3_000, 0, confidence = 0.5)
        val usd = obs(TIP, DELTA, 300, 1, currency = "USD")
        val pending = EarningsReports.pending(listOf(low, usd))
        assertEquals(2, pending.size)
        assertTrue(pending.none { it.assignable })
    }

    // ---- Goals ----

    private fun income(total: Long, confirmedTrips: Int, completed: Int = confirmedTrips) =
        EarningsPeriods.summarize(Currencies.DOP, (1..completed).map { i ->
            if (i <= confirmedTrips) confirmed("g$i", if (i == 1) total else 0)
            else TripFinancialSummary("g$i", null, null, null, null, completeness = FinancialCompleteness.UNKNOWN)
        }, emptyList())

    @Test fun noGoalNoProgress() {
        assertNull(Goals.daily(0, income(10_000, 2), 60 * minute))
        assertNull(Goals.weekly(-5, income(10_000, 2), 3))
    }

    @Test fun remainingIsAlwaysShownAndPaceNeedsEnoughData() {
        val goal = 100_000L
        val p = Goals.daily(goal, income(60_000, 2), 29 * minute)!!
        assertEquals(40_000, p.remainingMinor)
        assertEquals(600, p.permille)
        assertNull(p.pace) // 29 minutes is not enough
        assertNull(Goals.daily(goal, income(60_000, 1), 120 * minute)!!.pace) // one trip is not enough
        assertNotNull(Goals.daily(goal, income(60_000, 2), 30 * minute)!!.pace)
    }

    @Test fun paceIsTheExactDivision() {
        // RD$600 in 90 minutes online: RD$400 per hour; RD$400 left = 60 minutes
        val p = Goals.daily(100_000, income(60_000, 2), 90 * minute)!!
        assertEquals(40_000, p.pace!!.perHourMinor)
        assertEquals(60, p.pace!!.minutesToGoal)
        assertEquals("A tu ritmo de hoy, unas 1 h en línea", EarningsFormat.pace(p.pace!!))
        // Rounded up to the next minute: RD$400.01 left
        assertEquals(61, Goals.daily(100_001, income(60_000, 2), 90 * minute)!!.pace!!.minutesToGoal)
    }

    @Test fun reachedGoalHasNoPace() {
        val p = Goals.daily(50_000, income(60_000, 3), 90 * minute)!!
        assertTrue(p.reached)
        assertEquals(0, p.remainingMinor)
        assertEquals(1000, p.permille)
        assertNull(p.pace)
    }

    @Test fun weeklyGoalSplitsWhatIsLeft() {
        val p = Goals.weekly(1_000_000, income(500_000, 5), 3)!!
        assertEquals(500_000, p.remainingMinor)
        assertEquals(166_667L, p.perDayMinor) // rounded up: a division, not a forecast
        assertNull(Goals.weekly(1_000_000, income(500_000, 5), 0)!!.perDayMinor)
    }

    // ---- Manual assignment ----

    private fun ledger(trips: List<Trip>) = InMemoryFinancialLedger({ null }) { id ->
        trips.firstOrNull { it.id == id }?.offer?.offeredAmount?.let { MoneyAmount.fromLegacyDouble(it, Currencies.DOP) }
    }

    @Test fun candidatesAreTodayAndYesterdayClosestFirst() {
        val seen = at(2026, 10, 7, 12)
        val trips = listOf(
            trip("far", at(2026, 10, 7, 6)), trip("near", at(2026, 10, 7, 11, 50)), trip("yesterday", at(2026, 10, 6, 20)),
            trip("twoDaysAgo", at(2026, 10, 5, 20)), trip("cancelled", at(2026, 10, 7, 11, 55), status = TripStatus.CANCELLED)
        )
        val o = obs(TIP, DELTA, 3_000, seen)
        assertEquals(listOf("near", "far", "yesterday"), ManualAssignment.candidates(o, trips, clock).map { it.id })
    }

    @Test fun tipAfterTotalAddsOnceAndUndoBringsItBack() {
        val t = trip("t1", at(2026, 10, 7, 11))
        val l = ledger(listOf(t))
        l.persist(obs(TRIP_TOTAL, ABSOLUTE_TOTAL, 14_150, at(2026, 10, 7, 11, 1), trip = "t1"))
        val tip = obs(TIP, DELTA, 5_000, at(2026, 10, 7, 11, 30))
        l.persist(tip)
        val w = EarningsWindows.of(EarningsRange.DAY, 0, at(2026, 10, 7, 20), clock)
        fun total() = EarningsReports.build(w, clock, listOf(t), mapOf("t1" to l.rebuild("t1")), l.unassigned()).summary.totalObservedEarnings
        assertEquals(14_150, total())

        val check = ManualAssignment.check(tip, false, t, dop(15_000), l.observations.filter { it.tripId == "t1" })
        val preview = (check as AssignCheck.Ok).preview
        assertNull(preview.replacesTotal)
        assertEquals(dop(19_150), preview.after.effectiveEarnings)

        val now = at(2026, 10, 7, 13)
        ManualAssignment.assign(tip, "t1", now).forEach { l.persist(it) }
        assertEquals(dop(19_150), l.summary("t1")?.effectiveEarnings)
        assertEquals(19_150, total()) // counted once, in the trip
        val report = EarningsReports.build(w, clock, listOf(t), mapOf("t1" to l.rebuild("t1")), l.unassigned())
        assertTrue(report.pending.isEmpty())

        val live = ManualAssignment.live(l.observations)
        assertEquals(1, live.size)
        assertEquals(tip.observationId, live.single().originalId)
        assertTrue(live.single().copy.observationId.startsWith("TEST-")) // test data stays removable

        ManualAssignment.undo(live.single().copy, tip, now + minute).forEach { l.persist(it) }
        assertEquals(dop(14_150), l.summary("t1")?.effectiveEarnings)
        assertEquals(14_150, total())
        assertTrue(ManualAssignment.live(l.observations).isEmpty())
        val back = EarningsReports.pending(l.unassigned())
        assertEquals(1, back.size)
        assertEquals(dop(5_000), back.single().observation.amount)
        assertTrue(back.single().assignable)
        // Nothing was edited or deleted: every step is still in the history
        assertEquals(6, l.observations.size)
    }

    @Test fun totalOverADifferentTotalWarns() {
        val t = trip("t1", at(2026, 10, 7, 11))
        val existing = obs(TRIP_TOTAL, ABSOLUTE_TOTAL, 14_150, at(2026, 10, 7, 11, 1), trip = "t1")
        val loose = obs(TRIP_TOTAL, ABSOLUTE_TOTAL, 16_000, at(2026, 10, 7, 11, 1) + 30_000)
        val preview = (ManualAssignment.check(loose, false, t, dop(15_000), listOf(existing)) as AssignCheck.Ok).preview
        assertEquals(dop(14_150), preview.replacesTotal)
        assertEquals(dop(16_000), preview.after.effectiveEarnings) // the newer total becomes the total
        assertTrue(FinancialAnomaly.CONFLICTING_TOTALS in preview.newAnomalies)
        // The same total again is no conflict
        val same = obs(TRIP_TOTAL, ABSOLUTE_TOTAL, 14_150, at(2026, 10, 7, 11, 3))
        assertNull((ManualAssignment.check(same, false, t, null, listOf(existing)) as AssignCheck.Ok).preview.replacesTotal)
    }

    @Test fun rejectedAssignments() {
        val tip = obs(TIP, DELTA, 3_000, at(2026, 10, 7, 12))
        val open = trip("open", null, status = TripStatus.IN_TRIP)
        assertEquals(AssignRejection.TRIP_NOT_COMPLETED, (ManualAssignment.check(tip, false, open, null, emptyList()) as AssignCheck.Rejected).reason)
        val done = trip("t1", at(2026, 10, 7, 11))
        assertEquals(AssignRejection.NOT_PENDING, (ManualAssignment.check(tip, true, done, null, emptyList()) as AssignCheck.Rejected).reason)
        val low = obs(TIP, DELTA, 3_000, at(2026, 10, 7, 12), confidence = 0.4)
        assertEquals(AssignRejection.NOT_ASSIGNABLE, (ManualAssignment.check(low, false, done, null, emptyList()) as AssignCheck.Rejected).reason)
        val usdTotal = obs(TRIP_TOTAL, ABSOLUTE_TOTAL, 1_000, at(2026, 10, 7, 11, 1), trip = "t1", currency = "USD")
        assertEquals(AssignRejection.CURRENCY_MISMATCH, (ManualAssignment.check(tip, false, done, null, listOf(usdTotal)) as AssignCheck.Rejected).reason)
    }

    @Test fun notMineOnlyVoids() {
        val l = ledger(emptyList())
        val tip = obs(TIP, DELTA, 3_000, at(2026, 10, 7, 12))
        l.persist(tip)
        val void = ManualAssignment.reject(tip, at(2026, 10, 7, 13))
        assertEquals(FinancialObservationType.VOID, void.type)
        assertEquals(tip.observationId, void.voidsObservationId)
        assertEquals(0, void.amount.minorUnits)
        l.persist(void)
        assertTrue(EarningsReports.pending(l.unassigned()).isEmpty())
        assertEquals(2, l.observations.size)
        assertFalse(l.observations.any { it.tripId != null })
    }

    @Test fun manualRowsCarryNoScreenText() {
        val tip = obs(TIP, DELTA, 3_000, at(2026, 10, 7, 12))
        val rows = ManualAssignment.assign(tip, "t1", 1L)
        rows.forEach { r -> assertTrue(r.evidence.all { it.matches(Regex("[A-Z_]+(:[A-Za-z0-9-]+)?")) }) }
        assertEquals(FinancialSource.MANUAL, rows[1].source)
        assertEquals(tip.observedAtMs, rows[1].observedAtMs)
    }
}
