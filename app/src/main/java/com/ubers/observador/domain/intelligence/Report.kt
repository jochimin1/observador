package com.ubers.observador.domain.intelligence

import com.ubers.observador.domain.OfferQuality
import com.ubers.observador.domain.ProfitModelVersion
import com.ubers.observador.domain.ProfitSettingsSnapshot
import java.time.ZoneId
import kotlin.math.abs

enum class IntelligenceAnomaly(val severity: Int) {
    INSUFFICIENT_DATA(100),
    LOW_FINANCIAL_COVERAGE(90),
    LOW_TIME_COVERAGE(85),
    MODEL_OVERFIT(70),
    SHADOW_REGRESSION(70),
    NON_MONOTONIC_SCORE_BUCKETS(60),
    WEAK_SCORE_CORRELATION(55),
    HIGH_TIME_BIAS(50),
    HIGH_HOURLY_PREDICTION_ERROR(45),
    MARKET_DRIFT(40),
    DATA_CAPTURE_DROP(80)
}

enum class ModelHealth { HEALTHY, NEEDS_MORE_DATA, DEGRADED, CALIBRATION_RECOMMENDED }

enum class InsightType {
    TIME_UNDERESTIMATED,
    TIME_OVERESTIMATED,
    SCORE_RANKS_WELL,
    SCORE_NOT_MONOTONIC,
    CATEGORIES_COLLAPSE,
    EARNINGS_ABOVE_OFFER,
    EARNINGS_BELOW_OFFER,
    PICKUP_DISTANCE_HURTS
}

/** One explicit-rule finding. The UI turns [type] into words; numbers come from here. */
data class Insight(
    val type: InsightType,
    val severity: Int,
    val sampleCount: Int,
    /** The figure behind it (a ratio, a rho or RD$/h, depending on [type]). */
    val metric: Double,
    /** What it is compared with, when there is something (e.g. the other side of a split). */
    val comparison: Double? = null
)

/** Spec's evaluation of one model over a period (the active one, by its historical scores). */
data class ModelEvaluationReport(
    val modelVersionId: String?,
    val periodStartMs: Long?,
    val periodEndMs: Long,
    val totalCompletedTrips: Int,
    val validSamples: Int,
    val coverage: Double?,
    val scoreSpearman: Double?,
    val predictedHourlyMedian: Double?,
    val realizedHourlyMedian: Double?,
    val hourlyMedianError: Double?,
    val hourlyMedianAbsoluteError: Double?,
    val timeMedianErrorMinutes: Double?,
    val timeMedianErrorPercent: Double?,
    val offerActualMoneyMedianDifferenceMinor: Long?,
    val scoreBuckets: List<GroupPerformance>,
    val categoryPerformance: Map<OfferQuality, GroupPerformance>,
    val sampleConfidence: SampleConfidence,
    val health: ModelHealth,
    val healthReasons: List<IntelligenceAnomaly>
)

data class IntelligenceInput(
    val dataset: IntelligenceDataset,
    val activeSettings: ProfitSettingsSnapshot,
    val activeVersion: ProfitModelVersion?,
    /** The user's own hourly goal (minimum RD$/h in Ajustes). Never an invented number. */
    val targetHourly: Double?,
    val offers: List<OfferDecisionInput>,
    val shadowCandidate: ProfitModelVersion?,
    val shadowEvaluations: List<ShadowEvaluation>,
    val observedMarketHourlyBySlot: Map<Int, Double>,
    val periodStartMs: Long?,
    val nowMs: Long,
    val zone: ZoneId
)

data class IntelligenceReport(
    val quality: DataQualityReport,
    val confidence: SampleConfidence,
    val prediction: PredictionReport,
    val score: ScorePerformance,
    val thresholds: ThresholdReport,
    val realHours: RealBestHoursReport,
    val drift: DriftReport,
    val decisions: OfferDecisionSummary,
    val calibration: CandidateComparisonReport,
    val shadow: ShadowReport?,
    val historicalModel: ModelMetrics,
    /** Share of trips whose stored score the engine reproduces exactly with the stored settings. */
    val scoreReproduction: Coverage,
    val evaluation: ModelEvaluationReport,
    /** Most important first; the UI shows only the first few (no alert fatigue). */
    val anomalies: List<IntelligenceAnomaly>,
    val topInsight: Insight?,
    val insights: List<Insight>
)

/** Glues the analyzers. Pure: Room reads before, Compose renders after; nothing here touches either. */
object IntelligenceEngine {

