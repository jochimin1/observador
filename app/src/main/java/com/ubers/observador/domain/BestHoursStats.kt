package com.ubers.observador.domain

import com.ubers.observador.data.db.OfferEntity
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * How far a time slot's numbers can be trusted, by number of offers. Every cut-off lives here.
 * An INSUFFICIENT slot is still drawn (greyed) but never ranked and never "best".
 */
enum class SampleConfidence(val label: String, val level: String) {
    INSUFFICIENT("Pocos datos", "Insuficiente"),
    LOW("Confianza baja", "Baja"),
    MEDIUM("Confianza media", "Media"),
    HIGH("Confianza alta", "Alta");

    companion object {
        const val LOW_FROM = 5
        const val MEDIUM_FROM = 15
        const val HIGH_FROM = 30

        fun of(offerCount: Int): SampleConfidence = when {
            offerCount >= HIGH_FROM -> HIGH
            offerCount >= MEDIUM_FROM -> MEDIUM
            offerCount >= LOW_FROM -> LOW
            else -> INSUFFICIENT
        }
    }
}

/**
 * The observed market in one fixed 2-hour slot of the clock (start included, end excluded).
 * Medians and percentiles are null when the slot has no offers, so "0 RD$/h" is never shown as
 * if it were a measurement. Scores and categories come from the driver's current settings.
 */
data class TimeSlotStats(
    val startHour: Int,
    val endHour: Int,
    val offerCount: Int,
    val medianRdpPerHour: Double?,
    val p25RdpPerHour: Double?,
    val p75RdpPerHour: Double?,
    val medianRdpPerKm: Double?,
    val medianScore: Double?,
    val badCount: Int,
    val goodCount: Int,
    val excellentCount: Int,
    val goodRate: Double,
    val excellentRate: Double,
    val goodOrBetterRate: Double,
    val medianPickupKm: Double?,
    val medianPickupMinutes: Double?,
    /** Distinct local dates with at least one offer in this slot. */
    val activeDays: Int,
    /** Average offers seen per hour on the days the slot had activity. */
    val offersPerHour: Double,
    val confidence: SampleConfidence,
    /** 0-100; null for INSUFFICIENT slots, which do not compete. */
    val opportunityIndex: Int?
) {
    val isValid: Boolean get() = confidence != SampleConfidence.INSUFFICIENT

    /** "≈ 1 every N min"; null when there were no offers. An observed average, not a promise. */
    val minutesPerOffer: Double? get() = if (offersPerHour > 0.0) 60.0 / offersPerHour else null
}

data class BestHoursStats(
    /** Always 12 slots, 00-02 to 22-00. */
    val slots: List<TimeSlotStats>,
    val bestSlot: TimeSlotStats?,
    /** Up to [BestHours.TOP_SLOTS] valid slots, best first. */
    val rankedSlots: List<TimeSlotStats>,
    val totalOffers: Int
)

/**
 * Vertical positions for the chart, already worked out so the Canvas only draws.
 * Each value is a share of the drawable height (0-1).
 */
data class ChartScale(
    /** One per slot; null when the slot has no offers (nothing to draw, not a zero). */
    val barFractions: List<Double?>,
    /** Where the driver's own RD$/h minimum falls; null when it is off the scale or not set. */
    val minLineFraction: Double?
)

/**
 * "Mejores horas para salir": the market the driver saw, split by hour of the day.
 *
 * It measures offers seen, not money earned: nothing here knows which offers were accepted.
 * Slots are clock hours in the phone's zone; the working-day start never shifts them (an offer
 * at 1:30 am is always in 00-02, whatever day it counts for in the dashboard list).
 */
object BestHours {

    const val SLOT_HOURS = 2
    const val SLOT_COUNT = 24 / SLOT_HOURS
    const val TOP_SLOTS = 3

    /** Periods offered for this chart. "Todo" is left out on purpose: old months are another market. */
    val PERIODS: List<StatsPeriod> = listOf(StatsPeriod.WEEK, StatsPeriod.MONTH)
    val DEFAULT_PERIOD: StatsPeriod = StatsPeriod.MONTH

