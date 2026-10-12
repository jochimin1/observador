package com.ubers.observador.domain.intelligence

import com.ubers.observador.domain.OfferQuality
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/** Signed and absolute error of a prediction, robust (medians and quartiles, never only a mean). */
data class ErrorStats(
    val coverage: Coverage,
    val signed: Distribution,
    val medianAbsolute: Double,
    /** Median of signed percentage errors (e.g. +0.18 = 18% more than predicted). */
    val medianPercent: Double?,
    val medianAbsolutePercent: Double?
) {
    companion object {
        fun of(total: Int, values: List<Double>, percents: List<Double>): ErrorStats? {
            val signed = Distribution.of(values) ?: return null
            return ErrorStats(
                coverage = Coverage(total, values.size),
                signed = signed,
                medianAbsolute = Stats.median(values.map { abs(it) })!!,
                medianPercent = Stats.median(percents),
                medianAbsolutePercent = Stats.median(percents.map { abs(it) })
            )
        }
    }
}

/** Offered vs real money, in minor units (Long), never Double totals. */
data class MoneyDifferenceStats(
    val coverage: Coverage,
    val medianMinor: Long,
    val p25Minor: Long,
    val p75Minor: Long,
    val medianPercent: Double?
)

data class PredictionReport(
    val predictedHourly: Distribution?,
    val realizedHourly: Distribution?,
    val estimatedMinutes: Distribution?,
    val actualEngagedMinutes: Distribution?,
    /** Minutes: actual engaged - estimated (pickup + trip). */
    val time: ErrorStats?,
    /** RD$/h: realized - predicted. */
    val hourly: ErrorStats?,
    val money: MoneyDifferenceStats?
)

/** Prediction vs reality on the trips that have both sides. */
object PredictionErrorAnalyzer {

    fun analyze(samples: List<IntelligenceSample>): PredictionReport {
        val total = samples.size
        val timed = samples.filter { it.timeErrorMinutes != null }
        val hourly = samples.filter { it.hourlyError != null }
        val money = samples.filter { it.moneyDifferenceMinor != null }
        val diffs = money.map { it.moneyDifferenceMinor!! }
        return PredictionReport(
            predictedHourly = Distribution.of(hourly.map { it.predictedRdpPerHour!! }),
            realizedHourly = Distribution.of(hourly.map { it.realizedGrossPerEngagedHour!! }),
            estimatedMinutes = Distribution.of(timed.map { it.estimatedEngagedMinutes!!.toDouble() }),
            actualEngagedMinutes = Distribution.of(timed.map { it.actualEngagedMinutes!! }),
            time = ErrorStats.of(total, timed.map { it.timeErrorMinutes!! }, timed.mapNotNull { it.timeErrorPercent }),
            hourly = ErrorStats.of(total, hourly.map { it.hourlyError!! }, hourly.mapNotNull { it.hourlyErrorPercent }),
            money = if (diffs.isEmpty()) null else MoneyDifferenceStats(
                coverage = Coverage(total, diffs.size),
                medianMinor = Stats.medianMinor(diffs)!!,
                p25Minor = Stats.percentileMinor(diffs, 0.25)!!,
                p75Minor = Stats.percentileMinor(diffs, 0.75)!!,
                medianPercent = Stats.median(money.mapNotNull { it.moneyDifferencePercent })
            )
        )
    }
}

/** Real result of a group of trips (a score bucket, a category, a pickup range, an hour slot). */
data class GroupPerformance(
    val label: String,
    val tripCount: Int,
    val financialCoverage: Coverage,
    val hourlyN: Int,
    val medianEarningsMinor: Long?,
    val hourly: Distribution?,
    val medianTimeErrorPercent: Double?,
    val medianMoneyDifferenceMinor: Long?,
    val medianEngagedMinutes: Double?,
    val belowTargetRate: Double?
) {
    val confidence: SampleConfidence get() = SampleConfidence.of(hourlyN)
    val medianHourly: Double? get() = hourly?.median

    companion object {
        fun of(label: String, samples: List<IntelligenceSample>, targetHourly: Double?): GroupPerformance {
            val withHourly = samples.mapNotNull { it.realizedGrossPerEngagedHour }
            val earnings = samples.mapNotNull { it.actualEarnings?.minorUnits }
            return GroupPerformance(
                label = label,
                tripCount = samples.size,
                financialCoverage = Coverage(samples.size, earnings.size),
                hourlyN = withHourly.size,
                medianEarningsMinor = Stats.medianMinor(earnings),
                hourly = Distribution.of(withHourly),
                medianTimeErrorPercent = Stats.median(samples.mapNotNull { it.timeErrorPercent }),
                medianMoneyDifferenceMinor = Stats.medianMinor(samples.mapNotNull { it.moneyDifferenceMinor }),
                medianEngagedMinutes = Stats.median(samples.mapNotNull { it.actualEngagedMinutes }),
                belowTargetRate = if (targetHourly == null || withHourly.isEmpty()) null
                else withHourly.count { it < targetHourly }.toDouble() / withHourly.size
            )
        }
    }
}

