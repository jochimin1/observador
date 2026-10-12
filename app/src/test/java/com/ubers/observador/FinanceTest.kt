package com.ubers.observador

import com.ubers.observador.LifecycleFixtures.screen
import com.ubers.observador.LifecycleFixtures.text
import com.ubers.observador.data.debug.FinancialTestSeeder
import com.ubers.observador.data.debug.TripTestSeeder
import com.ubers.observador.domain.finance.AmountSemantics
import com.ubers.observador.domain.finance.AmountSemantics.ABSOLUTE_TOTAL
import com.ubers.observador.domain.finance.AmountSemantics.COMPONENT
import com.ubers.observador.domain.finance.AmountSemantics.DELTA
import com.ubers.observador.domain.finance.EarningsPeriods
import com.ubers.observador.domain.finance.FinancialAnomaly
import com.ubers.observador.domain.finance.FinancialCompleteness
import com.ubers.observador.domain.finance.FinancialCorrelator
import com.ubers.observador.domain.finance.FinancialDetector
import com.ubers.observador.domain.finance.FinancialIds
import com.ubers.observador.domain.finance.FinancialObservation
import com.ubers.observador.domain.finance.FinancialObservationType
import com.ubers.observador.domain.finance.FinancialObservationType.ADJUSTMENT
import com.ubers.observador.domain.finance.FinancialObservationType.FARE
import com.ubers.observador.domain.finance.FinancialObservationType.PROMOTION
import com.ubers.observador.domain.finance.FinancialObservationType.TIP
import com.ubers.observador.domain.finance.FinancialObservationType.TRIP_TOTAL
import com.ubers.observador.domain.finance.FinancialPersistResult
import com.ubers.observador.domain.finance.FinancialScope
import com.ubers.observador.domain.finance.FinancialScreenType
import com.ubers.observador.domain.finance.FinancialSource
import com.ubers.observador.domain.finance.InMemoryFinancialLedger
import com.ubers.observador.domain.finance.LinkConfidence
import com.ubers.observador.domain.finance.PlatformEarningsSnapshot
import com.ubers.observador.domain.finance.PlatformPeriodType
import com.ubers.observador.domain.finance.Reconciler
import com.ubers.observador.domain.finance.ReconciliationStatus
import com.ubers.observador.domain.finance.TripFinancialAggregator
import com.ubers.observador.domain.finance.TripFinancialSummary
import com.ubers.observador.domain.lifecycle.RideLifecycleContext
import com.ubers.observador.domain.lifecycle.RideLifecycleState
import com.ubers.observador.domain.lifecycle.UberScreenType
import com.ubers.observador.domain.money.Currencies
import com.ubers.observador.domain.money.MoneyAmount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** Fase 7C: real money, never the offer; no double counting; no guessing. */
class FinanceTest {

    private val minute = 60_000L
    private fun dop(minor: Long) = MoneyAmount(minor, Currencies.DOP)

    private fun obs(
        type: FinancialObservationType,
        semantics: AmountSemantics,
        minor: Long,
        at: Long,
        id: String = "o-$type-$semantics-$minor-$at",
        trip: String? = "t1",
        confidence: Double = 0.95,
        group: String? = null,
        currency: String = Currencies.DOP,
        voids: String? = null,
        scope: FinancialScope = FinancialScope.TRIP
    ) = FinancialObservation(
        observationId = id, observedAtMs = at, lifecycleSessionId = null, tripId = trip, type = type,
        semantics = semantics, amount = MoneyAmount(minor, currency), scope = scope, confidence = confidence,
        evidence = listOf("TEST"), source = FinancialSource.TEST, screenType = "TEST",
        linkConfidence = if (trip != null) LinkConfidence.HIGH else LinkConfidence.LOW,
        breakdownGroupId = group, voidsObservationId = voids
    )

    private fun agg(vararg o: FinancialObservation, offered: MoneyAmount? = dop(13_606)) =
        TripFinancialAggregator.aggregate("t1", offered, o.toList())

    // ---- Effective total (spec 20-23 and the double-count rule) ----

    @Test fun simpleTotal() {
        val s = agg(obs(TRIP_TOTAL, ABSOLUTE_TOTAL, 14_150, 0))
        assertEquals(dop(14_150), s.effectiveEarnings)
        assertEquals(FinancialCompleteness.CONFIRMED, s.completeness)
    }

