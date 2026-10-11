package com.ubers.observador

import com.ubers.observador.domain.StatsPeriod
import com.ubers.observador.domain.WorkdayClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class WorkdayClockTest {

    private val zone = ZoneId.of("America/Santo_Domingo")
    private val clock = WorkdayClock(4, zone)
    private fun at(day: Int, hour: Int, minute: Int = 0) =
        ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun `2_30 am belongs to the working day that started the day before`() {
        assertEquals(LocalDate.of(2026, 10, 9), clock.workdayOf(at(10, 2, 30)))
    }

    @Test
    fun `the working day changes exactly at the start hour`() {
        assertEquals(LocalDate.of(2026, 10, 9), clock.workdayOf(at(10, 3, 59)))
        assertEquals(LocalDate.of(2026, 10, 10), clock.workdayOf(at(10, 4, 0)))
    }

    @Test
    fun `today starts at 4 am of the current working day`() {
        assertEquals(at(10, 4), clock.periodStart(StatsPeriod.TODAY, at(10, 15)))
        // At 1 am the working day is still yesterday's
        assertEquals(at(9, 4), clock.periodStart(StatsPeriod.TODAY, at(10, 1)))
    }

    @Test
    fun `7 days covers exactly 7 working days`() {
        val now = at(10, 15)
        val since = clock.periodStart(StatsPeriod.WEEK, now)!!
        assertEquals(at(4, 4), since)
        val included = (0L..9L).map { clock.workdayOf(now) .minusDays(it) }
            .filter { clock.startOf(it) >= since }
        assertEquals(7, included.size)
    }

    @Test
    fun `30 days starts 29 working days before today`() {
        val since = clock.periodStart(StatsPeriod.MONTH, at(10, 15))!!
        assertEquals(LocalDate.of(2026, 9, 11), clock.workdayOf(since))
    }

    @Test
    fun `the whole history has no lower bound`() {
        assertNull(clock.periodStart(StatsPeriod.ALL, at(10, 15)))
    }

    @Test
    fun `an hour outside 0 to 23 is rejected`() {
        val failed = runCatching { WorkdayClock(24, zone) }.isFailure
        assertTrue(failed)
    }
}
