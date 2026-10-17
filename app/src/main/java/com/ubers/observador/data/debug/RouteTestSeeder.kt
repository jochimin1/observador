package com.ubers.observador.data.debug

import com.ubers.observador.domain.route.RouteClose
import com.ubers.observador.domain.route.RouteCoverage
import com.ubers.observador.domain.route.RouteFinish
import com.ubers.observador.domain.route.RoutePhase
import com.ubers.observador.domain.route.RoutePoint
import com.ubers.observador.domain.route.RoutePolicy
import com.ubers.observador.domain.route.TripRouteSummary
import com.ubers.observador.domain.trips.Trip
import com.ubers.observador.domain.trips.TripOutcome

/**
 * Fase 8K, debug only: a made-up route in Santo Domingo for a TEST- trip, so the trip's card, the
 * map and the GPX can be seen without driving. A point every 5 s along a few streets: to the rider
 * until «Iniciar viaje», then with the rider to the end. Simplified like a real one.
 */
object RouteTestSeeder {

    private val pickupLeg = listOf(18.4688 to -69.9396, 18.4712 to -69.9351, 18.4741 to -69.9318, 18.4768 to -69.9302)
    private val tripLeg = listOf(
        18.4768 to -69.9302, 18.4789 to -69.9263, 18.4823 to -69.9227, 18.4849 to -69.9178,
        18.4872 to -69.9134, 18.4902 to -69.9101, 18.4931 to -69.9068
    )

    /** Null for a trip that is not TEST- or has no acceptance time. */
    fun generate(trip: Trip, policy: RoutePolicy = RoutePolicy()): Pair<TripRouteSummary, List<RoutePoint>>? {
        if (!trip.id.startsWith(TripTestSeeder.PREFIX)) return null
        val accepted = trip.acceptedAt ?: trip.pickupNavigationAt ?: return null
        val end = TripOutcome.endedAt(trip) ?: trip.cancelledAt ?: trip.interruptedAt ?: (accepted + 15 * 60_000L)
        val start = trip.tripStartedAt?.takeIf { it in accepted..end }
        val points = ArrayList<RoutePoint>()
        if (start != null) {
            points += leg(pickupLeg, accepted, start, RoutePhase.PICKUP)
            points += leg(tripLeg, start, end, RoutePhase.PASSENGER)
        } else {
            points += leg(pickupLeg + tripLeg.drop(1), accepted, end, RoutePhase.PICKUP)
        }
        val numbered = points.mapIndexed { i, p -> p.copy(seq = i + 1) }
        val open = TripRouteSummary(trip.id, openedAt = accepted, pointsReceived = numbered.size + numbered.size / 10, updatedAt = end)
        val done = RouteFinish.finish(
            open, numbered,
            RouteClose(closedAt = end, coverage = RouteCoverage.FULL, cancelled = trip.cancelledAt != null),
            policy.toleranceM
        )
        return done.summary.copy(startPlace = "Prueba: ${if (start != null) "Gazcue" else "Ciudad Nueva"}", endPlace = "Prueba: Naco") to done.points
    }

    /** One point every 5 s from [from] to [to], walking the [path] at an even pace, with a little GPS wobble. */
    private fun leg(path: List<Pair<Double, Double>>, from: Long, to: Long, phase: RoutePhase): List<RoutePoint> {
        val count = ((to - from) / 5_000L).toInt().coerceAtLeast(2)
        val segments = path.zipWithNext()
        return (0..count).map { i ->
            val f = i.toDouble() / count * segments.size
            val s = minOf(f.toInt(), segments.size - 1)
            val t = f - s
            val (a, b) = segments[s]
            val wobble = if (i % 3 == 0) 0.000008 else -0.000005
            RoutePoint.of(
                atMs = from + (to - from) * i / count,
                latitude = a.first + (b.first - a.first) * t + wobble,
                longitude = a.second + (b.second - a.second) * t,
                accuracyM = 6f,
                phase = phase
            )
        }
    }
}
