package com.ubers.observador

import com.ubers.observador.data.db.toDomain
import com.ubers.observador.data.db.toEntity
import com.ubers.observador.data.debug.ExpenseTestSeeder
import com.ubers.observador.domain.WorkdayClock
import com.ubers.observador.domain.distance.DistanceSegment
import com.ubers.observador.domain.distance.DistanceTotals
import com.ubers.observador.domain.distance.SegmentOrigin
import com.ubers.observador.domain.expenses.ExpenseOverviews
import com.ubers.observador.domain.expenses.DistanceSource
import com.ubers.observador.domain.expenses.ExpenseEntry
import com.ubers.observador.domain.expenses.ExpenseFormat
import com.ubers.observador.domain.expenses.ExpenseInput
import com.ubers.observador.domain.expenses.ExpenseRules
import com.ubers.observador.domain.expenses.ExpenseType
import com.ubers.observador.domain.expenses.FieldResult
import com.ubers.observador.domain.expenses.Frequency
import com.ubers.observador.domain.expenses.FuelEntry
import com.ubers.observador.domain.expenses.FuelRules
import com.ubers.observador.domain.expenses.MaintenanceReminders
import com.ubers.observador.domain.expenses.Odometer
import com.ubers.observador.domain.expenses.OdometerCheck
import com.ubers.observador.domain.expenses.OdometerReading
import com.ubers.observador.domain.expenses.RecurringCost
import com.ubers.observador.domain.expenses.RecurringCosts
import com.ubers.observador.domain.expenses.RecurringType
import com.ubers.observador.domain.expenses.ReminderLevel
import com.ubers.observador.domain.expenses.SettingsSuggestions
import com.ubers.observador.domain.finance.EarningsPeriodSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/** Fase 8B: real expenses. Amounts are made up. */
class ExpensesTest {

    private val day = 86_400_000L
    private val clock = WorkdayClock(4, ZoneOffset.UTC)
    private val oct1 = LocalDate.of(2026, 10, 1)
    private fun at(d: LocalDate, hour: Int = 12) = clock.startOf(d) + (hour - 4) * 3_600_000L

    private fun fill(id: String, d: LocalDate, gallonsMilli: Long, amount: Long, odo: Long?, full: Boolean = true) =
        FuelEntry(id, at(d), gallonsMilli, amount, odometerKm = odo, fullTank = full, createdAt = 0, updatedAt = 0)

    private fun expense(id: String, d: LocalDate, type: ExpenseType, amount: Long, odo: Long? = null) =
        ExpenseEntry(id, at(d), type, amount, odometerKm = odo, createdAt = 0, updatedAt = 0)

    // --- input ---

    @Test fun fieldsAreReadExactlyAndWithinLimits() {
        assertEquals(FieldResult.Ok(2_500L), ExpenseInput.gallons("2.5"))
        assertEquals(FieldResult.Ok(3_250L), ExpenseInput.gallons("3.250"))
        assertTrue(ExpenseInput.gallons("0.05") is FieldResult.Error)
        assertTrue(ExpenseInput.gallons("21") is FieldResult.Error)
        assertTrue(ExpenseInput.gallons("1.2345") is FieldResult.Error)
        assertEquals(FieldResult.Ok(35_050L), ExpenseInput.amount("RD$350.50"))
        assertEquals(FieldResult.Ok(123_400L), ExpenseInput.amount("1,234"))
        assertTrue(ExpenseInput.amount("0.50") is FieldResult.Error)
        assertTrue(ExpenseInput.amount("100001") is FieldResult.Error)
        assertEquals(FieldResult.Ok(null), ExpenseInput.odometer("", required = false))
        assertTrue(ExpenseInput.odometer("", required = true) is FieldResult.Error)
        assertTrue(ExpenseInput.odometer("12.5", required = false) is FieldResult.Error)
        assertEquals(FieldResult.Ok(12_345L), ExpenseInput.odometer("12,345", required = true))
    }

    @Test fun gallonsFollowFromPriceAndTotal() {
        // RD$1,000 at RD$275.40 a gallon = 3.631 gal
        assertEquals(FieldResult.Ok(3_631L), ExpenseInput.gallonsFromPrice(100_000, 27_540))
        // Back from rounded gallons the price is within a centavo: 1,000 / 3.631 = 275.406
        assertEquals(27_541L, fill("f", oct1, 3_631, 100_000, null).pricePerGallonMinor)
        assertEquals("2.5", ExpenseFormat.gallons(2_500))
        assertEquals("0.1", ExpenseFormat.gallons(100))
        assertEquals("3.631", ExpenseFormat.gallons(3_631))
        // Precision: 0.1 + 0.2 gallons = 300 thousandths
        assertEquals(300L, (ExpenseInput.gallons("0.1") as FieldResult.Ok).value + (ExpenseInput.gallons("0.2") as FieldResult.Ok).value)
    }

