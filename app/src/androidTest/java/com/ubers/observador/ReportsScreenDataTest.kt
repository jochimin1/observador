package com.ubers.observador

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ubers.observador.data.ExpenseRepository
import com.ubers.observador.data.TripRepository
import com.ubers.observador.data.db.AppDatabase
import com.ubers.observador.data.db.OfferEntity
import com.ubers.observador.data.export.ReportData
import com.ubers.observador.domain.EngineSettings
import com.ubers.observador.domain.QualityThresholds
import com.ubers.observador.domain.TakenFilter
import com.ubers.observador.domain.WorkdayClock
import com.ubers.observador.domain.earnings.EarningsRange
import com.ubers.observador.domain.earnings.EarningsWindow
import com.ubers.observador.domain.expenses.FuelEntry
import com.ubers.observador.domain.lifecycle.RideLifecycleEventType
import com.ubers.observador.domain.lifecycle.RideLifecycleState
import com.ubers.observador.domain.reports.ButtonFilter
import com.ubers.observador.domain.reports.OfferReport
import com.ubers.observador.domain.reports.PeriodTotalsBuilder
import com.ubers.observador.domain.reports.ReportFilters
import com.ubers.observador.domain.trips.TripEventInput
import com.ubers.observador.domain.trips.TripStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

/**
 * Fase 8J on Room: «Reportes y exportación» reads a period with offers of both buttons and two
 * modalities, trips in several states and a fill-up, and its files and totals agree.
 */
@RunWith(AndroidJUnit4::class)
class ReportsScreenDataTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private val zone = ZoneId.of("UTC")
    private val clock = WorkdayClock(0, zone)
    private val day = LocalDate.of(2026, 10, 14)
    private val t0 = day.atStartOfDay(zone).toInstant().toEpochMilli()
    private val window = EarningsWindow(EarningsRange.DAY, 0, day, day, t0, t0 + 86_400_000L)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    }

    @After
    fun tearDown() = db.close()

    private fun offer(hash: String, at: Long, price: Double, product: String, action: String, exclusive: Boolean) = OfferEntity(
        offerHash = hash, detectedAt = at, price = price, pickupKm = 1.0, pickupMin = 3, tripKm = 4.0, tripMin = 10,
        totalKm = 5.0, totalMin = 13, rdpPerKm = 0.0, rdpPerHour = 0.0, fuelCost = 0.0, netProfit = 0.0, score = 60,
        cardAction = action, product = product, exclusive = exclusive
    )

    private suspend fun take(trips: TripRepository, session: String, hash: String, at: Long, end: RideLifecycleEventType?) {
        trips.persist(TripEventInput("$session-1", session, hash, RideLifecycleEventType.ACCEPTANCE_CONFIRMED, RideLifecycleState.OFFER_VISIBLE, RideLifecycleState.TO_PICKUP, at, null, null, false, emptyList()), null)
        when (end) {
            RideLifecycleEventType.TRIP_CANCELLED ->
                trips.persist(TripEventInput("$session-2", session, hash, end, RideLifecycleState.TO_PICKUP, RideLifecycleState.CANCELLED, at + 60_000, null, null, false, emptyList()), null)
            RideLifecycleEventType.SEARCHING_CONFIRMED ->
                trips.persist(TripEventInput("$session-2", session, hash, end, RideLifecycleState.TO_PICKUP, RideLifecycleState.IDLE, at + 900_000, null, null, false, emptyList()), null)
            else -> Unit
        }
    }

    @Test
    fun aPeriodWithEverythingExportsConsistentFilesAndTotals() = runBlocking {
        db.offerDao().insert(offer("a", t0 + 3_600_000, 74.17, "Moto", "INTEREST", false))
        db.offerDao().insert(offer("b", t0 + 7_200_000, 72.47, "Moto", "ACCEPT", true))
        db.offerDao().insert(offer("c", t0 + 10_800_000, 119.41, "Flash", "INTEREST", false))
        db.offerDao().insert(offer("TEST-x", t0 + 10_900_000, 50.0, "Moto", "INTEREST", false))
        val trips = TripRepository(db)
        take(trips, "s-b", "b", t0 + 7_210_000, RideLifecycleEventType.SEARCHING_CONFIRMED)
        take(trips, "s-c", "c", t0 + 10_810_000, RideLifecycleEventType.TRIP_CANCELLED)
        ExpenseRepository(db).saveFuel(FuelEntry("f1", t0 + 3_000_000, 1_000, 25_000, fullTank = true, createdAt = t0, updatedAt = t0))

        val source = ReportData(db).offerSource(window, clock, EngineSettings(), QualityThresholds(), includeTest = false)
        assertEquals(3, source.rows.size) // the TEST- offer is left out
        val byHashPrice = source.rows.associateBy { it.offeredMinor }
        assertEquals(TripStatus.INTERRUPTED, byHashPrice.getValue(7_247).tripStatus)
        assertEquals(TripStatus.CANCELLED, byHashPrice.getValue(11_941).tripStatus)
        assertEquals("Flash", byHashPrice.getValue(11_941).product)
        assertEquals(1, source.expenses.fuel.size)

        val taken = OfferReport.filter(source.rows, ReportFilters(origin = TakenFilter.TAKEN, button = ButtonFilter.INTEREST))
        assertEquals(listOf(11_941L), taken.map { it.offeredMinor })

        val totals = PeriodTotalsBuilder.of(source.rows)
        assertEquals(3, totals.count.all)
        assertEquals(2, totals.count.taken)
        assertEquals(1, totals.unconfirmed)
        assertEquals(1, totals.cancelled)
        assertEquals(0L, totals.realMinor)
        assertEquals(source.rows.sumOf { it.offeredMinor }, totals.offeredRefMinor)

        val csv = OfferReport.csv(source.rows, zone)
        assertTrue(csv.contains("boton"))
        assertTrue(csv.contains(",tomado,cancelado,Flash,no,Me interesa,"))
        assertEquals(4, csv.trim().split("\r\n").size)
    }
}
