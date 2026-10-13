package com.ubers.observador

import com.ubers.observador.data.db.DistanceSegmentEntity
import com.ubers.observador.data.db.TripDistanceEntity
import com.ubers.observador.data.db.toDomain
import com.ubers.observador.data.db.toEntity
import com.ubers.observador.data.debug.DistanceTestSeeder
import com.ubers.observador.domain.WorkdayClock
import com.ubers.observador.domain.distance.TripDistance
import com.ubers.observador.domain.distance.DayDistance
import com.ubers.observador.domain.distance.DistanceConditions
import com.ubers.observador.domain.distance.DistanceFormat
import com.ubers.observador.domain.distance.DistanceGate
import com.ubers.observador.domain.distance.DistanceMode
import com.ubers.observador.domain.distance.DistanceSegment
import com.ubers.observador.domain.distance.DistanceTracker
import com.ubers.observador.domain.distance.DistanceUpdate
import com.ubers.observador.domain.distance.Geo
import com.ubers.observador.domain.distance.LocationFix
import com.ubers.observador.domain.distance.RideContext
import com.ubers.observador.domain.distance.SegmentEndReason
import com.ubers.observador.domain.distance.SegmentOrigin
import com.ubers.observador.domain.distance.TripDistanceCoverage
import com.ubers.observador.domain.distance.TripStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/** Fase 8E: kilometres from GPS positions. Positions are made up; none is ever stored. */
class DistanceTest {

    private val sec = 1_000L
    private val min = 60 * sec
    private val clock = WorkdayClock(4, ZoneOffset.UTC)
    private val day = LocalDate.of(2026, 10, 12)
    private val t0 = clock.startOf(day) + 6 * 3_600_000L // 10:00

    /** 0.0001° of latitude is about 11.1 m. */
    private val stepDeg = 0.0001
    private val baseLat = 18.4861
    private val baseLon = -69.9312

    private fun fix(t: Long, steps: Double, accuracy: Float = 5f) = LocationFix(t, baseLat + steps * stepDeg, baseLon, accuracy)

    private fun tracker(): DistanceTracker {
        var n = 0
        return DistanceTracker(newId = { "seg${++n}" })
    }

    private val online = RideContext(online = true)

    /** Moves north one step every 5 s from [from] for [count] fixes. */
    private fun drive(t: DistanceTracker, from: Long, count: Int, ctx: RideContext = online, startStep: Int = 0): DistanceUpdate {
        var out = DistanceUpdate.NOTHING
        repeat(count) { i -> out = out + t.onFix(fix(from + i * 5 * sec, (startStep + i).toDouble()), ctx) }
        return out
    }

    private fun oneStepMm() = Geo.distanceMm(fix(0, 0.0), fix(0, 1.0))

    // --- Haversine ---

    @Test fun haversineIsWithinHalfAPercentOfTheGeodesic() {
        // Reference values from geographiclib (WGS84)
        fun within(expectedM: Double, actualM: Double) = Math.abs(actualM - expectedM) / expectedM < 0.005
        assertTrue(within(111_319.49, Geo.distanceM(0.0, 0.0, 0.0, 1.0)))
        assertTrue(within(5_176.43, Geo.distanceM(18.4861, -69.9312, 18.4735, -69.8840)))       // across Santo Domingo
        assertTrue(within(133_895.44, Geo.distanceM(18.4861, -69.9312, 19.4517, -70.6970)))     // to Santiago
        assertEquals(0.0, Geo.distanceM(18.5, -69.9, 18.5, -69.9), 1e-9)
    }

    @Test fun thousandsOfStepsAddUpExactlyInMillimetres() {
        val t = tracker()
        t.start(SegmentOrigin.AUTO_ONLINE, t0, clock)
        drive(t, t0, 2001)
        assertEquals(2000 * oneStepMm(), t.open!!.measuredMm)
        assertEquals(t.open!!.measuredMm, t.open!!.emptyMm)
    }

    @Test fun aPositionNeverShowsItsCoordinates() {
        val text = fix(0, 1.0).toString()
        assertFalse(text.contains("18.48"))
        assertFalse(text.contains("69.93"))
    }

    // --- filters ---

