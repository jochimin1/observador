package com.ubers.observador.domain.intelligence

import com.ubers.observador.domain.ModelVersionSource
import com.ubers.observador.domain.OfferDecisionFeatures
import com.ubers.observador.domain.OfferQuality
import com.ubers.observador.domain.ProfitModelVersion
import com.ubers.observador.domain.ProfitSettingsSnapshot

/**
 * Two models on the same offer: the active one (what the driver sees) and the candidate,
 * computed silently. Light by design: hash, scores, categories and version ids only.
 */
data class ShadowEvaluation(
    val evaluationId: String,
    val offerHash: String,
    val evaluatedAtMs: Long,
    val activeVersionId: String,
    val activeScore: Int,
    val activeCategory: OfferQuality,
    val candidateVersionId: String,
    val candidateScore: Int,
    val candidateCategory: OfferQuality
) {
    val disagrees: Boolean get() = activeCategory != candidateCategory
}

object ShadowScoring {

    /** One id per offer and candidate: the same offer read 50 times is one evaluation. */
    fun evaluationId(offerHash: String, candidateVersionId: String) = "shadow-$candidateVersionId-$offerHash"

    /**
     * Scores [features] with both models through the same engine. The active score here is
     * recomputed with the active settings, so it equals what the overlay showed.
     */
    fun evaluate(
        offerHash: String,
        features: OfferDecisionFeatures,
        active: ProfitModelVersion,
        candidate: ProfitModelVersion,
        nowMs: Long
    ): ShadowEvaluation {
        val a = ProfitModelBacktester.score(features, active.settings)
        val c = ProfitModelBacktester.score(features, candidate.settings)
        return ShadowEvaluation(
            evaluationId = evaluationId(offerHash, candidate.id),
            offerHash = offerHash,
            evaluatedAtMs = nowMs,
            activeVersionId = active.id,
            activeScore = a,
            activeCategory = active.settings.thresholds.classify(a),
            candidateVersionId = candidate.id,
            candidateScore = c,
            candidateCategory = candidate.settings.thresholds.classify(c)
        )
    }
}

data class DisagreementOutcome(
    val activeCategory: OfferQuality,
    val candidateCategory: OfferQuality,
    val n: Int,
    val medianHourly: Double?
)

enum class ShadowGateReason { TOO_FEW_RESULTS, LOW_COVERAGE, NOT_BETTER, CRITICAL_REGRESSION }

data class ShadowReport(
    val candidateVersionId: String,
    val offersEvaluated: Int,
    val disagreements: Int,
    val withOutcome: Int,
    val activeSpearman: Double?,
    val candidateSpearman: Double?,
    val activeSpread: Double?,
    val candidateSpread: Double?,
    val activeExcellentBelowTarget: Double?,
    val candidateExcellentBelowTarget: Double?,
    /** Only offers where the two models disagree and a real result exists. */
    val disagreementOutcomes: List<DisagreementOutcome>,
    val firstEvaluatedAtMs: Long?,
    val readyForReview: Boolean,
    val blockers: Set<ShadowGateReason>
) {
    val disagreementRate: Double? get() = if (offersEvaluated == 0) null else disagreements.toDouble() / offersEvaluated
}

/**
 * The cleanest comparison: both models scored the SAME offers in the same market. Only offers
 * that became completed trips with a real result count for performance; the rest show only how
 * often the models disagree. Unaccepted offers never get an invented outcome.
 */
object ShadowAnalyzer {