enum class Monotonicity { INCREASING, NON_MONOTONIC, INSUFFICIENT_DATA }

enum class OutcomeLabel { MEETS_TARGET, BELOW_TARGET }

/** Category at detection x real result against the hourly target. */
data class OutcomeMatrix(val targetHourly: Double, val cells: Map<OfferQuality, Map<OutcomeLabel, Int>>) {
    fun count(category: OfferQuality, outcome: OutcomeLabel): Int = cells[category]?.get(outcome) ?: 0

    /** "Predicción favorable que no alcanzó el objetivo": EXCELENTE but below target. */
    val favourableBelowTarget: Int get() = count(OfferQuality.EXCELLENT, OutcomeLabel.BELOW_TARGET)
}

data class ComponentCorrelation(val component: ScoreComponent, val rho: Double?, val n: Int)

enum class ScoreComponent { PREDICTED_HOURLY, EARNINGS_PER_ESTIMATED_KM, PICKUP_KM, PICKUP_MINUTES }

/** How the accepted trips were judged when they appeared. Not a verdict on the driver. */
data class DecisionQuality(
    val acceptedWithCategory: Int,
    val acceptedExcellentRate: Double?,
    val acceptedGoodOrBetterRate: Double?,
    val acceptedBadRate: Double?,
    val acceptedBelowTargetRate: Double?
)

data class ScorePerformance(
    val buckets: List<GroupPerformance>,
    val categories: Map<OfferQuality, GroupPerformance>,
    val spearman: Double?,
    val spearmanN: Int,
    val bucketMonotonicity: Monotonicity,
    val categoryMonotonicity: Monotonicity,
    /** Adjacent categories whose median results are within [IntelligencePolicy.COLLAPSE_RATIO]. */
    val collapsedCategories: List<Pair<OfferQuality, OfferQuality>>,
    val topQuartileMedian: Double?,
    val bottomQuartileMedian: Double?,
    val quartileN: Int,
    val components: List<ComponentCorrelation>,
    val matrix: OutcomeMatrix?,
    val decisionQuality: DecisionQuality
) {
    /** Top 25% score minus bottom 25% score, in real RD$/h: the model's ability to rank. */
    val topBottomSpread: Double?
        get() = if (topQuartileMedian != null && bottomQuartileMedian != null) topQuartileMedian - bottomQuartileMedian else null
}

/**
 * "Does a higher score bring better trips?" Measured on the historical score and category (the
 * rules of that moment), never assumed. A score is a ranking, not a probability.
 */
object ScorePerformanceAnalyzer {

    val BUCKETS = listOf(0..19, 20..39, 40..59, 60..79, 80..100)

    fun analyze(samples: List<IntelligenceSample>, currentTargetHourly: Double?): ScorePerformance {
        val scored = samples.filter { it.scoreAtDetection != null }
        val buckets = BUCKETS.map { range ->
            GroupPerformance.of("${range.first}–${range.last}", scored.filter { it.scoreAtDetection!! in range }, currentTargetHourly)
        }
        val categorised = samples.filter { it.categoryAtDetection != null }
        val categories = OfferQuality.entries.associateWith { q ->
            GroupPerformance.of(q.label, categorised.filter { it.categoryAtDetection == q }, currentTargetHourly)
        }
        val ranked = scored.filter { it.realizedGrossPerEngagedHour != null }
        val (top, bottom) = quartiles(ranked) { it.scoreAtDetection!!.toDouble() }
        return ScorePerformance(
            buckets = buckets,
            categories = categories,
            spearman = Spearman.rho(ranked.map { it.scoreAtDetection!!.toDouble() }, ranked.map { it.realizedGrossPerEngagedHour!! }),
            spearmanN = ranked.size,
            bucketMonotonicity = monotonicity(buckets),
            categoryMonotonicity = monotonicity(OfferQuality.entries.map { categories.getValue(it) }),
            collapsedCategories = collapsed(categories),
            topQuartileMedian = top,
            bottomQuartileMedian = bottom,
            quartileN = ranked.size,
            components = components(samples),
            matrix = currentTargetHourly?.let { matrix(categorised, it) },
            decisionQuality = decisionQuality(categorised, currentTargetHourly)
        )
    }