    @Test fun absolutePlusDelta() {
        val s = agg(obs(TRIP_TOTAL, ABSOLUTE_TOTAL, 10_000, 0), obs(TIP, DELTA, 2_000, 5 * minute))
        assertEquals(dop(12_000), s.effectiveEarnings)
        assertEquals(2_000L, s.tip)
    }

    @Test fun newAbsoluteAbsorbsEarlierDeltaNever140() {
        val s = agg(
            obs(TRIP_TOTAL, ABSOLUTE_TOTAL, 10_000, 0),
            obs(TIP, DELTA, 2_000, 5 * minute),
            obs(TRIP_TOTAL, ABSOLUTE_TOTAL, 12_000, 10 * minute)
        )
        assertEquals(dop(12_000), s.effectiveEarnings)
        assertTrue(s.anomalies.isEmpty())
    }

    @Test fun deltaAfterNewAbsoluteCounts() {
        val s = agg(
            obs(TRIP_TOTAL, ABSOLUTE_TOTAL, 10_000, 0),
            obs(TIP, DELTA, 2_000, 5 * minute),
            obs(TRIP_TOTAL, ABSOLUTE_TOTAL, 12_000, 10 * minute),
            obs(TIP, DELTA, 1_000, 15 * minute)
        )
        assertEquals(dop(13_000), s.effectiveEarnings)
    }

    @Test fun componentsAreNeverAddedToTheTotal() {
        val s = agg(
            obs(FARE, COMPONENT, 12_150, 0, group = "g"),
            obs(TIP, COMPONENT, 2_000, 0, group = "g"),
            obs(TRIP_TOTAL, ABSOLUTE_TOTAL, 14_150, 0)
        )
        assertEquals(dop(14_150), s.effectiveEarnings)
        assertEquals(12_150L, s.fare)
        assertEquals(2_000L, s.tip)
        assertFalse(FinancialAnomaly.COMPONENT_TOTAL_MISMATCH in s.anomalies)
    }

    @Test fun componentsThatDoNotAddUpAreFlagged() {
        val s = agg(obs(FARE, COMPONENT, 12_000, 0, group = "g"), obs(TRIP_TOTAL, ABSOLUTE_TOTAL, 14_150, 0))
        assertEquals(dop(14_150), s.effectiveEarnings)
        assertTrue(FinancialAnomaly.COMPONENT_TOTAL_MISMATCH in s.anomalies)
    }

    @Test fun withoutTotalEarningsStayNullEvenWithOfferKnown() {
        val s = agg(obs(TIP, DELTA, 2_000, 0), obs(FARE, COMPONENT, 12_000, 0, group = "g"))
        assertNull(s.effectiveEarnings)
        assertEquals(FinancialCompleteness.PARTIAL, s.completeness)
        assertEquals(dop(13_606), s.offeredAmount)
    }

    @Test fun completedTripWithNoMoneyIsUnknownNotTheOffer() {
        val s = agg()
        assertNull(s.effectiveEarnings)
        assertEquals(FinancialCompleteness.UNKNOWN, s.completeness)
        assertNull(s.differenceFromOffer())
    }

    @Test fun lateTipUpdatesTheSummary() {
        val before = agg(obs(TRIP_TOTAL, ABSOLUTE_TOTAL, 15_000, 0))
        val after = agg(obs(TRIP_TOTAL, ABSOLUTE_TOTAL, 15_000, 0), obs(TIP, DELTA, 2_000, 180 * minute))
        assertEquals(dop(15_000), before.effectiveEarnings)
        assertEquals(dop(17_000), after.effectiveEarnings)
    }

    @Test fun negativeAdjustmentIsAllowed() {
        val s = agg(obs(TRIP_TOTAL, ABSOLUTE_TOTAL, 17_000, 0), obs(ADJUSTMENT, DELTA, -1_000, 5 * minute))
        assertEquals(dop(16_000), s.effectiveEarnings)
        assertEquals(-1_000L, s.adjustment)
        assertTrue(s.anomalies.isEmpty())
    }

    @Test fun negativeFareOrTotalIsAnAnomalyAndNotSummed() {
        val s = agg(obs(TRIP_TOTAL, ABSOLUTE_TOTAL, 10_000, 0), obs(TIP, DELTA, -2_000, 5 * minute))
        assertEquals(dop(10_000), s.effectiveEarnings)
        assertTrue(FinancialAnomaly.NEGATIVE_UNEXPECTED in s.anomalies)
    }

