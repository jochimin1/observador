package com.ubers.observador.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.ubers.observador.domain.distance.DistanceSegment
import com.ubers.observador.domain.distance.SegmentEndReason
import com.ubers.observador.domain.distance.SegmentOrigin
import com.ubers.observador.domain.distance.TripDistance
import com.ubers.observador.domain.distance.TripDistanceCoverage
import java.time.LocalDate

/**
 * One measured stretch (Fase 8E). Totals only, in millimetres: there is no latitude, longitude,
 * route or address column, by design (a test checks it). Only the open stretch is updated (about
 * once a minute) and then its close; rows are never deleted except TEST ones.
 */
@Entity(
    tableName = "distance_segments",
    indices = [Index(value = ["workday"]), Index(value = ["started_at"]), Index(value = ["ended_at"])]
)
data class DistanceSegmentEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    /** The working day, ISO date (yyyy-MM-dd), named by the date it started on. */
    @ColumnInfo(name = "workday") val workday: String,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "ended_at") val endedAt: Long?,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "origin") val origin: String,
    @ColumnInfo(name = "end_reason") val endReason: String?,
    @ColumnInfo(name = "measured_mm") val measuredMm: Long,
    @ColumnInfo(name = "gap_mm") val gapMm: Long,
    @ColumnInfo(name = "passenger_mm") val passengerMm: Long,
    @ColumnInfo(name = "pickup_mm") val pickupMm: Long,
    @ColumnInfo(name = "empty_mm") val emptyMm: Long,
    @ColumnInfo(name = "offline_mm") val offlineMm: Long,
    @ColumnInfo(name = "fixes_used") val fixesUsed: Int,
    @ColumnInfo(name = "fixes_discarded") val fixesDiscarded: Int,
    @ColumnInfo(name = "seconds_without_signal") val secondsWithoutSignal: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long
)

/** Real kilometres of one trip (1:1, deleted with it). Totals only, no positions. */
@Entity(
    tableName = "trip_distances",
    foreignKeys = [
        ForeignKey(
            entity = TripEntity::class,
            parentColumns = ["id"],
            childColumns = ["trip_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class TripDistanceEntity(
    @PrimaryKey @ColumnInfo(name = "trip_id") val tripId: String,
    @ColumnInfo(name = "pickup_mm") val pickupMm: Long,
    @ColumnInfo(name = "trip_mm") val tripMm: Long,
    @ColumnInfo(name = "gap_mm") val gapMm: Long,
    @ColumnInfo(name = "coverage") val coverage: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)

fun DistanceSegment.toEntity() = DistanceSegmentEntity(
    id = id,
    workday = workday.toString(),
    startedAt = startedAt,
    endedAt = endedAt,
    updatedAt = updatedAt,
    origin = origin.storageKey,
    endReason = endReason?.storageKey,
    measuredMm = measuredMm,
    gapMm = gapMm,
    passengerMm = passengerMm,
    pickupMm = pickupMm,
    emptyMm = emptyMm,
    offlineMm = offlineMm,
    fixesUsed = fixesUsed,
    fixesDiscarded = fixesDiscarded,
    secondsWithoutSignal = secondsWithoutSignal,
    createdAt = createdAt
)

fun DistanceSegmentEntity.toDomain() = DistanceSegment(
    id = id,
    workday = LocalDate.parse(workday),
    startedAt = startedAt,
    endedAt = endedAt,
    updatedAt = updatedAt,
    origin = SegmentOrigin.fromStorage(origin),
    endReason = SegmentEndReason.fromStorage(endReason),
    measuredMm = measuredMm,
    gapMm = gapMm,
    passengerMm = passengerMm,
    pickupMm = pickupMm,
    emptyMm = emptyMm,
    offlineMm = offlineMm,
    fixesUsed = fixesUsed,
    fixesDiscarded = fixesDiscarded,
    secondsWithoutSignal = secondsWithoutSignal,
    createdAt = createdAt
)

fun TripDistance.toEntity(tripId: String) = TripDistanceEntity(
    tripId = tripId,
    pickupMm = pickupMm,
    tripMm = tripMm,
    gapMm = gapMm,
    coverage = coverage.storageKey,
    updatedAt = updatedAt
)

/** The session is not stored (the row is keyed by trip); [sessionId] is given back for the domain object. */
fun TripDistanceEntity.toDomain(sessionId: String) = TripDistance(
    tripSessionId = sessionId,
    pickupMm = pickupMm,
    tripMm = tripMm,
    gapMm = gapMm,
    coverage = TripDistanceCoverage.fromStorage(coverage),
    updatedAt = updatedAt
)
