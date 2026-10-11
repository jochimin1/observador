package com.ubers.observador.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The periods the dashboard summarises. [workdays] is null for the whole history. */
enum class StatsPeriod(val label: String, val workdays: Int?) {
    TODAY("Hoy", 1),
    WEEK("7 días", 7),
    MONTH("30 días", 30),
    ALL("Todo", null)
}

/**
 * A driver's working day ("jornada"). It starts at [startHour] and lasts 24 hours, so with
 * the default 4 an offer at 1:30 am still counts in the day that began the afternoon before.
 */
data class WorkdayClock(val startHour: Int, val zone: ZoneId) {

    init {
        require(startHour in 0..23) { "startHour must be 0..23, was $startHour" }
    }

    /** The working day an instant belongs to, named by the date it started on. */
    fun workdayOf(epochMillis: Long): LocalDate =
        Instant.ofEpochMilli(epochMillis).atZone(zone).minusHours(startHour.toLong()).toLocalDate()

    /** First instant of [workday]. */
    fun startOf(workday: LocalDate): Long =
        workday.atTime(startHour, 0).atZone(zone).toInstant().toEpochMilli()

    /** First instant included in [period] at [nowMillis]; null means no lower bound. */
    fun periodStart(period: StatsPeriod, nowMillis: Long): Long? {
        val days = period.workdays ?: return null
        return startOf(workdayOf(nowMillis).minusDays(days - 1L))
    }
}
