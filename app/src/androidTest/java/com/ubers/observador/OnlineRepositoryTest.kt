package com.ubers.observador

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ubers.observador.data.OnlineRepository
import com.ubers.observador.data.TripRepository
import com.ubers.observador.data.db.AppDatabase
import com.ubers.observador.domain.lifecycle.RideLifecycleEventType
import com.ubers.observador.domain.lifecycle.RideLifecycleState
import com.ubers.observador.domain.online.OnlineEndReason
import com.ubers.observador.domain.online.OnlineSession
import com.ubers.observador.domain.online.OnlineSessionRules
import com.ubers.observador.domain.online.OnlineStartKind
import com.ubers.observador.domain.trips.TripEventInput
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Fase 8A on a real Room database: one row per session, restore after a restart, period queries, TEST isolation. */
@RunWith(AndroidJUnit4::class)
class OnlineRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var repo: OnlineRepository
    private val min = 60_000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repo = OnlineRepository(db)
    }

    @After
    fun tearDown() = db.close()

    private fun session(id: String, start: Long, lastSeen: Long, end: Long? = null) = OnlineSession(
        id, start, OnlineStartKind.EXACT, lastSeen, endedAt = end, possibleEndAt = end,
        endReason = end?.let { OnlineEndReason.WENT_OFFLINE }, createdAt = start, updatedAt = lastSeen
    )

    @Test
    fun aSessionIsOneRowUpdatedInPlace() = runBlocking {
        repo.save(listOf(session("s1", 0, min)))
        repo.save(listOf(session("s1", 0, 5 * min)))
        repo.save(listOf(session("s1", 0, 5 * min, end = 6 * min)))
        assertEquals(1, repo.observeCount().first())
        assertTrue(repo.getOpen().isEmpty())
        assertNull(repo.observeOpen().first())
    }

    @Test
    fun theOpenSessionComesBackAfterARestart() = runBlocking {
        var n = 0
        val before = OnlineSessionRules(newId = { "s${++n}" })
        val reading = com.ubers.observador.domain.online.OnlineReading(com.ubers.observador.domain.online.OnlineSignal.ONLINE)
        before.onReading(reading, 0)
        repo.save(before.onReading(reading, 500).saves)
        // A new process: the rules start empty and get the open row back
        val after = OnlineSessionRules()
        after.restore(repo.getOpen(), 10 * min)
        assertEquals("s1", after.current!!.id)
        repo.save(after.onReading(reading, 20 * min).saves)
        assertEquals(20 * min, repo.getOpen().single().lastOnlineSeenAt)
    }

    @Test
    fun periodQueryFindsSessionsTouchingTheWindow() = runBlocking {
        repo.save(listOf(session("before", 0, 10 * min, end = 10 * min)))
        repo.save(listOf(session("inside", 60 * min, 90 * min, end = 90 * min)))
        repo.save(listOf(session("open", 100 * min, 110 * min)))
        val found = repo.observeBetween(30 * min, 200 * min).first().map { it.id }
        assertEquals(listOf("inside", "open"), found)
    }

    @Test
    fun testSessionsNeverTouchRealOnes() = runBlocking {
        repo.save(listOf(session("real", 0, min, end = min)))
        repo.replaceTestSessions("TEST-", listOf(session("TEST-a", 0, min, end = min), session("TEST-b", 2 * min, 3 * min, end = 3 * min)))
        assertEquals(3, repo.observeCount().first())
        repo.deleteTestSessions("TEST-")
        assertEquals(listOf("real"), repo.observeRecent(10).first().map { it.id })
    }

    @Test
    fun metricsReadTripsAndOffersOfTheWindow() = runBlocking {
        val trips = TripRepository(db)
        fun input(type: RideLifecycleEventType, from: RideLifecycleState, to: RideLifecycleState, at: Long, id: String) =
            TripEventInput(id, "l1", null, type, from, to, at, null, null, false, emptyList())
        trips.persist(input(RideLifecycleEventType.ACCEPTANCE_CONFIRMED, RideLifecycleState.OFFER_VISIBLE, RideLifecycleState.TO_PICKUP, 10 * min, "e1"), null)
        trips.persist(input(RideLifecycleEventType.TRIP_STARTED, RideLifecycleState.TO_PICKUP, RideLifecycleState.IN_TRIP, 15 * min, "e2"), null)
        trips.persist(input(RideLifecycleEventType.TRIP_COMPLETED, RideLifecycleState.IN_TRIP, RideLifecycleState.COMPLETED, 30 * min, "e3"), null)
        val s = session("s", 0, 60 * min, end = 60 * min)
        repo.save(listOf(s))
        val summary = repo.summarize(listOf(s), 0, 120 * min, 120 * min)
        assertEquals(60 * min, summary.confirmedMs)
        assertEquals(20 * min, summary.busyMs)
        assertEquals(1, summary.earnings!!.completedTrips)
        assertEquals(0, summary.earnings!!.confirmedTrips)
    }
}