    fun analyze(input: IntelligenceInput, newId: () -> String): IntelligenceReport {
        val samples = input.dataset.samples
        val quality = input.dataset.quality
        val prediction = PredictionErrorAnalyzer.analyze(samples)
        val score = ScorePerformanceAnalyzer.analyze(samples, input.targetHourly)
        val drift = DriftAnalyzer.analyze(samples, input.nowMs)
        val calibration = CalibrationEngine.calibrate(samples, input.activeSettings, input.activeVersion?.id, input.targetHourly, input.nowMs, newId)
        val shadow = input.shadowCandidate?.let { ShadowAnalyzer.analyze(it.id, input.shadowEvaluations, samples, input.targetHourly) }
        val reproducible = samples.mapNotNull { ProfitModelBacktester.reproduces(it) }
        val anomalies = anomalies(quality, prediction, score, drift, calibration, shadow)
        val health = health(quality, anomalies, calibration)
        val thresholds = ThresholdAnalyzer.analyze(samples, input.targetHourly)
        val insights = InsightGenerator.generate(prediction, score, thresholds)
        return IntelligenceReport(
            quality = quality,
            confidence = quality.hourly.confidence,
            prediction = prediction,
            score = score,
            thresholds = thresholds,
            realHours = RealBestHoursAnalyzer.analyze(samples, input.zone, input.targetHourly, input.observedMarketHourlyBySlot),
            drift = drift,
            decisions = OfferDecisionAnalyzer.summarize(input.offers, OfferDecisionAnalyzer.DECLINE_DETECTION_AVAILABLE),
            calibration = calibration,
            shadow = shadow,
            historicalModel = ProfitModelBacktester.evaluateHistorical(samples, input.targetHourly),
            scoreReproduction = Coverage(reproducible.size, reproducible.count { it }),
            evaluation = ModelEvaluationReport(
                modelVersionId = input.activeVersion?.id,
                periodStartMs = input.periodStartMs,
                periodEndMs = input.nowMs,
                totalCompletedTrips = quality.completedTrips,
                validSamples = quality.usableForHourly,
                coverage = quality.hourly.ratio,
                scoreSpearman = score.spearman,
                predictedHourlyMedian = prediction.predictedHourly?.median,
                realizedHourlyMedian = prediction.realizedHourly?.median,
                hourlyMedianError = prediction.hourly?.signed?.median,
                hourlyMedianAbsoluteError = prediction.hourly?.medianAbsolute,
                timeMedianErrorMinutes = prediction.time?.signed?.median,
                timeMedianErrorPercent = prediction.time?.medianPercent,
                offerActualMoneyMedianDifferenceMinor = prediction.money?.medianMinor,
                scoreBuckets = score.buckets,
                categoryPerformance = score.categories,
                sampleConfidence = quality.hourly.confidence,
                health = health,
                healthReasons = anomalies
            ),
            anomalies = anomalies,
            topInsight = insights.firstOrNull(),
            insights = insights
        )
    }

    fun anomalies(
        quality: DataQualityReport,
        prediction: PredictionReport,
        score: ScorePerformance,
        drift: DriftReport,
        calibration: CandidateComparisonReport,
        shadow: ShadowReport?
    ): List<IntelligenceAnomaly> {
        val list = ArrayList<IntelligenceAnomaly>()
        val enough = quality.usableForHourly >= IntelligencePolicy.EXPLORATORY_FROM
        if (!enough) list += IntelligenceAnomaly.INSUFFICIENT_DATA
        if (quality.completedTrips > 0 && (quality.financial.ratio ?: 0.0) < IntelligencePolicy.LOW_COVERAGE) list += IntelligenceAnomaly.LOW_FINANCIAL_COVERAGE
        if (quality.completedTrips > 0 && (quality.time.ratio ?: 0.0) < IntelligencePolicy.LOW_COVERAGE) list += IntelligenceAnomaly.LOW_TIME_COVERAGE
        if (enough) {
            if (score.bucketMonotonicity == Monotonicity.NON_MONOTONIC || score.categoryMonotonicity == Monotonicity.NON_MONOTONIC) {
                list += IntelligenceAnomaly.NON_MONOTONIC_SCORE_BUCKETS
            }
            if (score.spearman != null && score.spearman < IntelligencePolicy.WEAK_RHO) list += IntelligenceAnomaly.WEAK_SCORE_CORRELATION
            val timeBias = prediction.time?.medianPercent
            if (timeBias != null && abs(timeBias) > IntelligencePolicy.HIGH_TIME_BIAS) list += IntelligenceAnomaly.HIGH_TIME_BIAS
            val hourlyError = prediction.hourly?.medianAbsolutePercent
            if (hourlyError != null && hourlyError > IntelligencePolicy.HIGH_HOURLY_ERROR) list += IntelligenceAnomaly.HIGH_HOURLY_PREDICTION_ERROR
        }
        if (drift.status == DriftStatus.CHANGED) list += IntelligenceAnomaly.MARKET_DRIFT
        if (drift.status == DriftStatus.CAPTURE_DROP) list += IntelligenceAnomaly.DATA_CAPTURE_DROP
        if (calibration.status == RecommendationStatus.REJECTED_OVERFIT) list += IntelligenceAnomaly.MODEL_OVERFIT
        if (shadow != null && ShadowGateReason.CRITICAL_REGRESSION in shadow.blockers) list += IntelligenceAnomaly.SHADOW_REGRESSION
        return list.sortedByDescending { it.severity }
    }

