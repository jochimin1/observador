package com.ubers.observador.domain.intelligence

import com.ubers.observador.domain.OfferDecisionFeatures
import com.ubers.observador.domain.OfferQuality
import com.ubers.observador.domain.ProfitEngine
import com.ubers.observador.domain.ProfitSettingsSnapshot
import com.ubers.observador.domain.ScoreWeights

enum class CandidateSource { MANUAL, CALIBRATION, EXPERIMENTAL }

/** A proposed set of settings. Immutable, and never active by itself. */
data class CandidateProfitModel(
    val id: String,
    val baseModelVersionId: String?,
    val createdAtMs: Long,
    val settings: ProfitSettingsSnapshot,
    val source: CandidateSource
)

/** How well one set of settings ranks a set of accepted trips by their real result. */
data class ModelMetrics(
    val n: Int,
    /** Spearman(score, realized RD$/h): the main measure, ranking ability. */
    val spearman: Double?,
    val topQuartileMedian: Double?,
    val bottomQuartileMedian: Double?,
    /** Share of trips this model called EXCELENTE that ended below the hourly target. */
    val excellentBelowTargetRate: Double?,
    val categoryMedians: Map<OfferQuality, Double?>,
    val categoryMonotonicity: Monotonicity
) {
    val topBottomSpread: Double?
        get() = if (topQuartileMedian != null && bottomQuartileMedian != null) topQuartileMedian - bottomQuartileMedian else null
}

/** One accepted trip re-scored: never written back to the trip. */
data class BacktestRow(val tripId: String, val historicalScore: Int?, val candidateScore: Int, val realizedHourly: Double)

/**
 * Re-scores accepted trips with other settings through the SAME [ProfitEngine]. The only inputs
 * are [OfferDecisionFeatures] (what the offer showed): outcomes are labels, never inputs. Only
 * trips actually accepted and completed have a result, so this measures ranking within them —
 * never money from offers that were not taken.
 */
object ProfitModelBacktester {

    private val engine = ProfitEngine()

    /** The single scoring path for any model, current or candidate. */
    fun score(features: OfferDecisionFeatures, settings: ProfitSettingsSnapshot): Int = engine.evaluate(features, settings).score

    /** Samples a backtest can use: an offer to re-score and a real result to compare with. */
    fun usable(samples: List<IntelligenceSample>) =
        samples.filter { it.features != null && it.realizedGrossPerEngagedHour != null }

    fun rows(samples: List<IntelligenceSample>, settings: ProfitSettingsSnapshot): List<BacktestRow> =
        usable(samples).map { BacktestRow(it.tripId, it.scoreAtDetection, score(it.features!!, settings), it.realizedGrossPerEngagedHour!!) }

    /** Metrics of [settings] re-scoring the samples. */
    fun evaluate(samples: List<IntelligenceSample>, settings: ProfitSettingsSnapshot, targetHourly: Double?): ModelMetrics {
        val usable = usable(samples)
        val scores = usable.associate { it.tripId to score(it.features!!, settings) }
        return metrics(usable, { scores.getValue(it.tripId) }, { settings.thresholds.classify(scores.getValue(it.tripId)) }, targetHourly)
    }

    /** Metrics of the scores actually shown at the time (historical model, rules of that moment). */
    fun evaluateHistorical(samples: List<IntelligenceSample>, targetHourly: Double?): ModelMetrics {
        val usable = samples.filter { it.scoreAtDetection != null && it.categoryAtDetection != null && it.realizedGrossPerEngagedHour != null }
        return metrics(usable, { it.scoreAtDetection!! }, { it.categoryAtDetection!! }, targetHourly)
    }

    /** True when the stored score is exactly what the engine gives with the stored settings. */
    fun reproduces(sample: IntelligenceSample): Boolean? {
        val features = sample.features ?: return null
        val settings = sample.settingsAtDetection ?: return null
        val stored = sample.scoreAtDetection ?: return null
        return score(features, settings) == stored
    }

