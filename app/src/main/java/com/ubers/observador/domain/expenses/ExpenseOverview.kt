package com.ubers.observador.domain.expenses

import com.ubers.observador.domain.distance.DistanceSegment
import com.ubers.observador.domain.distance.DistanceTotals

/** Everything Gastos derives from what was written (and from the GPS km of 8E, when there are some). */
data class ExpenseOverview(
    val timeline: List<OdometerPoint> = emptyList(),
    val efficiency: FuelEfficiency = FuelEfficiency(),
    val reminders: List<MaintenanceReminder> = emptyList(),
    val suggestion: SettingsSuggestion? = null
) {
    val currentOdometer: OdometerPoint? get() = timeline.lastOrNull()
}

object ExpenseOverviews {

    fun build(
        fuel: List<FuelEntry>,
        expenses: List<ExpenseEntry>,
        readings: List<OdometerReading>,
        segments: List<DistanceSegment>,
        intervals: Map<ExpenseType, Long>,
        settingsKmPerGallon: Double,
        settingsPricePerGallon: Double,
        nowMs: Long
    ): ExpenseOverview {
        val timeline = Odometer.timeline(fuel, expenses, readings)
        val gpsBetween: (Long, Long) -> Long? = { a, b ->
            if (segments.isEmpty()) null else DistanceTotals.kmBetween(segments, a, b, nowMs).takeIf { it > 0 }
        }
        val efficiency = FuelRules.efficiency(fuel, timeline, gpsBetween)
        return ExpenseOverview(
            timeline = timeline,
            efficiency = efficiency,
            reminders = MaintenanceReminders.of(intervals, expenses, timeline) { since -> gpsBetween(since, nowMs) },
            suggestion = SettingsSuggestions.of(efficiency, settingsKmPerGallon, settingsPricePerGallon)
        )
    }
}
