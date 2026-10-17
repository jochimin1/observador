package com.ubers.observador.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.ubers.observador.domain.route.RouteCoverage
import com.ubers.observador.domain.route.RoutePhase
import com.ubers.observador.domain.route.RoutePoint
import com.ubers.observador.domain.route.RouteStatus
import com.ubers.observador.domain.route.TripRouteSummary

/**
 * Fase 8K (DB 14): one point of a taken trip's route. With trip_routes, the only tables with
 * coordinates (the driver's decision of 17/10). Degrees as whole 1e-7 units, accuracy in
 * decimetres. Deleted with its trip (CASCADE).
 *
 * Indices: (trip_id, seq) unique serves the route query in order, the next-number query and the
 * foreign key, and forbids two points with the same number in one trip.
 */
@Entity(
    tableName = "trip_route_points",
    foreignKeys = [
        ForeignKey(
            entity = TripEntity::class,
            parentColumns = ["id"],
            childColumns = ["trip_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["trip_id", "seq"], unique = true)]
)
data class TripRoutePointEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "trip_id") val tripId: String,
    @ColumnInfo(name = "seq") val seq: Int,
    @ColumnInfo(name = "at_ms") val atMs: Long,
    @ColumnInfo(name = "lat_e7") val latE7: Int,
    @ColumnInfo(name = "lng_e7") val lngE7: Int,
    @ColumnInfo(name = "accuracy_dm") val accuracyDm: Int,
    @ColumnInfo(name = "phase") val phase: String
)

/**
 * Fase 8K (DB 14): the summary of one trip's route (1:1, deleted with its trip). Start and end
 * time, point and place name; counts, coverage and status. Index on status: the routes left open
 * by a restart are closed at start-up.
 */
@Entity(
    tableName = "trip_routes",
    foreignKeys = [
        ForeignKey(
            entity = TripEntity::class,
            parentColumns = ["id"],
            childColumns = ["trip_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["status"])]
)
data class TripRouteEntity(
    @PrimaryKey @ColumnInfo(name = "trip_id") val tripId: String,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "coverage") val coverage: String,
    @ColumnInfo(name = "opened_at") val openedAt: Long,
    @ColumnInfo(name = "closed_at") val closedAt: Long?,
    @ColumnInfo(name = "points_received") val pointsReceived: Int,
    @ColumnInfo(name = "points_kept") val pointsKept: Int,
    @ColumnInfo(name = "first_at") val firstAt: Long?,
    @ColumnInfo(name = "last_at") val lastAt: Long?,
    @ColumnInfo(name = "seconds_without_signal") val secondsWithoutSignal: Long,
    @ColumnInfo(name = "start_at") val startAt: Long?,
    @ColumnInfo(name = "start_lat_e7") val startLatE7: Int?,
    @ColumnInfo(name = "start_lng_e7") val startLngE7: Int?,
    @ColumnInfo(name = "start_with_passenger") val startWithPassenger: Boolean,
    @ColumnInfo(name = "end_at") val endAt: Long?,
    @ColumnInfo(name = "end_lat_e7") val endLatE7: Int?,
    @ColumnInfo(name = "end_lng_e7") val endLngE7: Int?,
    @ColumnInfo(name = "start_place") val startPlace: String?,
    @ColumnInfo(name = "end_place") val endPlace: String?,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)

fun RoutePoint.toEntity(tripId: String, seq: Int) = TripRoutePointEntity(
    tripId = tripId,
    seq = seq,
    atMs = atMs,
    latE7 = latE7,
    lngE7 = lngE7,
    accuracyDm = accuracyDm,
    phase = phase.storageKey
)

fun TripRoutePointEntity.toDomain() = RoutePoint(
    seq = seq,
    atMs = atMs,
    latE7 = latE7,
    lngE7 = lngE7,
    accuracyDm = accuracyDm,
    phase = RoutePhase.fromStorage(phase)
)

fun TripRouteSummary.toEntity() = TripRouteEntity(
    tripId = tripId,
    status = status.storageKey,
    coverage = coverage.storageKey,
    openedAt = openedAt,
    closedAt = closedAt,
    pointsReceived = pointsReceived,
    pointsKept = pointsKept,
    firstAt = firstAt,
    lastAt = lastAt,
    secondsWithoutSignal = secondsWithoutSignal,
    startAt = startAt,
    startLatE7 = startLatE7,
    startLngE7 = startLngE7,
    startWithPassenger = startWithPassenger,
    endAt = endAt,
    endLatE7 = endLatE7,
    endLngE7 = endLngE7,
    startPlace = startPlace,
    endPlace = endPlace,
    updatedAt = updatedAt
)

fun TripRouteEntity.toDomain() = TripRouteSummary(
    tripId = tripId,
    status = RouteStatus.fromStorage(status),
    coverage = RouteCoverage.fromStorage(coverage),
    openedAt = openedAt,
    closedAt = closedAt,
    pointsReceived = pointsReceived,
    pointsKept = pointsKept,
    firstAt = firstAt,
    lastAt = lastAt,
    secondsWithoutSignal = secondsWithoutSignal,
    startAt = startAt,
    startLatE7 = startLatE7,
    startLngE7 = startLngE7,
    startWithPassenger = startWithPassenger,
    endAt = endAt,
    endLatE7 = endLatE7,
    endLngE7 = endLngE7,
    startPlace = startPlace,
    endPlace = endPlace,
    updatedAt = updatedAt
)
