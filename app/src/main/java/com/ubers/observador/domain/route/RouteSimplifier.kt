package com.ubers.observador.domain.route

import com.ubers.observador.domain.distance.Geo
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Douglas–Peucker, run on each stretch of one phase: removes the points that fall almost on the
 * line between their neighbours (closer than the tolerance, 5 m). The first and last point, and
 * the first and last of every phase, always stay. Running it again changes nothing.
 */
object RouteSimplifier {

    fun simplify(points: List<RoutePoint>, toleranceM: Double): List<RoutePoint> {
        if (points.size <= 2) return points
        val out = ArrayList<RoutePoint>(points.size)
        runsOf(points).forEach { run -> out += simplifyRun(run, toleranceM) }
        return out
    }

    /** Consecutive points of the same phase. */
    fun runsOf(points: List<RoutePoint>): List<List<RoutePoint>> {
        val runs = ArrayList<List<RoutePoint>>()
        var start = 0
        for (i in 1..points.size) {
            if (i == points.size || points[i].phase != points[start].phase) {
                runs += points.subList(start, i)
                start = i
            }
        }
        return runs
    }

    private fun simplifyRun(run: List<RoutePoint>, toleranceM: Double): List<RoutePoint> {
        if (run.size <= 2) return run
        val keep = BooleanArray(run.size)
        keep[0] = true
        keep[run.size - 1] = true
        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(0, run.size - 1))
        while (stack.isNotEmpty()) {
            val (from, to) = stack.removeLast().let { it[0] to it[1] }
            if (to - from < 2) continue
            var worst = -1
            var worstM = -1.0
            for (i in from + 1 until to) {
                val d = distanceToSegmentM(run[i], run[from], run[to])
                if (d > worstM) {
                    worstM = d
                    worst = i
                }
            }
            if (worstM > toleranceM) {
                keep[worst] = true
                stack.addLast(intArrayOf(from, worst))
                stack.addLast(intArrayOf(worst, to))
            }
        }
        return run.filterIndexed { i, _ -> keep[i] }
    }

    /** Metres from [p] to the segment [a]–[b], on a flat projection around [a] (exact enough for a city). */
    fun distanceToSegmentM(p: RoutePoint, a: RoutePoint, b: RoutePoint): Double {
        val metresPerDegLat = Math.toRadians(1.0) * Geo.EARTH_RADIUS_M
        val metresPerDegLng = metresPerDegLat * cos(Math.toRadians(a.latitude))
        val bx = (b.longitude - a.longitude) * metresPerDegLng
        val by = (b.latitude - a.latitude) * metresPerDegLat
        val px = (p.longitude - a.longitude) * metresPerDegLng
        val py = (p.latitude - a.latitude) * metresPerDegLat
        val len2 = bx * bx + by * by
        val t = if (len2 == 0.0) 0.0 else ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
        val dx = px - t * bx
        val dy = py - t * by
        return sqrt(dx * dx + dy * dy)
    }
}

/**
 * Closing a route, from the points stored: drops the tail Uber spent searching, renames pickup to
 * «sin distinguir» when «Iniciar viaje» was never seen (unless cancelled), simplifies, and works
 * out start, end and counts. Pure, so the same code closes a trip that just ended and one left
 * open by a restart.
 */
object RouteFinish {

    data class Result(val points: List<RoutePoint>, val summary: TripRouteSummary)

    fun finish(summary: TripRouteSummary, stored: List<RoutePoint>, close: RouteClose, toleranceM: Double): Result {
        var pts = stored.sortedBy { it.seq }
        close.trimAfterMs?.let { cut -> pts = pts.filter { it.atMs <= cut } }
        val hasPassenger = pts.any { it.phase == RoutePhase.PASSENGER }
        if (!hasPassenger && !close.cancelled) {
            pts = pts.map { if (it.phase == RoutePhase.PICKUP) it.copy(phase = RoutePhase.UNKNOWN) else it }
        }
        val kept = RouteSimplifier.simplify(pts, toleranceM)
        val coverage = when {
            kept.isEmpty() -> RouteCoverage.NO_GPS
            summary.coverage == RouteCoverage.PARTIAL || close.coverage != RouteCoverage.FULL -> RouteCoverage.PARTIAL
            else -> RouteCoverage.FULL
        }
        return Result(kept, withEnds(summary, kept).copy(
            status = if (summary.status == RouteStatus.CAPPED) RouteStatus.CAPPED else RouteStatus.CLOSED,
            coverage = coverage,
            closedAt = close.closedAt,
            updatedAt = close.closedAt
        ))
    }

    /** Start (first point with passenger, else the first), end (the last), first and last time, count. */
    fun withEnds(summary: TripRouteSummary, points: List<RoutePoint>): TripRouteSummary {
        val first = points.firstOrNull()
        val last = points.lastOrNull()
        val boarding = points.firstOrNull { it.phase == RoutePhase.PASSENGER }
        val start = boarding ?: first
        // A name stays only while its point is the same
        val sameStart = start != null && start.latE7 == summary.startLatE7 && start.lngE7 == summary.startLngE7
        val sameEnd = last != null && last.latE7 == summary.endLatE7 && last.lngE7 == summary.endLngE7
        return summary.copy(
            startPlace = summary.startPlace.takeIf { sameStart },
            endPlace = summary.endPlace.takeIf { sameEnd },
            pointsKept = points.size,
            firstAt = first?.atMs,
            lastAt = last?.atMs,
            startAt = start?.atMs,
            startLatE7 = start?.latE7,
            startLngE7 = start?.lngE7,
            startWithPassenger = boarding != null,
            endAt = last?.atMs,
            endLatE7 = last?.latE7,
            endLngE7 = last?.lngE7
        )
    }
}
