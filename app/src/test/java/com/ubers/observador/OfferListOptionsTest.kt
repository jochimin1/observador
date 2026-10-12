package com.ubers.observador

import com.ubers.observador.data.debug.TestOfferSeeder
import com.ubers.observador.domain.CategoryFilter
import com.ubers.observador.domain.DashboardStats
import com.ubers.observador.domain.OfferListOptions
import com.ubers.observador.domain.OfferQuality
import com.ubers.observador.domain.OfferSort
import com.ubers.observador.domain.QualityThresholds
import com.ubers.observador.domain.WorkdayClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class OfferListOptionsTest {

    private val zone = ZoneId.of("America/Santo_Domingo")
    private val now = ZonedDateTime.of(2026, 10, 10, 15, 0, 0, 0, zone)
    private val cuts = QualityThresholds(goodFrom = 60, excellentFrom = 80)
    private val days = DashboardStats.groupByWorkday(
        TestOfferSeeder.generate(now, includeMarketPattern = false).sortedByDescending { it.detectedAt },
        WorkdayClock(4, zone)
    )

    @Test
    fun `all with time order changes nothing`() {
        assertEquals(days, OfferListOptions.apply(days, CategoryFilter.ALL, OfferSort.TIME, cuts))
    }

    @Test
    fun `excelentes keeps only green offers and the day totals`() {
        val result = OfferListOptions.apply(days, CategoryFilter.EXCELLENT, OfferSort.TIME, cuts)
        assertEquals(listOf(5, 5, 5), result.map { it.offers.size })
        assertEquals(listOf(20, 20, 20), result.map { it.count })
        assertTrue(result.all { d -> d.offers.all { cuts.classify(it.score) == OfferQuality.EXCELLENT } })
    }

    @Test
    fun `malas and buenas split the rest`() {
        val bad = OfferListOptions.apply(days, CategoryFilter.BAD, OfferSort.TIME, cuts)
        val good = OfferListOptions.apply(days, CategoryFilter.GOOD, OfferSort.TIME, cuts)
        assertEquals(listOf(6, 6, 6), bad.map { it.offers.size })
        assertEquals(listOf(9, 9, 9), good.map { it.offers.size })
    }

    @Test
    fun `days with no matching offers disappear`() {
        val onlyOneDay = listOf(days.first().copy(offers = days.first().offers.filter { it.score < 60 })) + days.drop(1)
        val result = OfferListOptions.apply(onlyOneDay, CategoryFilter.EXCELLENT, OfferSort.TIME, cuts)
        assertEquals(2, result.size)
    }

    @Test
    fun `score order puts the highest first inside each day`() {
        val result = OfferListOptions.apply(days, CategoryFilter.ALL, OfferSort.SCORE, cuts)
        assertTrue(result.all { d -> d.offers.zipWithNext().all { (a, b) -> a.score >= b.score } })
        assertEquals(96, result.first().offers.first().score)
        assertEquals(days.map { it.date }, result.map { it.date })
    }

    @Test
    fun `price and RD per km orders are descending`() {
        val byPrice = OfferListOptions.apply(days, CategoryFilter.ALL, OfferSort.PRICE, cuts)
        assertTrue(byPrice.all { d -> d.offers.zipWithNext().all { (a, b) -> a.price >= b.price } })
        val byKm = OfferListOptions.apply(days, CategoryFilter.ALL, OfferSort.PER_KM, cuts)
        assertTrue(byKm.all { d -> d.offers.zipWithNext().all { (a, b) -> a.rdpPerKm >= b.rdpPerKm } })
    }

    @Test
    fun `ties fall back to newest first`() {
        val result = OfferListOptions.apply(days, CategoryFilter.ALL, OfferSort.SCORE, cuts)
        result.forEach { d ->
            d.offers.zipWithNext().filter { (a, b) -> a.score == b.score }
                .forEach { (a, b) -> assertTrue(a.detectedAt >= b.detectedAt) }
        }
    }

    @Test
    fun `chip counts come from the summary`() {
        val all = days.flatMap { it.offers }
        val summary = DashboardStats.summarize(all, cuts)
        assertEquals(60, OfferListOptions.countFor(CategoryFilter.ALL, summary))
        assertEquals(15, OfferListOptions.countFor(CategoryFilter.EXCELLENT, summary))
        assertEquals(27, OfferListOptions.countFor(CategoryFilter.GOOD, summary))
        assertEquals(18, OfferListOptions.countFor(CategoryFilter.BAD, summary))
    }
}