    private fun metrics(
        usable: List<IntelligenceSample>,
        scoreOf: (IntelligenceSample) -> Int,
        categoryOf: (IntelligenceSample) -> OfferQuality,
        target: Double?
    ): ModelMetrics {
        val (top, bottom) = ScorePerformanceAnalyzer.quartiles(usable) { scoreOf(it).toDouble() }
        val excellent = usable.filter { categoryOf(it) == OfferQuality.EXCELLENT }
        val groups = OfferQuality.entries.associateWith { q -> GroupPerformance.of(q.label, usable.filter { categoryOf(it) == q }, target) }
        return ModelMetrics(
            n = usable.size,
            spearman = Spearman.rho(usable.map { scoreOf(it).toDouble() }, usable.map { it.realizedGrossPerEngagedHour!! }),
            topQuartileMedian = top,
            bottomQuartileMedian = bottom,
            excellentBelowTargetRate = if (target == null || excellent.isEmpty()) null
            else excellent.count { it.realizedGrossPerEngagedHour!! < target }.toDouble() / excellent.size,
            categoryMedians = groups.mapValues { it.value.medianHourly },
            categoryMonotonicity = ScorePerformanceAnalyzer.monotonicity(OfferQuality.entries.map { groups.getValue(it) })
        )
    }
}

/** Older part calibrates, newer part validates. Ordered by when the offer appeared. Never random. */
object TemporalSplit {

    data class Split(val training: List<IntelligenceSample>, val validation: List<IntelligenceSample>)

    fun split(samples: List<IntelligenceSample>, trainFraction: Double = IntelligencePolicy.TRAIN_FRACTION): Split {
        val ordered = samples.sortedWith(compareBy<IntelligenceSample> { it.orderingTimeMs }.thenBy { it.tripId })
        val cut = (ordered.size * trainFraction).toInt()
        return Split(ordered.take(cut), ordered.drop(cut))
    }
}

/**
 * A small, documented search: the four weights in steps of [IntelligencePolicy.GRID_STEP] points,
 * each at least [IntelligencePolicy.GRID_MIN_WEIGHT], always adding up to 100 (84 combinations).
 * Thresholds and minimums are not searched in this version (their analysis is shown apart).
 */
object WeightGrid {

    fun all(): List<ScoreWeights> {
        val step = IntelligencePolicy.GRID_STEP
        val min = IntelligencePolicy.GRID_MIN_WEIGHT
        val result = ArrayList<ScoreWeights>()
        for (km in min..100 step step) for (hour in min..100 step step) for (pickupKm in min..100 step step) {
            val pickupMin = 100 - km - hour - pickupKm
            if (pickupMin >= min) result += ScoreWeights(km, hour, pickupKm, pickupMin)
        }
        return result
    }

    /** How far weights are from [base], in points: the complexity penalty prefers small changes. */
    fun distance(a: ScoreWeights, base: ScoreWeights): Int =
        kotlin.math.abs(a.kmPct - base.kmPct) + kotlin.math.abs(a.hourPct - base.hourPct) +
            kotlin.math.abs(a.pickupKmPct - base.pickupKmPct) + kotlin.math.abs(a.pickupMinPct - base.pickupMinPct)
}

enum class RecommendationStatus {
    NOT_ENOUGH_DATA,
    NO_CHANGE,
    EXPERIMENTAL_CANDIDATE,
    SHADOW_RECOMMENDED,
    READY_FOR_USER_REVIEW,
    REJECTED_OVERFIT
}

enum class ComparisonFlag { TRAINING_ONLY_IMPROVEMENT, VALIDATION_NOT_CONFIRMED, CRITICAL_REGRESSION, SPREAD_WORSE, TOO_FEW_SAMPLES }

data class CandidateComparisonReport(
    val totalSamples: Int,
    val trainingN: Int,
    val validationN: Int,
    val currentTraining: ModelMetrics,
    val candidateTraining: ModelMetrics,
    val currentValidation: ModelMetrics,
    val candidateValidation: ModelMetrics,
    val candidate: CandidateProfitModel?,
    val status: RecommendationStatus,
    val flags: Set<ComparisonFlag>
) {
    /** Validation rho gain: the number a recommendation stands on. */
    val validationImprovement: Double?
        get() {
            val a = currentValidation.spearman ?: return null
            val b = candidateValidation.spearman ?: return null
            return b - a
        }
}

/**
 * Calibration: search on the older 70%, confirm on the newer 30%, then decide. The objective is
 * documented: ranking ability, Spearman(score, realized RD$/h); quartile spread and the share of
 * "excellent but below target" trips must not get worse. A candidate that only wins in training
 * is rejected as overfit. The result is at most a recommendation for shadow testing: nothing here
 * can change the active model.
 */
object CalibrationEngine {