    fun analyze(
        candidateVersionId: String,
        evaluations: List<ShadowEvaluation>,
        samples: List<IntelligenceSample>,
        targetHourly: Double?
    ): ShadowReport {
        val mine = evaluations.filter { it.candidateVersionId == candidateVersionId }
            .groupBy { it.offerHash }.map { (_, list) -> list.minBy { it.evaluatedAtMs } }
        val byHash = samples.filter { it.offerHash != null && it.realizedGrossPerEngagedHour != null }.associateBy { it.offerHash!! }
        val joined = mine.mapNotNull { e -> byHash[e.offerHash]?.let { e to it } }
        val outcomes = joined.map { it.second.realizedGrossPerEngagedHour!! }
        fun rho(score: (ShadowEvaluation) -> Int) = Spearman.rho(joined.map { score(it.first).toDouble() }, outcomes)
        val evalByTrip = joined.associate { it.second.tripId to it.first }
        fun spread(score: (ShadowEvaluation) -> Int): Double? {
            val (top, bottom) = ScorePerformanceAnalyzer.quartiles(joined.map { it.second }) { s ->
                score(evalByTrip.getValue(s.tripId)).toDouble()
            }
            return if (top != null && bottom != null) top - bottom else null
        }
        fun belowTarget(category: (ShadowEvaluation) -> OfferQuality): Double? {
            val excellent = joined.filter { category(it.first) == OfferQuality.EXCELLENT }
            if (targetHourly == null || excellent.isEmpty()) return null
            return excellent.count { it.second.realizedGrossPerEngagedHour!! < targetHourly }.toDouble() / excellent.size
        }
        val activeRho = rho { it.activeScore }
        val candidateRho = rho { it.candidateScore }
        val activeBelow = belowTarget { it.activeCategory }
        val candidateBelow = belowTarget { it.candidateCategory }
        val blockers = LinkedHashSet<ShadowGateReason>()
        if (joined.size < IntelligencePolicy.SHADOW_MIN_RESULTS) blockers += ShadowGateReason.TOO_FEW_RESULTS
        // Of the shadow-scored offers that became completed trips, how many have a usable result
        val hashes = mine.map { it.offerHash }.toSet()
        val tripsFromShadow = samples.count { it.offerHash != null && it.offerHash in hashes }
        if (tripsFromShadow > 0 && joined.size.toDouble() / tripsFromShadow < IntelligencePolicy.LOW_COVERAGE) {
            blockers += ShadowGateReason.LOW_COVERAGE
        }
        if (activeRho == null || candidateRho == null || candidateRho - activeRho < IntelligencePolicy.MIN_RHO_IMPROVEMENT) {
            blockers += ShadowGateReason.NOT_BETTER
        }
        if (activeBelow != null && candidateBelow != null && candidateBelow - activeBelow > IntelligencePolicy.CRITICAL_REGRESSION_RATE) {
            blockers += ShadowGateReason.CRITICAL_REGRESSION
        }
        val disagreementOutcomes = joined.filter { it.first.disagrees }
            .groupBy { it.first.activeCategory to it.first.candidateCategory }
            .map { (pair, list) -> DisagreementOutcome(pair.first, pair.second, list.size, Stats.median(list.map { it.second.realizedGrossPerEngagedHour!! })) }
            .sortedWith(compareBy({ it.activeCategory.ordinal }, { it.candidateCategory.ordinal }))
        return ShadowReport(
            candidateVersionId = candidateVersionId,
            offersEvaluated = mine.size,
            disagreements = mine.count { it.disagrees },
            withOutcome = joined.size,
            activeSpearman = activeRho,
            candidateSpearman = candidateRho,
            activeSpread = spread { it.activeScore },
            candidateSpread = spread { it.candidateScore },
            activeExcellentBelowTarget = activeBelow,
            candidateExcellentBelowTarget = candidateBelow,
            disagreementOutcomes = disagreementOutcomes,
            firstEvaluatedAtMs = mine.minOfOrNull { it.evaluatedAtMs },
            readyForReview = blockers.isEmpty(),
            blockers = blockers
        )
    }
}

/**
 * Model versions are never edited: every change is a new version. The ACTIVE model is the newest
 * version from the active history (INITIAL, USER_EDIT, SHADOW_PROMOTED, ROLLBACK). Calibration
 * candidates are versions too (CALIBRATION_RECOMMENDATION) but never active by themselves: the
 * only way in is [promote], which needs a ready shadow report AND the user's confirmation.
 */
