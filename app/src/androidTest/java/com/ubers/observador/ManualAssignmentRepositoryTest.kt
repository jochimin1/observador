package com.ubers.observador

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ubers.observador.data.FinancialRepository
import com.ubers.observador.data.TripRepository
import com.ubers.observador.data.db.AppDatabase
import com.ubers.observador.domain.WorkdayClock
import com.ubers.observador.domain.earnings.AssignCheck
import com.ubers.observador.domain.earnings.AssignRejection
import com.ubers.observador.domain.earnings.EarningsReports
import com.ubers.observador.domain.earnings.ManualAssignment
import com.ubers.observador.domain.finance.AmountSemantics
import com.ubers.observador.domain.finance.FinancialIds
import com.ubers.observador.domain.finance.FinancialObservation
import com.ubers.observador.domain.finance.FinancialObservationType
import com.ubers.observador.domain.finance.FinancialScope
import com.ubers.observador.domain.finance.FinancialSource
import com.ubers.observador.domain.finance.LinkConfidence
import com.ubers.observador.domain.lifecycle.RideLifecycleEventType
import com.ubers.observador.domain.lifecycle.RideLifecycleState
import com.ubers.observador.domain.money.Currencies
import com.ubers.observador.domain.money.MoneyAmount
import com.ubers.observador.domain.trips.TripEventInput
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId

/** Fase 8C on Room: an assignment is a void plus a linked copy in one transaction, undoable, never counted twice. */
@RunWith(AndroidJUnit4::class)
class ManualAssignmentRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var money: FinancialRepository
    private lateinit var trips: TripRepository
    private val clock = WorkdayClock(4, ZoneId.of("UTC"))
    private val day = 86_400_000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        money = FinancialRepository(db)
        trips = TripRepository(db)
    }

    @After
    fun tearDown() = db.close()

    private fun dop(minor: Long) = MoneyAmount(minor, Currencies.DOP)

    /** A trip completed at [end] (ms), with its session [session]. */
    private suspend fun completedTrip(session: String, end: Long, open: Boolean = false): String {
        fun input(type: RideLifecycleEventType, from: RideLifecycleState, to: RideLifecycleState, at: Long, id: String) =
            TripEventInput(id, session, null, type, from, to, at, null, null, false, emptyList())
        trips.persist(input(RideLifecycleEventType.ACCEPTANCE_CONFIRMED, RideLifecycleState.OFFER_VISIBLE, RideLifecycleState.TO_PICKUP, end - 2_000, "$session-1"), null)
        trips.persist(input(RideLifecycleEventType.TRIP_STARTED, RideLifecycleState.TO_PICKUP, RideLifecycleState.IN_TRIP, end - 1_000, "$session-2"), null)
        if (!open) trips.persist(input(RideLifecycleEventType.TRIP_COMPLETED, RideLifecycleState.IN_TRIP, RideLifecycleState.COMPLETED, end, "$session-3"), null)
        return trips.getTripByLifecycleSessionId(session)!!.id
    }

    private fun amount(type: FinancialObservationType, semantics: AmountSemantics, minor: Long, at: Long, session: String?) = FinancialObservation(
        observationId = "TEST-" + FinancialIds.observationId(session, type, semantics, dop(minor), "TRIP_EARNINGS", at),
        observedAtMs = at, lifecycleSessionId = session, tripId = null, type = type, semantics = semantics,
        amount = dop(minor), scope = if (session != null) FinancialScope.TRIP else FinancialScope.UNKNOWN,
        confidence = 0.95, evidence = listOf("TEST"), source = FinancialSource.TEST, screenType = "TRIP_EARNINGS",
        linkConfidence = if (session != null) LinkConfidence.HIGH else LinkConfidence.LOW
    )

    @Test
    fun assignTipThenUndo() = runBlocking {
        val t0 = 10 * day + 12 * 3_600_000L
        val tripId = completedTrip("s1", t0)
        money.persistObservation(amount(FinancialObservationType.TRIP_TOTAL, AmountSemantics.ABSOLUTE_TOTAL, 14_150, t0 + 1_000, "s1"), t0)
        val tip = amount(FinancialObservationType.TIP, AmountSemantics.DELTA, 5_000, t0 + 600_000, null)
        money.persistObservation(tip, t0)
        val from = clock.startOf(clock.workdayOf(t0))
        val to = from + day
        assertEquals(14_150, money.periodEarnings(from, to).totalObservedEarnings)

        assertEquals(listOf(tripId), money.assignmentCandidates(tip.observationId, clock).map { it.first.id })
        val preview = money.previewAssignment(tip.observationId, tripId) as AssignCheck.Ok
        assertEquals(dop(19_150), preview.preview.after.effectiveEarnings)

        assertTrue(money.assignToTrip(tip.observationId, tripId, t0 + day) is AssignCheck.Ok)
        assertEquals(dop(19_150), money.getStoredSummary(tripId)!!.effectiveEarnings)
        assertEquals(19_150, money.periodEarnings(from, to).totalObservedEarnings) // once, even with the void written a day later
        val inputs = money.earningsInputs(from, to)
        assertTrue(EarningsReports.pending(inputs.unassigned).isEmpty())
        // A second tap does nothing
        assertEquals(AssignRejection.NOT_PENDING, (money.assignToTrip(tip.observationId, tripId, t0 + day + 1) as AssignCheck.Rejected).reason)

        val live = ManualAssignment.live(money.observeManual().first())
        assertEquals(1, live.size)
        assertTrue(money.undoAssignment(live.single().copy.observationId, t0 + 2 * day))
        assertFalse(money.undoAssignment(live.single().copy.observationId, t0 + 2 * day + 1))
        assertEquals(dop(14_150), money.getStoredSummary(tripId)!!.effectiveEarnings)
        assertEquals(14_150, money.periodEarnings(from, to).totalObservedEarnings)
        val back = EarningsReports.pending(money.earningsInputs(from, to).unassigned)
        assertEquals(listOf(dop(5_000)), back.map { it.observation.amount })
        assertTrue(back.single().observation.observationId.startsWith("TEST-"))
        assertEquals(0, money.driftedSummaries())
    }

    @Test
    fun notMineAndOpenTrips() = runBlocking {
        val t0 = 20 * day + 12 * 3_600_000L
        val open = completedTrip("s2", t0, open = true)
        val tip = amount(FinancialObservationType.TIP, AmountSemantics.DELTA, 3_000, t0 + 60_000, null)
        money.persistObservation(tip, t0)
        assertEquals(AssignRejection.TRIP_NOT_COMPLETED, (money.assignToTrip(tip.observationId, open, t0 + 1) as AssignCheck.Rejected).reason)
        assertTrue(money.rejectObservation(tip.observationId, t0 + 2))
        assertFalse(money.rejectObservation(tip.observationId, t0 + 3))
        val from = clock.startOf(clock.workdayOf(t0))
        assertTrue(EarningsReports.pending(money.earningsInputs(from, from + day).unassigned).isEmpty())
    }
}
