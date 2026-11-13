package com.ubers.observador

import com.ubers.observador.domain.WorkdayClock
import com.ubers.observador.domain.earnings.*
import com.ubers.observador.domain.finance.*
import com.ubers.observador.domain.finance.AmountSemantics.*
import com.ubers.observador.domain.finance.FinancialObservationType.*
import com.ubers.observador.domain.money.MoneyAmount
import com.ubers.observador.domain.trips.*
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class ManualMoneyTest {
    @Test fun explicitPeriodRejectsInvalidDatesAndAmbiguousWallTimes() {
        assertNotNull(ExplicitMoneyPeriod.parse("2026-11-12 00:00", "2026-11-13 00:00", ZoneId.of("UTC")))
        assertNull(ExplicitMoneyPeriod.parse("2026-02-30 00:00", "2026-03-01 00:00", ZoneId.of("UTC")))
        assertNull(ExplicitMoneyPeriod.parse("2026-11-12 00:00", "2026-11-12 00:00", ZoneId.of("UTC")))
        assertNull(ExplicitMoneyPeriod.parse("2026-11-01 01:30", "2026-11-02 00:00", ZoneId.of("America/New_York")))
        assertNull(ExplicitMoneyPeriod.parse("2026-03-08 02:30", "2026-03-09 00:00", ZoneId.of("America/New_York")))
    }
    private val trip = Trip("t", "s", null, TripStatus.COMPLETED, false, false,
        completedAt = 3_000, createdAt = 1_000, updatedAt = 3_000)
    private fun screen(type: FinancialObservationType, semantics: AmountSemantics, minor: Long, at: Long) =
        FinancialObservation("screen-$at", at, "s", "t", type, semantics, MoneyAmount(minor, "DOP"),
            FinancialScope.TRIP, 1.0, emptyList(), FinancialSource.UBER_SCREEN, null, LinkConfidence.HIGH)
    private fun request(id: String, type: FinancialObservationType, semantics: AmountSemantics, value: String, replaces: String? = null) =
        ManualMoneyRequest(id, type, semantics, value, replaces)
    private fun sum(rows: List<FinancialObservation>) = TripFinancialAggregator.aggregate("t", MoneyAmount(99_999, "DOP"), rows)

    @Test fun parseIsExactAndRejectsAmbiguousOrOversizedInput() {
        assertEquals(12345L, ManualMoney.parse("123,45")!!.minorUnits)
        assertEquals(-125L, ManualMoney.parse("-1.25")!!.minorUnits)
        listOf("1,234.56", "1.234,56", "1.234", "NaN", "1e8", "100000000", "", "RD$ 20").forEach { assertNull(it, ManualMoney.parse(it)) }
    }

    @Test fun invalidChangesCannotConfirmOpenTripsOrAddCollectionsAsIncome() {
        assertNotNull(ManualMoney.validate(trip.copy(status = TripStatus.IN_TRIP), request("a", TRIP_TOTAL, ABSOLUTE_TOTAL, "100")))
        assertNotNull(ManualMoney.validate(trip, request("a", TIP, ABSOLUTE_TOTAL, "100")))
        assertNotNull(ManualMoney.validate(trip, request("a", COMMISSION, DELTA, "-10")))
        assertNotNull(ManualMoney.validate(trip, request("a", CASH_COLLECTED, DELTA, "100")))
        assertNull(ManualMoney.validate(trip, request("a", ADJUSTMENT, DELTA, "-10")))
    }

    @Test fun correctionAndUndoRestoreOriginalDateSourceAndAbsorbedDeltas() {
        val total = screen(TRIP_TOTAL, ABSOLUTE_TOTAL, 10_000, 4_000)
        val tip = screen(TIP, DELTA, 2_000, 5_000)
        val history = listOf(total, tip)
        val correction = ManualMoney.rows(trip, request("edit", TRIP_TOTAL, ABSOLUTE_TOTAL, "110", total.observationId), total, 6_000)
        val changed = history + correction
        assertEquals(11_000L, sum(changed).effectiveEarnings!!.minorUnits)
        assertTrue(changed.contains(total))
        val undo = ManualMoney.undoRows(trip, "edit", changed, "undo", 9_000)!!
        val restored = undo.first { it.type != VOID }
        assertEquals(total.observedAtMs, restored.observedAtMs)
        assertEquals(total.source, restored.source)
        assertEquals(12_000L, sum(changed + undo).effectiveEarnings!!.minorUnits)
        assertNull(ManualMoney.undoRows(trip, "edit", changed + undo, "again", 10_000))
    }

    @Test fun componentsCollectionsAndLaterTotalNeverDoubleCount() {
        var rows = listOf(screen(TRIP_TOTAL, ABSOLUTE_TOTAL, 10_000, 4_000))
        rows += ManualMoney.rows(trip, request("tip", TIP, COMPONENT, "20"), null, 5_000)
        assertEquals(10_000L, sum(rows).effectiveEarnings!!.minorUnits)
        rows += ManualMoney.rows(trip, request("extra", ADJUSTMENT, DELTA, "10"), null, 6_000)
        rows += ManualMoney.rows(trip, request("cash", CASH_COLLECTED, COMPONENT, "100"), null, 7_000)
        rows += ManualMoney.rows(trip, request("digital", DIGITAL_COLLECTED, COMPONENT, "10"), null, 8_000)
        assertEquals(11_000L, sum(rows).effectiveEarnings!!.minorUnits)
        assertEquals(TripCollections(10_000, 1_000), MoneyExplanation.collections(rows))
        rows += ManualMoney.rows(trip, request("total", TRIP_TOTAL, ABSOLUTE_TOTAL, "110"), rows.first(), 9_000)
        assertEquals(11_000L, sum(rows).effectiveEarnings!!.minorUnits)
    }

    @Test fun commissionSubtractsOnlyWhenAdditionalAndDoesNotInventABase() {
        val fee = ManualMoney.rows(trip, request("fee", COMMISSION, DELTA, "10"), null, 5_000)
        assertEquals(FinancialCompleteness.PARTIAL, sum(fee).completeness)
        assertNull(sum(fee).effectiveEarnings)
        val total = screen(TRIP_TOTAL, ABSOLUTE_TOTAL, 10_000, 4_000)
        assertEquals(9_000L, sum(listOf(total) + fee).effectiveEarnings!!.minorUnits)
        val included = ManualMoney.rows(trip, request("fee2", COMMISSION, COMPONENT, "10"), null, 5_000)
        assertEquals(10_000L, sum(listOf(total) + included).effectiveEarnings!!.minorUnits)
    }

    @Test fun manualTotalHasPriorityOverLaterScreensUntilUndone() {
        val original = screen(TRIP_TOTAL, ABSOLUTE_TOTAL, 10_000, 4_000)
        val edit = ManualMoney.rows(trip, request("edit", TRIP_TOTAL, ABSOLUTE_TOTAL, "110"), original, 5_000)
        val history = listOf(original) + edit + screen(TRIP_TOTAL, ABSOLUTE_TOTAL, 12_000, 7_000)
        assertEquals(11_000L, sum(history).effectiveEarnings!!.minorUnits)
        val undo = ManualMoney.undoRows(trip, "edit", history, "undo", 9_000)!!
        assertEquals(12_000L, sum(history + undo).effectiveEarnings!!.minorUnits)
    }

    @Test fun deletionUndoRestoresCollectionWithoutAffectingIncome() {
        val value = ManualMoney.rows(trip, request("cash", CASH_COLLECTED, COMPONENT, "100"), null, 4_000).single()
        val void = ManualMoney.void(value, "delete", 5_000,
            listOf(ManualMoney.ACTION, ManualMoney.COMMAND + "delete", ManualMoney.REPLACES + value.observationId))
        val history = listOf(value, void)
        assertEquals(0L, MoneyExplanation.collections(history).cash)
        val undo = ManualMoney.undoRows(trip, "delete", history, "undo", 6_000)!!
        assertEquals(10_000L, MoneyExplanation.collections(history + undo).cash)
        assertEquals(FinancialCompleteness.UNKNOWN, sum(history + undo).completeness)
    }

    @Test fun cancellationNeedsConfirmedMoneyAndDoesNotBecomeACompletedTrip() {
        val cancelled = trip.copy(status = TripStatus.CANCELLED, completedAt = null, cancelledAt = 3_000)
        val clock = WorkdayClock(0, ZoneId.of("UTC"))
        val window = EarningsWindows.of(EarningsRange.DAY, 0, 4_000, clock)
        val partial = sum(ManualMoney.rows(cancelled, request("fee", CANCELLATION_FEE, COMPONENT, "50"), null, 5_000))
        assertTrue(EarningsReports.build(window, clock, listOf(cancelled), mapOf("t" to partial), emptyList()).trips.isEmpty())
        val confirmed = sum(ManualMoney.rows(cancelled, request("total", TRIP_TOTAL, ABSOLUTE_TOTAL, "50"), null, 500_000))
        val report = EarningsReports.build(window, clock, listOf(cancelled), mapOf("t" to confirmed), emptyList())
        assertEquals(5_000L, report.summary.totalObservedEarnings)
        assertEquals(0, report.summary.completedTripCount)
        assertEquals(1, report.summary.cancelledIncomeCount)
        assertTrue(report.trips.single().cancelled)
        assertEquals(3_000L, report.trips.single().completedAt)
    }

    @Test fun latePaymentBelongsToTripDayAndExplicitUberIntervalIsRequired() {
        val clock = WorkdayClock(4, ZoneId.of("UTC"))
        val dayOne = 12 * 3_600_000L
        val ended = trip.copy(completedAt = dayOne)
        val summary = sum(ManualMoney.rows(ended, request("late", TRIP_TOTAL, ABSOLUTE_TOTAL, "100"), null, dayOne + 86_400_000))
        val w = EarningsWindows.of(EarningsRange.DAY, 0, dayOne, clock)
        val report = EarningsReports.build(w, clock, listOf(ended), mapOf("t" to summary), emptyList())
        assertEquals(10_000L, report.summary.totalObservedEarnings)
        val tomorrow = EarningsWindows.of(EarningsRange.DAY, 0, dayOne + 86_400_000, clock)
        assertEquals(0L, EarningsReports.build(tomorrow, clock, listOf(ended), mapOf("t" to summary), emptyList()).summary.totalObservedEarnings)
        val snap = PlatformEarningsSnapshot("snap", dayOne, PlatformPeriodType.DAY, 0, 86_400_000,
            MoneyAmount(10_000, "DOP"), 1.0, null, emptyList())
        assertEquals(ReconciliationStatus.PERIOD_MISMATCH, Reconciler.reconcile(report.summary, w.startMs, w.endMs, snap).status)
        assertEquals(ReconciliationStatus.MATCHED, Reconciler.reconcile(report.summary, 0, 86_400_000, snap).status)
    }
}