    @Test fun impreciseAndImpossiblePositionsAreDiscarded() {
        val t = tracker()
        t.start(SegmentOrigin.AUTO_ONLINE, t0, clock)
        t.onFix(fix(t0, 0.0), online)
        t.onFix(fix(t0 + 5 * sec, 1.0, accuracy = 40f), online)          // worse than 25 m
        t.onFix(fix(t0 + 10 * sec, 500.0), online)                        // 5.5 km in 10 s: a jump
        t.onFix(fix(t0 + 15 * sec, 2.0), online)
        val s = t.open!!
        assertEquals(Geo.distanceMm(fix(0, 0.0), fix(0, 2.0)), s.measuredMm)
        assertEquals(2, s.fixesDiscarded)
        assertEquals(1L, t.diagnostics.discardedAccuracy)
        assertEquals(1L, t.diagnostics.discardedJump)
    }

    @Test fun standingAtALightAddsNothing() {
        val t = tracker()
        t.start(SegmentOrigin.AUTO_ONLINE, t0, clock)
        // Two minutes of jitter of about 3 m around the same point, 10 m accuracy
        repeat(25) { i -> t.onFix(fix(t0 + i * 5 * sec, if (i % 2 == 0) 0.0 else 0.27, accuracy = 10f), online) }
        assertEquals(0L, t.open!!.measuredMm)
        assertEquals(0L, t.open!!.secondsWithoutSignal) // standing still is not a gap
        // Then it moves on: counted from the point where it stood
        t.onFix(fix(t0 + 125 * sec, 2.0, accuracy = 10f), online)
        assertEquals(Geo.distanceMm(fix(0, 0.0), fix(0, 2.0)), t.open!!.measuredMm)
    }

    @Test fun aGapOf59SecondsIsMeasuredAndOneOf61IsAnEstimate() {
        val t = tracker()
        t.start(SegmentOrigin.AUTO_ONLINE, t0, clock)
        t.onFix(fix(t0, 0.0), online)
        t.onFix(fix(t0 + 59 * sec, 5.0), online)
        assertEquals(Geo.distanceMm(fix(0, 0.0), fix(0, 5.0)), t.open!!.measuredMm)
        t.onFix(fix(t0 + 120 * sec, 10.0), online)
        val s = t.open!!
        assertEquals(Geo.distanceMm(fix(0, 0.0), fix(0, 5.0)), s.measuredMm)
        assertEquals(Geo.distanceMm(fix(0, 5.0), fix(0, 10.0)), s.gapMm)
        assertEquals(61L, s.secondsWithoutSignal)
    }

    @Test fun moreThanTenMinutesWithoutSignalAddsNothing() {
        val t = tracker()
        t.start(SegmentOrigin.AUTO_ONLINE, t0, clock)
        t.onFix(fix(t0, 0.0), online)
        t.onFix(fix(t0 + 11 * min, 60.0), online)
        assertEquals(0L, t.open!!.measuredMm)
        assertEquals(0L, t.open!!.gapMm)
        assertEquals(660L, t.open!!.secondsWithoutSignal)
    }

    // --- buckets and trips ---

    @Test fun metresGoToPassengerPickupEmptyOrOffline() {
        val t = tracker()
        t.start(SegmentOrigin.MANUAL, t0, clock)
        drive(t, t0, 3, RideContext(online = false))                                       // 2 steps offline
        drive(t, t0 + 15 * sec, 2, online, startStep = 3)                                  // 2 steps empty
        drive(t, t0 + 25 * sec, 2, RideContext(true, "s1", TripStage.PICKUP), startStep = 5)
        drive(t, t0 + 35 * sec, 3, RideContext(true, "s1", TripStage.ON_TRIP), startStep = 7)
        val s = t.open!!
        val step = oneStepMm()
        assertEquals(2 * step, s.offlineMm)
        assertEquals(2 * step, s.emptyMm)
        assertEquals(2 * step, s.pickupMm)
        assertEquals(3 * step, s.passengerMm)
        assertEquals(s.offlineMm + s.emptyMm + s.pickupMm + s.passengerMm, s.measuredMm)
    }

    @Test fun aTripMeasuredFromPickupToTheEndIsFull() {
        val t = tracker()
        t.start(SegmentOrigin.AUTO_ONLINE, t0, clock)
        drive(t, t0, 2)
        drive(t, t0 + 10 * sec, 4, RideContext(true, "s1", TripStage.PICKUP), startStep = 2)
        drive(t, t0 + 30 * sec, 6, RideContext(true, "s1", TripStage.ON_TRIP), startStep = 6)
        val ended = t.onContext(online, t0 + 60 * sec).trips.single()
        val step = oneStepMm()
        assertEquals("s1", ended.tripSessionId)
        assertEquals(4 * step, ended.pickupMm)
        assertEquals(6 * step, ended.tripMm)
        assertEquals(TripDistanceCoverage.FULL, ended.coverage)
    }

