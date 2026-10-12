package com.ubers.observador

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ubers.observador.data.FinancialRepository
import com.ubers.observador.data.TripRepository
import com.ubers.observador.data.db.AppDatabase
import com.ubers.observador.data.debug.FinancialTestSeeder
import com.ubers.observador.data.debug.TripTestSeeder
import com.ubers.observador.domain.finance.AmountSemantics
import com.ubers.observador.domain.finance.FinancialCompleteness
import com.ubers.observador.domain.finance.FinancialIds
import com.ubers.observador.domain.finance.FinancialObservation
import com.ubers.observador.domain.finance.FinancialObservationType
import com.ubers.observador.domain.finance.FinancialPersistResult
import com.ubers.observador.domain.finance.FinancialScope
import com.ubers.observador.domain.finance.FinancialSource
import com.ubers.observador.domain.finance.LinkConfidence
import com.ubers.observador.domain.lifecycle.RideLifecycleEventType
import com.ubers.observador.domain.lifecycle.RideLifecycleState
import com.ubers.observador.domain.money.Currencies
import com.ubers.observador.domain.money.MoneyAmount
import com.ubers.observador.domain.trips.TripEventInput
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Fase 7C on a real Room database: one write path, deduplicated, rebuildable, test data isolated. */
@RunWith(AndroidJUnit4::class)
class FinancialRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var money: FinancialRepository
    private lateinit var trips: TripRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        money = FinancialRepository(db)
        trips = TripRepository(db)
    }

    @After
    fun tearDown() = db.close()

    private fun dop(minor: Long) = MoneyAmount(minor, Currencies.DOP)

    private suspend fun completedTrip(session: String = "s1") {
        fun input(type: RideLifecycleEventType, from: RideLifecycleState, to: RideLifecycleState, at: Long, id: String) =
            TripEventInput(id, session, null, type, from, to, at, null, null, false, emptyList())
        trips.persist(input(RideLifecycleEventType.ACCEPTANCE_CONFIRMED, RideLifecycleState.OFFER_VISIBLE, RideLifecycleState.TO_PICKUP, 1_000, "$session-1"), null)
        trips.persist(input(RideLifecycleEventType.TRIP_STARTED, RideLifecycleState.TO_PICKUP, RideLifecycleState.IN_TRIP, 2_000, "$session-2"), null)
        trips.persist(input(RideLifecycleEventType.TRIP_COMPLETED, RideLifecycleState.IN_TRIP, RideLifecycleState.COMPLETED, 3_000, "$session-3"), null)
    }

    private fun linked(type: FinancialObservationType, semantics: AmountSemantics, minor: Long, at: Long, session: String? = "s1") =
        FinancialObservation(
            observationId = FinancialIds.observationId(session, type, semantics, dop(minor), "TRIP_EARNINGS", at),
            observedAtMs = at, lifecycleSessionId = session, tripId = null, type = type, semantics = semantics,
            amount = dop(minor), scope = if (session != null) FinancialScope.TRIP else FinancialScope.UNKNOWN,
            confidence = 0.95, evidence = listOf("TEST"), source = FinancialSource.TEST, screenType = "TRIP_EARNINGS",
            linkConfidence = if (session != null) LinkConfidence.HIGH else LinkConfidence.LOW
        )

    @Test
    fun totalThenTipThenNewTotalIsNeverDoubleCounted() = runBlocking {
        completedTrip()
        val tripId = trips.getTripByLifecycleSessionId("s1")!!.id
        money.persistObservation(linked(FinancialObservationType.TRIP_TOTAL, AmountSemantics.ABSOLUTE_TOTAL, 10_000, 4_000), 4_000)
        money.persistObservation(linked(FinancialObservationType.TIP, AmountSemantics.DELTA, 2_000, 5_000), 5_000)
        assertEquals(dop(12_000), money.getStoredSummary(tripId)!!.effectiveEarnings)
        money.persistObservation(linked(FinancialObservationType.TRIP_TOTAL, AmountSemantics.ABSOLUTE_TOTAL, 12_000, 600_000), 600_000)
        assertEquals(dop(12_000), money.getStoredSummary(tripId)!!.effectiveEarnings)
        assertEquals(money.computeSummary(tripId).effectiveEarnings, money.getStoredSummary(tripId)!!.effectiveEarnings)
    }

    @Test
    fun sameReadingFiftyTimesIsStoredOnce() = runBlocking {
        completedTrip()
        val o = linked(FinancialObservationType.TRIP_TOTAL, AmountSemantics.ABSOLUTE_TOTAL, 14_150, 4_000)
        val results = List(50) { money.persistObservation(o, 4_000) }
        assertEquals(1, results.count { it is FinancialPersistResult.Stored })
        assertEquals(1, money.getStoredSummary(trips.getTripByLifecycleSessionId("s1")!!.id)!!.observationCount)
    }

    @Test
    fun unknownSessionStaysUnassigned() = runBlocking {
        val result = money.persistObservation(linked(FinancialObservationType.TIP, AmountSemantics.DELTA, 2_000, 4_000, session = "nobody"), 4_000)
        assertNull((result as FinancialPersistResult.Stored).tripId)
    }

    @Test
    fun completedTripWithoutMoneyIsUnknownInThePeriod() = runBlocking {
        completedTrip()
        val period = money.periodEarnings(0, 10_000)
        assertEquals(1, period.completedTripCount)
        assertEquals(0, period.confirmedTripCount)
        assertEquals(0L, period.totalObservedEarnings)
    }

    @Test
    fun testDataLoadsCorrectlyAndDeletesWithoutTouchingRealMoney() = runBlocking {
        completedTrip()
        money.persistObservation(linked(FinancialObservationType.TRIP_TOTAL, AmountSemantics.ABSOLUTE_TOTAL, 9_900, 4_000), 4_000)
        val now = 10 * 3_600_000L
        val tripSeed = TripTestSeeder.generate(now)
        trips.replaceTestTrips(TripTestSeeder.PREFIX, tripSeed.trips, tripSeed.events)
        val seed = FinancialTestSeeder.generate(tripSeed.trips, now, 0, now + 3_600_000L)
        money.loadTestData(TripTestSeeder.PREFIX, seed.observations, seed.snapshots, now)
        money.loadTestData(TripTestSeeder.PREFIX, seed.observations, seed.snapshots, now) // twice: replaces, never doubles

        FinancialTestSeeder.expectedSummaries(tripSeed.trips, seed).forEach { (id, expected) ->
            val stored = money.getStoredSummary(id)!!
            assertEquals(expected.effectiveEarnings, stored.effectiveEarnings)
            assertEquals(expected.completeness, stored.completeness)
        }
        assertEquals(FinancialCompleteness.CONFIRMED, money.getStoredSummary("TEST-trip-5")!!.completeness)
        assertEquals(dop(16_000), money.getStoredSummary("TEST-trip-5")!!.effectiveEarnings)
        assertEquals(0, money.driftedSummaries())

        money.deleteTestData(TripTestSeeder.PREFIX)
        val real = trips.getTripByLifecycleSessionId("s1")!!.id
        assertEquals(dop(9_900), money.getStoredSummary(real)!!.effectiveEarnings)
        assertNull(money.getStoredSummary("TEST-trip-1"))
        assertTrue(money.getLatestPlatformSnapshot() == null)
    }
}
