package com.ubers.observador

import com.ubers.observador.domain.DayGroup
import com.ubers.observador.domain.DayStrip
import com.ubers.observador.domain.OfferQuality
import com.ubers.observador.domain.PeriodSummary
import com.ubers.observador.domain.QualityGroups
import com.ubers.observador.domain.Rings
import com.ubers.observador.domain.StatsPeriod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** 1.27.0: Inicio in Violeta. Rings never leave 0-100; the strip follows the working day. */
class HomeVioletTest {

    private val today = LocalDate.of(2026, 10, 17)

    @Test fun ringsStayBetweenZeroAndHundred() {
        assertEquals(0, Rings.percent(5, 0))
        assertEquals(0, Rings.percent(-3, 10))
        assertEquals(100, Rings.percent(12, 10))
        assertEquals(85, Rings.percent(1_700, 2_000))
        assertEquals(67, Rings.percent(2, 3)) // 66.67 rounds half up
        assertEquals(0.5f, Rings.fraction(1, 2))
        assertEquals(73, Rings.ofPermille(725))
        assertEquals(100, Rings.ofPermille(1_400))
        assertEquals(0, Rings.ofPermille(-10))
        (0L..50L).forEach { p -> assertTrue(Rings.percent(p, 37) in 0..100) }
    }

    @Test fun theStripShowsTheLastSevenWorkingDays() {
        val days = listOf(DayGroup(today, 9, 0.0, 0.0, emptyList()), DayGroup(today.minusDays(2), 4, 0.0, 0.0, emptyList()))
        val week = DayStrip.of(today, days, loadedWorkdays = 7)
        assertEquals(7, week.size)
        assertEquals(today.minusDays(6), week.first().date)
        assertTrue(week.last().isToday)
        assertEquals(9, week.last().offers)
        assertEquals(4, week[4].offers)
        assertEquals(0, week[5].offers)
        // With «Hoy» only today is loaded: the rest is unknown, not zero
        val todayOnly = DayStrip.of(today, days.take(1), loadedWorkdays = 1)
        assertEquals(9, todayOnly.last().offers)
        assertNull(todayOnly.first().offers)
        assertEquals(StatsPeriod.WEEK, DayStrip.periodFor(today.minusDays(3), today, StatsPeriod.TODAY))
        assertEquals(StatsPeriod.TODAY, DayStrip.periodFor(today, today, StatsPeriod.TODAY))
        assertEquals(StatsPeriod.MONTH, DayStrip.periodFor(today.minusDays(3), today, StatsPeriod.MONTH))
    }

    @Test fun groupsAddUpToThePeriod() {
        val g = QualityGroups.of(PeriodSummary(count = 20, excellent = 5, good = 9, bad = 6))
        assertEquals(listOf(OfferQuality.EXCELLENT, OfferQuality.GOOD, OfferQuality.BAD), g.map { it.quality })
        assertEquals(listOf(25, 45, 30), g.map { it.percent })
        assertEquals(listOf(0, 0, 0), QualityGroups.of(PeriodSummary()).map { it.percent })
    }
}