    @Test fun aTripJoinedMidwayOrCutByAGapIsPartial() {
        val t = tracker()
        // The trip was already in course when measuring began
        t.onContext(RideContext(true, "s1", TripStage.ON_TRIP), t0)
        t.start(SegmentOrigin.AUTO_ONLINE, t0, clock)
        drive(t, t0, 3, RideContext(true, "s1", TripStage.ON_TRIP))
        assertEquals(TripDistanceCoverage.PARTIAL, t.onContext(online, t0 + 20 * sec).trips.single().coverage)
        // A gap during the next one
        drive(t, t0 + 30 * sec, 2, RideContext(true, "s2", TripStage.PICKUP), startStep = 3)
        t.onFix(fix(t0 + 3 * min, 20.0), RideContext(true, "s2", TripStage.ON_TRIP))
        val second = t.onContext(online, t0 + 4 * min).trips.single()
        assertEquals(TripDistanceCoverage.PARTIAL, second.coverage)
        assertTrue(second.gapMm > 0)
    }

    @Test fun stoppingMidTripSavesItAsPartial() {
        val t = tracker()
        t.start(SegmentOrigin.AUTO_ONLINE, t0, clock)
        drive(t, t0, 3, RideContext(true, "s1", TripStage.PICKUP))
        val out = t.stop(SegmentEndReason.PAUSED, t0 + 20 * sec)
        assertEquals(SegmentEndReason.PAUSED, out.segments.single().endReason)
        assertEquals(TripDistanceCoverage.PARTIAL, out.trips.single().coverage)
        assertNull(t.open)
    }

    // --- stretches and days ---

    @Test fun theOpenStretchIsSavedAtMostOncePerMinute() {
        val t = tracker()
        t.start(SegmentOrigin.AUTO_ONLINE, t0, clock)
        val saves = (0 until 36).count { i -> t.onFix(fix(t0 + i * 5 * sec, i.toDouble()), online).segments.isNotEmpty() }
        assertEquals(2, saves) // at 60 s and at 120 s... of 175 s
    }

    @Test fun aStretchCrossingTheStartOfTheDayIsCutThere() {
        val t = tracker()
        val start = clock.startOf(day.plusDays(1)) - 30 * sec // 3:59:30
        t.start(SegmentOrigin.AUTO_ONLINE, start, clock)
        val out = drive(t, start, 13) // one minute
        val closed = out.segments.first { it.endReason == SegmentEndReason.WORKDAY_CHANGE }
        assertEquals(day, closed.workday)
        assertEquals(clock.startOf(day.plusDays(1)), closed.endedAt)
        val next = t.open!!
        assertEquals(day.plusDays(1), next.workday)
        assertEquals(clock.startOf(day.plusDays(1)), next.startedAt)
        assertEquals(12 * oneStepMm(), closed.measuredMm + next.measuredMm)
    }

    @Test fun aDayIsTheSumOfItsStretchesAndTheNextStartsAtZero() {
        fun seg(id: String, d: LocalDate, mm: Long, gap: Long = 0) =
            DistanceSegment(id, d, t0, t0 + min, t0 + min, SegmentOrigin.AUTO_ONLINE, SegmentEndReason.WENT_OFFLINE,
                measuredMm = mm, gapMm = gap, emptyMm = mm, createdAt = t0)
        val all = listOf(seg("a", day, 10_000_000), seg("b", day, 20_500_000, gap = 1_200_000), seg("c", day.plusDays(1), 5_000_000))
        val today = DayDistance.of(day, all)
        assertEquals(30_500_000L, today.measuredMm)
        assertEquals(2, today.segments.size)
        assertEquals(0L, DayDistance.of(day.plusDays(2), all).measuredMm)
        assertEquals("30.5 km medidos + 1.2 km aprox. sin señal", DistanceFormat.total(today.measuredMm, today.gapMm))
        assertEquals("84.3", DistanceFormat.km(84_349_999))
        assertEquals("84.4", DistanceFormat.km(84_350_000))
    }

