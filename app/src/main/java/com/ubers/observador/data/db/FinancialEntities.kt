package com.ubers.observador.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Fase 7C ledger: one row per amount seen, inserted once, never updated (a mistake is cancelled
 * by a VOID row). Money is amount_minor (Long, centavos) + currency_code: never REAL.
 * If its trip is deleted the amount stays, unassigned (SET NULL): money is never lost with a trip.
 *
 * Indices: observation_id (unique: the same reading is stored once), trip_id (rebuilding a
 * trip's summary), observed_at (amounts of a period).
 */
@Entity(
    tableName = "financial_observations",
    foreignKeys = [
        ForeignKey(entity = TripEntity::class, parentColumns = ["id"], childColumns = ["trip_id"], onDelete = ForeignKey.SET_NULL)
    ],
    indices = [
        Index(value = ["observation_id"], unique = true),
        Index(value = ["trip_id"]),
        Index(value = ["observed_at"])
    ]
)
data class FinancialObservationEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "database_id") val databaseId: Long = 0,
    @ColumnInfo(name = "observation_id") val observationId: String,
    @ColumnInfo(name = "trip_id") val tripId: String?,
    @ColumnInfo(name = "lifecycle_session_id") val lifecycleSessionId: String?,
    @ColumnInfo(name = "observed_at") val observedAt: Long,
    @ColumnInfo(name = "type") val type: String,
    @ColumnInfo(name = "semantics") val semantics: String,
    @ColumnInfo(name = "amount_minor") val amountMinor: Long,
    @ColumnInfo(name = "currency_code") val currencyCode: String,
    @ColumnInfo(name = "scope") val scope: String,
    /** Detection confidence (0-1), not money. */
    @ColumnInfo(name = "confidence") val confidence: Double,
    @ColumnInfo(name = "source") val source: String,
    @ColumnInfo(name = "screen_type") val screenType: String?,
    @ColumnInfo(name = "evidence_codes") val evidenceCodes: String,
    @ColumnInfo(name = "link_confidence") val linkConfidence: String,
    @ColumnInfo(name = "breakdown_group_id") val breakdownGroupId: String?,
    @ColumnInfo(name = "voids_observation_id") val voidsObservationId: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long
)

/**
 * A trip's money, materialised from the ledger and rebuilt from it at any time (1:1 with the
 * trip; deleted with it). effective_earnings_minor is null when no real total was observed:
 * the offered amount is a reference and is never copied into it.
 */
@Entity(
    tableName = "trip_financial_summaries",
    foreignKeys = [
        ForeignKey(entity = TripEntity::class, parentColumns = ["id"], childColumns = ["trip_id"], onDelete = ForeignKey.CASCADE)
    ]
)
data class TripFinancialSummaryEntity(
    @PrimaryKey @ColumnInfo(name = "trip_id") val tripId: String,
    @ColumnInfo(name = "currency_code") val currencyCode: String?,
    @ColumnInfo(name = "offered_amount_minor") val offeredAmountMinor: Long?,
    @ColumnInfo(name = "latest_absolute_minor") val latestAbsoluteMinor: Long?,
    @ColumnInfo(name = "effective_earnings_minor") val effectiveEarningsMinor: Long?,
    @ColumnInfo(name = "fare_minor") val fareMinor: Long,
    @ColumnInfo(name = "tip_minor") val tipMinor: Long,
    @ColumnInfo(name = "promotion_minor") val promotionMinor: Long,
    @ColumnInfo(name = "adjustment_minor") val adjustmentMinor: Long,
    @ColumnInfo(name = "cancellation_fee_minor") val cancellationFeeMinor: Long,
    @ColumnInfo(name = "other_minor") val otherMinor: Long,
    @ColumnInfo(name = "completeness") val completeness: String,
    @ColumnInfo(name = "anomaly_codes") val anomalyCodes: String,
    @ColumnInfo(name = "observation_count") val observationCount: Int,
    @ColumnInfo(name = "pending_review_count") val pendingReviewCount: Int,
    @ColumnInfo(name = "first_observed_at") val firstObservedAt: Long?,
    @ColumnInfo(name = "last_observed_at") val lastObservedAt: Long?,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)

/**
 * "Uber says period X totals Y": a reference for reconciliation, never income. Period limits stay
 * null unless Uber's screen states them. Indices: snapshot_id (unique: one logical snapshot),
 * captured_at (latest snapshot).
 */
@Entity(
    tableName = "platform_earnings_snapshots",
    indices = [
        Index(value = ["snapshot_id"], unique = true),
        Index(value = ["captured_at"])
    ]
)
data class PlatformEarningsSnapshotEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "database_id") val databaseId: Long = 0,
    @ColumnInfo(name = "snapshot_id") val snapshotId: String,
    @ColumnInfo(name = "captured_at") val capturedAt: Long,
    @ColumnInfo(name = "period_type") val periodType: String,
    @ColumnInfo(name = "period_start") val periodStart: Long?,
    @ColumnInfo(name = "period_end") val periodEnd: Long?,
    @ColumnInfo(name = "amount_minor") val amountMinor: Long,
    @ColumnInfo(name = "currency_code") val currencyCode: String,
    @ColumnInfo(name = "confidence") val confidence: Double,
    @ColumnInfo(name = "screen_type") val screenType: String?,
    @ColumnInfo(name = "evidence_codes") val evidenceCodes: String
)
