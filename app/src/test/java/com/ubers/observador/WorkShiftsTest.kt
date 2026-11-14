package com.ubers.observador

import com.ubers.observador.domain.WorkdayClock
import com.ubers.observador.domain.expenses.*
import com.ubers.observador.domain.online.OnlineSession
import com.ubers.observador.domain.online.OnlineStartKind
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class WorkShiftsTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private val clock = WorkdayClock(4, zone)
    private val day = LocalDate.of(2026, 11, 10)
    private fun at(d: Int, h: Int, m: Int = 0) = day.plusDays(d.toLong()).atTime(h, m).atZone(zone).toInstant().toEpochMilli()
    private fun reading(id: String, atMs: Long, km: Long, reset: Boolean = false, vehicle: String = INITIAL_VEHICLE) =
        OdometerReading(id, atMs, km, reset, atMs, atMs, vehicle)
    private fun shift(id: String, start: Long, startKm: Long, end: Long?, endKm: Long?, vehicle: String = INITIAL_VEHICLE, reset: Boolean = false) =
        WorkShift(id, vehicle, reading("$id-s", start, startKm, reset, vehicle), end?.let { reading("$id-e", it, endKm!!, vehicle = vehicle) }, start, start)
    private fun timeline(shifts: List<WorkShift>) = Odometer.timeline(emptyList(), emptyList(), shifts.flatMap { listOfNotNull(it.start, it.end) })
    private fun measure(shifts: List<WorkShift>, from: LocalDate = day, to: LocalDate = day.plusDays(30)) =
        WorkShiftRules.measure(shifts, timeline(shifts), clock, from, to)

    @Test fun uberAndPersonalKmFromConsecutiveShifts() {
        // Day 1: 1000→1120 (120 Uber); 30 personal; day 2: 1150→1250 (100 Uber)
        val m = measure(listOf(shift("a", at(0, 7), 1000, at(0, 15), 1120), shift("b", at(1, 7), 1150, at(1, 16), 1250)))
        assertEquals(2, m.closedShifts)
        assertEquals(220, m.uberKm)
        assertEquals(30, m.personalKm)
        assertEquals(88, m.pct) // 220 / 250
    }

    @Test fun severalShiftsInOneDayCountTheBreakAsPersonal() {
        val m = measure(listOf(shift("a", at(0, 7), 1000, at(0, 12), 1080), shift("b", at(0, 13), 1086, at(0, 18), 1166)))
        assertEquals(160, m.uberKm)
        assertEquals(6, m.personalKm)
        assertEquals(96, m.pct)
    }

    @Test fun oneShiftIsNotEnoughAndFallsBackToManual() {
        val m = measure(listOf(shift("a", at(0, 7), 1000, at(0, 15), 1120)))
        assertNull(m.pct)
        assertNull(WorkShiftRules.suggestion(m, 70, null))
    }

    @Test fun openShiftsAreNotCounted() {
        val m = measure(listOf(shift("a", at(0, 7), 1000, at(0, 15), 1120), shift("b", at(1, 7), 1150, null, null)))
        assertEquals(1, m.closedShifts)
        assertNull(m.pct)
    }

    @Test fun resetBetweenShiftsLeavesThatGapOut() {
        val m = measure(listOf(shift("a", at(0, 7), 1000, at(0, 15), 1120), shift("b", at(1, 7), 5, at(1, 15), 105, reset = true),
            shift("c", at(2, 7), 125, at(2, 15), 225)))
        assertEquals(320, m.uberKm)
        assertEquals(20, m.personalKm) // only b→c
        assertEquals(1, m.measuredGaps)
        assertEquals(94, m.pct)
    }

    @Test fun differentMotorbikesAreNotChained() {
        val m = measure(listOf(shift("a", at(0, 7), 1000, at(0, 15), 1120), shift("b", at(1, 7), 50, at(1, 15), 150, vehicle = "moto-2")))
        assertEquals(0, m.measuredGaps)
        assertNull(m.pct)
    }

    @Test fun nightShiftBelongsToTheDayItStarted() {
        // Starts 22:00 of day 0, ends 02:00 of day 1 (before the 4:00 cut): one shift of day 0
        val s = listOf(shift("a", at(0, 22), 1000, at(1, 2), 1060), shift("b", at(1, 20), 1070, at(2, 1), 1130))
        val onlyDay0 = measure(s, day, day)
        assertEquals(1, onlyDay0.closedShifts)
        assertEquals(60, onlyDay0.uberKm)
        val both = measure(s, day, day.plusDays(1))
        assertEquals(120, both.uberKm)
        assertEquals(10, both.personalKm)
    }

    @Test fun gapFromThePreviousPeriodCountsForTheFirstShift() {
        val s = listOf(shift("a", at(0, 7), 1000, at(0, 15), 1100), shift("b", at(5, 7), 1140, at(5, 15), 1240), shift("c", at(6, 7), 1250, at(6, 15), 1350))
        val m = measure(s, day.plusDays(5), day.plusDays(6))
        assertEquals(200, m.uberKm)
        assertEquals(50, m.personalKm) // 40 (a→b) + 10 (b→c)
    }

    @Test fun startChecks() {
        val closed = shift("a", at(0, 7), 1000, at(0, 15), 1120)
        val tl = timeline(listOf(closed))
        val now = at(1, 8)
        assertEquals(ShiftCheck.Ok, WorkShiftRules.checkStart(1150, at(1, 7), now, listOf(closed), tl, reset = false))
        assertTrue(WorkShiftRules.checkStart(1100, at(1, 7), now, listOf(closed), tl, reset = false) is ShiftCheck.Rejected)
        assertEquals(ShiftCheck.Ok, WorkShiftRules.checkStart(10, at(1, 7), now, listOf(closed), tl, reset = true))
        assertTrue(WorkShiftRules.checkStart(1150, at(1, 9), now, listOf(closed), tl, reset = false) is ShiftCheck.Rejected) // future
        assertTrue(WorkShiftRules.checkStart(1150, at(0, 10), now, listOf(closed), tl, reset = false) is ShiftCheck.Rejected) // inside
        val open = shift("b", at(1, 7), 1150, null, null)
        assertTrue(WorkShiftRules.checkStart(1160, at(1, 7, 30), now, listOf(closed, open), timeline(listOf(closed, open)), reset = false) is ShiftCheck.Rejected)
    }

    @Test fun endChecks() {
        val open = shift("a", at(0, 7), 1000, null, null)
        val now = at(0, 20)
        assertEquals(ShiftCheck.Ok, WorkShiftRules.checkEnd(open, 1120, at(0, 15), now, listOf(open)))
        assertTrue(WorkShiftRules.checkEnd(open, 999, at(0, 15), now, listOf(open)) is ShiftCheck.Rejected)
        assertTrue(WorkShiftRules.checkEnd(open, 1120, at(0, 6), now, listOf(open)) is ShiftCheck.Rejected)
        assertTrue(WorkShiftRules.checkEnd(open, 1120, at(0, 21), now, listOf(open)) is ShiftCheck.Rejected)
        assertTrue(WorkShiftRules.checkEnd(open, 1700, at(0, 15), now, listOf(open)) is ShiftCheck.Confirm)
        // A forgotten end is closed the next day with its real time
        assertEquals(ShiftCheck.Ok, WorkShiftRules.checkEnd(open, 1120, at(0, 16), at(1, 9), listOf(open)))
    }

    @Test fun suggestionNeedsADifferenceAndRespectsDismissal() {
        val m = measure(listOf(shift("a", at(0, 7), 1000, at(0, 15), 1120), shift("b", at(1, 7), 1150, at(1, 16), 1250)))
        assertEquals(88, WorkShiftRules.suggestion(m, 70, null)!!.measuredPct)
        assertNull(WorkShiftRules.suggestion(m, 88, null))
        assertNull(WorkShiftRules.suggestion(m, 70, 86))       // moved 2 points since «Ahora no»
        assertNotNull(WorkShiftRules.suggestion(m, 70, 85))    // moved 3
    }

    private fun session(id: String, start: Long, seen: Long, ended: Long? = null) =
        OnlineSession(id, start, OnlineStartKind.entries.first(), seen, endedAt = ended, createdAt = start, updatedAt = seen)

    @Test fun remindersOnlyOncePerSessionAndAfterThirtyMinutesOffline() {
        val open = shift("a", at(0, 7), 1000, null, null)
        val online = session("s1", at(0, 7, 5), at(0, 9))
        assertEquals(ShiftReminder(ShiftReminderKind.START, "start:s1"), WorkShiftRules.reminder(online, null, at(0, 9), onlineNow = true))
        assertNull(WorkShiftRules.reminder(online, open, at(0, 9), onlineNow = true))
        val ended = session("s1", at(0, 7, 5), at(0, 15), ended = at(0, 15))
        assertNull(WorkShiftRules.reminder(ended, open, at(0, 15, 20), onlineNow = false))
        assertEquals(ShiftReminder(ShiftReminderKind.END, "end:a:s1"), WorkShiftRules.reminder(ended, open, at(0, 15, 31), onlineNow = false))
        // Offline since before the shift started: no end reminder
        val before = session("s0", at(0, 5), at(0, 6), ended = at(0, 6))
        assertNull(WorkShiftRules.reminder(before, open, at(0, 9), onlineNow = false))
        assertNull(WorkShiftRules.reminder(ended, null, at(0, 16), onlineNow = false))
    }

    @Test fun attachSkipsShiftsWhoseStartReadingIsMissing() {
        val r = listOf(reading("r1", at(0, 7), 1000), reading("r2", at(0, 15), 1100))
        val links = listOf(WorkShiftLink("a", INITIAL_VEHICLE, "r1", "r2", 0, 0), WorkShiftLink("b", INITIAL_VEHICLE, "gone", null, 0, 0))
        val shifts = WorkShiftRules.attach(links, r)
        assertEquals(listOf("a"), shifts.map { it.id })
        assertEquals(100L, shifts.single().uberKm)
    }
}