    @Test fun stretchesLeftOpenByARestartCloseAtTheirLastSave() {
        val left = DistanceSegment("x", day, t0, null, t0 + 5 * min, SegmentOrigin.MANUAL, measuredMm = 1, offlineMm = 1, createdAt = t0)
        val closed = tracker().restore(listOf(left)).segments.single()
        assertEquals(t0 + 5 * min, closed.endedAt)
        assertEquals(SegmentEndReason.RESTARTED, closed.endReason)
    }

    // --- when to measure ---

    private fun conditions(
        enabled: Boolean = true, permitted: Boolean = true, observerOn: Boolean = true,
        mode: DistanceMode = DistanceMode.AUTO_AND_MANUAL, online: Boolean = false, manual: Boolean = false
    ) = DistanceConditions(enabled, permitted, observerOn, mode, online, manual)

    @Test fun theGpsIsOnOnlyWhenMeasuring() {
        assertNull(DistanceGate.desired(conditions()))
        assertEquals(SegmentOrigin.AUTO_ONLINE, DistanceGate.desired(conditions(online = true)))
        assertEquals(SegmentOrigin.MANUAL, DistanceGate.desired(conditions(manual = true)))
        assertEquals(SegmentOrigin.MANUAL, DistanceGate.desired(conditions(online = true, manual = true)))
        assertNull(DistanceGate.desired(conditions(online = true, mode = DistanceMode.MANUAL_ONLY)))
        assertNull(DistanceGate.desired(conditions(manual = true, mode = DistanceMode.AUTO_ONLY)))
        assertNull(DistanceGate.desired(conditions(online = true, permitted = false)))
        assertNull(DistanceGate.desired(conditions(online = true, observerOn = false)))
        assertNull(DistanceGate.desired(conditions(online = true, enabled = false)))
    }

    @Test fun eachStopSaysWhy() {
        assertEquals(SegmentEndReason.PAUSED, DistanceGate.endReason(SegmentOrigin.AUTO_ONLINE, conditions(observerOn = false, online = true)))
        assertEquals(SegmentEndReason.WENT_OFFLINE, DistanceGate.endReason(SegmentOrigin.AUTO_ONLINE, conditions()))
        assertEquals(SegmentEndReason.STOPPED, DistanceGate.endReason(SegmentOrigin.MANUAL, conditions()))
        assertEquals(SegmentEndReason.PERMISSION_LOST, DistanceGate.endReason(SegmentOrigin.MANUAL, conditions(permitted = false)))
    }

    // --- privacy and storage ---

    @Test fun nothingStoredCanHoldAPosition() {
        val forbidden = listOf("lat", "lon", "lng", "coord", "address", "route", "geo")
        listOf(DistanceSegmentEntity::class.java, TripDistanceEntity::class.java, DistanceSegment::class.java, TripDistance::class.java)
            .forEach { cls ->
                cls.declaredFields.forEach { f ->
                    assertFalse("${cls.simpleName}.${f.name}", forbidden.any { f.name.lowercase().contains(it) })
                    assertFalse("${cls.simpleName}.${f.name} is a Double", f.type == java.lang.Double.TYPE || f.type == java.lang.Float.TYPE)
                }
            }
    }

    @Test fun aStretchSurvivesTheDatabaseRow() {
        val s = DistanceSegment("a", day, t0, t0 + min, t0 + min, SegmentOrigin.MANUAL, SegmentEndReason.STOPPED,
            measuredMm = 1234, gapMm = 5, offlineMm = 1234, fixesUsed = 12, fixesDiscarded = 2, secondsWithoutSignal = 61, createdAt = t0)
        assertEquals(s, s.toEntity().toDomain())
        val trip = TripDistance("sess", 10, 20, 3, TripDistanceCoverage.FULL, t0)
        assertEquals(trip.copy(tripSessionId = ""), trip.toEntity("trip-1").toDomain(""))
    }

    @Test fun testStretchesAreClosedPrefixedAndYesterdayDoesNotCountToday() {
        val now = t0 + 8 * 3_600_000L
        val all = DistanceTestSeeder.generate(now, clock)
        assertEquals(all, DistanceTestSeeder.generate(now, clock))
        assertTrue(all.all { it.id.startsWith(DistanceTestSeeder.PREFIX) && !it.isOpen })
        assertTrue(all.all { it.passengerMm + it.pickupMm + it.emptyMm + it.offlineMm == it.measuredMm })
        val today = DayDistance.of(clock.workdayOf(now), all)
        assertEquals(3, today.segments.size)
        assertEquals("57.3 km medidos + 1.2 km aprox. sin señal", DistanceFormat.total(today.measuredMm, today.gapMm))
    }
}
