package com.ubers.observador

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ubers.observador.data.TripRepository
import com.ubers.observador.data.db.AppDatabase
import com.ubers.observador.data.db.OfferEntity
import com.ubers.observador.data.db.RideEventEntity
import com.ubers.observador.data.db.toEntity
import com.ubers.observador.data.debug.TripTestSeeder
import com.ubers.observador.domain.lifecycle.RideLifecycleEventType
import com.ubers.observador.domain.lifecycle.RideLifecycleState
import com.ubers.observador.domain.trips.TripEventInput
import com.ubers.observador.domain.trips.TripPersistenceResult
import com.ubers.observador.domain.trips.TripStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Fase 7B on a real Room database (in memory unless a restart is being tested). */
@RunWith(AndroidJUnit4::class)
class TripRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var repository: TripRepository
    private var ids = 0

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repository = TripRepository(db)
    }

    @After
    fun tearDown() = db.close()

    private fun input(
        type: RideLifecycleEventType,
        from: RideLifecycleState,
        to: RideLifecycleState,
        at: Long,
        session: String = "s1",
        eventId: String = "e${++ids}"
    ) = TripEventInput(eventId, session, "136.06-4.1-7-7.2-11", type, from, to, at, null, null, false, emptyList())

    private suspend fun normalRide(repo: TripRepository = repository, session: String = "s1") {
        repo.persist(input(RideLifecycleEventType.ACCEPTANCE_CONFIRMED, RideLifecycleState.OFFER_VISIBLE, RideLifecycleState.TO_PICKUP, 1_000, session), null)
        repo.persist(input(RideLifecycleEventType.ARRIVAL_CONFIRMED, RideLifecycleState.TO_PICKUP, RideLifecycleState.ARRIVED_OR_WAITING, 2_000, session), null)
        repo.persist(input(RideLifecycleEventType.TRIP_STARTED, RideLifecycleState.ARRIVED_OR_WAITING, RideLifecycleState.IN_TRIP, 3_000, session), null)
        repo.persist(input(RideLifecycleEventType.TRIP_COMPLETED, RideLifecycleState.IN_TRIP, RideLifecycleState.COMPLETED, 4_000, session), null)
    }

    @Test
    fun normalRideIsOneCompletedTripWithFourEvents() = runBlocking {
        normalRide()
        val trips = db.tripDao().getAllTrips()
        assertEquals(1, trips.size)
        assertEquals(TripStatus.COMPLETED.storageKey, trips.single().status)
        assertEquals(4, db.tripDao().countEventsForTrip(trips.single().id))
        assertEquals(listOf(1, 2, 3, 4), db.tripDao().getEventsForTrip(trips.single().id).map { it.sequence })
        assertNull(repository.observeActiveTrip().first())
    }

    @Test
    fun theSameEventFiftyTimesIsOneRow() = runBlocking {
        repository.persist(input(RideLifecycleEventType.ACCEPTANCE_CONFIRMED, RideLifecycleState.OFFER_VISIBLE, RideLifecycleState.TO_PICKUP, 1_000), null)
        val started = input(RideLifecycleEventType.TRIP_STARTED, RideLifecycleState.TO_PICKUP, RideLifecycleState.IN_TRIP, 2_000, eventId = "same")
        repeat(50) { i ->
            val result = repository.persist(started.copy(timestampMs = 2_000L + i), null).result
            if (i > 0) assertTrue(result is TripPersistenceResult.DuplicateIgnored)
        }
        assertEquals(1, db.tripDao().getAllEvents().count { it.eventId == "same" })
        assertEquals(1, db.tripDao().getAllTrips().size)
        assertEquals(2_000L, db.tripDao().getAllTrips().single().tripStartedAt)
    }

    @Test
    fun twoSessionsAreTwoTrips() = runBlocking {
        normalRide(session = "A")
        normalRide(session = "B")
        assertEquals(2, db.tripDao().getAllTrips().size)
    }

    @Test
    fun aSessionCanNeverHoldTwoTrips() = runBlocking {
        normalRide()
        val copy = db.tripDao().getAllTrips().single().copy(id = "other")
        try {
            db.tripDao().insertTrip(copy)
            fail("a second trip with the same session must be refused")
        } catch (expected: SQLiteConstraintException) {
            assertEquals(1, db.tripDao().getAllTrips().size)
        }
    }

    @Test
    fun anEventCannotPointToAMissingTripAndDeletingATripDeletesItsEvents() = runBlocking {
        try {
            db.tripDao().insertEvent(RideEventEntity(0, "e", "missing", "s", "ACCEPTED", 1, 1, null, null, false, null, null, "", null))
            fail("foreign key must refuse an orphan event")
        } catch (expected: SQLiteConstraintException) {
            assertTrue(db.tripDao().getAllEvents().isEmpty())
        }
        normalRide()
        val id = db.tripDao().getAllTrips().single().id
        db.tripDao().deleteTrip(id)
        assertTrue(db.tripDao().getAllEvents().isEmpty())
    }

    @Test
    fun aFailedTransactionLeavesNothingHalfWritten() = runBlocking {
        val seed = TripTestSeeder.generate(100_000_000L)
        repository.replaceTestTrips(TripTestSeeder.PREFIX, seed.trips, seed.events)
        val broken = seed.events + seed.events.first().copy(eventId = "${TripTestSeeder.PREFIX}orphan", tripId = "${TripTestSeeder.PREFIX}none")
        try {
            repository.replaceTestTrips(TripTestSeeder.PREFIX, seed.trips, broken)
            fail("the orphan event must abort the transaction")
        } catch (expected: SQLiteConstraintException) {
            // The delete at the start of that transaction was rolled back too
            assertEquals(seed.trips.size, db.tripDao().getAllTrips().size)
            assertEquals(seed.events.size, db.tripDao().getAllEvents().size)
        }
        try {
            db.withTransaction {
                db.tripDao().insertTrip(seed.trips.first().copy(id = "x", lifecycleSessionId = "x").toEntity())
                error("crash mid-transaction")
            }
        } catch (expected: IllegalStateException) {
            assertNull(db.tripDao().getById("x"))
        }
    }

    @Test
    fun deletingTestTripsKeepsRealTripsAndOffers() = runBlocking {
        db.offerDao().insert(OfferEntity(0, "136.06-4.1-7-7.2-11", 900, 136.06, 4.1, 7, 7.2, 11, 11.3, 18, 12.0, 453.0, 17.0, 119.0, 76))
        normalRide()
        val seed = TripTestSeeder.generate(100_000_000L)
        repository.replaceTestTrips(TripTestSeeder.PREFIX, seed.trips, seed.events)
        repository.replaceTestTrips(TripTestSeeder.PREFIX, seed.trips, seed.events)
        assertEquals(1 + seed.trips.size, db.tripDao().getAllTrips().size)
        repository.deleteTestTrips(TripTestSeeder.PREFIX)
        val left = db.tripDao().getAllTrips()
        assertEquals(1, left.size)
        assertEquals(4, db.tripDao().getAllEvents().size)
        assertEquals(1, db.offerDao().getSince(0).size)
    }

    @Test
    fun theOriginalOfferIsCopiedWhenTheTripBegins() = runBlocking {
        db.offerDao().insert(OfferEntity(0, "136.06-4.1-7-7.2-11", 900, 136.06, 4.1, 7, 7.2, 11, 11.3, 18, 12.0, 453.0, 17.0, 119.0, 76))
        normalRide()
        val trip = db.tripDao().getAllTrips().single()
        assertEquals(136.06, trip.offeredAmount ?: 0.0, 1e-9)
        assertEquals(76, trip.scoreAtDetection)
        assertEquals(900L, trip.offerSeenAt)
    }

    @Test
    fun aTripSurvivesARestartAndIsRestoredAsTheSameTrip() = runBlocking {
        val name = "trip-restart-test.db"
        context.deleteDatabase(name)
        val first = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        TripRepository(first).persist(
            input(RideLifecycleEventType.ACCEPTANCE_CONFIRMED, RideLifecycleState.OFFER_VISIBLE, RideLifecycleState.TO_PICKUP, 1_000), null
        )
        val id = first.tripDao().getAllTrips().single().id
        first.close()

        val second = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        val plan = TripRepository(second).restoreOpenTrip(5_000)
        assertEquals(id, plan.active?.id)
        assertEquals(1, second.tripDao().getAllTrips().size)
        assertEquals(TripStatus.UNKNOWN.storageKey, second.tripDao().getById(id)?.status)
        second.close()
        context.deleteDatabase(name)
    }
}
