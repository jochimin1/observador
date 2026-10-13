package com.ubers.observador.data.debug

import com.ubers.observador.domain.expenses.ExpenseData
import com.ubers.observador.domain.WorkdayClock
import com.ubers.observador.domain.expenses.ExpenseEntry
import com.ubers.observador.domain.expenses.ExpenseType
import com.ubers.observador.domain.expenses.Frequency
import com.ubers.observador.domain.expenses.FuelEntry
import com.ubers.observador.domain.expenses.OdometerReading
import com.ubers.observador.domain.expenses.RecurringCost
import com.ubers.observador.domain.expenses.RecurringType

/**
 * Debug-only TEST expenses (Fase 8B). Ids start with [PREFIX]; real rows are never touched.
 * Five fill-ups (four full tanks → three stretches of about 88 km/gal), an oil change 1,850 km
 * ago (with a 2,000 km interval it is "pronto"), loose expenses, a monthly bike payment and a
 * data plan, and an odometer reading.
 */
object ExpenseTestSeeder {

    const val PREFIX = "TEST-gasto-"
    private const val DAY = 86_400_000L

    fun generate(now: Long, clock: WorkdayClock): ExpenseData {
        fun ago(days: Int, hour: Int = 9) = clock.startOf(clock.workdayOf(now).minusDays(days.toLong())) + (hour - clock.startHour) * 3_600_000L
        fun fill(n: Int, days: Int, gallons: Long, amount: Long, odo: Long?, full: Boolean) =
            FuelEntry("${PREFIX}fuel-$n", ago(days), gallons, amount, odometerKm = odo, fullTank = full, createdAt = ago(days), updatedAt = ago(days))
        val today = clock.workdayOf(now)
        return ExpenseData(
            fuel = listOf(
                fill(1, 20, 2_800, 77_000, 12_000, true),
                fill(2, 15, 2_900, 79_750, 12_255, true),
                fill(3, 12, 1_200, 33_000, null, false),
                fill(4, 9, 1_800, 49_500, 12_520, true),
                fill(5, 3, 3_000, 82_500, 12_790, true)
            ),
            expenses = listOf(
                ExpenseEntry("${PREFIX}oil", ago(18), ExpenseType.OIL, 45_000, odometerKm = 11_050, createdAt = ago(18), updatedAt = ago(18)),
                ExpenseEntry("${PREFIX}wash", ago(6), ExpenseType.WASH, 15_000, createdAt = ago(6), updatedAt = ago(6)),
                ExpenseEntry("${PREFIX}toll", ago(2), ExpenseType.PARKING_TOLL, 5_000, createdAt = ago(2), updatedAt = ago(2))
            ),
            recurring = listOf(
                RecurringCost("${PREFIX}cuota", RecurringType.BIKE_PAYMENT, 450_000, frequency = Frequency.MONTHLY,
                    startsOn = today.minusMonths(2).withDayOfMonth(1), createdAt = now - 60 * DAY, updatedAt = now - 60 * DAY),
                RecurringCost("${PREFIX}datos", RecurringType.DATA_PLAN, 35_000, frequency = Frequency.WEEKLY,
                    startsOn = today.minusDays(40), createdAt = now - 40 * DAY, updatedAt = now - 40 * DAY)
            ),
            readings = listOf(
                OdometerReading("${PREFIX}odo", ago(1, 20), 12_900, createdAt = ago(1, 20), updatedAt = ago(1, 20))
            )
        )
    }
}