    /** Median result of the top and bottom quarter by [key]; null below [IntelligencePolicy.MIN_QUARTILE_N]. */
    fun quartiles(samples: List<IntelligenceSample>, key: (IntelligenceSample) -> Double): Pair<Double?, Double?> {
        if (samples.size < IntelligencePolicy.MIN_QUARTILE_N) return null to null
        val sorted = samples.sortedWith(compareBy<IntelligenceSample>(key).thenBy { it.tripId })
        val quarter = sorted.size / 4
        val bottom = sorted.take(quarter).mapNotNull { it.realizedGrossPerEngagedHour }
        val top = sorted.takeLast(quarter).mapNotNull { it.realizedGrossPerEngagedHour }
        return Stats.median(top) to Stats.median(bottom)
    }

    /** Groups with too few results are skipped; with fewer than two left there is nothing to compare. */
    fun monotonicity(groups: List<GroupPerformance>): Monotonicity {
        val medians = groups.filter { it.hourlyN >= IntelligencePolicy.MIN_BUCKET_N }.mapNotNull { it.medianHourly }
        if (medians.size < 2) return Monotonicity.INSUFFICIENT_DATA
        return if (medians.zipWithNext().all { (a, b) -> b > a }) Monotonicity.INCREASING else Monotonicity.NON_MONOTONIC
    }

    private fun collapsed(categories: Map<OfferQuality, GroupPerformance>): List<Pair<OfferQuality, OfferQuality>> =
        OfferQuality.entries.zipWithNext().filter { (a, b) ->
            val ga = categories.getValue(a)
            val gb = categories.getValue(b)
            val ma = ga.medianHourly
            val mb = gb.medianHourly
            ga.hourlyN >= IntelligencePolicy.MIN_BUCKET_N && gb.hourlyN >= IntelligencePolicy.MIN_BUCKET_N &&
                ma != null && mb != null && ma > 0 && abs(mb - ma) / ma <= IntelligencePolicy.COLLAPSE_RATIO
        }

    private fun components(samples: List<IntelligenceSample>): List<ComponentCorrelation> {
        val usable = samples.filter { it.features != null && it.realizedGrossPerEngagedHour != null }
        fun corr(c: ScoreComponent, value: (IntelligenceSample) -> Double?): ComponentCorrelation {
            val pairs = usable.mapNotNull { s -> value(s)?.let { it to s.realizedGrossPerEngagedHour!! } }
            return ComponentCorrelation(c, Spearman.rho(pairs.map { it.first }, pairs.map { it.second }), pairs.size)
        }
        return listOf(
            corr(ScoreComponent.PREDICTED_HOURLY) { it.predictedRdpPerHour },
            corr(ScoreComponent.EARNINGS_PER_ESTIMATED_KM) { it.earningsPerEstimatedKm },
            corr(ScoreComponent.PICKUP_KM) { it.features!!.pickupKm },
            corr(ScoreComponent.PICKUP_MINUTES) { it.features!!.pickupMinutes.toDouble() }
        )
    }

    private fun matrix(samples: List<IntelligenceSample>, target: Double): OutcomeMatrix {
        val withResult = samples.filter { it.realizedGrossPerEngagedHour != null }
        return OutcomeMatrix(target, OfferQuality.entries.associateWith { q ->
            val group = withResult.filter { it.categoryAtDetection == q }
            mapOf(
                OutcomeLabel.MEETS_TARGET to group.count { it.realizedGrossPerEngagedHour!! >= target },
                OutcomeLabel.BELOW_TARGET to group.count { it.realizedGrossPerEngagedHour!! < target }
            )
        })
    }

