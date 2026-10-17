package com.ubers.observador.domain.earnings

import com.ubers.observador.domain.finance.EarningsPeriodSummary
import java.time.DayOfWeek

/** Daily and weekly goals in minor units (0 = no goal). Kept in Ajustes; a profile never changes them. */
data class GoalSettings(
    val dailyMinor: Long = 0,
    val weeklyMinor: Long = 0,
    /** Decision 10: the "meta alcanzada" notification is off until the driver turns it on. */
    val notify: Boolean = false,
    val weekStart: DayOfWeek = DayOfWeek.MONDAY
) {
    val hasAny: Boolean get() = dailyMinor > 0 || weeklyMinor > 0
}

/** "A tu ritmo de hoy": real income per confirmed hour online, and the time the rest would take at that pace. */
data class GoalPace(val perHourMinor: Long, val minutesToGoal: Long)

/** Where the progress figure of a goal comes from. */
enum class GoalSource { OWN, UBER }

data class GoalProgress(
    val goalMinor: Long,
    /** Real income only (confirmed trips + confirmed period income). Pending amounts never count. */
    val earnedMinor: Long,
    val remainingMinor: Long,
    /** 0..1000 */
    val permille: Int,
    val reached: Boolean,
    val pace: GoalPace? = null,
    /** Weekly goal: what is left split over the working days left (a division, not a forecast). */
    val perDayMinor: Long? = null,
    val daysLeft: Int? = null,
    /** UBER: the figure is Uber's own "HOY" total from the map, used because it is higher than what Observador confirmed. */
    val source: GoalSource = GoalSource.OWN
)

object Goals {

    /** No pace with less than this confirmed time online today. */
    const val MIN_ONLINE_MS = 30 * 60_000L

    /** No pace with fewer trips with confirmed money today. */
    const val MIN_CONFIRMED_TRIPS = 2

    const val MAX_GOAL_MINOR = 100_000_000L

    private const val HOUR_MS = 3_600_000L
    private const val MINUTE_MS = 60_000L

    /**
     * Today's goal. The pace is real income today / confirmed hours online today, and only with
     * at least [MIN_ONLINE_MS] online and [MIN_CONFIRMED_TRIPS] trips with confirmed money; with
     * less, nothing is estimated.
     */
    fun daily(goalMinor: Long, income: EarningsPeriodSummary, confirmedOnlineMs: Long): GoalProgress? {
        val base = progress(goalMinor, income.totalObservedEarnings) ?: return null
        if (base.reached) return base
        val earned = income.totalObservedEarnings
        val enough = confirmedOnlineMs >= MIN_ONLINE_MS && income.confirmedTripCount >= MIN_CONFIRMED_TRIPS && earned > 0
        if (!enough) return base
        val perHour = divHalfUp(earned * HOUR_MS, confirmedOnlineMs)
        val minutes = ceilDiv(base.remainingMinor * confirmedOnlineMs, earned * MINUTE_MS)
        return base.copy(pace = GoalPace(perHour, minutes))
    }

    /**
     * Fase 8H: Uber's "HOY" figure (the pill above the map, read from the screen) as today's
     * progress when it is higher than the income Observador confirmed trip by trip, which stays
     * low while Uber's money screens are not recognised. Never lowers the confirmed figure and
     * has no pace (that needs confirmed trips).
     */
    fun withUberFigure(base: GoalProgress?, uberMinor: Long?): GoalProgress? {
        if (base == null || uberMinor == null || uberMinor <= base.earnedMinor) return base
        return progress(base.goalMinor, uberMinor)?.copy(source = GoalSource.UBER)
    }

    /** This week's goal, with what is left per working day in the [daysLeft] days still to come (today included). */
    fun weekly(goalMinor: Long, income: EarningsPeriodSummary, daysLeft: Int): GoalProgress? {
        val base = progress(goalMinor, income.totalObservedEarnings) ?: return null
        if (base.reached || daysLeft <= 0) return base.copy(daysLeft = daysLeft.coerceAtLeast(0))
        return base.copy(perDayMinor = ceilDiv(base.remainingMinor, daysLeft.toLong()), daysLeft = daysLeft)
    }

    fun progress(goalMinor: Long, earnedMinor: Long): GoalProgress? {
        if (goalMinor <= 0) return null
        val remaining = (goalMinor - earnedMinor).coerceAtLeast(0)
        val permille = when {
            earnedMinor <= 0 -> 0
            earnedMinor >= goalMinor -> 1000
            else -> (earnedMinor * 1000 / goalMinor).toInt()
        }
        return GoalProgress(goalMinor, earnedMinor, remaining, permille, reached = earnedMinor >= goalMinor)
    }

    private fun divHalfUp(a: Long, b: Long): Long = if (b <= 0) 0 else (a * 2 + b) / (b * 2)
    private fun ceilDiv(a: Long, b: Long): Long = if (b <= 0) 0 else (a + b - 1) / b
}
