package com.ubers.observador.domain.route

/** Display-only geometry: never bridge a period without signal, never mutate stored points. */
object RouteGeometry {
    fun displayRuns(points: List<RoutePoint>, gapMs: Long = 60_000): List<List<RoutePoint>> {
        if (points.isEmpty()) return emptyList()
        val runs = mutableListOf<List<RoutePoint>>()
        var start = 0
        for (i in 1..points.size) {
            if (i == points.size || points[i].phase != points[i - 1].phase || points[i].atMs - points[i - 1].atMs > gapMs) {
                runs += RouteSimplifier.simplify(points.subList(start, i), 5.0)
                start = i
            }
        }
        return runs
    }
}