    private fun decisionQuality(samples: List<IntelligenceSample>, target: Double?): DecisionQuality {
        val n = samples.size
        fun rate(count: Int) = if (n == 0) null else count.toDouble() / n
        val hourly = samples.mapNotNull { it.realizedGrossPerEngagedHour }
        return DecisionQuality(
            acceptedWithCategory = n,
            acceptedExcellentRate = rate(samples.count { it.categoryAtDetection == OfferQuality.EXCELLENT }),
            acceptedGoodOrBetterRate = rate(samples.count { it.categoryAtDetection != OfferQuality.BAD }),
            acceptedBadRate = rate(samples.count { it.categoryAtDetection == OfferQuality.BAD }),
            acceptedBelowTargetRate = if (target == null || hourly.isEmpty()) null else hourly.count { it < target }.toDouble() / hourly.size
        )
    }
}

/** What happened to an offer after it was shown. Only ACCEPTED is detected today. */
enum class OfferDecisionOutcome { ACCEPTED, EXPIRED, DISMISSED, UNKNOWN }

data class OfferDecisionInput(val offerHash: String, val score: Int, val category: OfferQuality, val outcome: OfferDecisionOutcome)

data class OfferDecisionSummary(
    val offersSeen: Int,
    val accepted: Int,
    val knownDecisions: Int,
    val unknownDecisions: Int,
    /** accepted / known decisions; UNKNOWN never counts. Null when no non-accepted outcome can be known. */
    val acceptanceRate: Double?,
    /** EXCELENTE offers known to be not accepted (expired or dismissed). */
    val highScoreNotAccepted: Int,
    /** EXCELENTE offers with no recorded trip and no known decision: not "missed", just unknown. */
    val highScoreWithoutKnownDecision: Int
)

/**
 * Offers vs accepted trips. 7A cannot yet tell an expired offer from a dismissed one (or from an
 * acceptance it did not see), so those are UNKNOWN and stay out of every rate. No "you lost RD$X":
 * a trip that never happened has no result.
 */
object OfferDecisionAnalyzer {

    fun summarize(offers: List<OfferDecisionInput>, declineDetectionAvailable: Boolean): OfferDecisionSummary {
        val accepted = offers.count { it.outcome == OfferDecisionOutcome.ACCEPTED }
        val notAccepted = offers.count { it.outcome == OfferDecisionOutcome.EXPIRED || it.outcome == OfferDecisionOutcome.DISMISSED }
        val known = accepted + notAccepted
        return OfferDecisionSummary(
            offersSeen = offers.size,
            accepted = accepted,
            knownDecisions = known,
            unknownDecisions = offers.size - known,
            acceptanceRate = if (!declineDetectionAvailable || known == 0) null else accepted.toDouble() / known,
            highScoreNotAccepted = offers.count {
                it.category == OfferQuality.EXCELLENT && (it.outcome == OfferDecisionOutcome.EXPIRED || it.outcome == OfferDecisionOutcome.DISMISSED)
            },
            highScoreWithoutKnownDecision = offers.count { it.category == OfferQuality.EXCELLENT && it.outcome == OfferDecisionOutcome.UNKNOWN }
        )
    }

    /** True once 7A records expired/dismissed offers. Until then acceptance rate is "no disponible". */
    const val DECLINE_DETECTION_AVAILABLE = false
}

data class ThresholdOutcome(val limit: Double, val within: GroupPerformance, val beyond: GroupPerformance)

data class ScoreThresholdOption(
    val goodFrom: Int,
    val excellentFrom: Int,
    val categories: Map<OfferQuality, GroupPerformance>,
    val monotonicity: Monotonicity,
    /** Median EXCELENTE - median MALO, when both have enough results. */
    val separation: Double?
)

data class ThresholdReport(
    val pickupKm: List<GroupPerformance>,
    val pickupMinutes: List<GroupPerformance>,
    val pickupKmLimits: List<ThresholdOutcome>,
    val scoreThresholds: List<ScoreThresholdOption>
)

/**
 * Pickup ranges and limits, and score cut-offs, evaluated only inside accepted trips. "Within
 * your accepted trips, those with pickup <= 4 km did better" — never "you would have earned more".
 */
object ThresholdAnalyzer {