    // --- odometer ---

    @Test fun anOdometerThatGoesDownNeedsAReset() {
        val readings = listOf(OdometerReading("r1", at(oct1), 10_000, createdAt = 0, updatedAt = 0))
        val timeline = Odometer.timeline(emptyList(), emptyList(), readings)
        assertEquals(OdometerCheck.Lower(10_000, at(oct1)), Odometer.check(9_500, at(oct1.plusDays(1)), timeline))
        assertEquals(OdometerCheck.Ok, Odometer.check(10_200, at(oct1.plusDays(1)), timeline))
        // Editing the same entry never compares it with itself
        assertEquals(OdometerCheck.Ok, Odometer.check(9_000, at(oct1), timeline, excludeId = "r1"))
    }

    // --- km per gallon ---

    @Test fun fullTankMethodCountsWhatWasBoughtAfterTheFirstFullTank() {
        val fuel = listOf(
            fill("a", oct1, 3_000, 82_000, 10_000),                          // full: start
            fill("b", oct1.plusDays(2), 1_000, 28_000, null, full = false),  // partial in between
            fill("c", oct1.plusDays(4), 2_000, 56_000, 10_300)               // full: end
        )
        val eff = FuelRules.efficiency(fuel, Odometer.timeline(fuel, emptyList(), emptyList()))
        val s = eff.last!!
        assertEquals(300L, s.km)
        assertEquals(3_000L, s.gallonsMilli)          // b + c, not a
        assertEquals(84_000L, s.amountMinor)
        assertEquals(1_000L, s.kmPerGallonTenths)     // 100.0 km/gal
        assertEquals(280L, s.costPerKmMinor)          // RD$2.80 per km
        assertEquals(DistanceSource.ODOMETER, s.source)
    }

    @Test fun noStretchAcrossAResetAndGpsFillsInWithoutOdometer() {
        val fuel = listOf(fill("a", oct1, 2_000, 55_000, 50_000), fill("c", oct1.plusDays(3), 2_000, 55_000, 200))
        val readings = listOf(OdometerReading("r", at(oct1.plusDays(1)), 0, isReset = true, createdAt = 0, updatedAt = 0))
        val timeline = Odometer.timeline(fuel, emptyList(), readings)
        assertTrue(FuelRules.efficiency(fuel, timeline).stretches.isEmpty())
        val withGps = FuelRules.efficiency(fuel, timeline) { _, _ -> 190L }
        assertEquals(DistanceSource.GPS, withGps.last!!.source)
        assertEquals(190L, withGps.last!!.km)
    }

    @Test fun oneFullTankAloneGivesNothing() {
        val fuel = listOf(fill("a", oct1, 2_000, 55_000, 10_000), fill("b", oct1.plusDays(1), 1_000, 27_000, 10_100, full = false))
        assertNull(FuelRules.efficiency(fuel, Odometer.timeline(fuel, emptyList(), emptyList())).last)
    }

    @Test fun medianOfTheLastFiveStretches() {
        var odo = 1_000L
        val fuel = (0..6).map { i ->
            val km = listOf(0, 100, 200, 300, 90, 110, 95)[i]
            odo += km
            fill("f$i", oct1.plusDays(i.toLong()), 1_000, 27_000, odo)
        }
        val eff = FuelRules.efficiency(fuel, Odometer.timeline(fuel, emptyList(), emptyList()))
        assertEquals(6, eff.stretches.size)
        assertEquals(5, eff.sample)
        // Last five: 200, 300, 90, 110, 95 km per gallon -> median 110
        assertEquals(1_100L, eff.medianKmPerGallonTenths)
    }

    // --- fixed costs ---

