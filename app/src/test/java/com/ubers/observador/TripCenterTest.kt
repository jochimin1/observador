package com.ubers.observador

import com.ubers.observador.domain.EngineSettings
import com.ubers.observador.domain.WorkdayClock
import com.ubers.observador.domain.earnings.EarningsRange
import com.ubers.observador.domain.earnings.EarningsReports
import com.ubers.observador.domain.earnings.EarningsWindows
import com.ubers.observador.domain.finance.FinancialCompleteness
import com.ubers.observador.domain.finance.TripFinancialSummary
import com.ubers.observador.domain.money.Currencies
import com.ubers.observador.domain.money.MoneyAmount
import com.ubers.observador.domain.trips.CorrectionField
import com.ubers.observador.domain.trips.CorrectionOrigin
import com.ubers.observador.domain.trips.Trip
import com.ubers.observador.domain.trips.TripCenter
import com.ubers.observador.domain.trips.TripCorrections
import com.ubers.observador.domain.trips.TripDecisionSnapshot
import com.ubers.observador.domain.trips.TripDetails
import com.ubers.observador.domain.trips.TripOfferSnapshot
import com.ubers.observador.domain.trips.TripOutcome
import com.ubers.observador.domain.trips.TripQuery
import com.ubers.observador.domain.trips.TripStatus
import com.ubers.observador.domain.trips.TripStatusFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** 1.40.0: every state listed, missing data tolerated, amounts as in Ganancias, corrections recorded. */
class TripCenterTest {

    private val zone = ZoneId.of("America/Santo_Domingo")
    private val clock = WorkdayClock(4, zone)
    private val min = 60_000L
    private fun at(h: Int, mi: Int = 0) = LocalDateTime.of(2026, 11, 10, h, mi).atZone(zone).toInstant().toEpochMilli()
    private fun dop(minor: Long) = MoneyAmount(minor, Currencies.DOP)

    private fun offer(price: Double = 150.0, product: String? = "Moto", pickup: String? = "Calle 5, Los Alcarrizos", dropoff: String? = "Av. Churchill, Piantini") =
        TripOfferSnapshot(price, 1.5, 4, 6.0, 15, 70, at(10), pickupAddress = pickup, dropoffAddress = dropoff, product = product)

    private fun trip(
        id: String, status: TripStatus, accepted: Long? = at(10, 1), offer: TripOfferSnapshot? = offer(),
        recovered: Boolean = false, rider: String? = null,
        arrived: Long? = null, started: Long? = null, completed: Long? = null, cancelled: Long? = null, interrupted: Long? = null
    ) = Trip(
        id = id, lifecycleSessionId = "s-$id", relatedOfferHash = null, status = status, recovered = recovered,
        startedFromKnownOffer = offer != null, acceptedAt = accepted, arrivedAt = arrived, tripStartedAt = started,
        completedAt = completed, cancelledAt = cancelled, interruptedAt = interrupted,
        createdAt = accepted ?: at(10, 2), updatedAt = at(11), offer = offer, riderName = rider,
        decision = offer?.let { TripDecisionSnapshot(6.0, 300.0, 6.0, 12, 60, 80) }
    )

    private val trips = listOf(
        trip("done", TripStatus.COMPLETED, rider = "Stefany", arrived = at(10, 8), started = at(10, 10), completed = at(10, 30)),
        trip("cancel", TripStatus.CANCELLED, arrived = at(10, 8), cancelled = at(10, 12), offer = offer(product = "Moto Flash")),
        trip("closed", TripStatus.INTERRUPTED, interrupted = at(10, 40)),
        trip("lost", TripStatus.INTERRUPTED, accepted = null, interrupted = at(10, 40), offer = null),
        trip("now", TripStatus.IN_TRIP, recovered = true, accepted = null, offer = null, started = at(10, 50))
    )
    private val summaries = mapOf(
        "done" to TripFinancialSummary("done", Currencies.DOP, dop(15_000), dop(18_000), dop(18_000), tip = 2_000, completeness = FinancialCompleteness.CONFIRMED),
        "cancel" to TripFinancialSummary("cancel", Currencies.DOP, dop(15_000), dop(5_000), dop(5_000), cancellationFee = 5_000, completeness = FinancialCompleteness.CONFIRMED)
    )
    private val rows = TripCenter.rows(trips, summaries, setOf("closed"), Currencies.DOP)

    @Test fun everyStateIsListedWithOrWithoutTheOffer() {
        assertEquals(5, rows.size)
        TripStatusFilter.entries.forEach { f -> assertTrue("$f", TripCenter.filter(rows, TripQuery(status = f)).isNotEmpty()) }
        assertEquals(listOf("now"), TripCenter.filter(rows, TripQuery(status = TripStatusFilter.IN_PROGRESS)).map { it.id })
        assertEquals(setOf("lost", "now"), TripCenter.filter(rows, TripQuery(status = TripStatusFilter.NO_OFFER)).map { it.id }.toSet())
        assertEquals(listOf("now"), TripCenter.filter(rows, TripQuery(status = TripStatusFilter.RECOVERED)).map { it.id })
        assertEquals("Cerrado sin confirmar", TripCenter.statusLabel(trips[2]))
        assertEquals("Interrumpido", TripCenter.statusLabel(trips[3]))
        // Newest first; the open trip has no end
        assertEquals("now", rows.first().id)
        assertNull(rows.first().endAt)
        assertTrue(rows.single { it.id == "closed" }.corrected)
    }

