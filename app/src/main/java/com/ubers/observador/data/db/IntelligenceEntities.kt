package com.ubers.observador.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** One immutable configuration of the engine (Fase 7D). Rows are only ever inserted. */
@Entity(tableName = "profit_model_versions", indices = [Index(value = ["number"], unique = true)])
data class ProfitModelVersionEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "number") val number: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "source") val source: String,
    @ColumnInfo(name = "algorithm_version") val algorithmVersion: String,
    @ColumnInfo(name = "min_rdp_per_km") val minRdpPerKm: Double,
    @ColumnInfo(name = "min_rdp_per_hour") val minRdpPerHour: Double,
    @ColumnInfo(name = "max_pickup_km") val maxPickupKm: Double,
    @ColumnInfo(name = "max_pickup_minutes") val maxPickupMinutes: Int,
    @ColumnInfo(name = "weight_km_pct") val weightKmPct: Int,
    @ColumnInfo(name = "weight_hour_pct") val weightHourPct: Int,
    @ColumnInfo(name = "weight_pickup_km_pct") val weightPickupKmPct: Int,
    @ColumnInfo(name = "weight_pickup_min_pct") val weightPickupMinPct: Int,
    @ColumnInfo(name = "good_from") val goodFrom: Int,
    @ColumnInfo(name = "excellent_from") val excellentFrom: Int,
    @ColumnInfo(name = "note") val note: String?
)

/** Active and candidate scores of one offer while a shadow test runs. Hash, scores and ids only. */
@Entity(
    tableName = "shadow_evaluations",
    indices = [Index(value = ["offer_hash"]), Index(value = ["candidate_version_id"])]
)
data class ShadowEvaluationEntity(
    @PrimaryKey @ColumnInfo(name = "evaluation_id") val evaluationId: String,
    @ColumnInfo(name = "offer_hash") val offerHash: String,
    @ColumnInfo(name = "evaluated_at") val evaluatedAt: Long,
    @ColumnInfo(name = "active_version_id") val activeVersionId: String,
    @ColumnInfo(name = "active_score") val activeScore: Int,
    @ColumnInfo(name = "active_category") val activeCategory: String,
    @ColumnInfo(name = "candidate_version_id") val candidateVersionId: String,
    @ColumnInfo(name = "candidate_score") val candidateScore: Int,
    @ColumnInfo(name = "candidate_category") val candidateCategory: String
)

/**
 * A calibration kept for the record: period, counts, metrics and decision. The dataset itself is
 * not copied; it can be rebuilt from trips and money with [analysisVersion] and [algorithmVersion].
 */
@Entity(tableName = "calibration_runs", indices = [Index(value = ["created_at"])])
data class CalibrationRunEntity(
    @PrimaryKey @ColumnInfo(name = "run_id") val runId: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "period_start") val periodStart: Long?,
    @ColumnInfo(name = "period_end") val periodEnd: Long,
    @ColumnInfo(name = "total_trips") val totalTrips: Int,
    @ColumnInfo(name = "valid_samples") val validSamples: Int,
    @ColumnInfo(name = "training_n") val trainingN: Int,
    @ColumnInfo(name = "validation_n") val validationN: Int,
    @ColumnInfo(name = "base_version_id") val baseVersionId: String?,
    @ColumnInfo(name = "candidate_version_id") val candidateVersionId: String?,
    @ColumnInfo(name = "current_training_rho") val currentTrainingRho: Double?,
    @ColumnInfo(name = "candidate_training_rho") val candidateTrainingRho: Double?,
    @ColumnInfo(name = "current_validation_rho") val currentValidationRho: Double?,
    @ColumnInfo(name = "candidate_validation_rho") val candidateValidationRho: Double?,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "flags") val flags: String,
    @ColumnInfo(name = "analysis_version") val analysisVersion: String,
    @ColumnInfo(name = "algorithm_version") val algorithmVersion: String
)
