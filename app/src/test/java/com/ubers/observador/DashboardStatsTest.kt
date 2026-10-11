package com.ubers.observador

import com.ubers.observador.data.db.OfferEntity
import com.ubers.observador.data.debug.TestOfferSeeder
import com.ubers.observador.domain.DashboardStats
import com.ubers.observador.domain.EngineSettings
import com.ubers.observador.domain.QualityThresholds
import com.ubers.observador.domain.WorkdayClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class DashboardStatsTest {

    private val zone = ZoneId.of("America/Santo_Domingo")
    private val now = ZonedDateTime.of(2026, 10, 10, 15, 0, 0, 0, zone)
    private val clock4 = WorkdayClock(4, zone)
    private val cuts = QualityThresholds(goodFrom = 60, excellentFrom = 80)

    // Same order the DAO returns: newest first
    private val seeded = TestOfferSeeder.generate(now).sortedByDescending { it.detectedAt }

    private fun offer(score: Int, price: Double = 100.0, rdpPerKm: Double = 10.0, at: ZonedDateTime = now) =
        OfferEntity(
            offerHash = "x", detectedAt = at.toInstant().toEpochMilli(), price = price,
            pickupKm = 1.0, pickupMin = 2, tripKm = 4.0, tripMin = 10, totalKm = 5.0, totalMin = 12,
            rdpPerKm = rdpPerKm, rdpPerHour = 500.0, fuelCost = 7.5, netProfit = 92.5, score = score
        )

    @Test
    fun `no offers gives zeros and never NaN`() {
        val s = DashboardStats.summarize(emptyList(), cuts)
        assertEquals(0, s.count)
        assertEquals(0.0, s.medianRdpPerKm, 0.0)
        assertEquals(0.0, s.avgScore, 0.0)
        assertNull(s.best)
        assertTrue(DashboardStats.groupByWorkday(emptyList(), clock4).isEmpty())
    }

    @Test
    fun `scores on the cuts fall in the right category`() {
        val s = DashboardStats.summarize(listOf(offer(59), offer(60), offer(79), offer(80)), cuts)
        assertEquals(1, s.bad)
        assertEquals(2, s.good)
        assertEquals(1, s.excellent)
    }

    @Test
    fun `median works with odd and even counts`() {
        assertEquals(3.0, DashboardStats.median(listOf(5.0, 1.0, 3.0)), 1e-9)
        assertEquals(2.5, DashboardStats.median(listOf(4.0, 1.0, 3.0, 2.0)), 1e-9)
        assertEquals(0.0, DashboardStats.median(emptyList()), 0.0)
    }

    @Test
    fun `percentages always add up to 100`() {
        assertEquals(listOf(30, 45, 25), DashboardStats.percentages(listOf(6, 9, 5)))
        assertEquals(listOf(34, 33, 33), DashboardStats.percentages(listOf(1, 1, 1)))
        assertEquals(listOf(0, 100, 0), DashboardStats.percentages(listOf(0, 7, 0)))
        assertEquals(listOf(0, 0, 0), DashboardStats.percentages(listOf(0, 0, 0)))
        for (counts in listOf(listOf(1, 2, 4), listOf(3, 3, 1), listOf(17, 5, 9))) {
            assertEquals(100, DashboardStats.percentages(counts).sum())
        }
    }

    @Test
    fun `short trips do not inflate the median like they inflate the average`() {
        val s = DashboardStats.summarize(
            listOf(offer(70, rdpPerKm = 12.0), offer(70, rdpPerKm = 13.0), offer(70, rdpPerKm = 40.0)), cuts
        )
        assertEquals(13.0, s.medianRdpPerKm, 1e-9)
        assertEquals(21.67, s.avgRdpPerKm, 0.01)
    }

    @Test
    fun `best offer is the highest score and a tie goes to the higher price`() {
        val s = DashboardStats.summarize(listOf(offer(90, price = 120.0), offer(90, price = 150.0), offer(70)), cuts)
        assertEquals(150.0, s.best!!.price, 0.0)
    }

    @Test
    fun `rescore uses the current settings`() {
        // RD$75.50 example stored with a stale score of 0
        val stale = offer(0, price = 75.50).copy(pickupKm = 1.8, pickupMin = 4, tripKm = 3.5, tripMin = 9)
        assertEquals(76, DashboardStats.rescore(listOf(stale), EngineSettings()).single().score)
        val strict = EngineSettings(minRdpPerKm = 12.0, minRdpPerHour = 400.0, maxPickupKm = 2.0, maxPickupMin = 4)
        val rescored = DashboardStats.rescore(listOf(stale), strict).single()
        assertEquals(36, rescored.score)
        assertEquals(14.25, rescored.rdpPerKm, 0.01)
        assertEquals(stale.detectedAt, rescored.detectedAt)
    }

    @Test
    fun `with a 4 am start the after-midnight test offers join their working day`() {
        val days = DashboardStats.groupByWorkday(seeded, clock4)
        val d = now.toLocalDate()
        assertEquals(listOf(d, d.minusDays(1), d.minusDays(6)), days.map { it.date })
        assertEquals(listOf(20, 20, 20), days.map { it.count })
    }

    @Test
    fun `with a midnight start working days are calendar days`() {
        val days = DashboardStats.groupByWorkday(seeded, WorkdayClock(0, zone))
        assertEquals(listOf(20, 20, 2, 18), days.map { it.count })
    }

    @Test
    fun `each working day keeps its offers newest first`() {
        val days = DashboardStats.groupByWorkday(seeded, clock4)
        assertTrue(days.all { day -> day.offers.zipWithNext().all { (a, b) -> a.detectedAt >= b.detectedAt } })
    }

    @Test
    fun `day medians come from that day only`() {
        val list = listOf(
            offer(70, rdpPerKm = 10.0, at = now.minusHours(1)),
            offer(70, rdpPerKm = 14.0, at = now.minusHours(2)),
            offer(70, rdpPerKm = 30.0, at = now.minusDays(1))
        )
        val days = DashboardStats.groupByWorkday(list, clock4)
        assertEquals(12.0, days.first().medianRdpPerKm, 1e-9)
        assertEquals(LocalDate.of(2026, 10, 9), days.last().date)
    }
}