    private val KM_RANGES = listOf(0.0 to 1.0, 1.0 to 2.0, 2.0 to 4.0, 4.0 to 6.0, 6.0 to Double.MAX_VALUE)
    private val MIN_RANGES = listOf(0..3, 4..6, 7..10, 11..Int.MAX_VALUE)
    val KM_LIMITS = listOf(3.0, 4.0, 5.0, 6.0)
    val GOOD_OPTIONS = listOf(50, 55, 60, 65, 70)
    val EXCELLENT_OPTIONS = listOf(75, 80, 85, 90)

    fun analyze(samples: List<IntelligenceSample>, target: Double?): ThresholdReport {
        val withOffer = samples.filter { it.features != null }
        val pickupKm = KM_RANGES.map { (from, to) ->
            val label = if (to == Double.MAX_VALUE) "${from.toInt()}+ km" else "${from.toInt()}–${to.toInt()} km"
            GroupPerformance.of(label, withOffer.filter { it.features!!.pickupKm >= from && it.features.pickupKm < to }, target)
        }
        val pickupMinutes = MIN_RANGES.map { r ->
            val label = if (r.last == Int.MAX_VALUE) "${r.first}+ min" else "${r.first}–${r.last} min"
            GroupPerformance.of(label, withOffer.filter { it.features!!.pickupMinutes in r }, target)
        }
        val limits = KM_LIMITS.map { limit ->
            ThresholdOutcome(
                limit,
                GroupPerformance.of("≤ $limit km", withOffer.filter { it.features!!.pickupKm <= limit }, target),
                GroupPerformance.of("> $limit km", withOffer.filter { it.features!!.pickupKm > limit }, target)
            )
        }
        val scored = samples.filter { it.scoreAtDetection != null }
        val options = GOOD_OPTIONS.flatMap { good ->
            EXCELLENT_OPTIONS.filter { it > good }.map { excellent ->
                val cuts = com.ubers.observador.domain.QualityThresholds(good, excellent)
                val groups = OfferQuality.entries.associateWith { q ->
                    GroupPerformance.of(q.label, scored.filter { cuts.classify(it.scoreAtDetection!!) == q }, target)
                }
                val bad = groups.getValue(OfferQuality.BAD)
                val exc = groups.getValue(OfferQuality.EXCELLENT)
                ScoreThresholdOption(
                    good, excellent, groups,
                    ScorePerformanceAnalyzer.monotonicity(OfferQuality.entries.map { groups.getValue(it) }),
                    if (bad.hourlyN >= IntelligencePolicy.MIN_BUCKET_N && exc.hourlyN >= IntelligencePolicy.MIN_BUCKET_N) {
                        exc.medianHourly!! - bad.medianHourly!!
                    } else {
                        null
                    }
                )
            }
        }
        return ThresholdReport(pickupKm, pickupMinutes, limits, options)
    }
}

enum class DayType { WEEKDAY, WEEKEND }

data class RealHourSlot(
    val slotIndex: Int,
    val performance: GroupPerformance,
    /** Fase 5's median predicted RD$/h of offers seen in this slot, kept apart (market, not results). */
    val observedMarketMedianHourly: Double?,
    /** Real median / observed market median. A ratio, not a cause. */
    val marketRealizationRatio: Double?
) {
    val startHour: Int get() = slotIndex * 2
    val lowData: Boolean get() = performance.hourlyN < IntelligencePolicy.CELL_MIN
}

data class RealBestHoursReport(
    val slots: List<RealHourSlot>,
    /** Present only when both day types have enough results. */
    val byDayType: Map<DayType, List<GroupPerformance>>?,
    /** Day of week x slot medians; cells below [IntelligencePolicy.CELL_MIN] are flagged, never hidden. */
    val heatmap: Map<DayOfWeek, List<GroupPerformance>>,
    val samplesWithAcceptance: Int
)

/**
 * Best hours by REAL results of completed trips, assigned to the 2-hour slot of acceptedAt
 * (when the driver committed). Never by completedAt; a trip crossing slots is not split. This
 * never replaces Fase 5 (offers seen): the two are shown apart and only compared.
 */
object RealBestHoursAnalyzer {

    const val SLOTS = 12

