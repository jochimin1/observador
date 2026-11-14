package com.ubers.observador.domain.scoring

import com.ubers.observador.domain.TestData
import com.ubers.observador.domain.online.OnlineSession
import com.ubers.observador.domain.online.TripWaits
import com.ubers.observador.domain.trips.Trip
import com.ubers.observador.domain.trips.TripDetails

/** 1.44.0: the driver's own history that V2 may use, already cleaned of TEST data. */
object ScoreHistory {

    const val WINDOW_MS = 30L * 24 * 60 * 60_000

    /** «Llegué» → viaje iniciado of real trips; a wait ended by a cancellation is not a pickup wait. */
    fun pickupWaits(trips: List<Trip>): List<Long> = TestData.filter(trips, false) { it.id }
        .mapNotNull { TripDetails.waitAtPickup(it)?.takeIf { w -> !w.endedByCancellation }?.ms }

    /**
     * Time from the end of a trip to the next accepted one, inside an online session (the same rule
     * as «Esperas entre viajes»). Shown as information only: it does not depend on the offer, so it
     * never enters the score.
     */
    fun afterTripWaits(trips: List<Trip>, sessions: List<OnlineSession>, nowMs: Long, routeEnds: Map<String, Long>): List<Long> =
        TripWaits.of(TestData.filter(trips, false) { it.id }, sessions, nowMs, routeEnds = routeEnds)
            .filter { it.inSession }.map { it.waitMs }
}
