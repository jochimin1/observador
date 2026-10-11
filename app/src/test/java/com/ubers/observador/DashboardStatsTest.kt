package com.ubers.observador

import com.ubers.observador.data.db.DaySummary
import com.ubers.observador.data.db.OfferEntity
import com.ubers.observador.data.debug.TestOfferSeeder
import com.ubers.observador.domain.DashboardStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class DashboardStatsTest {

    private val zone = ZoneId.of("America/Santo_Domingo")
    private val now = ZonedDateTime.of(2026, 10, 10, 15, 0, 0, 0, zone)
    // Same order the DAO returns: newest first
    private val offers = TestOfferSeeder.generate(now).sortedByDescending { it.detectedAt }

    private fun offerAt(at: ZonedDateTime, rdpPerKm: Double, score: Int) = OfferEntity(
        offerHash = "x", detectedAt = at.toInstant().toEpochMilli(), price = 100.0,
        pickupKm = 1.0, pickupMin = 2, tripKm = 4.0, tripMin = 10, totalKm = 5.0, totalMin = 12,
        rdpPerKm = rdpPerKm, rdpPerHour = 500.0, fuelCost = 7.5, netProfit = 92.5, score = score
    )

    @Test
    fun `no offers gives no days`() {
        assertTrue(DashboardStats.groupByDay(emptyList(), emptyList(), zone).isEmpty())
    }

    @Test
    fun `days come newest first with their offers`() {
        val days = DashboardStats.groupByDay(offers, emptyList(), zone)
        val d = now.toLocalDate()
        assertEquals(listOf(d, d.minusDays(1), d.minusDays(5), d.minusDays(6)), days.map { it.date })
        assertEquals(listOf(20, 20, 2, 18), days.map { it.offers.size })
        assertEquals(listOf(20, 20, 2, 18), days.map { it.count })
    }

    @Test
    fun `without a summary the averages come from the loaded offers`() {
        val d = now.toLocalDate()
        val list = listOf(
            offerAt(now.minusHours(1), rdpPerKm = 10.0, score = 50),
            offerAt(now.minusHours(2), rdpPerKm = 14.0, score = 70)
        )
        val day = DashboardStats.groupByDay(list, emptyList(), zone).single()
        assertEquals(d, day.date)
        assertEquals(12.0, day.avgRdpPerKm, 1e-9)
        assertEquals(60.0, day.avgScore, 1e-9)
    }

    @Test
    fun `a summary row wins over a day cut short by the list limit`() {
        val d = now.toLocalDate()
        val list = listOf(offerAt(now.minusHours(1), rdpPerKm = 10.0, score = 50))
        val summary = DaySummary(day = d.toString(), count = 25, avgRdpPerKm = 13.5, avgScore = 71.0)
        val day = DashboardStats.groupByDay(list, listOf(summary), zone).single()
        assertEquals(25, day.count)
        assertEquals(13.5, day.avgRdpPerKm, 1e-9)
        assertEquals(71.0, day.avgScore, 1e-9)
        assertEquals(1, day.offers.size)
    }

    @Test
    fun `days follow the phone time zone, not UTC`() {
        // 02:30 UTC on Oct 10 is 22:30 on Oct 9 in Santo Domingo (UTC-4)
        val utc = ZonedDateTime.of(2026, 10, 10, 2, 30, 0, 0, ZoneId.of("UTC"))
        val day = DashboardStats.groupByDay(listOf(offerAt(utc, 10.0, 50)), emptyList(), zone).single()
        assertEquals(LocalDate.of(2026, 10, 9), day.date)
    }
}