    /** The tallest bar reaches 1 / 1.10 of the height, so it never touches the top edge. */
    const val CHART_HEADROOM = 1.10

    // Opportunity index weights; they add up to 1
    const val WEIGHT_HOURLY = 0.40
    const val WEIGHT_PER_KM = 0.25
    const val WEIGHT_QUALITY = 0.20
    const val WEIGHT_VOLUME = 0.15

    /**
     * Whole pipeline: rescore with the current settings (through ProfitEngine, the single source
     * of truth), group into slots, score the valid slots against each other and rank them.
     */
    fun calculate(
        offers: List<OfferEntity>,
        settings: EngineSettings,
        cuts: QualityThresholds,
        zone: ZoneId
    ): BestHoursStats {
        val scored = DashboardStats.rescore(offers, settings)
        val bySlot = scored.groupBy { slotIndexOf(it.detectedAt, zone) }
        val slots = (0 until SLOT_COUNT).map { i ->
            slotStats(i * SLOT_HOURS, bySlot[i].orEmpty(), cuts, zone)
        }
        val indexed = withOpportunityIndex(slots)
        val ranked = rankSlots(indexed)
        return BestHoursStats(indexed, ranked.firstOrNull(), ranked, offers.size)
    }

    /** Slot index 0..11 for an instant: 17:59:59 -> 8 (16-18), 18:00 -> 9 (18-20). */
    fun slotIndexOf(epochMillis: Long, zone: ZoneId): Int =
        Instant.ofEpochMilli(epochMillis).atZone(zone).hour / SLOT_HOURS

    /** Start hour of the slot an instant falls in. */
    fun timeSlotFor(epochMillis: Long, zone: ZoneId): Int = slotIndexOf(epochMillis, zone) * SLOT_HOURS

    /** Statistics of one slot from offers already rescored with the current settings. */
    fun slotStats(
        startHour: Int,
        offers: List<OfferEntity>,
        cuts: QualityThresholds,
        zone: ZoneId
    ): TimeSlotStats {
        val count = offers.size
        val perHour = offers.map { it.rdpPerHour }.sorted()
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
        val activeDays = offers.map { Instant.ofEpochMilli(it.detectedAt).atZone(zone).toLocalDate() }
            .distinct().size
        return TimeSlotStats(
            startHour = startHour,
            endHour = startHour + SLOT_HOURS,
            offerCount = count,
            medianRdpPerHour = DashboardStats.percentile(perHour, 0.5),
            p25RdpPerHour = DashboardStats.percentile(perHour, 0.25),
            p75RdpPerHour = DashboardStats.percentile(perHour, 0.75),
            medianRdpPerKm = DashboardStats.percentile(offers.map { it.rdpPerKm }.sorted(), 0.5),
            medianScore = DashboardStats.percentile(offers.map { it.score.toDouble() }.sorted(), 0.5),
            badCount = bad,
            goodCount = good,
            excellentCount = excellent,
            goodRate = rate(good, count),
            excellentRate = rate(excellent, count),
            goodOrBetterRate = rate(good + excellent, count),
            medianPickupKm = DashboardStats.percentile(offers.map { it.pickupKm }.sorted(), 0.5),
            medianPickupMinutes = DashboardStats.percentile(offers.map { it.pickupMin.toDouble() }.sorted(), 0.5),
            activeDays = activeDays,
            offersPerHour = offersPerHour(count, activeDays),
            confidence = SampleConfidence.of(count),
            opportunityIndex = null
        )
    }

    /**
     * Offers per hour on the days the slot was observed: count / (2 h x active days).
     * With every offer on one day this is count / 2 (20 offers -> 10 per hour). Over 30 days it
     * does not pile all days into one 2-hour window, which would overstate the pace 30 times.
     */
    fun offersPerHour(offerCount: Int, activeDays: Int): Double =
        if (offerCount <= 0 || activeDays <= 0) 0.0 else offerCount / (SLOT_HOURS.toDouble() * activeDays)

