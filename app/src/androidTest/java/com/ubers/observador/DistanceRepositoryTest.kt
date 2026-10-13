package com.ubers.observador

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ubers.observador.data.DistanceRepository
import com.ubers.observador.data.TripRepository
import com.ubers.observador.data.db.AppDatabase
import com.ubers.observador.domain.distance.DistanceSegment
import com.ubers.observador.domain.distance.DistanceUpdate
import com.ubers.observador.domain.distance.SegmentEndReason
import com.ubers.observador.domain.distance.SegmentOrigin
import com.ubers.observador.domain.distance.TripDistance
import com.ubers.observador.domain.distance.TripDistanceCoverage
import com.ubers.observador.domain.lifecycle.RideLifecycleEventType
import com.ubers.observador.domain.lifecycle.RideLifecycleState
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
import java.time.LocalDate

/** Fase 8E on a real Room database: one row per stretch, day sums, trip kilometres keyed by trip, TEST isolation. */
@RunWith(AndroidJUnit4::class)
class DistanceRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var repo: DistanceRepository
    private val day = LocalDate.of(2026, 10, 12)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repo = DistanceRepository(db)
    }

    @After
    fun tearDown() = db.close()

    private fun seg(id: String, d: LocalDate, mm: Long, open: Boolean = false) = DistanceSegment(
        id, d, 1_000, if (open) null else 2_000, 2_000, SegmentOrigin.AUTO_ONLINE,
        if (open) null else SegmentEndReason.WENT_OFFLINE, measuredMm = mm, emptyMm = mm, createdAt = 1_000
    )

    @Test
    fun aStretchIsOneRowAndADayIsTheSumOfItsStretches() = runBlocking {
        repo.save(DistanceUpdate(segments = listOf(seg("a", day, 1_000_000, open = true))))
        repo.save(DistanceUpdate(segments = listOf(seg("a", day, 5_000_000))))
        repo.save(DistanceUpdate(segments = listOf(seg("b", day, 2_500_000), seg("c", day.plusDays(1), 9_000_000))))
        val today = repo.observeDay(day).first()
        assertEquals(2, today.segments.size)
        assertEquals(7_500_000L, today.measuredMm)
        assertTrue(repo.getOpenSegments().isEmpty())
        assertEquals(0L, repo.observeDay(day.plusDays(2)).first().measuredMm)
    }

    @Test
    fun tripKilometresAreKeyedByTripAndGoWithIt() = runBlocking {
        val trips = TripRepository(db)
        trips.persist(
            TripEventInput("e1", "sess-1", null, RideLifecycleEventType.ACCEPTANCE_CONFIRMED,
                RideLifecycleState.OFFER_VISIBLE, RideLifecycleState.TO_PICKUP, 1_000, null, null, false, emptyList()),
            null
        )
        val tripId = trips.getTripByLifecycleSessionId("sess-1")!!.id
        repo.save(DistanceUpdate(trips = listOf(TripDistance("sess-1", 1_000_000, 4_000_000, 0, TripDistanceCoverage.FULL, 5_000))))
        // A session with no stored trip is skipped, never invented
        repo.save(DistanceUpdate(trips = listOf(TripDistance("nobody", 1, 1, 0, TripDistanceCoverage.PARTIAL, 5_000))))
        val stored = repo.observeTripDistance(tripId).first()!!
        assertEquals(4_000_000L, stored.tripMm)
        assertEquals(TripDistanceCoverage.FULL, stored.coverage)
        db.tripDao().deleteTrip(tripId)
        assertNull(repo.observeTripDistance(tripId).first())
    }

    @Test
    fun testStretchesNeverTouchRealOnes() = runBlocking {
        repo.save(DistanceUpdate(segments = listOf(seg("real", day, 1_000_000))))
        repo.replaceTestSegments("TEST-km-", listOf(seg("TEST-km-1", day, 2_000_000)))
        assertEquals(2, repo.observeDay(day).first().segments.size)
        repo.deleteTestSegments("TEST-km-")
        assertEquals(listOf("real"), repo.observeDay(day).first().segments.map { it.id })
    }
}