object ModelVersionRules {

    private val ACTIVE_SOURCES = setOf(
        ModelVersionSource.INITIAL, ModelVersionSource.USER_EDIT, ModelVersionSource.SHADOW_PROMOTED, ModelVersionSource.ROLLBACK
    )

    fun activeHistory(versions: List<ProfitModelVersion>) =
        versions.filter { it.source in ACTIVE_SOURCES }.sortedBy { it.number }

    fun active(versions: List<ProfitModelVersion>): ProfitModelVersion? = activeHistory(versions).lastOrNull()

    fun previous(versions: List<ProfitModelVersion>): ProfitModelVersion? = activeHistory(versions).dropLast(1).lastOrNull()

    fun candidates(versions: List<ProfitModelVersion>) = versions.filter { it.source == ModelVersionSource.CALIBRATION_RECOMMENDATION }

    /**
     * The settings in use must match the active version; if the user edited them in Ajustes (or
     * nothing was recorded yet), a new version is created. Returns null when nothing changed.
     */
    fun ensureCurrent(versions: List<ProfitModelVersion>, inUse: ProfitSettingsSnapshot, nowMs: Long, newId: () -> String): ProfitModelVersion? {
        val active = active(versions)
        if (active != null && active.settings == inUse) return null
        return ProfitModelVersion(
            id = newId(),
            number = nextNumber(versions),
            createdAtMs = nowMs,
            source = if (active == null) ModelVersionSource.INITIAL else ModelVersionSource.USER_EDIT,
            settings = inUse
        )
    }

    /** Records a calibration result as a candidate version. Does not touch the active model. */
    fun recordCandidate(versions: List<ProfitModelVersion>, candidate: CandidateProfitModel, runId: String): ProfitModelVersion =
        ProfitModelVersion(
            id = candidate.id,
            number = nextNumber(versions),
            createdAtMs = candidate.createdAtMs,
            source = ModelVersionSource.CALIBRATION_RECOMMENDATION,
            settings = candidate.settings,
            note = "RUN:$runId"
        )

    sealed interface PromotionResult {
        data class Promoted(val version: ProfitModelVersion) : PromotionResult
        data class Refused(val reasons: Set<ShadowGateReason>) : PromotionResult
        data object NotConfirmed : PromotionResult
    }

    /** Only with a ready shadow report and an explicit confirmation from the user. */
    fun promote(
        versions: List<ProfitModelVersion>,
        candidate: ProfitModelVersion,
        shadow: ShadowReport,
        userConfirmed: Boolean,
        nowMs: Long,
        newId: () -> String
    ): PromotionResult = when {
        !userConfirmed -> PromotionResult.NotConfirmed
        shadow.candidateVersionId != candidate.id || !shadow.readyForReview -> PromotionResult.Refused(shadow.blockers.ifEmpty { setOf(ShadowGateReason.NOT_BETTER) })
        else -> PromotionResult.Promoted(
            ProfitModelVersion(newId(), nextNumber(versions), nowMs, ModelVersionSource.SHADOW_PROMOTED, candidate.settings, note = "FROM:${candidate.id}")
        )
    }

    /** Back to the previous active settings, exactly, as a new version (history is never rewritten). */
    fun rollback(versions: List<ProfitModelVersion>, nowMs: Long, newId: () -> String): ProfitModelVersion? {
        val previous = previous(versions) ?: return null
        return ProfitModelVersion(newId(), nextNumber(versions), nowMs, ModelVersionSource.ROLLBACK, previous.settings, note = "TO:${previous.id}")
    }

    private fun nextNumber(versions: List<ProfitModelVersion>) = (versions.maxOfOrNull { it.number } ?: 0) + 1
}
