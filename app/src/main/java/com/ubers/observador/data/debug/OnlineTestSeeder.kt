package com.ubers.observador.data.debug

import com.ubers.observador.domain.online.OnlineAnomaly
import com.ubers.observador.domain.online.OnlineEndReason
import com.ubers.observador.domain.online.OnlineSession
import com.ubers.observador.domain.online.OnlineStartKind
import com.ubers.observador.domain.online.TimeSpan

/**
 * Debug-only TEST online sessions (Fase 8A), laid around the TEST trips of TripTestSeeder (A at
 * now−5 h … E at now−1 h) so the "Tiempo en línea" card has something to show. Every id starts
 * with [PREFIX]; all are closed, so the rules never pick one up as the open session.
 *
 * 1. exact start, closed by going offline (covers trips A and B);
 * 2. recovered start with a 35-minute unseen gap, closed by going offline (C, D, E);
 * 3. a short one closed by the pause;
 * 4. yesterday's, closed as observation lost, with the 24-hour anomaly (to see how it reads).
 */
object OnlineTestSeeder {

    const val PREFIX = "TEST-online-"
    private const val MIN = 60_000L
    private const val HOUR = 60 * MIN

    fun generate(now: Long): List<OnlineSession> {
        fun closed(
            n: Int,
            start: Long,
            end: Long,
            kind: OnlineStartKind,
            reason: OnlineEndReason,
            gaps: List<TimeSpan> = emptyList(),
            possibleEnd: Long = end,
            anomalies: Set<OnlineAnomaly> = emptySet()
        ) = OnlineSession(
            id = "$PREFIX$n",
            startedAt = start,
            startKind = kind,
            lastOnlineSeenAt = end,
            endedAt = end,
            possibleEndAt = possibleEnd,
            endReason = reason,
            unobservedMs = gaps.sumOf { it.durationMs },
            gaps = gaps,
            anomalies = anomalies,
            createdAt = start,
            updatedAt = end
        )
        return listOf(
            closed(1, now - 5 * HOUR - 10 * MIN, now - 3 * HOUR - 20 * MIN, OnlineStartKind.EXACT, OnlineEndReason.WENT_OFFLINE),
            closed(
                2, now - 3 * HOUR - 5 * MIN, now - 30 * MIN, OnlineStartKind.RECOVERED, OnlineEndReason.WENT_OFFLINE,
                gaps = listOf(TimeSpan(now - 100 * MIN, now - 65 * MIN))
            ),
            closed(3, now - 25 * MIN, now - 10 * MIN, OnlineStartKind.EXACT, OnlineEndReason.PAUSED),
            closed(
                4, now - 50 * HOUR, now - 25 * HOUR, OnlineStartKind.RECOVERED, OnlineEndReason.OBSERVATION_LOST,
                anomalies = setOf(OnlineAnomaly.LONGER_THAN_24H)
            )
        )
    }
}
