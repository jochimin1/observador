package com.ubers.observador

import com.ubers.observador.data.db.OfferEntity
import com.ubers.observador.data.debug.TestOfferSeeder
import com.ubers.observador.domain.OfferQuality
import com.ubers.observador.domain.QualityThresholds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class TestOfferSeederTest {

    private val zone = ZoneId.of("America/Santo_Domingo")
    private val now = ZonedDateTime.of(2026, 10, 10, 15, 0, 0, 0, zone)
    private val offers = TestOfferSeeder.generate(now)
    private val cuts = QualityThresholds(goodFrom = 60, excellentFrom = 80)

    private fun OfferEntity.at() = Instant.ofEpochMilli(detectedAt).atZone(zone)
    private fun onDate(date: LocalDate) = offers.filter { it.at().toLocalDate() == date }
    private fun median(xs: List<Double>): Double {
        val s = xs.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    @Test
    fun `generates 60 offers all tagged as test`() {
        assertEquals(60, offers.size)
        assertTrue(offers.all { it.offerHash.startsWith(TestOfferSeeder.HASH_PREFIX) })
    }

    @Test
    fun `mix is 18 MALO, 27 BUENO and 15 EXCELENTE with factory cuts`() {
        val byQuality = offers.groupingBy { cuts.classify(it.score) }.eachCount()
        assertEquals(18, byQuality[OfferQuality.BAD])
        assertEquals(27, byQuality[OfferQuality.GOOD])
        assertEquals(15, byQuality[OfferQuality.EXCELLENT])
    }

    @Test
    fun `today has 20 offers in the 40 minutes before now and none in the future`() {
        val today = onDate(now.toLocalDate())
        assertEquals(20, today.size)
        val newest = today.maxOf { it.detectedAt }
        val oldest = today.minOf { it.detectedAt }
        assertEquals(now.minusMinutes(2).toInstant().toEpochMilli(), newest)
        assertEquals(now.minusMinutes(40).toInstant().toEpochMilli(), oldest)
        assertTrue(offers.all { it.detectedAt < now.toInstant().toEpochMilli() })
    }

    @Test
    fun `calendar days are 20 today, 20 yesterday, 2 five days ago and 18 six days ago`() {
        val d = now.toLocalDate()
        assertEquals(20, onDate(d.minusDays(1)).size)
        assertEquals(2, onDate(d.minusDays(5)).size)
        assertEquals(18, onDate(d.minusDays(6)).size)
        val afterMidnight = onDate(d.minusDays(5)).map { it.at().toLocalTime() }.sorted()
        assertEquals(listOf(LocalTime.of(0, 30), LocalTime.of(1, 30)), afterMidnight)
    }

    @Test
    fun `offers from 6 to 8 pm pay the most per hour`() {
        val past = offers.filter { it.at().toLocalDate() != now.toLocalDate() }
        val (evening, rest) = past.partition { it.at().hour in 18..19 }
        assertTrue(median(evening.map { it.rdpPerHour }) > median(rest.map { it.rdpPerHour }) * 1.5)
    }

    @Test
    fun `includes the RD75_50 example with score 76`() {
        val example = offers.filter { it.price == 75.50 && it.pickupKm == 1.8 }
        assertEquals(3, example.size)
        assertTrue(example.all { it.score == 76 })
    }

    @Test
    fun `same now gives the same offers`() {
        assertEquals(offers, TestOfferSeeder.generate(now))
    }
}