    /**
     * Gives every valid slot its index, measured against the best valid slot of the same period.
     * Invalid slots get null and do not set the reference values.
     */
    fun withOpportunityIndex(slots: List<TimeSlotStats>): List<TimeSlotStats> {
        val valid = slots.filter { it.isValid }
        val bestHourly = valid.maxOfOrNull { it.medianRdpPerHour ?: 0.0 } ?: 0.0
        val bestPerKm = valid.maxOfOrNull { it.medianRdpPerKm ?: 0.0 } ?: 0.0
        val bestVolume = valid.maxOfOrNull { it.offersPerHour } ?: 0.0
        return slots.map { slot ->
            slot.copy(
                opportunityIndex = if (slot.isValid) {
                    calculateOpportunityIndex(slot, bestHourly, bestPerKm, bestVolume)
                } else {
                    null
                }
            )
        }
    }

    /**
     * Opportunity index, 0-100:
     *   40 x (median RD$/h ÷ best median RD$/h of the period)
     * + 25 x (median RD$/km ÷ best median RD$/km)
     * + 20 x (share of BUENAS + EXCELENTES, already 0-1)
     * + 15 x (offers per hour ÷ busiest valid slot)
     * Each ratio is clamped to 0-1 and is 0 when the best value is 0, so the result is always
     * 0-100 and never NaN. A ratio to the best (not min-max) keeps proportions: a slot at 75% of
     * the best hourly pay scores 30 of 40, instead of 0 just for not being first.
     */
    fun calculateOpportunityIndex(
        slot: TimeSlotStats,
        bestHourly: Double,
        bestPerKm: Double,
        bestVolume: Double
    ): Int {
        val value = WEIGHT_HOURLY * ratio(slot.medianRdpPerHour ?: 0.0, bestHourly) +
            WEIGHT_PER_KM * ratio(slot.medianRdpPerKm ?: 0.0, bestPerKm) +
            WEIGHT_QUALITY * slot.goodOrBetterRate.coerceIn(0.0, 1.0) +
            WEIGHT_VOLUME * ratio(slot.offersPerHour, bestVolume)
        return (value * 100).roundToInt().coerceIn(0, 100)
    }

    /**
     * Tie-breaks, in order: higher index, higher share of good-or-better offers, higher median
     * RD$/h, more offers, earlier start hour. The last one makes the order total and repeatable.
     */
    val RANKING: Comparator<TimeSlotStats> =
        compareByDescending<TimeSlotStats> { it.opportunityIndex ?: -1 }
            .thenByDescending { it.goodOrBetterRate }
            .thenByDescending { it.medianRdpPerHour ?: 0.0 }
            .thenByDescending { it.offerCount }
            .thenBy { it.startHour }

    /** The valid slots, best first, at most [TOP_SLOTS]. Slots are never invented to fill it. */
    fun rankSlots(slots: List<TimeSlotStats>): List<TimeSlotStats> =
        slots.filter { it.isValid && it.opportunityIndex != null }.sortedWith(RANKING).take(TOP_SLOTS)

    /** Twelve empty slots, for the screen before the first query answers. */
    fun emptyStats(): BestHoursStats = calculate(
        emptyList(), EngineSettings(), QualityThresholds(), ZoneId.systemDefault()
    )

    /**
     * Bar heights against the tallest median of every slot with offers, greyed ones included:
     * hiding a tall "Pocos datos" bar would hide what was seen. Never NaN, never above 1.
     */
    fun chartScale(slots: List<TimeSlotStats>, minRdpPerHour: Double): ChartScale {
        val tallest = slots.maxOfOrNull { it.medianRdpPerHour ?: 0.0 } ?: 0.0
        if (tallest <= 0.0 || !tallest.isFinite()) return ChartScale(slots.map { null }, null)
        val top = tallest * CHART_HEADROOM
        return ChartScale(
            barFractions = slots.map { slot -> slot.medianRdpPerHour?.let { (it / top).coerceIn(0.0, 1.0) } },
            minLineFraction = minRdpPerHour.takeIf { it > 0.0 && it <= top }?.let { it / top }
        )
    }

    private fun ratio(value: Double, best: Double): Double =
        if (best <= 0.0 || !value.isFinite()) 0.0 else (value / best).coerceIn(0.0, 1.0)

    private fun rate(part: Int, total: Int): Double = if (total <= 0) 0.0 else part.toDouble() / total

