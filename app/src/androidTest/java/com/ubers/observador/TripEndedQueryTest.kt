package com.ubers.observador

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ubers.observador.data.FinancialRepository
import com.ubers.observador.data.TripRepository
import com.ubers.observador.data.db.AppDatabase
import com.ubers.observador.domain.lifecycle.RideLifecycleEventType
import com.ubers.observador.domain.lifecycle.RideLifecycleState
import com.ubers.observador.domain.trips.TripEventInput
import com.ubers.observador.domain.trips.TripStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Fase 8H on Room: the earnings screens read trips that ended, including the ones Uber closed by going back to searching. */
@RunWith(AndroidJUnit4::class)
class TripEndedQueryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var trips: TripRepository
    private lateinit var money: FinancialRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        trips = TripRepository(db)
        money = FinancialRepository(db)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun send(session: String, n: Int, type: RideLifecycleEventType, from: RideLifecycleState, to: RideLifecycleState, at: Long) {
        trips.persist(TripEventInput("$session-$n", session, "h-$session", type, from, to, at, null, null, false, emptyList()), null)
    }

    @Test
    fun interruptedTripsThatWereTakenCountAsEnded() = runBlocking {
        // s1: completed · s2: closed by searching · s3: cancelled · s4: still going to the rider
        send("s1", 1, RideLifecycleEventType.ACCEPTANCE_CONFIRMED, RideLifecycleState.OFFER_VISIBLE, RideLifecycleState.TO_PICKUP, 1_000)
        send("s1", 2, RideLifecycleEventType.TRIP_STARTED, RideLifecycleState.TO_PICKUP, RideLifecycleState.IN_TRIP, 2_000)
        send("s1", 3, RideLifecycleEventType.TRIP_COMPLETED, RideLifecycleState.IN_TRIP, RideLifecycleState.COMPLETED, 3_000)
        send("s2", 1, RideLifecycleEventType.ACCEPTANCE_CONFIRMED, RideLifecycleState.OFFER_VISIBLE, RideLifecycleState.TO_PICKUP, 10_000)
        send("s2", 2, RideLifecycleEventType.SEARCHING_CONFIRMED, RideLifecycleState.TO_PICKUP, RideLifecycleState.IDLE, 20_000)
        send("s3", 1, RideLifecycleEventType.ACCEPTANCE_CONFIRMED, RideLifecycleState.OFFER_VISIBLE, RideLifecycleState.TO_PICKUP, 30_000)
        send("s3", 2, RideLifecycleEventType.TRIP_CANCELLED, RideLifecycleState.TO_PICKUP, RideLifecycleState.CANCELLED, 31_000)

        assertEquals(TripStatus.INTERRUPTED, trips.getTripByLifecycleSessionId("s2")!!.status)
        val inputs = money.earningsInputs(0, 100_000)
        assertEquals(setOf("s1", "s2"), inputs.completedTrips.map { it.lifecycleSessionId }.toSet())
        // Outside the window nothing ended
        assertEquals(0, money.earningsInputs(50_000, 100_000).completedTrips.size)
        // The home list sees all three taken trips, whatever their status
        assertEquals(3, trips.observeCreatedSince(0).first().size)
    }
}
