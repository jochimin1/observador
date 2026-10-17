package com.ubers.observador.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Fase 8K storage: the routes of the taken trips. Written only by RouteMonitor's writer and «Borrar». */
@Dao
interface RouteDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRoute(route: TripRouteEntity)

    @Query("SELECT * FROM trip_routes WHERE trip_id = :tripId")
    suspend fun getRoute(tripId: String): TripRouteEntity?

    @Query("SELECT * FROM trip_routes WHERE trip_id = :tripId")
    fun observeRoute(tripId: String): Flow<TripRouteEntity?>

    @Query("SELECT * FROM trip_routes WHERE status = 'OPEN'")
    suspend fun getOpenRoutes(): List<TripRouteEntity>

    @Query("SELECT * FROM trip_routes WHERE trip_id IN (:tripIds)")
    suspend fun getRoutes(tripIds: List<String>): List<TripRouteEntity>

    /** The routes of the trips created since [from], for the list of Inicio. */
    @Query("SELECT r.* FROM trip_routes r INNER JOIN trips t ON t.id = r.trip_id WHERE t.created_at >= :from")
    fun observeRoutesOfTripsSince(from: Long): Flow<List<TripRouteEntity>>

    /** Closed routes whose places still have no name (they need internet). */
    @Query(
        "SELECT * FROM trip_routes WHERE status IN ('CLOSED', 'CAPPED') AND " +
            "((start_place IS NULL AND start_lat_e7 IS NOT NULL) OR (end_place IS NULL AND end_lat_e7 IS NOT NULL)) " +
            "ORDER BY closed_at DESC LIMIT :limit"
    )
    suspend fun getUnnamed(limit: Int): List<TripRouteEntity>

    @Query("UPDATE trip_routes SET start_place = :startPlace, end_place = :endPlace WHERE trip_id = :tripId")
    suspend fun setPlaces(tripId: String, startPlace: String?, endPlace: String?): Int

    @Insert
    suspend fun insertPoints(points: List<TripRoutePointEntity>)

    @Query("SELECT COALESCE(MAX(seq), 0) + 1 FROM trip_route_points WHERE trip_id = :tripId")
    suspend fun nextSeq(tripId: String): Int

    @Query("SELECT * FROM trip_route_points WHERE trip_id = :tripId ORDER BY seq")
    suspend fun getPoints(tripId: String): List<TripRoutePointEntity>

    @Query("SELECT * FROM trip_route_points WHERE trip_id = :tripId ORDER BY seq")
    fun observePoints(tripId: String): Flow<List<TripRoutePointEntity>>

    @Query("SELECT * FROM trip_route_points WHERE trip_id IN (:tripIds) ORDER BY trip_id, seq")
    suspend fun getPointsOf(tripIds: List<String>): List<TripRoutePointEntity>

    @Query("DELETE FROM trip_route_points WHERE trip_id = :tripId")
    suspend fun deletePoints(tripId: String): Int

    @Query("DELETE FROM trip_route_points")
    suspend fun deleteAllPoints(): Int

    /** «Borrar recorrido»: the points go; times, place names, km and money stay. */
    @Query(
        "UPDATE trip_routes SET status = 'DELETED', points_kept = 0, start_lat_e7 = NULL, start_lng_e7 = NULL, " +
            "end_lat_e7 = NULL, end_lng_e7 = NULL, updated_at = :nowMs WHERE trip_id = :tripId"
    )
    suspend fun markDeleted(tripId: String, nowMs: Long): Int

    @Query(
        "UPDATE trip_routes SET status = 'DELETED', points_kept = 0, start_lat_e7 = NULL, start_lng_e7 = NULL, " +
            "end_lat_e7 = NULL, end_lng_e7 = NULL, updated_at = :nowMs"
    )
    suspend fun markAllDeleted(nowMs: Long): Int

    /** How many routes have points, and how many points: for Ajustes. */
    @Query("SELECT COUNT(*) FROM trip_routes WHERE points_kept > 0 AND status != 'DELETED'")
    fun observeRouteCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM trip_route_points")
    fun observePointCount(): Flow<Int>
}