    @Test fun aMonthlyCostAddsUpExactlyOverItsMonth() {
        val cuota = RecurringCost("c", RecurringType.BIKE_PAYMENT, 450_001, frequency = Frequency.MONTHLY,
            startsOn = LocalDate.of(2026, 1, 1), createdAt = 0, updatedAt = 0)
        listOf(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 4, 1), LocalDate.of(2026, 10, 1)).forEach { first ->
            assertEquals(450_001L, RecurringCosts.amountBetween(cuota, first, first.withDayOfMonth(first.lengthOfMonth())))
        }
        // 28, 30 and 31 days: a day is a different share
        assertEquals(450_001L / 28, RecurringCosts.amountOn(cuota, LocalDate.of(2026, 2, 1)))
    }

    @Test fun weeklyAndBiweeklyCostsRepeatFromTheirStart() {
        val start = LocalDate.of(2026, 10, 5)
        val weekly = RecurringCost("w", RecurringType.DATA_PLAN, 70_000, frequency = Frequency.WEEKLY, startsOn = start, createdAt = 0, updatedAt = 0)
        assertEquals(10_000L, RecurringCosts.amountOn(weekly, start))
        assertEquals(140_000L, RecurringCosts.amountBetween(weekly, start, start.plusDays(13)))
        assertEquals(0L, RecurringCosts.amountOn(weekly, start.minusDays(1)))
        val biweekly = weekly.copy(frequency = Frequency.BIWEEKLY, amountMinor = 150_000, endsOn = start.plusDays(14))
        assertEquals(150_000L, RecurringCosts.amountBetween(biweekly, start, start.plusDays(60)))
    }

    // --- summary and net ---

    @Test fun periodSummaryAppliesTheWorkShareAndShowsBoth() {
        val fuel = listOf(fill("a", oct1, 2_000, 55_000, null), fill("x", oct1.plusMonths(1), 2_000, 99_999, null))
        val expenses = listOf(expense("o", oct1.plusDays(3), ExpenseType.OIL, 45_000), expense("w", oct1.plusDays(5), ExpenseType.WASH, 15_000))
        val fixed = listOf(RecurringCost("c", RecurringType.BIKE_PAYMENT, 310_000, frequency = Frequency.MONTHLY, startsOn = oct1, createdAt = 0, updatedAt = 0))
        val s = ExpenseRules.summarize(oct1, oct1.withDayOfMonth(31), clock, fuel, expenses, fixed, workSharePct = 80)
        assertEquals(55_000L, s.fuelMinor)
        assertEquals(45_000L, s.maintenanceMinor)
        assertEquals(15_000L, s.looseMinor)
        assertEquals(310_000L, s.fixedMinor)
        assertEquals(425_000L, s.totalMinor)
        assertEquals(340_000L, s.workMinor)
    }

    @Test fun aFillUpCountsOnTheWorkingDayItWasPaid() {
        // 2:00 on Oct 2 still belongs to the working day of Oct 1 (start 4:00)
        val f = FuelEntry("n", clock.startOf(oct1.plusDays(1)) - 2 * 3_600_000L, 1_000, 30_000, fullTank = false, createdAt = 0, updatedAt = 0)
        assertEquals(30_000L, ExpenseRules.summarize(oct1, oct1, clock, listOf(f), emptyList(), emptyList(), 100).fuelMinor)
    }

    private fun income(total: Long, completed: Int, confirmed: Int) =
        EarningsPeriodSummary("DOP", total, 0, 0, 0, 0, 0, total, completed, confirmed, completed - confirmed, 0, 0, 0)

    @Test fun netNeedsRealIncomeAndSaysWhenPartial() {
        val s = ExpenseRules.summarize(oct1, oct1, clock, listOf(fill("a", oct1, 1_000, 30_000, null)), emptyList(), emptyList(), 100)
        assertNull(ExpenseRules.net(income(0, 0, 0), s))
        val net = ExpenseRules.net(income(250_000, 5, 3), s)!!
        assertEquals(220_000L, net.netMinor)
        assertTrue(net.partial)
    }

    // --- maintenance ---

    @Test fun oilReminderWarnsAtTenPercentAndOverdue() {
        val oil = expense("o", oct1, ExpenseType.OIL, 45_000, odo = 10_000)
        fun reading(km: Long) = listOf(OdometerReading("r", at(oct1.plusDays(5)), km, createdAt = 0, updatedAt = 0))
        fun level(km: Long) = MaintenanceReminders.of(mapOf(ExpenseType.OIL to 2_000L), listOf(oil),
            Odometer.timeline(emptyList(), listOf(oil), reading(km))).single()
        assertEquals(ReminderLevel.OK, level(11_700).level)
        assertEquals(ReminderLevel.SOON, level(11_800).level)        // 200 left = 10 %
        assertEquals(200L, level(11_800).remainingKm)
        assertEquals(ReminderLevel.OVERDUE, level(12_050).level)
        assertEquals(-50L, level(12_050).remainingKm)
    }

    @Test fun gpsKeepsTheOdometerEstimatedAndAResetLeavesNoData() {
        val oil = expense("o", oct1, ExpenseType.OIL, 45_000, odo = 10_000)
        val timeline = Odometer.timeline(emptyList(), listOf(oil), emptyList())
        val r = MaintenanceReminders.of(mapOf(ExpenseType.OIL to 2_000L), listOf(oil), timeline) { 1_850L }.single()
        assertTrue(r.estimated)
        assertEquals(11_850L, r.currentKm)
        assertEquals(ReminderLevel.SOON, r.level)
        val reset = OdometerReading("r", at(oct1.plusDays(1)), 0, isReset = true, createdAt = 0, updatedAt = 0)
        val after = MaintenanceReminders.of(mapOf(ExpenseType.OIL to 2_000L), listOf(oil), Odometer.timeline(emptyList(), listOf(oil), listOf(reset))).single()
        assertEquals(ReminderLevel.NO_DATA, after.level)
        // No interval written: no reminder at all
        assertTrue(MaintenanceReminders.of(mapOf(ExpenseType.OIL to 0L), listOf(oil), timeline).isEmpty())
    }

    // --- suggestion ---

    @Test fun suggestionNeedsThreeStretchesAndARealDifference() {
        var odo = 0L
        val fuel = (0..3).map { i -> odo += if (i == 0) 0 else 80; fill("f$i", oct1.plusDays(i.toLong()), 1_000, 27_500, odo) }
        val eff = FuelRules.efficiency(fuel, Odometer.timeline(fuel, emptyList(), emptyList()))
        val s = SettingsSuggestions.of(eff, settingsKmPerGallon = 100.0, settingsPricePerGallon = 275.0)!!
        assertEquals(800L, s.kmPerGallonTenths)
        assertEquals(27_500L, s.pricePerGallonMinor)
        assertNull(SettingsSuggestions.of(eff, settingsKmPerGallon = 80.0, settingsPricePerGallon = 275.0))
        assertNull(SettingsSuggestions.of(FuelRules.efficiency(fuel.take(3), Odometer.timeline(fuel, emptyList(), emptyList())), 100.0, 275.0))
    }

    // --- GPS km, TEST data and storage ---

    @Test fun gpsKmBetweenTwoMomentsProrateAStretchPartlyInside() {
        val s = DistanceSegment("s", oct1, 0, 10 * 60_000L, 10 * 60_000L, SegmentOrigin.AUTO_ONLINE, measuredMm = 20_000_000, createdAt = 0)
        assertEquals(20L, DistanceTotals.kmBetween(listOf(s), 0, 20 * 60_000L, 30 * 60_000L))
        assertEquals(10L, DistanceTotals.kmBetween(listOf(s), 5 * 60_000L, 20 * 60_000L, 30 * 60_000L))
        assertEquals(0L, DistanceTotals.kmBetween(listOf(s), 11 * 60_000L, 20 * 60_000L, 30 * 60_000L))
    }

    @Test fun testExpensesShowEveryCardAndSurviveTheirRows() {
        val now = at(oct1.plusDays(25), 15)
        val data = ExpenseTestSeeder.generate(now, clock)
        assertTrue(listOf(data.fuel, data.expenses, data.recurring, data.readings).flatten().all { (it as Any).toString().contains(ExpenseTestSeeder.PREFIX) })
        val overview = ExpenseOverviews.build(data.fuel, data.expenses, data.readings, emptyList(), mapOf(ExpenseType.OIL to 2_000L), 100.0, 275.0, now)
        assertEquals(3, overview.efficiency.stretches.size)
        assertEquals(ReminderLevel.SOON, overview.reminders.single().level)
        assertEquals(150L, overview.reminders.single().remainingKm)
        assertTrue(overview.suggestion != null)
        data.fuel.forEach { assertEquals(it, it.toEntity().toDomain()) }
        data.expenses.forEach { assertEquals(it, it.toEntity().toDomain()) }
        data.recurring.forEach { assertEquals(it, it.toEntity().toDomain()) }
        data.readings.forEach { assertEquals(it, it.toEntity().toDomain()) }
    }
}
