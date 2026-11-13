package com.ubers.observador

import com.ubers.observador.domain.WorkdayClock
import com.ubers.observador.domain.distance.*
import com.ubers.observador.domain.earnings.*
import com.ubers.observador.domain.expenses.*
import com.ubers.observador.domain.finance.*
import com.ubers.observador.domain.money.MoneyAmount
import com.ubers.observador.domain.online.*
import com.ubers.observador.domain.operating.*
import com.ubers.observador.domain.trips.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class OperatingCostsTest {
    private val hour = 3_600_000L
    private val clock = WorkdayClock(4, ZoneId.of("UTC"))
    private val day = LocalDate.of(2026, 11, 13)
    private val start = clock.startOf(day)
    private val end = clock.startOf(day.plusDays(1))
    private val window = EarningsWindows.of(EarningsRange.DAY, 0, start + hour, clock)
    private val model = OperatingCostModel("m1", 1, day.toString(), start, start, "UTC", 4, 30_000, 100_000, 150, 0, 4_000, FixedCostBasis.WORKDAY)
    private val trip = Trip("t1", "s1", null, TripStatus.COMPLETED, false, false,
        acceptedAt = start+hour, completedAt = start+2*hour, createdAt = start+hour, updatedAt = start+2*hour)
    private val summary = TripFinancialSummary("t1", "DOP", MoneyAmount(99_999, "DOP"), MoneyAmount(20_000, "DOP"), MoneyAmount(20_000, "DOP"), completeness = FinancialCompleteness.CONFIRMED)
    private val distance = TripDistance("s1", 2_000_000, 8_000_000, 0, TripDistanceCoverage.FULL, start+2*hour)
    private val segment = DistanceSegment("d1", day, start, start+4*hour, start+4*hour, SegmentOrigin.AUTO_ONLINE,
        measuredMm = 15_000_000, passengerMm = 8_000_000, pickupMm = 2_000_000, emptyMm = 5_000_000, fixesUsed = 100, createdAt = start)
    private val session = OnlineSession("o1", start, OnlineStartKind.EXACT, start+4*hour, endedAt = start+4*hour, createdAt = start, updatedAt = start+4*hour)
    private fun income(s: TripFinancialSummary = summary) = EarningsPeriods.summarize("DOP", listOf(s), emptyList())
    private fun report(m: List<OperatingCostModel> = listOf(model), ts: List<Trip> = listOf(trip),
        sums: Map<String, TripFinancialSummary> = mapOf("t1" to summary), ds: Map<String, TripDistance> = mapOf("t1" to distance),
        seg: List<DistanceSegment> = listOf(segment), ss: List<OnlineSession> = listOf(session),
        expenses: List<ExpenseEntry> = emptyList(), money: EarningsPeriodSummary = income()) =
        OperatingCosts.report(window, clock, end, m, ts, sums, ds, seg, ss, expenses, money)

    @Test fun exampleTripAndPoolReconcileExactly() {
        val r = report()
        assertTrue(r.issues.toString(), r.issues.isEmpty())
        assertEquals(14_500L, r.trips.single().profitMinor)
        assertEquals(5_500L, r.trips.single().cost.total)
        assertEquals(5_250L, r.unallocated.total)
        assertEquals(10_750L, r.costs.total)
        assertEquals(9_250L, r.profitMinor)
        assertEquals(r.costs.total, r.trips.sumOf { it.cost.total } + r.unallocated.total)
        assertEquals(2_313L, r.perOnlineHour)
        assertEquals(9_250L, r.perCommittedHour)
        assertEquals(617L, r.perKm)
    }

    @Test fun fuelWearAndDepreciationUseExactDistanceAndNoFloatingPoint() {
        val p = OperatingCosts.variable(model.copy(depreciationPerKmMinor = 100), 10_000_000)
        assertEquals(OperatingCostParts(3_000, 1_500, 1_000), p)
        assertEquals(1L, OperatingCosts.variable(model.copy(fuelPriceMinor = 100, kmPerGallonMilli = 100_000), 500_000).fuel)
    }

    @Test fun hourAndWorkdayFixedBasesMatchWhenBudgetsMatch() {
        val hourly = report(m = listOf(model.copy(fixedMinor = 1_000, fixedBasis = FixedCostBasis.HOUR)))
        assertEquals(report().costs, hourly.costs)
        assertEquals(4_000L, hourly.costs.fixed)
    }

    @Test fun eachCentIsAllocatedOnceWithStableTiesAndLargeWeights() {
        val weights = mapOf("c" to 1L, "a" to 1L, "b" to 1L)
        assertEquals(mapOf("a" to 1L, "b" to 1L, "c" to 0L), OperatingMath.allocate(2, weights))
        val large = OperatingMath.allocate(10_000_000, mapOf("a" to Long.MAX_VALUE, "b" to Long.MAX_VALUE))
        assertEquals(10_000_000L, large.values.sum())
        assertEquals(0L, OperatingMath.allocate(100, mapOf("a" to 0L)).getValue("a"))
    }

    @Test fun pickupAndPassengerBucketsAreNotAddedAgainAndOfflineKmAreExcluded() {
        val r = report(seg = listOf(segment.copy(measuredMm = 115_000_000, offlineMm = 100_000_000)))
        assertEquals(15_000_000L, r.distanceMm)
        assertEquals(report().costs, r.costs)
    }

    @Test fun personalOfflineGpsGapsDoNotInvalidateCoveredWork() {
        val personal = segment.copy(id = "personal", startedAt = start+5*hour, endedAt = start+7*hour,
            updatedAt = start+7*hour, origin = SegmentOrigin.MANUAL, passengerMm = 0, pickupMm = 0,
            emptyMm = 0, offlineMm = 50_000_000, measuredMm = 50_000_000, gapMm = 1_000_000, secondsWithoutSignal = 120)
        val r = report(seg = listOf(segment, personal))
        assertTrue(r.issues.toString(), r.issues.isEmpty())
        assertEquals(9_250L, r.profitMinor)
        assertEquals(15_000_000L, r.distanceMm)
    }

    @Test fun unknownMoneyNeverUsesOfferedPriceAndCostsAreStillVisible() {
        val unknown = summary.copy(effectiveEarnings = null, latestAbsoluteTotal = null, completeness = FinancialCompleteness.UNKNOWN)
        val r = report(sums = mapOf("t1" to unknown), money = income(unknown))
        assertNull(r.profitMinor)
        assertNull(r.trips.single().profitMinor)
        assertEquals(0L, r.incomeMinor)
        assertTrue(r.costs.total > 0)
        assertTrue(OperatingIssue.MONEY in r.issues)
    }

    @Test fun incompleteTripGpsDoesNotGenerateProfitFromPartialKm() {
        val r = report(ds = mapOf("t1" to distance.copy(coverage = TripDistanceCoverage.PARTIAL)))
        assertNull(r.profitMinor)
        assertNull(r.trips.single().profitMinor)
        assertEquals(0L, r.trips.single().cost.fuel)
        assertTrue(OperatingIssue.TRIP_GPS in r.issues)
    }

    @Test fun missingOnlineTimeAndGpsGapsAreExplained() {
        assertTrue(OperatingIssue.ONLINE in report(ss = emptyList()).issues)
        assertNull(report(ss = emptyList()).profitMinor)
        val gap = report(seg = listOf(segment.copy(gapMm = 1_000, secondsWithoutSignal = 60)))
        assertTrue(OperatingIssue.GPS in gap.issues)
        assertNull(gap.profitMinor)
        assertEquals(0, gap.gpsCoveragePct)
    }

    @Test fun coverageThresholdIsExplicitAndPartialTimeIsNotRoundedToComplete() {
        val enough = report(seg = listOf(segment.copy(endedAt = start+36*hour/10, updatedAt = start+36*hour/10)))
        assertEquals(90, enough.gpsCoveragePct)
        assertNotNull(enough.profitMinor)
        val less = report(seg = listOf(segment.copy(endedAt = start+35*hour/10, updatedAt = start+35*hour/10)))
        assertTrue(OperatingIssue.GPS in less.issues)
        assertNull(less.profitMinor)
    }

    @Test fun recoveredOrMissingAcceptancePreventsAnInventedDuration() {
        val r = report(ts = listOf(trip.copy(acceptedAt = null, recovered = true)))
        assertTrue(OperatingIssue.TIME in r.issues)
        assertNull(r.trips.single().committedMs)
        assertNull(r.profitMinor)
    }

    @Test fun recoveredSessionOrUnobservedTripTimeDoesNotGiveACompleteOnlineRate() {
        assertNull(report(ss = listOf(session.copy(startKind = OnlineStartKind.RECOVERED))).perOnlineHour)
        val late = session.copy(startedAt = start+3*hour)
        val r = report(ss = listOf(late))
        assertTrue(OperatingIssue.ONLINE in r.issues)
        assertNull(r.profitMinor)
    }

    @Test fun unknownDurationAlsoMakesOtherTripsFixedAllocationUncertain() {
        val unknown = trip.copy(id = "t2", lifecycleSessionId = "s2", acceptedAt = null, createdAt = start+3*hour, completedAt = start+4*hour)
        val r = report(ts = listOf(trip, unknown))
        assertTrue(OperatingIssue.TIME in r.trips.first { it.tripId == "t1" }.issues)
        assertNull(r.trips.first { it.tripId == "t1" }.profitMinor)
    }

    @Test fun newFutureParametersCannotRewriteHistoricalTripResults() {
        val next = model.copy(id = "m2", revision = 2, effectiveDay = day.plusDays(1).toString(), effectiveAtMs = end, fuelPriceMinor = 60_000)
        assertEquals(report(), report(m = listOf(model, next)))
        assertEquals(model, OperatingCosts.modelAt(listOf(model, next), end-1))
        assertEquals(next, OperatingCosts.modelAt(listOf(model, next), end))
        assertNull(OperatingCosts.modelAt(listOf(model), start-1))
        assertNull(report(m = emptyList()).profitMinor)
    }

    @Test fun fuelAndMaintenancePaymentsDoNotEnterOperatingCostsAgain() {
        val payments = listOf(
            ExpenseEntry("oil", start+hour, ExpenseType.OIL, 500_000, createdAt = start, updatedAt = start),
            ExpenseEntry("tire", start+hour, ExpenseType.TIRES, 900_000, createdAt = start, updatedAt = start))
        assertEquals(report(), report(expenses = payments))
    }

    @Test fun looseWorkExpensesStayInPoolAndUseTheirModelShare() {
        val spending = ExpenseEntry("parking", start+hour, ExpenseType.PARKING_TOLL, 1_001, createdAt = start, updatedAt = start)
        val r = report(m = listOf(model.copy(looseWorkSharePct = 50)), expenses = listOf(spending))
        assertEquals(501L, r.costs.loose)
        assertEquals(501L, r.unallocated.loose)
        assertEquals(0L, r.trips.single().cost.loose)
        assertEquals(8_749L, r.profitMinor)
    }

    @Test fun invalidModelOrUnsupportedAlgorithmCannotBeTreatedAsZeroCost() {
        listOf(model.copy(algorithm = "FUTURE"), model.copy(kmPerGallonMilli = 0), model.copy(fuelPriceMinor = -1)).forEach {
            val r = report(m = listOf(it))
            assertNull(r.profitMinor)
            assertTrue(OperatingIssue.MODEL in r.issues)
        }
    }

    @Test fun overlappingTripsAndActiveTripPreventWholePeriodProfit() {
        val other = trip.copy(id = "t2", lifecycleSessionId = "s2")
        assertTrue(OperatingIssue.OVERLAP in report(ts = listOf(trip, other)).issues)
        val active = other.copy(status = TripStatus.IN_TRIP, completedAt = null)
        assertTrue(OperatingIssue.OPEN_TRIP in report(ts = listOf(trip, active)).issues)
    }

    @Test fun cancellationNeedsExplicitMoneyIncludingConfirmedZero() {
        val cancelled = trip.copy(status = TripStatus.CANCELLED, cancelledAt = trip.completedAt, completedAt = null)
        val zero = summary.copy(effectiveEarnings = MoneyAmount(0, "DOP"), latestAbsoluteTotal = MoneyAmount(0, "DOP"))
        val r = report(ts = listOf(cancelled), sums = mapOf("t1" to zero), money = income(zero))
        assertEquals(-5_500L, r.trips.single().profitMinor)
        assertEquals(-10_750L, r.profitMinor)
    }

    @Test fun mixedCurrencyDoesNotBecomeDopIncome() {
        val usd = summary.copy(currencyCode = "USD", effectiveEarnings = MoneyAmount(100, "USD"))
        val r = report(sums = mapOf("t1" to usd), money = income(usd))
        assertNull(r.profitMinor)
        assertTrue(OperatingIssue.MONEY in r.issues)
    }

    @Test fun noActivityDoesNotChargeADailyBudgetOrInventProfit() {
        val r = report(ts = emptyList(), sums = emptyMap(), ds = emptyMap(), seg = emptyList(), ss = emptyList(), money = EarningsPeriods.summarize("DOP", emptyList(), emptyList()))
        assertEquals(0L, r.costs.total)
        assertNull(r.profitMinor)
        assertTrue(OperatingIssue.NO_ACTIVITY in r.issues)
    }

    @Test fun inputRejectsAmbiguityAndKeepsThreeEfficiencyDecimals() {
        val input = OperatingCostInput("300,25", "100.125", "1.50", "0", "40", FixedCostBasis.WORKDAY, "100", day.toString())
        assertEquals(listOf(30_025L, 100_125L, 150L, 0L, 4_000L, 100L), input.values())
        assertNull(input.copy(fuelPrice = "1,000.00").values())
        assertNull(input.copy(kmPerGallon = "1.2345").values())
        assertNull(input.copy(fixed = "-1").values())
    }
}
