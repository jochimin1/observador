package com.ubers.observador

import com.ubers.observador.domain.distance.*
import com.ubers.observador.domain.expenses.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class VehicleCareTest {
    private val zone = ZoneId.of("UTC")
    private val day = LocalDate.of(2026, 11, 1)
    private val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
    private val hour = 3_600_000L
    private fun fill(id: String, offset: Long, km: Long?, full: Boolean = true, gallons: Long = 1000) =
        FuelEntry(id, start + offset, gallons, 30_000, odometerKm = km, fullTank = full, createdAt = start, updatedAt = start)
    private fun segment(id: String = "gps", a: Long = 0, b: Long = hour, mm: Long = 100_000_000) =
        DistanceSegment(id, day, start+a, start+b, start+b, SegmentOrigin.AUTO_ONLINE,
            measuredMm = mm, offlineMm = mm / 2, emptyMm = mm / 2, fixesUsed = 120,
            createdAt = start+a, firstFixAt = start+a, lastFixAt = start+b)
    private val oil = ExpenseEntry("oil", start, ExpenseType.OIL, 40_000, odometerKm = 1000, createdAt = start, updatedAt = start)
    private fun reading(km: Long, reset: Boolean = false) = OdometerReading("odo", start+hour, km, reset, start, start)
    private fun component(data: ExpenseData, plan: ComponentPlan = ComponentPlan(INITIAL_VEHICLE, ExpenseType.OIL, 1000, 30),
        now: Long = start+hour, gps: List<DistanceSegment> = emptyList()) = VehicleCare.components(data, listOf(plan), gps, now, zone).single()

    @Test fun partialGpsOfAWholeTankCannotBecomeFuelEfficiency() {
        val fuel = listOf(fill("a", 0, null), fill("b", 10*hour, null))
        val e = FuelRules.efficiency(fuel, emptyList()) { a,b -> FuelGpsCoverage.between(listOf(segment()), a,b) }
        assertNull(e.last)
        assertTrue(e.excludedReasons.isNotEmpty())
    }

    @Test fun completeGpsIncludesPersonalAndWorkKilometres() {
        val fuel = listOf(fill("a", 0, null), fill("b", hour, null))
        val e = FuelRules.efficiency(fuel, emptyList()) { a,b -> FuelGpsCoverage.between(listOf(segment()), a,b) }
        assertEquals(100L, e.last!!.km)
        assertEquals(DistanceSource.GPS, e.last!!.source)
        assertEquals(1000L, e.last!!.kmPerGallonTenths)
    }

    @Test fun staleOpenSegmentDoesNotExtendCoverageToTheTankEnd() {
        val gps = segment().copy(endedAt = null)
        assertFalse(FuelGpsCoverage.between(listOf(gps), start, start+4*hour).usable)
    }

    @Test fun historicalGpsWithoutFixBoundsIsNotCertified() {
        val gps = segment().copy(firstFixAt = null, lastFixAt = null)
        assertFalse(FuelGpsCoverage.between(listOf(gps), start, start+hour).usable)
    }

    @Test fun lateFirstFixAndStaleLastFixInvalidateCoverage() {
        listOf(segment().copy(firstFixAt = start+2*hour/3), segment().copy(lastFixAt = start+hour/2)).forEach {
            assertFalse(FuelGpsCoverage.between(listOf(it), start, start+hour).usable)
        }
    }

    @Test fun signalGapsAndDiscardedPositionsInvalidateFuelEvidence() {
        listOf(segment().copy(gapMm = 100), segment().copy(secondsWithoutSignal = 2), segment().copy(fixesDiscarded = 1)).forEach {
            assertFalse(FuelGpsCoverage.between(listOf(it), start, start+hour).usable)
        }
    }

    @Test fun overlapAndClippingCannotInventTankDistance() {
        assertFalse(FuelGpsCoverage.between(listOf(segment(), segment("duplicate")), start, start+hour).usable)
        assertFalse(FuelGpsCoverage.between(listOf(segment(a = -hour)), start, start+hour).usable)
        assertFalse(FuelGpsCoverage.between(listOf(segment(b = 2*hour)), start, start+hour).usable)
    }

    @Test fun minimumCoverageIsEnforcedEvenWithSmallBoundaryGaps() {
        val short = segment(b = 10*60_000).copy(firstFixAt = start+60_000, lastFixAt = start+9*60_000)
        assertFalse(FuelGpsCoverage.between(listOf(short), start, start+10*60_000).usable)
        assertTrue(FuelGpsCoverage.between(listOf(segment().copy(firstFixAt = start+30_000)), start, start+hour).usable)
    }

    @Test fun noEfficiencyAcrossVehicleChangeOrOdometerReset() {
        val fuel = listOf(fill("a", 0, 1000), fill("b", hour, 1200).copy(vehicleId = "new"))
        assertNull(FuelRules.efficiency(fuel, emptyList()).last)
        val sameMoto = fuel.map { it.copy(vehicleId = INITIAL_VEHICLE) }
        val reset = reading(0, true).copy(atMs = start+hour/2)
        assertNull(FuelRules.efficiency(sameMoto, Odometer.timeline(sameMoto, emptyList(), listOf(reset))) { _,_ -> FuelGpsEvidence(200,100) }.last)
    }

    @Test fun inconsistentOrUnchangedOdometerCannotFallBackToGps() {
        val fuel = listOf(fill("a", 0, 1000), fill("b", hour, 1000))
        assertNull(FuelRules.efficiency(fuel, Odometer.timeline(fuel, emptyList(), emptyList())) { _,_ -> FuelGpsEvidence(200,100) }.last)
        val middle = reading(900).copy(atMs = start+hour/2)
        val increasing = listOf(fuel.first(), fuel.last().copy(odometerKm = 1200))
        assertNull(FuelRules.efficiency(increasing, Odometer.timeline(increasing, emptyList(), listOf(middle))).last)
    }

    @Test fun partialFillCountsOnlyInsideFullToFullInterval() {
        val fuel = listOf(fill("a", 0, 1000), fill("partial", hour, null, false), fill("b", 2*hour, 1200))
        val e = FuelRules.efficiency(fuel, Odometer.timeline(fuel, emptyList(), emptyList()))
        assertEquals(2000L, e.last!!.gallonsMilli)
        assertEquals(200L, e.last!!.km)
    }

    @Test fun suggestionsUseOnlyFiveRecentValidIntervals() {
        val fuel = (0..8).map { fill("$it", it*hour, 1000L+it*100) }
        val e = FuelRules.efficiency(fuel, Odometer.timeline(fuel, emptyList(), emptyList()))
        assertEquals(5, e.sample)
        assertEquals(5, SettingsSuggestions.of(e, 1.0, 1.0)!!.stretches)
        assertTrue(e.confidence.contains("odómetro"))
    }

    @Test fun datesWorkWithoutOdometerAndExpireOnTheDueDay() {
        val data = ExpenseData(expenses = listOf(oil.copy(odometerKm = null)))
        val r = component(data, now = start+30*24*hour)
        assertEquals(0L, r.remainingDays)
        assertEquals(ReminderLevel.OVERDUE, r.level)
        assertNull(r.remainingKm)
    }

    @Test fun distanceExpiresExactlyAtTheThreshold() {
        val r = component(ExpenseData(expenses = listOf(oil), readings = listOf(reading(2000))))
        assertEquals(0L, r.remainingKm)
        assertEquals(ReminderLevel.OVERDUE, r.level)
    }

    @Test fun firstDateOrDistanceThresholdDeterminesReminder() {
        val data = ExpenseData(expenses = listOf(oil), readings = listOf(reading(1100)))
        assertEquals(ReminderLevel.SOON, component(data, now = start+28*24*hour).level)
        assertEquals(ReminderLevel.OVERDUE, component(data, now = start+31*24*hour).level)
        assertEquals(ReminderLevel.SOON, component(data.copy(readings = listOf(reading(1950)))).level)
    }

    @Test fun resetInvalidatesKmButPreservesDateReminder() {
        val r = component(ExpenseData(expenses = listOf(oil), readings = listOf(reading(0, true))), now = start+31*24*hour)
        assertNull(r.remainingKm)
        assertEquals(ReminderLevel.OVERDUE, r.level)
        assertTrue(r.kmProblem!!.contains("reiniciado"))
    }

    @Test fun lifeWrittenWithTheChangeSurvivesLaterPlanChanges() {
        val data = ExpenseData(expenses = listOf(oil.copy(expectedLifeKm = 2000, expectedLifeDays = 60)), readings = listOf(reading(1500)))
        val r = component(data)
        assertEquals(1500L, r.remainingKm)
        assertEquals(60L, r.remainingDays)
        assertEquals(2000L, r.plan.intervalKm)
    }

    @Test fun replacementStartsWithoutOldPlansReadingsOrFuel() {
        val fresh = VehicleProfile("new", "Otra", 70, start+hour)
        val care = VehicleCareData(listOf(VehicleProfile(retiredAt = start+hour), fresh))
        val scoped = VehicleCare.scope(ExpenseData(fuel = listOf(fill("f",0,1000)), expenses = listOf(oil), readings = listOf(reading(2000))), fresh)
        assertTrue(scoped.fuel.isEmpty() && scoped.expenses.isEmpty() && scoped.readings.isEmpty())
        assertTrue(VehicleCare.plansFor(care, mapOf(ExpenseType.OIL to 1000)).all { it.intervalKm == 0L })
        assertFalse(care.vehicles.first().contains(start+hour))
        assertTrue(fresh.contains(start+hour))
    }

    @Test fun explicitDisabledPlanOverridesLegacyInterval() {
        val care = VehicleCareData(plans = listOf(ComponentPlan(INITIAL_VEHICLE, ExpenseType.OIL)))
        assertEquals(0L, VehicleCare.plansFor(care, mapOf(ExpenseType.OIL to 1000)).first { it.type == ExpenseType.OIL }.intervalKm)
    }

    @Test fun gpsForMaintenanceIsOnlyKnownLowerBoundAndAvoidsPartialSegments() {
        val data = ExpenseData(expenses = listOf(oil))
        val r = component(data, now = start+2*hour, gps = listOf(segment()))
        assertEquals(900L, r.remainingKm)
        assertTrue(r.estimatedKm)
        val partial = component(data, now = start+2*hour, gps = listOf(segment(a = -hour)))
        assertEquals(1000L, partial.remainingKm)
    }

    @Test fun notificationKeysAreStableUntilChangePlanVehicleOrLevelChanges() {
        val data = ExpenseData(expenses = listOf(oil), readings = listOf(reading(1950)))
        val r = component(data)
        assertEquals(r.notificationKey, component(data, now = start+2*hour).notificationKey)
        assertNotEquals(r.notificationKey, component(data.copy(readings = listOf(reading(2000)))).notificationKey)
        assertNotEquals(r.notificationKey, component(data.copy(expenses = listOf(oil.copy(updatedAt = start+1)))).notificationKey)
    }

    @Test fun trackerStoresActualFixBoundsAndDiscardedFixCannotExtendThem() {
        val tracker = DistanceTracker()
        tracker.start(SegmentOrigin.AUTO_ONLINE, start, com.ubers.observador.domain.WorkdayClock(4,zone))
        tracker.onFix(LocationFix(start+10_000,18.0,-69.0,5f),RideContext(online=true))
        tracker.onFix(LocationFix(start+30_000,18.0001,-69.0,5f),RideContext(online=true))
        tracker.onFix(LocationFix(start+50_000,18.0001,-69.0,500f),RideContext(online=true))
        assertEquals(start+10_000,tracker.open!!.firstFixAt)
        assertEquals(start+30_000,tracker.open!!.lastFixAt)
        assertEquals(1,tracker.open!!.fixesDiscarded)
        val restored = DistanceTracker().restore(listOf(tracker.open!!)).segments.single()
        assertEquals(tracker.open!!.lastFixAt,restored.lastFixAt)
    }

    @Test fun overlappingGpsDoesNotDoubleCountMaintenanceDistance() {
        val data = ExpenseData(expenses=listOf(oil))
        val r = component(data, now=start+2*hour, gps=listOf(segment(),segment("duplicate")))
        assertEquals(900L,r.remainingKm)
    }

    @Test fun dueDateUsesLocalCalendarEvenWhenUtcHasChangedDay() {
        val local = ZoneId.of("America/Caracas")
        val changeAt = day.atTime(23,30).atZone(local).toInstant().toEpochMilli()
        val now = day.plusDays(1).atTime(0,30).atZone(local).toInstant().toEpochMilli()
        val data = ExpenseData(expenses=listOf(oil.copy(atMs=changeAt,odometerKm=null)))
        val result = VehicleCare.components(data,listOf(ComponentPlan(INITIAL_VEHICLE,ExpenseType.OIL,0,1)),emptyList(),now,local).single()
        assertEquals(0L,result.remainingDays)
        assertEquals(ReminderLevel.OVERDUE,result.level)
    }
}
