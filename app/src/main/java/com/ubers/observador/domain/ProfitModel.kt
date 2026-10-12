package com.ubers.observador.domain

import com.ubers.observador.domain.models.UberOffer
import com.ubers.observador.domain.money.MoneyAmount

/**
 * What an offer card says, and only that: the inputs a decision model may use (Fase 7D rule 1,
 * no data leakage). Earnings, real times, tips and adjustments are outcomes and never fit here.
 */
data class OfferDecisionFeatures(
    val offeredAmount: MoneyAmount,
    val pickupKm: Double,
    val pickupMinutes: Int,
    val tripKm: Double,
    val tripMinutes: Int,
    val detectedAtMs: Long
) {
    /** The engine works in Double for ratios; the conversion from minor units happens here, once. */
    fun toOffer() = UberOffer(
        price = offeredAmount.toDecimal().toDouble(),
        pickupKm = pickupKm,
        pickupMin = pickupMinutes,
        tripKm = tripKm,
        tripMin = tripMinutes,
        detectedAt = detectedAtMs
    )

    val estimatedEngagedMinutes: Int get() = pickupMinutes + tripMinutes
}

/** Every setting that changes a score or a category. Two models differ only here. */
data class ProfitSettingsSnapshot(
    val minRdpPerKm: Double,
    val minRdpPerHour: Double,
    val maxPickupKm: Double,
    val maxPickupMinutes: Int,
    val weights: ScoreWeights,
    val goodFrom: Int,
    val excellentFrom: Int
) {
    val thresholds: QualityThresholds get() = QualityThresholds.of(goodFrom, excellentFrom)

    /** Cost per km does not enter the score, so the engine default is fine here. */
    fun toEngineSettings() = EngineSettings(
        minRdpPerKm = minRdpPerKm,
        minRdpPerHour = minRdpPerHour,
        maxPickupKm = maxPickupKm,
        maxPickupMin = maxPickupMinutes,
        weights = weights
    )

    companion object {
        fun of(engine: EngineSettings, cuts: QualityThresholds) = ProfitSettingsSnapshot(
            minRdpPerKm = engine.minRdpPerKm,
            minRdpPerHour = engine.minRdpPerHour,
            maxPickupKm = engine.maxPickupKm,
            maxPickupMinutes = engine.maxPickupMin,
            weights = engine.weights,
            goodFrom = cuts.goodFrom,
            excellentFrom = cuts.excellentFrom
        )
    }
}

data class ProfitEvaluation(
    val score: Int,
    val category: OfferQuality,
    /** offered / estimated minutes * 60: what the offer promised, before anything happened. */
    val predictedRdpPerHour: Double,
    /** offered / estimated km: earnings per ESTIMATED km, never a real RD$/km. */
    val predictedRdpPerKm: Double,
    val estimatedMinutes: Int
)

enum class ModelVersionSource(val storageKey: String) {
    INITIAL("INITIAL"),
    USER_EDIT("USER_EDIT"),
    CALIBRATION_RECOMMENDATION("CALIBRATION_RECOMMENDATION"),
    SHADOW_PROMOTED("SHADOW_PROMOTED"),
    ROLLBACK("ROLLBACK");

    companion object {
        fun fromStorage(key: String?) = entries.firstOrNull { it.storageKey == key } ?: USER_EDIT
    }
}

/**
 * One configuration of the engine, never edited after creation. [number] is the human label
 * (v1, v2...). [algorithmVersion] names the formula; [settings] the parameters.
 */
data class ProfitModelVersion(
    val id: String,
    val number: Int,
    val createdAtMs: Long,
    val source: ModelVersionSource,
    val settings: ProfitSettingsSnapshot,
    val algorithmVersion: String = ProfitEngine.ALGORITHM_VERSION,
    /** Short code-like note ("calibration run X"), never free user text. */
    val note: String? = null
)