    @Test fun lowConfidenceIsKeptButNotSummed() {
        val s = agg(obs(TRIP_TOTAL, ABSOLUTE_TOTAL, 10_000, 0), obs(TIP, DELTA, 2_000, 5 * minute, confidence = 0.6))
        assertEquals(dop(10_000), s.effectiveEarnings)
        assertEquals(1, s.pendingReviewCount)
        assertEquals(2, s.observationCount)
    }

    @Test fun otherCurrencyIsNeverMixed() {
        val s = agg(obs(TRIP_TOTAL, ABSOLUTE_TOTAL, 10_000, 0), obs(TIP, DELTA, 500, 5 * minute, currency = Currencies.USD))
        assertEquals(dop(10_000), s.effectiveEarnings)
        assertTrue(FinancialAnomaly.CURRENCY_MISMATCH in s.anomalies)
    }

    @Test fun twoDifferentTotalsAtOnceAreAConflict() {
        val s = agg(obs(TRIP_TOTAL, ABSOLUTE_TOTAL, 10_000, 0), obs(TRIP_TOTAL, ABSOLUTE_TOTAL, 11_000, 20_000))
        assertTrue(FinancialAnomaly.CONFLICTING_TOTALS in s.anomalies)
    }

    @Test fun totalGoingDownWithoutAdjustmentIsARegression() {
        val s = agg(obs(TRIP_TOTAL, ABSOLUTE_TOTAL, 12_000, 0), obs(TRIP_TOTAL, ABSOLUTE_TOTAL, 11_000, 30 * minute))
        assertEquals(dop(11_000), s.effectiveEarnings)
        assertTrue(FinancialAnomaly.TOTAL_REGRESSION in s.anomalies)
        val explained = agg(
            obs(TRIP_TOTAL, ABSOLUTE_TOTAL, 12_000, 0),
            obs(ADJUSTMENT, DELTA, -1_000, 10 * minute),
            obs(TRIP_TOTAL, ABSOLUTE_TOTAL, 11_000, 30 * minute)
        )
        assertFalse(FinancialAnomaly.TOTAL_REGRESSION in explained.anomalies)
        assertEquals(dop(11_000), explained.effectiveEarnings)
    }

    @Test fun voidCancelsAnObservationWithoutDeletingIt() {
        val tip = obs(TIP, DELTA, 2_000, 5 * minute, id = "tip")
        val s = agg(
            obs(TRIP_TOTAL, ABSOLUTE_TOTAL, 10_000, 0), tip,
            obs(FinancialObservationType.VOID, DELTA, 0, 6 * minute, id = "v", voids = "tip")
        )
        assertEquals(dop(10_000), s.effectiveEarnings)
    }

    @Test fun orderOfArrivalDoesNotChangeTheResult() {
        val list = listOf(
            obs(TRIP_TOTAL, ABSOLUTE_TOTAL, 10_000, 0),
            obs(TIP, DELTA, 2_000, 5 * minute),
            obs(TRIP_TOTAL, ABSOLUTE_TOTAL, 12_000, 10 * minute),
            obs(TIP, DELTA, 1_000, 15 * minute)
        )
        val a = TripFinancialAggregator.aggregate("t1", null, list)
        val b = TripFinancialAggregator.aggregate("t1", null, list.reversed())
        assertEquals(a, b)
    }

    // ---- Offer vs real (spec 31, 61) ----

    @Test fun offerIsNeverIncome() {
        val s = agg(obs(TRIP_TOTAL, ABSOLUTE_TOTAL, 15_000, 0), offered = dop(13_606))
        val (diff, pct) = s.differenceFromOffer()!!
        assertEquals(dop(1_394), diff)
        assertEquals("10.2", pct.toPlainString())
    }

    @Test fun zeroOfferHasNoPercentage() {
        assertNull(agg(obs(TRIP_TOTAL, ABSOLUTE_TOTAL, 15_000, 0), offered = dop(0)).differenceFromOffer())
    }

    // ---- Dedup, ids, linking (spec 26-28, 32-35) ----

    @Test fun sameReadingHasTheSameIdAndIsStoredOnce() {
        val a = FinancialIds.observationId("s1", TRIP_TOTAL, ABSOLUTE_TOTAL, dop(14_150), "TRIP_EARNINGS", 1_000)
        val b = FinancialIds.observationId("s1", TRIP_TOTAL, ABSOLUTE_TOTAL, dop(14_150), "TRIP_EARNINGS", 9_000_000)
        assertEquals(a, b)
        val ledger = InMemoryFinancialLedger({ if (it == "s1") "t1" else null }, { null })
        val o = obs(TRIP_TOTAL, ABSOLUTE_TOTAL, 14_150, 0, id = a, trip = null).copy(lifecycleSessionId = "s1", linkConfidence = LinkConfidence.HIGH)
        val results = List(50) { ledger.persist(o) }
        assertTrue(results.first() is FinancialPersistResult.Stored)
        assertEquals(49, results.count { it is FinancialPersistResult.DuplicateIgnored })
        assertEquals(1, ledger.observations.size)
        assertEquals(dop(14_150), ledger.summary("t1")!!.effectiveEarnings)
    }