    /** Explainable: the reasons are the anomalies, never a bare traffic light. */
    fun health(quality: DataQualityReport, anomalies: List<IntelligenceAnomaly>, calibration: CandidateComparisonReport): ModelHealth {
        val problems = setOf(
            IntelligenceAnomaly.NON_MONOTONIC_SCORE_BUCKETS, IntelligenceAnomaly.WEAK_SCORE_CORRELATION,
            IntelligenceAnomaly.HIGH_TIME_BIAS, IntelligenceAnomaly.HIGH_HOURLY_PREDICTION_ERROR
        )
        return when {
            IntelligenceAnomaly.INSUFFICIENT_DATA in anomalies -> ModelHealth.NEEDS_MORE_DATA
            anomalies.any { it in problems } && (calibration.status == RecommendationStatus.SHADOW_RECOMMENDED ||
                calibration.status == RecommendationStatus.EXPERIMENTAL_CANDIDATE) -> ModelHealth.CALIBRATION_RECOMMENDED
            anomalies.any { it in problems } -> ModelHealth.DEGRADED
            quality.usableForHourly < IntelligencePolicy.MODERATE_FROM -> ModelHealth.NEEDS_MORE_DATA
            else -> ModelHealth.HEALTHY
        }
    }
}

/**
 * Explicit rules, no language model. A finding is produced only with at least
 * [IntelligencePolicy.EXPLORATORY_FROM] results behind it, and ranked by severity.
 */
object InsightGenerator {

    fun generate(prediction: PredictionReport, score: ScorePerformance, thresholds: ThresholdReport): List<Insight> {
        val list = ArrayList<Insight>()
        val min = IntelligencePolicy.EXPLORATORY_FROM
        prediction.time?.let { t ->
            val pct = t.medianPercent
            if (t.coverage.valid >= min && pct != null && abs(pct) >= 0.10) {
                list += Insight(if (pct > 0) InsightType.TIME_UNDERESTIMATED else InsightType.TIME_OVERESTIMATED, 80, t.coverage.valid, pct)
            }
        }
        if (score.spearmanN >= min && score.bucketMonotonicity == Monotonicity.NON_MONOTONIC) {
            list += Insight(InsightType.SCORE_NOT_MONOTONIC, 75, score.spearmanN, score.spearman ?: 0.0)
        }
        score.collapsedCategories.firstOrNull()?.let { (a, b) ->
            val ga = score.categories.getValue(a)
            val gb = score.categories.getValue(b)
            list += Insight(InsightType.CATEGORIES_COLLAPSE, 60, ga.hourlyN + gb.hourlyN, gb.medianHourly ?: 0.0, ga.medianHourly)
        }
        thresholds.pickupKmLimits.firstOrNull { it.limit == 4.0 }?.let { o ->
            val a = o.within.medianHourly
            val b = o.beyond.medianHourly
            if (o.within.hourlyN >= min && o.beyond.hourlyN >= IntelligencePolicy.MIN_BUCKET_N && a != null && b != null && a > 0 && (a - b) / a >= 0.15) {
                list += Insight(InsightType.PICKUP_DISTANCE_HURTS, 55, o.within.hourlyN + o.beyond.hourlyN, b, a)
            }
        }
        prediction.money?.let { m ->
            val pct = m.medianPercent
            if (m.coverage.valid >= min && pct != null && abs(pct) >= 0.05) {
                list += Insight(if (pct > 0) InsightType.EARNINGS_ABOVE_OFFER else InsightType.EARNINGS_BELOW_OFFER, 40, m.coverage.valid, pct)
            }
        }
        if (score.spearmanN >= min && score.spearman != null && score.spearman >= 0.5 && score.bucketMonotonicity == Monotonicity.INCREASING) {
            list += Insight(InsightType.SCORE_RANKS_WELL, 30, score.spearmanN, score.spearman)
        }
        return list.sortedByDescending { it.severity }
    }
}