    fun calibrate(
        samples: List<IntelligenceSample>,
        current: ProfitSettingsSnapshot,
        currentVersionId: String?,
        targetHourly: Double?,
        nowMs: Long,
        newId: () -> String
    ): CandidateComparisonReport {
        val usable = ProfitModelBacktester.usable(samples)
        val split = TemporalSplit.split(usable)
        val best = WeightGrid.all()
            .map { w -> current.copy(weights = w) }
            .map { s -> s to ProfitModelBacktester.evaluate(split.training, s, targetHourly) }
            // Highest training rho; ties go to the smallest change from the current weights, then a fixed order
            .sortedWith(
                compareByDescending<Pair<ProfitSettingsSnapshot, ModelMetrics>> { it.second.spearman ?: Double.NEGATIVE_INFINITY }
                    .thenBy { WeightGrid.distance(it.first.weights, current.weights) }
                    .thenBy { it.first.weights.toString() }
            )
            .first().first
        return compare(usable, current, best, currentVersionId, targetHourly, nowMs, newId)
    }

    /** Current vs one candidate (from the search or entered by hand), with the same gates. */
    fun compare(
        samples: List<IntelligenceSample>,
        current: ProfitSettingsSnapshot,
        candidateSettings: ProfitSettingsSnapshot,
        currentVersionId: String?,
        targetHourly: Double?,
        nowMs: Long,
        newId: () -> String,
        source: CandidateSource = CandidateSource.CALIBRATION
    ): CandidateComparisonReport {
        val usable = ProfitModelBacktester.usable(samples)
        val split = TemporalSplit.split(usable)
        val ct = ProfitModelBacktester.evaluate(split.training, current, targetHourly)
        val kt = ProfitModelBacktester.evaluate(split.training, candidateSettings, targetHourly)
        val cv = ProfitModelBacktester.evaluate(split.validation, current, targetHourly)
        val kv = ProfitModelBacktester.evaluate(split.validation, candidateSettings, targetHourly)
        val flags = LinkedHashSet<ComparisonFlag>()
        val trainGain = gain(ct.spearman, kt.spearman)
        val validGain = gain(cv.spearman, kv.spearman)
        if (cv.excellentBelowTargetRate != null && kv.excellentBelowTargetRate != null &&
            kv.excellentBelowTargetRate - cv.excellentBelowTargetRate > IntelligencePolicy.CRITICAL_REGRESSION_RATE
        ) {
            flags += ComparisonFlag.CRITICAL_REGRESSION
        }
        val currentSpread = cv.topBottomSpread
        val candidateSpread = kv.topBottomSpread
        if (currentSpread != null && candidateSpread != null && candidateSpread < currentSpread) {
            flags += ComparisonFlag.SPREAD_WORSE
        }
        val status = when {
            usable.size < IntelligencePolicy.CALIBRATION_MIN_SAMPLES -> {
                flags += ComparisonFlag.TOO_FEW_SAMPLES
                RecommendationStatus.NOT_ENOUGH_DATA
            }
            candidateSettings == current || trainGain == null || trainGain < IntelligencePolicy.MIN_RHO_IMPROVEMENT -> RecommendationStatus.NO_CHANGE
            validGain == null || validGain < 0.0 -> {
                flags += ComparisonFlag.TRAINING_ONLY_IMPROVEMENT
                RecommendationStatus.REJECTED_OVERFIT
            }
            validGain < IntelligencePolicy.MIN_RHO_IMPROVEMENT -> {
                flags += ComparisonFlag.VALIDATION_NOT_CONFIRMED
                RecommendationStatus.NO_CHANGE
            }
            ComparisonFlag.CRITICAL_REGRESSION in flags || ComparisonFlag.SPREAD_WORSE in flags -> RecommendationStatus.NO_CHANGE
            usable.size < IntelligencePolicy.CANDIDATE_MIN_SAMPLES -> RecommendationStatus.EXPERIMENTAL_CANDIDATE
            else -> RecommendationStatus.SHADOW_RECOMMENDED
        }
        val candidate = if (status == RecommendationStatus.EXPERIMENTAL_CANDIDATE || status == RecommendationStatus.SHADOW_RECOMMENDED) {
            CandidateProfitModel(newId(), currentVersionId, nowMs, candidateSettings, source)
        } else {
            null
        }
        return CandidateComparisonReport(usable.size, split.training.size, split.validation.size, ct, kt, cv, kv, candidate, status, flags)
    }

    private fun gain(a: Double?, b: Double?): Double? = if (a == null || b == null) null else b - a
}