    fun analyze(
        samples: List<IntelligenceSample>,
        zone: ZoneId,
        target: Double?,
        observedMarketHourlyBySlot: Map<Int, Double> = emptyMap()
    ): RealBestHoursReport {
        val accepted = samples.filter { it.acceptedAtMs != null }
        fun slotOf(s: IntelligenceSample) = Instant.ofEpochMilli(s.acceptedAtMs!!).atZone(zone).hour / 2
        fun dayOf(s: IntelligenceSample) = Instant.ofEpochMilli(s.acceptedAtMs!!).atZone(zone).dayOfWeek
        fun slots(group: List<IntelligenceSample>) = (0 until SLOTS).map { i ->
            GroupPerformance.of("%02d–%02d".format(i * 2, i * 2 + 2), group.filter { slotOf(it) == i }, target)
        }
        val slotList = slots(accepted).mapIndexed { i, perf ->
            val market = observedMarketHourlyBySlot[i]
            RealHourSlot(i, perf, market, if (market != null && market > 0 && perf.medianHourly != null) perf.medianHourly!! / market else null)
        }
        val weekend = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
        val byType = accepted.groupBy { if (dayOf(it) in weekend) DayType.WEEKEND else DayType.WEEKDAY }
        val split = if (DayType.entries.all { t -> (byType[t]?.count { it.realizedGrossPerEngagedHour != null } ?: 0) >= IntelligencePolicy.DAY_TYPE_MIN }) {
            DayType.entries.associateWith { slots(byType.getValue(it)) }
        } else {
            null
        }
        val heatmap = DayOfWeek.entries.associateWith { d -> slots(accepted.filter { dayOf(it) == d }) }
        return RealBestHoursReport(slotList, split, heatmap, accepted.size)
    }
}

enum class DriftStatus { INSUFFICIENT_DATA, STABLE, CHANGED, CAPTURE_DROP }

data class DriftReport(
    val status: DriftStatus,
    val recentN: Int,
    val previousN: Int,
    val recentMedianHourly: Double?,
    val previousMedianHourly: Double?,
    val recentTimeErrorPercent: Double?,
    val previousTimeErrorPercent: Double?,
    val recentSpearman: Double?,
    val previousSpearman: Double?
)

/**
 * Recent window vs the one before. Fewer detected trips is a data problem before it is a market
 * change, so a big drop in captured trips is reported as CAPTURE_DROP, not as "the market got worse".
 */
object DriftAnalyzer {

    private const val DAY = 86_400_000L

    fun analyze(samples: List<IntelligenceSample>, nowMs: Long): DriftReport {
        val window = IntelligencePolicy.DRIFT_WINDOW_DAYS * DAY
        val recent = samples.filter { it.orderingTimeMs in (nowMs - window) until nowMs }
        val previous = samples.filter { it.orderingTimeMs in (nowMs - 2 * window) until (nowMs - window) }
        fun hourly(l: List<IntelligenceSample>) = Stats.median(l.mapNotNull { it.realizedGrossPerEngagedHour })
        fun time(l: List<IntelligenceSample>) = Stats.median(l.mapNotNull { it.timeErrorPercent })
        fun rho(l: List<IntelligenceSample>): Double? {
            val r = l.filter { it.scoreAtDetection != null && it.realizedGrossPerEngagedHour != null }
            return Spearman.rho(r.map { it.scoreAtDetection!!.toDouble() }, r.map { it.realizedGrossPerEngagedHour!! })
        }
        val recentValid = recent.count { it.realizedGrossPerEngagedHour != null }
        val previousValid = previous.count { it.realizedGrossPerEngagedHour != null }
        val status = when {
            previous.size >= IntelligencePolicy.DRIFT_MIN_PER_PERIOD && recent.size * 2 < previous.size -> DriftStatus.CAPTURE_DROP
            recentValid < IntelligencePolicy.DRIFT_MIN_PER_PERIOD || previousValid < IntelligencePolicy.DRIFT_MIN_PER_PERIOD -> DriftStatus.INSUFFICIENT_DATA
            else -> {
                val a = hourly(previous)!!
                val b = hourly(recent)!!
                if (a > 0 && abs(b - a) / a > IntelligencePolicy.HIGH_TIME_BIAS) DriftStatus.CHANGED else DriftStatus.STABLE
            }
        }
        return DriftReport(status, recent.size, previous.size, hourly(recent), hourly(previous), time(recent), time(previous), rho(recent), rho(previous))
    }
}