    // ---- Wording. The logic above never depends on these strings. ----

    /** "18–20", "22–00" on a 24-hour phone; "6–8 pm", "10 am–12 pm", "10 pm–12 am" on a 12-hour one. */
    fun slotLabel(startHour: Int, endHour: Int, use24Hour: Boolean): String {
        val end = endHour % 24
        if (use24Hour) return "%02d–%02d".format(startHour, end)
        val fromSuffix = suffix(startHour)
        val toSuffix = suffix(end)
        return if (fromSuffix == toSuffix) {
            "${hour12(startHour)}–${hour12(end)} $toSuffix"
        } else {
            "${hour12(startHour)} $fromSuffix–${hour12(end)} $toSuffix"
        }
    }

    /** Headline form: "18:00–20:00" on a 24-hour phone; "6:00–8:00 pm" on a 12-hour one. */
    fun fullSlotLabel(startHour: Int, endHour: Int, use24Hour: Boolean): String {
        val end = endHour % 24
        if (use24Hour) return "%02d:00–%02d:00".format(startHour, end)
        val fromSuffix = suffix(startHour)
        val toSuffix = suffix(end)
        return if (fromSuffix == toSuffix) {
            "${hour12(startHour)}:00–${hour12(end)}:00 $toSuffix"
        } else {
            "${hour12(startHour)}:00 $fromSuffix–${hour12(end)}:00 $toSuffix"
        }
    }

    /** Short label under each bar: "18" or "6p". */
    fun axisLabel(startHour: Int, use24Hour: Boolean): String =
        if (use24Hour) "%02d".format(startHour) else "${hour12(startHour)}${suffix(startHour).first()}"

    /** For TalkBack: "De 6 de la tarde a 8 de la noche". */
    fun spokenRange(startHour: Int, endHour: Int): String {
        val from = spokenHour(startHour)
        val to = spokenHour(endHour % 24)
        return if (from.second == to.second && from.second.isNotEmpty()) {
            "De ${from.first} a ${to.first} ${to.second}"
        } else {
            "De ${(from.first + " " + from.second).trim()} a ${(to.first + " " + to.second).trim()}"
        }
    }

    /** What TalkBack reads for one bar; the best slot says so, since the star is only drawn. */
    fun describe(slot: TimeSlotStats, isBest: Boolean = false): String {
        val range = spokenRange(slot.startHour, slot.endHour)
        if (slot.offerCount == 0) return "$range. Sin ofertas."
        val median = slot.medianRdpPerHour?.roundToInt() ?: 0
        val goodPct = (slot.goodOrBetterRate * 100).roundToInt()
        val best = if (isBest) " Mejor franja observada." else ""
        return "$range.$best ${offersText(slot.offerCount)}. Pago mediano $median pesos por hora. " +
            "$goodPct por ciento buenas o excelentes. ${slot.confidence.label}."
    }

    /** What TalkBack reads for one row of the Top 3. */
    fun describeRank(position: Int, slot: TimeSlotStats): String {
        val median = slot.medianRdpPerHour?.roundToInt() ?: 0
        return "Puesto $position. ${spokenRange(slot.startHour, slot.endHour)}. " +
            "Índice ${slot.opportunityIndex ?: 0} de 100. Pago mediano $median pesos por hora. " +
            "${offersText(slot.offerCount)}."
    }

    private fun offersText(count: Int) = if (count == 1) "1 oferta" else "$count ofertas"

    private fun hour12(hour: Int) = if (hour % 12 == 0) 12 else hour % 12
    private fun suffix(hour: Int) = if (hour < 12) "am" else "pm"

    /** (number, part of day) as said in Spanish; midnight and noon are said whole. */
    private fun spokenHour(hour: Int): Pair<String, String> = when (hour) {
        0 -> "12 de la noche" to ""
        12 -> "12 del mediodía" to ""
        in 1..5 -> "$hour" to "de la madrugada"
        in 6..11 -> "$hour" to "de la mañana"
        in 13..18 -> "${hour - 12}" to "de la tarde"
        else -> "${hour - 12}" to "de la noche"
    }
}
