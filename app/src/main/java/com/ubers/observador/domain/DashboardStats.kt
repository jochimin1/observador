package com.ubers.observador.domain

import com.ubers.observador.data.db.OfferEntity
import com.ubers.observador.domain.models.UberOffer
import java.time.LocalDate
import java.time.ZoneId

/** Totals for the selected period. Every value is 0 (never NaN) when there are no offers. */
data class PeriodSummary(
    val count: Int = 0,
    val medianRdpPerKm: Double = 0.0,
    val avgRdpPerKm: Double = 0.0,
    val medianRdpPerHour: Double = 0.0,
    val avgRdpPerHour: Double = 0.0,
    val avgScore: Double = 0.0,
    val bad: Int = 0,
    val good: Int = 0,
    val excellent: Int = 0,
    /** Highest score; on a tie, the higher price. */
    val best: OfferEntity? = null
)

/** Offers of one working day, newest first. [date] is the date the working day started on. */
data class DayGroup(
    val date: LocalDate,
    val count: Int,
    val medianRdpPerKm: Double,
    val avgScore: Double,
    val offers: List<OfferEntity>
)

/** Calculations behind the dashboard, free of Android so they can be unit tested. */
object DashboardStats {

    /**
     * Recomputes score, fuel and profit with the driver's current [settings]. Stored rows keep
     * the values from the moment they were detected (exports use those); the dashboard shows
     * these, so changing a threshold re-judges the whole history by the same rule.
     */
    fun rescore(
        offers: List<OfferEntity>,
        settings: EngineSettings,
        engine: ProfitEngine = ProfitEngine()
    ): List<OfferEntity> = offers.map { o ->
        val a = engine.analyze(
            UberOffer(o.price, o.pickupKm, o.pickupMin, o.tripKm, o.tripMin, o.detectedAt),
            settings
        )
        o.copy(
            totalKm = a.totalKm,
            totalMin = a.totalMin,
            rdpPerKm = a.rdpPerKm,
            rdpPerHour = a.rdpPerHour,
            fuelCost = a.fuelCost,
            netProfit = a.netProfit,
            score = a.score
        )
    }

    fun summarize(offers: List<OfferEntity>, cuts: QualityThresholds): PeriodSummary {
        if (offers.isEmpty()) return PeriodSummary()
        val perKm = offers.map { it.rdpPerKm }
        val perHour = offers.map { it.rdpPerHour }
        var bad = 0
        var good = 0
        var excellent = 0
        for (o in offers) {
            when (cuts.classify(o.score)) {
                OfferQuality.BAD -> bad++
                OfferQuality.GOOD -> good++
                OfferQuality.EXCELLENT -> excellent++
            }
        }
        return PeriodSummary(
            count = offers.size,
            medianRdpPerKm = median(perKm),
            avgRdpPerKm = perKm.average(),
            medianRdpPerHour = median(perHour),
            avgRdpPerHour = perHour.average(),
            avgScore = offers.map { it.score }.average(),
            bad = bad,
            good = good,
            excellent = excellent,
            best = offers.maxWith(compareBy<OfferEntity>({ it.score }, { it.price }))
        )
    }

    /** Groups [offers] (newest first) by working day; groups keep that order. */
    fun groupByWorkday(offers: List<OfferEntity>, clock: WorkdayClock): List<DayGroup> =
        offers
            .groupBy { clock.workdayOf(it.detectedAt) }
            .map { (date, dayOffers) ->
                DayGroup(
                    date = date,
                    count = dayOffers.size,
                    medianRdpPerKm = median(dayOffers.map { it.rdpPerKm }),
                    avgScore = dayOffers.map { it.score }.average(),
                    offers = dayOffers
                )
            }

    /**
     * Whole percentages for [counts] that always add up to 100 (largest remainder), so a caption
     * never reads 33% + 33% + 33%. All zeros when the counts add up to 0.
     */
    fun percentages(counts: List<Int>): List<Int> {
        val total = counts.sum()
        if (total <= 0) return counts.map { 0 }
        val exact = counts.map { it * 100.0 / total }
        val result = exact.map { it.toInt() }.toMutableList()
        var missing = 100 - result.sum()
        val byRemainder = exact.indices.sortedByDescending { exact[it] - result[it] }
        for (i in byRemainder) {
            if (missing == 0) break
            result[i] += 1
            missing--
        }
        return result
    }

    /** Middle value; the mean of the two middle values when the count is even; 0 when empty. */
    fun median(values: List<Double>): Double = percentile(values.sorted(), 0.5) ?: 0.0

    /**
     * Percentile [p] (0-1) of [sorted] values, by linear interpolation between closest ranks
     * (the "type 7" method of R, Excel's PERCENTILE.INC): rank h = (n - 1) x p, then
     * x[floor(h)] + (h - floor(h)) x (x[floor(h) + 1] - x[floor(h)]).
     * P50 is the usual median. Null when there are no values; a single value is every percentile.
     */
    fun percentile(sorted: List<Double>, p: Double): Double? {
        if (sorted.isEmpty()) return null
        val h = (sorted.size - 1) * p.coerceIn(0.0, 1.0)
        val lower = h.toInt()
        val upper = minOf(lower + 1, sorted.size - 1)
        return sorted[lower] + (h - lower) * (sorted[upper] - sorted[lower])
    }

    /** Fase 5 entry point: the "Mejores horas" statistics for [offers] (see [BestHours]). */
    fun calculateBestHours(
        offers: List<OfferEntity>,
        settings: EngineSettings,
        cuts: QualityThresholds,
        zone: ZoneId
    ): BestHoursStats = BestHours.calculate(offers, settings, cuts, zone)
}