    @Test fun unlinkedSameValueLaterIsANewReading() {
        val a = FinancialIds.observationId(null, TIP, DELTA, dop(2_000), "TIP_NOTICE", 0)
        val b = FinancialIds.observationId(null, TIP, DELTA, dop(2_000), "TIP_NOTICE", 60 * minute)
        assertFalse(a == b)
    }

    @Test fun lowLinkStaysUnassignedNeverGuessed() {
        val ledger = InMemoryFinancialLedger({ "t1" }, { null })
        val o = obs(TIP, DELTA, 2_000, 0, trip = null).copy(lifecycleSessionId = "s1", linkConfidence = LinkConfidence.LOW)
        ledger.persist(o)
        assertEquals(1, ledger.unassigned().size)
        assertNull(ledger.summary("t1"))
    }

    @Test fun highLinkWithoutStoredTripIsUnassigned() {
        val ledger = InMemoryFinancialLedger({ null }, { null })
        ledger.persist(obs(TRIP_TOTAL, ABSOLUTE_TOTAL, 10_000, 0, trip = null).copy(lifecycleSessionId = "s9", linkConfidence = LinkConfidence.HIGH))
        assertEquals(LinkConfidence.LOW, ledger.unassigned().single().linkConfidence)
    }

    @Test fun correlatorLinksOnlyAJustEndedTrip() {
        val ended = RideLifecycleContext(state = RideLifecycleState.COMPLETED, sessionId = "s1", lastTransitionAt = 0)
        assertEquals("s1" to LinkConfidence.HIGH, FinancialCorrelator.link(FinancialScreenType.TRIP_EARNINGS, ended, 5 * minute))
        assertEquals(null to LinkConfidence.LOW, FinancialCorrelator.link(FinancialScreenType.TRIP_EARNINGS, ended, 11 * minute))
        assertEquals(null to LinkConfidence.LOW, FinancialCorrelator.link(FinancialScreenType.TIP_NOTICE, ended, minute))
        val riding = ended.copy(state = RideLifecycleState.IN_TRIP)
        assertEquals(null to LinkConfidence.LOW, FinancialCorrelator.link(FinancialScreenType.TRIP_EARNINGS, riding, minute))
    }

    @Test fun rebuildEqualsIncrementalSummary() {
        val ledger = InMemoryFinancialLedger({ null }, { dop(13_606) })
        val list = listOf(
            obs(TRIP_TOTAL, ABSOLUTE_TOTAL, 15_000, 0),
            obs(TIP, DELTA, 2_000, 20 * minute),
            obs(TRIP_TOTAL, ABSOLUTE_TOTAL, 17_000, 30 * minute),
            obs(ADJUSTMENT, DELTA, -1_000, 35 * minute)
        )
        list.forEach { ledger.persist(it) }
        val incremental = ledger.summary("t1")
        assertEquals(incremental, ledger.rebuild("t1"))
        assertEquals(dop(16_000), incremental!!.effectiveEarnings)
    }

    // ---- Detector (spec 12-16) ----

    private val detector = FinancialDetector()
    private val ended = RideLifecycleContext(state = RideLifecycleState.COMPLETED, sessionId = "s1", lastTransitionAt = 0)

    @Test fun earningsScreenAfterTripIsLinkedTotalWithBreakdown() {
        val snap = screen(text("Ganaste"), text("RD$141.50"), text("Tarifa RD$121.50"), text("Propina RD$20.00"))
        val d = detector.detect(snap, UberScreenType.TRIP_SUMMARY, ended, minute, ZoneId.of("America/Santo_Domingo"))
        assertEquals(FinancialScreenType.TRIP_EARNINGS, d.classification.type)
        val total = d.observations.single { it.semantics == ABSOLUTE_TOTAL }
        assertEquals(dop(14_150), total.amount)
        assertEquals(LinkConfidence.HIGH, total.linkConfidence)
        assertEquals(2, d.observations.count { it.semantics == COMPONENT })
        assertTrue(d.observations.all { "PROVISIONAL_SIGNATURE" in it.evidence })
    }