    @Test fun amountsAreTheOnesGananciasShows() {
        val window = EarningsWindows.of(EarningsRange.DAY, 0, at(12), clock)
        val report = EarningsReports.build(window, clock, trips.filter { TripOutcome.endedAt(it) != null }, summaries, emptyList(), Currencies.DOP)
        report.trips.forEach { money ->
            val row = rows.single { it.id == money.tripId }
            assertEquals(money.real, row.real)
            assertEquals(money.offered, row.offered)
            assertEquals(money.completeness, row.completeness)
        }
        assertEquals(setOf("done", "closed"), report.trips.map { it.tripId }.toSet())
        // Counted as done but without a confirmed total: money pending
        assertEquals(listOf("closed"), TripCenter.filter(rows, TripQuery(moneyPendingOnly = true)).map { it.id })
        // A cancelled trip is not income, but its fee is visible
        assertEquals(5_000L, rows.single { it.id == "cancel" }.cancellationFeeMinor)
    }

    @Test fun filtersAndPrivateSearch() {
        assertEquals(listOf("cancel"), TripCenter.filter(rows, TripQuery(product = "moto flash")).map { it.id })
        assertEquals(setOf("done", "cancel", "closed"), TripCenter.filter(rows, TripQuery(sector = "Los Alcarrizos")).map { it.id }.toSet())
        assertEquals(listOf("done"), TripCenter.filter(rows, TripQuery(text = "stef")).map { it.id })
        assertEquals(3, TripCenter.filter(rows, TripQuery(text = "piantini")).size)
        // Names hidden: not shown and not searchable
        assertTrue(TripCenter.filter(rows, TripQuery(text = "stef", hideNames = true)).isEmpty())
        assertEquals("Pasajero", TripCenter.riderShown(trips[0], hideNames = true))
        assertEquals("Stefany", TripCenter.riderShown(trips[0], hideNames = false))
        assertEquals(listOf("Moto", "Moto Flash"), TripCenter.products(rows))
    }

    @Test fun theDetailCopesWithMissingData() {
        val lost = trips[3]
        assertNull(TripDetails.scores(lost, EngineSettings()))
        assertNull(TripDetails.waitAtPickup(lost))
        assertNull(TripDetails.cancelled(lost, null, null))
        assertEquals(listOf("Observación cerrada sin final visto"), TripDetails.timeline(lost).map { it.first })
        val row = TripCenter.rows(listOf(lost), emptyMap(), emptySet(), Currencies.DOP).single()
        assertNull(row.offered)
        assertNull(row.real)
        assertEquals(FinancialCompleteness.UNKNOWN, row.completeness)
    }

    @Test fun scoresAtDetectionAndTodayAreKeptApart() {
        val done = trips[0]
        val same = TripDetails.scores(done, EngineSettings())!!
        assertEquals(70, same.atDetection)
        assertNotNull(same.detectionSettings)
        val strict = TripDetails.scores(done, EngineSettings(minRdpPerKm = 40.0, minRdpPerHour = 2_000.0))!!
        assertEquals(70, strict.atDetection)
        assertNotEquals(same.today, strict.today)
        assertTrue(strict.today < same.today)
    }

    @Test fun aCancelledTripShowsWhatItCost() {
        val c = TripDetails.cancelled(trips[1], 2_300_000, summaries["cancel"])!!
        assertEquals(11 * min, c.lostMs)
        assertEquals(2_300_000L, c.measuredMm)
        assertTrue(c.feeConfirmed)
        val w = TripDetails.waitAtPickup(trips[1])!!
        assertEquals(4 * min, w.ms)
        assertTrue(w.endedByCancellation)
        assertTrue(TripDetails.timeline(trips[1]).any { it.first == "Viaje cancelado" })
        assertFalse(TripDetails.cancelled(trips[1], null, null)!!.feeConfirmed)
    }

    @Test fun statusCorrectionsAreReversibleAndChangeWhatGananciasCounts() {
        val lost = trips[3]
        assertNull(TripOutcome.endedAt(lost))
        assertTrue(TripCorrections.canCorrectStatus(lost, TripStatus.COMPLETED))
        assertFalse(TripCorrections.canCorrectStatus(trips[4], TripStatus.COMPLETED))
        assertFalse(TripCorrections.canCorrectStatus(lost, TripStatus.INTERRUPTED))
        val fixed = TripCorrections.correctStatus(lost, TripStatus.COMPLETED, at(12))
        assertEquals(TripStatus.COMPLETED, fixed.status)
        assertEquals(TripOutcome.realEndAt(lost), fixed.completedAt)
        assertNotNull(TripOutcome.endedAt(fixed))
        assertEquals(lost.interruptedAt, fixed.interruptedAt)
        val old = TripCorrections.decodeStatus(TripCorrections.encodeStatus(lost))!!
        val back = TripCorrections.restoreStatus(fixed, old, at(13))
        assertEquals(lost.copy(updatedAt = at(13)), back)
        // Cancelled -> completed keeps the seen end and leaves no conflicting ending
        val c = TripCorrections.correctStatus(trips[1], TripStatus.COMPLETED, at(12))
        assertEquals(trips[1].cancelledAt, c.completedAt)
        assertNull(c.cancelledAt)
        assertNull(TripCorrections.decodeStatus("roto"))
    }

    @Test fun namesAreCleanedAndOriginsAreFixedKeys() {
        assertNull(TripCorrections.cleanName("   "))
        assertEquals("Ana", TripCorrections.cleanName("  Ana "))
        assertEquals(60, TripCorrections.cleanName("x".repeat(80))!!.length)
        assertEquals(CorrectionField.RIDER_NAME, CorrectionField.fromStorage("RIDER_NAME"))
        assertEquals(CorrectionOrigin.UBER_SCREEN, CorrectionOrigin.fromStorage("UBER_SCREEN"))
        assertNull(CorrectionOrigin.fromStorage("OTHER"))
    }
}
