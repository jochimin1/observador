package com.ubers.observador.domain

import com.ubers.observador.domain.intelligence.DataQualityReport
import java.time.LocalDate

/**
 * The numbers behind Inicio in the Violeta theme (1.27.0): rings, the strip of days and the
 * category groups. Pure, so every percentage is tested on the JVM; the screen only draws them.
 */
object Rings {

    /** Share of [part] in [whole] as 0-100, rounded half up; 0 for an empty whole. Never outside 0-100. */
    fun percent(part: Long, whole: Long): Int {
        if (whole <= 0 || part <= 0) return 0
        if (part >= whole) return 100
        return ((part * 200 + whole) / (whole * 2)).toInt().coerceIn(0, 100)
    }

    /** The same share as 0..1 for drawing the arc. */
    fun fraction(part: Long, whole: Long): Float = percent(part, whole) / 100f

    /** A goal's progress (0..1000 permille) as a ring percentage. */
    fun ofPermille(permille: Int): Int = ((permille.coerceIn(0, 1000) + 5) / 10).coerceIn(0, 100)
}

/** One day of the strip: its working day, how many offers it had (null = not loaded in this period). */
data class StripDay(val date: LocalDate, val offers: Int?, val isToday: Boolean)

object DayStrip {

    /** The [count] working days ending today, oldest first, with the offers of [days] when loaded. */
    fun of(today: LocalDate, days: List<DayGroup>, loadedWorkdays: Int?, count: Int = 7): List<StripDay> {
        val byDate = days.associate { it.date to it.count }
        return (count - 1 downTo 0).map { back ->
            val d = today.minusDays(back.toLong())
            val loaded = loadedWorkdays == null || back < loadedWorkdays
            StripDay(d, if (loaded) byDate[d] ?: 0 else null, back == 0)
        }
    }

    /** The period that shows [date]: today alone stays «Hoy», older days need «7 días». */
    fun periodFor(date: LocalDate, today: LocalDate, current: StatsPeriod): StatsPeriod {
        val back = today.toEpochDay() - date.toEpochDay()
        val shown = current.workdays
        return if (shown == null || back < shown) current else StatsPeriod.WEEK
    }
}

/** One row of «Grupos»: a category, how many offers it had and its share of the period. */
data class QualityGroup(val quality: OfferQuality, val count: Int, val percent: Int)

object QualityGroups {
    fun of(summary: PeriodSummary): List<QualityGroup> {
        val total = summary.count.toLong()
        return listOf(
            QualityGroup(OfferQuality.EXCELLENT, summary.excellent, Rings.percent(summary.excellent.toLong(), total)),
            QualityGroup(OfferQuality.GOOD, summary.good, Rings.percent(summary.good.toLong(), total)),
            QualityGroup(OfferQuality.BAD, summary.bad, Rings.percent(summary.bad.toLong(), total))
        )
    }
}

/**
 * 1.30.0: the summary card of Análisis in Violeta. The ring is the share of the period's trips
 * with a real RD$/hour (money and times seen); the rest are counts already in «Calidad de datos».
 */
data class AnalysisHero(val percentWithResult: Int, val trips: Int, val withResult: Int, val withMoney: Int, val withGpsKm: Int)

object AnalysisHeroes {
    fun of(q: DataQualityReport): AnalysisHero = AnalysisHero(
        percentWithResult = Rings.percent(q.usableForHourly.toLong(), q.completedTrips.toLong()),
        trips = q.completedTrips,
        withResult = q.usableForHourly,
        withMoney = q.withConfirmedMoney,
        withGpsKm = q.withRealDistance
    )
}
