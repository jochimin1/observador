package com.ubers.observador.data.db

import com.ubers.observador.domain.ModelVersionSource
import com.ubers.observador.domain.OfferQuality
import com.ubers.observador.domain.ProfitModelVersion
import com.ubers.observador.domain.ProfitSettingsSnapshot
import com.ubers.observador.domain.ScoreWeights
import com.ubers.observador.domain.intelligence.CandidateComparisonReport
import com.ubers.observador.domain.intelligence.IntelligencePolicy
import com.ubers.observador.domain.intelligence.ShadowEvaluation

fun ProfitModelVersion.toEntity() = ProfitModelVersionEntity(
    id = id,
    number = number,
    createdAt = createdAtMs,
    source = source.storageKey,
    algorithmVersion = algorithmVersion,
    minRdpPerKm = settings.minRdpPerKm,
    minRdpPerHour = settings.minRdpPerHour,
    maxPickupKm = settings.maxPickupKm,
    maxPickupMinutes = settings.maxPickupMinutes,
    weightKmPct = settings.weights.kmPct,
    weightHourPct = settings.weights.hourPct,
    weightPickupKmPct = settings.weights.pickupKmPct,
    weightPickupMinPct = settings.weights.pickupMinPct,
    goodFrom = settings.goodFrom,
    excellentFrom = settings.excellentFrom,
    note = note
)

fun ProfitModelVersionEntity.toDomain() = ProfitModelVersion(
    id = id,
    number = number,
    createdAtMs = createdAt,
    source = ModelVersionSource.fromStorage(source),
    settings = ProfitSettingsSnapshot(
        minRdpPerKm = minRdpPerKm,
        minRdpPerHour = minRdpPerHour,
        maxPickupKm = maxPickupKm,
        maxPickupMinutes = maxPickupMinutes,
        // Stored exactly as written; only the engine guards against invalid weights
        weights = ScoreWeights(weightKmPct, weightHourPct, weightPickupKmPct, weightPickupMinPct),
        goodFrom = goodFrom,
        excellentFrom = excellentFrom
    ),
    algorithmVersion = algorithmVersion,
    note = note
)

fun ShadowEvaluation.toEntity() = ShadowEvaluationEntity(
    evaluationId = evaluationId,
    offerHash = offerHash,
    evaluatedAt = evaluatedAtMs,
    activeVersionId = activeVersionId,
    activeScore = activeScore,
    activeCategory = activeCategory.name,
    candidateVersionId = candidateVersionId,
    candidateScore = candidateScore,
    candidateCategory = candidateCategory.name
)

fun ShadowEvaluationEntity.toDomain(): ShadowEvaluation? {
    val active = OfferQuality.entries.firstOrNull { it.name == activeCategory } ?: return null
    val candidate = OfferQuality.entries.firstOrNull { it.name == candidateCategory } ?: return null
    return ShadowEvaluation(evaluationId, offerHash, evaluatedAt, activeVersionId, activeScore, active, candidateVersionId, candidateScore, candidate)
}

fun CandidateComparisonReport.toRunEntity(runId: String, nowMs: Long, periodStart: Long?, totalTrips: Int) = CalibrationRunEntity(
    runId = runId,
    createdAt = nowMs,
    periodStart = periodStart,
    periodEnd = nowMs,
    totalTrips = totalTrips,
    validSamples = totalSamples,
    trainingN = trainingN,
    validationN = validationN,
    baseVersionId = candidate?.baseModelVersionId,
    candidateVersionId = candidate?.id,
    currentTrainingRho = currentTraining.spearman,
    candidateTrainingRho = candidateTraining.spearman,
    currentValidationRho = currentValidation.spearman,
    candidateValidationRho = candidateValidation.spearman,
    status = status.name,
    flags = flags.joinToString(",") { it.name },
    analysisVersion = IntelligencePolicy.ANALYSIS_VERSION,
    algorithmVersion = com.ubers.observador.domain.ProfitEngine.ALGORITHM_VERSION
)