    @Test fun offerPriceOnSummaryIsNotIncome() {
        // The 7A summary fixture shows the offer price with no label: never stored as money
        val d = detector.detect(LifecycleFixtures.summary(), UberScreenType.TRIP_SUMMARY, ended, minute, ZoneId.of("UTC"))
        assertTrue(d.observations.isEmpty())
        assertEquals(1, d.unlabeledAmounts)
    }

    @Test fun offerScreenIsIgnored() {
        val d = detector.detect(LifecycleFixtures.offer(), UberScreenType.OFFER, ended, minute, ZoneId.of("UTC"))
        assertEquals(FinancialScreenType.UNKNOWN, d.classification.type)
        assertTrue(d.observations.isEmpty())
    }

    @Test fun largestAmountIsNeverAssumedToBeTheTotal() {
        val snap = screen(text("Ganaste"), text("Detalles"), text("RD$500.00"), text("RD$141.50"))
        val d = detector.detect(snap, UberScreenType.TRIP_SUMMARY, ended, minute, ZoneId.of("UTC"))
        assertTrue(d.observations.none { it.amount == dop(50_000) })
    }

    @Test fun twoDifferentTotalsOnOneScreenAreDropped() {
        val snap = screen(text("Total RD$141.50"), text("Total RD$150.00"), text("Ganaste"))
        val d = detector.detect(snap, UberScreenType.TRIP_SUMMARY, ended, minute, ZoneId.of("UTC"))
        assertTrue(d.observations.none { it.semantics == ABSOLUTE_TOTAL })
        assertTrue(d.ambiguousAmounts >= 2)
    }

    @Test fun tipNoticeIsAnUnlinkedDelta() {
        val snap = screen(text("Recibiste una propina"), text("Propina RD$20.00"))
        val d = detector.detect(snap, UberScreenType.UNKNOWN, ended, 3 * 60 * minute, ZoneId.of("UTC"))
        val tip = d.observations.single()
        assertEquals(DELTA, tip.semantics)
        assertNull(tip.lifecycleSessionId)
        assertEquals(LinkConfidence.LOW, tip.linkConfidence)
    }

    @Test fun dailySummaryIsASnapshotWithUnknownLimits() {
        val snap = screen(text("Ganancias de hoy"), text("RD$1,978.38"))
        val d = detector.detect(snap, UberScreenType.UNKNOWN, RideLifecycleContext(), minute, ZoneId.of("UTC"))
        val s = d.snapshots.single()
        assertEquals(PlatformPeriodType.DAY, s.periodType)
        assertEquals(dop(197_838), s.amount)
        assertNull(s.periodStartMs)
        assertTrue(d.observations.isEmpty())
    }

    @Test fun observationsCarryNoScreenText() {
        val snap = screen(text("Ganaste con Ana Ficticia"), text("RD$141.50"), text("Calle Falsa 123"))
        val d = detector.detect(snap, UberScreenType.TRIP_SUMMARY, ended, minute, ZoneId.of("UTC"))
        val dump = d.toString()
        assertFalse(dump.contains("Ana"))
        assertFalse(dump.contains("Falsa"))
    }

    // ---- Periods and reconciliation (spec 47-59) ----

    private fun confirmed(id: String, minor: Long) = TripFinancialSummary(
        tripId = id, currencyCode = Currencies.DOP, offeredAmount = null, latestAbsoluteTotal = dop(minor),
        effectiveEarnings = dop(minor), completeness = FinancialCompleteness.CONFIRMED
    )
    private fun unknown(id: String) = TripFinancialSummary(
        tripId = id, currencyCode = null, offeredAmount = null, latestAbsoluteTotal = null,
        effectiveEarnings = null, completeness = FinancialCompleteness.UNKNOWN
    )

    @Test fun periodSumsOnlyConfirmedMoney() {
        val p = EarningsPeriods.summarize(Currencies.DOP, listOf(confirmed("a", 14_150), confirmed("b", 16_000), unknown("c")), emptyList())
        assertEquals(30_150L, p.totalObservedEarnings)
        assertEquals(3, p.completedTripCount)
        assertEquals(2, p.confirmedTripCount)
        assertEquals("67", p.coverage!!.toPlainString())
    }

