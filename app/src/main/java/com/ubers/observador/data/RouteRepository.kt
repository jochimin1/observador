package com.ubers.observador.data

import androidx.room.withTransaction
import com.ubers.observador.data.db.AppDatabase
import com.ubers.observador.data.db.toDomain
import com.ubers.observador.data.db.toEntity
import com.ubers.observador.domain.route.RouteClose
import com.ubers.observador.domain.route.RouteCoverage
import com.ubers.observador.domain.route.RouteFinish
import com.ubers.observador.domain.route.RoutePoint
import com.ubers.observador.domain.route.RoutePolicy
import com.ubers.observador.domain.route.RouteStatus
import com.ubers.observador.domain.route.RouteUpdate
import com.ubers.observador.domain.route.RouteWrite
import com.ubers.observador.domain.route.TripRouteSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Fase 8K data access. Written by RouteMonitor's writer (through the shared queue) and by
 * «Borrar recorrido»; screens and exports read. A route is keyed by its trip, found from the
 * lifecycle session; a session with no stored trip is skipped, like the kilometres.
 */
class RouteRepository(private val db: AppDatabase, private val policy: RoutePolicy = RoutePolicy()) {

    private val dao = db.routeDao()
    private val trips = db.tripDao()

    suspend fun save(update: RouteUpdate) {
        if (update.isEmpty) return
        db.withTransaction { update.writes.forEach { apply(it) } }
    }

    private suspend fun apply(w: RouteWrite) {
        if (w.isEmpty) return
        val trip = trips.getByLifecycleSessionId(w.session) ?: return
        val stored = dao.getRoute(trip.id)?.toDomain()
        // Deleted by the driver: nothing more is kept for this trip
        if (stored?.status == RouteStatus.DELETED) return
        val now = maxOf(w.openAt ?: 0L, w.points.lastOrNull()?.atMs ?: 0L, w.close?.closedAt ?: 0L)
        var route = when {
            stored == null -> TripRouteSummary(tripId = trip.id, openedAt = w.openAt ?: w.points.firstOrNull()?.atMs ?: now, updatedAt = now)
            // Picked up again after a restart closed it: it goes on, and it is no longer complete
            w.openAt != null && !stored.isOpen && stored.status != RouteStatus.CAPPED ->
                stored.copy(status = RouteStatus.OPEN, coverage = RouteCoverage.PARTIAL, closedAt = null)
            else -> stored
        }
        if (w.points.isNotEmpty()) {
            var seq = dao.nextSeq(trip.id)
            dao.insertPoints(w.points.map { it.toEntity(trip.id, seq++) })
            route = route.copy(
                pointsKept = route.pointsKept + w.points.size,
                firstAt = route.firstAt ?: w.points.first().atMs,
                lastAt = w.points.last().atMs
            )
        }
        route = route.copy(
            pointsReceived = route.pointsReceived + w.receivedDelta,
            secondsWithoutSignal = route.secondsWithoutSignal + w.gapSecondsDelta,
            status = if (w.capped) RouteStatus.CAPPED else route.status,
            updatedAt = maxOf(route.updatedAt, now)
        )
        val close = w.close
        if (close != null) route = finish(route, close)
        dao.upsertRoute(route.toEntity())
    }

    /** Simplifies the stored points and works out the start and the end. */
    private suspend fun finish(route: TripRouteSummary, close: RouteClose): TripRouteSummary {
        val stored = dao.getPoints(route.tripId).map { it.toDomain() }
        val done = RouteFinish.finish(route, stored, close, policy.toleranceM)
        if (done.points.size != stored.size || done.points != stored) {
            dao.deletePoints(route.tripId)
            if (done.points.isNotEmpty()) dao.insertPoints(done.points.map { it.toEntity(route.tripId, it.seq) })
        }
        return done.summary
    }

    /** At start-up: routes a previous process left open are closed with what they have (partial). */
    suspend fun closeLeftOpen(nowMs: Long): List<String> {
        val open = dao.getOpenRoutes().map { it.toDomain() }
        if (open.isEmpty()) return emptyList()
        db.withTransaction {
            open.forEach { r ->
                val trip = trips.getById(r.tripId)
                val cancelled = trip?.status == "CANCELLED"
                val closed = finish(r, RouteClose(closedAt = r.lastAt ?: r.openedAt, coverage = RouteCoverage.PARTIAL, cancelled = cancelled))
                dao.upsertRoute(closed.copy(updatedAt = maxOf(closed.updatedAt, nowMs)).toEntity())
            }
        }
        return open.map { it.tripId }
    }

    suspend fun getRoute(tripId: String): TripRouteSummary? = dao.getRoute(tripId)?.toDomain()

    fun observeRoute(tripId: String): Flow<TripRouteSummary?> = dao.observeRoute(tripId).map { it?.toDomain() }

    suspend fun getPoints(tripId: String): List<RoutePoint> = dao.getPoints(tripId).map { it.toDomain() }

    fun observePoints(tripId: String): Flow<List<RoutePoint>> = dao.observePoints(tripId).map { l -> l.map { it.toDomain() } }

    /** Routes of the trips created since [fromMs], by trip id. */
    fun observeRoutesSince(fromMs: Long): Flow<Map<String, TripRouteSummary>> =
        dao.observeRoutesOfTripsSince(fromMs).map { l -> l.associate { it.tripId to it.toDomain() } }

    /** Summaries and points of several trips, in chunks (SQLite allows 999 values per query). */
    suspend fun routesOf(tripIds: List<String>): Map<String, TripRouteSummary> =
        tripIds.distinct().chunked(500).flatMap { dao.getRoutes(it) }.associate { it.tripId to it.toDomain() }

    suspend fun pointsOf(tripIds: List<String>): Map<String, List<RoutePoint>> =
        tripIds.distinct().chunked(500).flatMap { dao.getPointsOf(it) }.groupBy({ it.tripId }, { it.toDomain() })

    suspend fun unnamed(limit: Int): List<TripRouteSummary> = dao.getUnnamed(limit).map { it.toDomain() }

    suspend fun setPlaces(tripId: String, startPlace: String?, endPlace: String?) {
        dao.setPlaces(tripId, startPlace, endPlace)
    }

    /** «Borrar recorrido»: points and coordinates go; times, place names, km and money stay. */
    suspend fun deleteRoute(tripId: String, nowMs: Long) {
        db.withTransaction {
            dao.deletePoints(tripId)
            dao.markDeleted(tripId, nowMs)
        }
    }

    /** «Borrar todos los recorridos» in Ajustes. */
    suspend fun deleteAll(nowMs: Long) {
        db.withTransaction {
            dao.deleteAllPoints()
            dao.markAllDeleted(nowMs)
        }
    }

    /** Debug only: replaces the route of TEST- trips; real ones are never touched. */
    suspend fun putTestRoutes(routes: List<Pair<TripRouteSummary, List<RoutePoint>>>) {
        require(routes.all { it.first.tripId.startsWith("TEST-") }) { "test routes must belong to TEST- trips" }
        db.withTransaction {
            routes.forEach { (summary, points) ->
                if (trips.getById(summary.tripId) == null) return@forEach
                dao.deletePoints(summary.tripId)
                dao.insertPoints(points.mapIndexed { i, p -> p.toEntity(summary.tripId, i + 1) })
                dao.upsertRoute(summary.copy(pointsKept = points.size).toEntity())
            }
        }
    }

    fun observeCounts(): Flow<Pair<Int, Int>> =
        kotlinx.coroutines.flow.combine(dao.observeRouteCount(), dao.observePointCount()) { routes, points -> routes to points }
}