    @Test fun unlinkedPromotionCountsApartUnlinkedTipAndTotalWait() {
        val unassigned = listOf(
            obs(PROMOTION, DELTA, 8_000, 0, trip = null, scope = FinancialScope.PERIOD),
            obs(TIP, DELTA, 3_000, 0, trip = null, scope = FinancialScope.UNKNOWN),
            obs(TRIP_TOTAL, ABSOLUTE_TOTAL, 14_150, 0, trip = null, scope = FinancialScope.UNKNOWN)
        )
        val p = EarningsPeriods.summarize(Currencies.DOP, listOf(confirmed("a", 14_150)), unassigned)
        assertEquals(14_150L, p.tripEarnings)
        assertEquals(8_000L, p.unassignedEarnings)
        assertEquals(22_150L, p.totalObservedEarnings)
        assertEquals(2, p.unlinkedPendingCount)
    }

    private fun snapshot(minor: Long, start: Long? = 0, end: Long? = 100, currency: String = Currencies.DOP, at: Long = 50) =
        PlatformEarningsSnapshot("snap-$minor-$at", at, PlatformPeriodType.DAY, start, end, MoneyAmount(minor, currency), 0.95, "TEST", emptyList())

    private val local = EarningsPeriods.summarize(Currencies.DOP, listOf(confirmed("a", 197_838)), emptyList())

    @Test fun reconciliationMatchesWithinOneCent() {
        assertEquals(ReconciliationStatus.MATCHED, Reconciler.reconcile(local, 0, 100, snapshot(197_838)).status)
        assertEquals(ReconciliationStatus.MATCHED, Reconciler.reconcile(local, 0, 100, snapshot(197_839)).status)
    }

    @Test fun reconciliationShowsADifferenceNeverForcesIt() {
        val r = Reconciler.reconcile(local, 0, 100, snapshot(202_056))
        assertEquals(ReconciliationStatus.DIFFERENCE, r.status)
        assertEquals(dop(4_218), r.difference)
        assertEquals(dop(197_838), r.local)
    }

    @Test fun reconciliationRefusesUnknownPeriodLimits() {
        assertEquals(ReconciliationStatus.PERIOD_MISMATCH, Reconciler.reconcile(local, 0, 100, snapshot(197_838, null, null)).status)
        assertEquals(ReconciliationStatus.NO_REFERENCE, Reconciler.reconcile(local, 0, 100, null).status)
        assertEquals(ReconciliationStatus.CURRENCY_MISMATCH, Reconciler.reconcile(local, 0, 100, snapshot(197_838, currency = Currencies.USD)).status)
    }

    @Test fun incompleteWhenTripsLackMoney() {
        val partial = EarningsPeriods.summarize(Currencies.DOP, listOf(confirmed("a", 100_000), unknown("b")), emptyList())
        assertEquals(ReconciliationStatus.INCOMPLETE, Reconciler.reconcile(partial, 0, 100, snapshot(197_838)).status)
    }

    @Test fun snapshotsAreNeverAddedAsIncome() {
        // Three readings of Uber's day: the period figure stays Observador's own sum
        val p = EarningsPeriods.summarize(Currencies.DOP, listOf(confirmed("a", 50_000)), emptyList())
        assertEquals(50_000L, p.totalObservedEarnings)
        val regressions = Reconciler.regressions(listOf(snapshot(50_000, at = 1), snapshot(190_000, at = 2), snapshot(185_000, at = 3)))
        assertEquals(1, regressions.size)
    }

    // ---- TEST seeder (the debug data the phone will show) ----

    @Test fun seederMatchesTheSpecScenarios() {
        val now = 100 * 60 * minute
        val trips = TripTestSeeder.generate(now).trips
        val seed = FinancialTestSeeder.generate(trips, now, 0, now + 60 * minute)
        assertTrue(seed.observations.all { it.observationId.startsWith(TripTestSeeder.PREFIX) })
        assertTrue(seed.snapshots.all { it.snapshotId.startsWith(TripTestSeeder.PREFIX) })
        val expected = FinancialTestSeeder.expectedSummaries(trips, seed)
        assertEquals(dop(14_150), expected.getValue("TEST-trip-1").effectiveEarnings)
        assertEquals(dop(16_000), expected.getValue("TEST-trip-5").effectiveEarnings)
        assertFalse("TEST-trip-2" in expected)
        val equal = seed.snapshots.first()
        assertEquals(14_150L + 16_000L + 8_000L, equal.amount.minorUnits)
        assertEquals(equal.amount.minorUnits + 3_000, seed.snapshots.last().amount.minorUnits)
    }
}
